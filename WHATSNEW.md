# 2.3

* **Faster sign-in.** Frigate's session cookie is preserved end-to-end, so the WebView opens straight to your cameras instead of bouncing through `/login` on first launch.
* **Per-server filter memory.** Switching the configured URL between two Frigate servers now keeps each server's camera/zone picks; flipping back restores them exactly.
* **Friendlier camera & zone pickers.** A "Select all / Deselect all" header at the top, with clear "No / All / X of N" summaries that mean what they say.
* **Reverse-proxy support.** If you put nginx / Caddy / Authelia in front of Frigate with HTTP Basic Auth, Phylax reuses the same username and password you've stored for Frigate's own login.
