# 2.4

* **Multiple Frigate servers.** Configure several deployments and switch between them from Settings → Connection → Server. Each profile keeps its own URLs, credentials, mTLS certificate, Wi-Fi auto-switch list, and camera/zone notification filters.
* **Clearer sign-in state.** When credentials are missing or wrong, the background listener pauses after a couple of retries and the persistent notification reads "Sign-in needed" instead of looping silently.
* **Works against unauthenticated Frigates.** The notification listener and camera/zone pickers no longer block when credentials aren't configured — Frigate setups without auth answer the API directly.
* **Cleaner Connection screen.** The Server picker lives at the top of Connection so it's clear which deployment the URLs and credentials apply to.
