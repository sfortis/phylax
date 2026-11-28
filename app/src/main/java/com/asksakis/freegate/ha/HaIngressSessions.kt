package com.asksakis.freegate.ha

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one Home Assistant ingress session per HA origin and renews it on demand.
 *
 * The Supervisor keeps a session alive for 15 minutes after its last request. The app
 * reuses a session for [SESSION_REUSE_MS] after its last use and creates a new one after
 * that, which is cheaper and simpler than validating the old one. A WebSocket that is
 * already open through ingress is not checked again, so a long-lived Frigate socket does
 * not need the session to stay valid. Each new session also asks for the add-on's ingress
 * path again, so a reinstalled add-on (new ingress token) is picked up.
 *
 * Access tokens come from the refresh token captured from the WebView. A refresh token
 * that Home Assistant reports as revoked is deleted, and the user signs in again in the
 * app's WebView.
 */
class HaIngressSessions(
    private val tokens: TokenSource,
    private val authClientFor: (origin: String) -> HaAuthClient,
    private val webSocketClientFor: (origin: String) -> HaWebSocketClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Token storage, separated from the Android encrypted prefs so the logic is testable. */
    interface TokenSource {
        fun tokens(origin: String): HaTokens?
        fun save(tokens: HaTokens)
        fun dropTokens(origin: String)
    }

    data class IngressSession(val ingressPath: String, val session: String) {
        override fun toString() = "IngressSession(<hidden>)"
    }

    private data class State(
        val slug: String,
        val session: IngressSession,
        val createdAtMs: Long,
        val lastUsedMs: Long,
    )

    private val states = ConcurrentHashMap<String, State>()
    private val lastFailureMs = ConcurrentHashMap<String, Long>()
    private val lastRejectionRenewalMs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** True when a sign-in exists for [origin], so ingress can be attempted. */
    fun hasCredentials(origin: String): Boolean = tokens.tokens(origin) != null

    /**
     * A usable session for [panel], creating one when needed. Returns null when no token
     * exists, Home Assistant refuses, or a recent attempt failed. Blocking.
     */
    fun sessionFor(panel: HaPanelUrl): IngressSession? {
        reuse(panel)?.let { return it }
        val lock = locks.getOrPut(panel.origin) { Any() }
        synchronized(lock) {
            reuse(panel)?.let { return it }
            val failedAt = lastFailureMs[panel.origin]
            if (failedAt != null && clock() - failedAt < FAILURE_COOLDOWN_MS) return null
            return establish(panel).also { if (it == null) lastFailureMs[panel.origin] = clock() }
        }
    }

    /**
     * Called when a request with [failed] got an answer that an expired session or a stale
     * ingress path produces (401 or 503). Returns the session to retry with, or null when
     * a retry would not help.
     *
     * Frigate behind the add-on can answer 401 by itself (authentication enabled, wrong
     * password). Renewing on that would cost an HA round trip per request and send every
     * failed Frigate login twice. A session younger than [FRESH_SESSION_MS] is therefore
     * kept, and renewals after a rejection are limited to one per [REJECTION_RENEWAL_MS].
     */
    fun replaceAfterRejection(panel: HaPanelUrl, failed: IngressSession): IngressSession? {
        val lock = locks.getOrPut(panel.origin) { Any() }
        synchronized(lock) {
            val now = clock()
            val state = states[panel.origin]
            // Another request already replaced the failed session: retry with that one.
            if (state != null && state.session != failed) return reuse(panel)
            if (state != null && now - state.createdAtMs < FRESH_SESSION_MS) return null
            val renewedAt = lastRejectionRenewalMs[panel.origin]
            if (renewedAt != null && now - renewedAt < REJECTION_RENEWAL_MS) return null
            lastRejectionRenewalMs[panel.origin] = now
            states.remove(panel.origin)
            return establish(panel).also { if (it == null) lastFailureMs[panel.origin] = now }
        }
    }

    /** Reuse the current session if it is inside the window, updating its last use atomically. */
    private fun reuse(panel: HaPanelUrl): IngressSession? {
        val now = clock()
        var reused: IngressSession? = null
        states.computeIfPresent(panel.origin) { _, state ->
            if (state.slug == panel.slug && now - state.lastUsedMs < SESSION_REUSE_MS) {
                reused = state.session
                state.copy(lastUsedMs = now)
            } else {
                state
            }
        }
        return reused
    }

    private fun establish(panel: HaPanelUrl): IngressSession? {
        var outcome = attempt(panel, forceRefresh = false)
        if (outcome is Attempt.RetryWithFreshToken) outcome = attempt(panel, forceRefresh = true)
        return (outcome as? Attempt.Done)?.session
    }

    private sealed interface Attempt {
        data class Done(val session: IngressSession?) : Attempt
        data object RetryWithFreshToken : Attempt
    }

    private fun attempt(panel: HaPanelUrl, forceRefresh: Boolean): Attempt {
        val accessToken = accessToken(panel.origin, forceRefresh) ?: return Attempt.Done(null)
        val result = webSocketClientFor(panel.origin).createSession(
            origin = panel.origin,
            accessToken = accessToken.value,
            slug = panel.slug,
            needIngressPath = true,
        )
        return when (result) {
            is HaWebSocketClient.Result.Success -> Attempt.Done(remember(panel, result))
            // A cached access token can be revoked early (the user signed out). One retry
            // with a freshly refreshed token tells that apart from a dead credential.
            HaWebSocketClient.Result.AuthInvalid -> if (accessToken.cached && !forceRefresh) {
                Attempt.RetryWithFreshToken
            } else {
                Log.w(TAG, "Home Assistant rejected the access token for ${panel.origin}")
                Attempt.Done(null)
            }
            is HaWebSocketClient.Result.Failed -> {
                Log.w(TAG, "Ingress session for ${panel.origin} failed: ${result.reason}")
                Attempt.Done(null)
            }
        }
    }

    private fun remember(panel: HaPanelUrl, result: HaWebSocketClient.Result.Success): IngressSession? {
        val path = result.ingressPath ?: return null
        val session = IngressSession(path, result.session)
        val now = clock()
        states[panel.origin] = State(panel.slug, session, createdAtMs = now, lastUsedMs = now)
        lastFailureMs.remove(panel.origin)
        Log.d(TAG, "Ingress session ready for ${panel.origin}/${panel.slug}")
        return session
    }

    private data class AccessToken(val value: String, val cached: Boolean)

    private fun accessToken(origin: String, forceRefresh: Boolean): AccessToken? {
        val stored = tokens.tokens(origin) ?: return null
        val cached = stored.accessToken
        if (!forceRefresh && cached != null && stored.accessTokenExpiresAtMs - clock() > EXPIRY_MARGIN_MS) {
            return AccessToken(cached, cached = true)
        }
        return when (val result = authClientFor(origin).refresh(stored)) {
            is HaAuthClient.Result.Success -> {
                tokens.save(
                    stored.copy(
                        accessToken = result.accessToken,
                        accessTokenExpiresAtMs = clock() + result.expiresInSeconds * MILLIS_PER_SECOND,
                    ),
                )
                AccessToken(result.accessToken, cached = false)
            }
            HaAuthClient.Result.InvalidGrant -> {
                // The user signed out of Home Assistant, or revoked the token. The next
                // sign-in in the app's WebView captures a new one.
                Log.w(TAG, "Home Assistant revoked the refresh token for $origin; deleting the copy")
                tokens.dropTokens(origin)
                null
            }
            is HaAuthClient.Result.Failed -> {
                Log.w(TAG, "Access token refresh for $origin failed: ${result.reason}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "HaIngressSessions"

        /** Below the Supervisor's 15-minute sliding window, with room for clock skew. */
        const val SESSION_REUSE_MS = 12 * 60 * 1000L

        /** Do not retry a failed origin more often than this; the stats poller asks every few seconds. */
        const val FAILURE_COOLDOWN_MS = 30 * 1000L

        /** A rejection of a session this young comes from Frigate, not from the Supervisor. */
        const val FRESH_SESSION_MS = 30 * 1000L

        /** At most one session renewal per origin in this interval because of rejections. */
        const val REJECTION_RENEWAL_MS = 2 * 60 * 1000L

        private const val EXPIRY_MARGIN_MS = 60 * 1000L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
