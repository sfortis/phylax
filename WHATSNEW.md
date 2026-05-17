# 2.4

* **Multiple Frigate servers.** Configure several deployments and switch between them from Settings → Connection → Server. Each profile keeps its own URLs, credentials, mTLS certificate, Wi-Fi auto-switch list, and camera/zone notification filters.
* **Clearer sign-in state.** When credentials are missing or wrong, the background listener pauses after a couple of retries and the persistent notification reads "Sign-in needed" instead of looping silently.
* **Works against unauthenticated Frigates.** The notification listener and camera/zone pickers no longer block when credentials aren't configured — Frigate setups without auth answer the API directly.
* **Cleaner Connection screen.** The Server picker lives at the top of Connection so it's clear which deployment the URLs and credentials apply to.
* **Pick your alert and detection sounds.** Settings → Notifications → Sounds opens a ringtone picker for each, with the bundled Phylax tones as defaults and an explicit Silent option.
* **Friendlier alerts.** Critical alerts no longer force the alarm volume to maximum, so an event arriving while you're listening to music won't blast your media at full volume.
