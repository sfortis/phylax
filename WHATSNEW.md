# 2.0

Big release focused on notification reliability and live system visibility.

## New

- **Live CPU / GPU badges in the top bar**, fed by Frigate's `/api/stats` endpoint every 2 seconds. Tap either to open a full System Statistics panel with RAM, uptime, per-detector inference time and per-camera FPS.
- **Connection status widget**: tap the INT or EXT badge for a live dialog with current URL, rolling latency graph, Wi-Fi state and current mode.
- **Notification deep-links by severity**: alerts open `/review?id=<id>`, detections open `/explore?event_id=<id>` on Frigate 0.14+.
- **Per-event dedupe**: a tracked object's `new → update → end` lifecycle now collapses to exactly one notification.
- **Custom dialog theme** shared across all popups (stats, connection status, URL edit, update prompts).

## Reliability

- Foreground service switched to `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` to sidestep the Android 14+ 6-hour cap that was killing the listener silently.
- Partial WakeLock + high-perf WifiLock keep the WebSocket ping loop alive during doze.
- 15-minute WorkManager watchdog revives the service if an OEM kills it.
- Dismissing the persistent notification now re-posts it within ~1 second (Android 14+ made FGS notifications user-dismissable).
- Force-reconnect on network regain: no more waiting up to 60 seconds of exponential backoff after a Wi-Fi flap.
- Build hardened: detekt integrated into the build, numerous code-quality and null-safety fixes.

## UI polish

- Redesigned Settings as drill-down categories (Connection, Notifications, Downloads, Advanced) with fade transitions and a back arrow.
- Goldman font on the toolbar title, matching the rest of the app suite.
- Settings hides the gear icon when you're already inside.

## Fixes

- URLs without a scheme no longer crash the app; auto-prefixes `http://` for internal, `https://` for external.
- Notification cameras picker summary was lying "All cameras selected" even when only a subset was active.
- Permission denial UX no longer shows a misleading Wi-Fi snackbar when the user denies an unrelated permission (notifications, mic).
- Update check respects the throttle on cold start; network or API failures now show a proper error instead of "No updates available".
