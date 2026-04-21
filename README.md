# Frigate Viewer

A seamless Android client for [Frigate NVR](https://frigate.video/) that intelligently switches between local and remote URLs based on your network, and delivers push-like notifications for alerts and detections.

**This app is not part of the official Frigate NVR software.**

<p align="center">
  <img src="screenshots/01-home.png" width="260" />
  <img src="screenshots/02-stats-popup.png" width="260" />
  <img src="screenshots/03-connection-status.png" width="260" />
</p>
<p align="center">
  <img src="screenshots/04-settings-root.png" width="260" />
  <img src="screenshots/05-notifications-settings.png" width="260" />
  <img src="screenshots/06-connection-settings.png" width="260" />
</p>

## Highlights

- **Network-aware URL switching**. Auto-detects your home Wi-Fi via an SSID allowlist and flips between internal and external Frigate URLs on the fly. No hand-editing configs when you walk out the door.
- **Always-on alert listener**. A foreground service keeps a persistent WebSocket to Frigate. Rich notifications with snapshot thumbnails deep-link into the Review or Explore page for that event. Survives doze, Wi-Fi switches and OEM kill policies.
- **Live system stats in the top bar**. CPU and GPU badges fed by the `/api/stats` endpoint, polled every 2 seconds while the app is visible. Tap either badge to open a full System Statistics panel with RAM, uptime, per-detector inference time, and per-camera FPS.
- **Connection status widget**. Tap the INT or EXT badge for a live dialog showing the current URL, connection latency graph (rolling 24 samples), Wi-Fi state and auto vs forced mode.
- **Category-based Settings**. Connection, Notifications, Downloads, Advanced. Drill-down screens with fade transitions. Per-URL Test button that probes the real server before save.
- **mTLS support**. Import a PKCS#12 client certificate once and the app re-uses it for the WebView, the notification WebSocket, HTTP polling and downloads.
- **Smart filtering for alerts**. Per-camera on/off, per-zone filtering, alert vs detection severity toggles, global + per-camera cooldowns, and dedupe per event so a single object tracked for 10 minutes is one notification, not twenty.
- **Hardware voice DSP for two-way talk**. Toggles `MODE_IN_COMMUNICATION` so echo cancellation and noise suppression kick in for doorbell / intercom cameras.

## How URL switching works

| Connection mode | Behavior |
|---|---|
| **Auto** | Current Wi-Fi SSID is in your home list: Internal URL. Anything else (cellular, guest Wi-Fi, airport): External URL. |
| **Internal** | Always Internal, regardless of network. |
| **External** | Always External. Useful for debugging remote access from home. |

Every URL change triggers a validation probe; the badge turns orange (INT, connected to home) or red (EXT, or unreachable). A 24-sample latency history feeds the graph in the status popup.

## Notifications

The background `FrigateAlertService` keeps one `wss://.../ws` connection open to Frigate and listens for `reviews` and `events` frames.

- **Alert** (severity `alert`): tap opens `/review?id=<review_id>`.
- **Detection** (severity `detection`): tap opens `/explore?event_id=<event_id>` (Frigate 0.14+).
- **Dedupe per event / review id** so the `new → update → end` lifecycle collapses to exactly one notification.
- **Zone filter**. If you allow-list zones for a camera, events without matching zones are dropped.
- **Android 14+ reliability**. Declared as `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (no 6 hour cap), holds partial WakeLock and high-perf WifiLock, and has a 15 minute WorkManager watchdog that revives the service if an OEM kills it. Dismissing the persistent notification fires a BroadcastReceiver that re-posts it within ~1 second.

On first enable the app prompts you to grant **Ignore battery optimizations**. This is needed to keep the listener alive on aggressive OEM skins (Samsung One UI, Xiaomi MIUI, etc.).

## Live stats

Both badges in the top bar and the full dialog are fed from Frigate's `/api/stats` endpoint, polled every 2 seconds while the activity is visible. Parsing tolerates Frigate 0.13 / 0.14 / 0.15+ schema drift (`cpu_usages`, `gpu_usages` vs legacy `gpus`, percent-suffixed string values, nested `service.uptime`).

The dialog surfaces:
- CPU and GPU cards with progress bars and hot-load tint (neutral, amber, red at or above 80%).
- RAM chip, uptime chip.
- Per-detector inference time (ms).
- Per-camera capture FPS and detection FPS.

If polling fails or Frigate goes away, the badges dim after 10 seconds instead of lying bright.

## Downloads

Frigate Viewer can download clips and snapshots directly from your Frigate instance:

- Works seamlessly on both internal (self-signed) and external networks.
- Configurable destination:
  - `Downloads/Frigate` (default)
  - Pictures folder (appears in Gallery)
  - Movies folder (appears in Gallery)
  - Downloads root
- Progress notifications with **Open** action on completion.

## Deep linking

The app registers the `frigate://` (and legacy `freegate://`) scheme:

| URI | Opens |
|---|---|
| `frigate://home` | Camera grid |
| `frigate://settings` | Settings root |
| `frigate://review/<id>` | Specific review segment |
| `frigate://event/<id>` | Specific event in the Explore view |
| `frigate://camera/<id>` | Specific camera (future) |

Great for home-automation integrations or Tasker-style shortcuts.

## Permissions

Minimum viable set; each is justified below. Everything else the OS normally asks for is **not** requested.

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Base connectivity |
| `NEARBY_WIFI_DEVICES` (API 33+) or `ACCESS_FINE_LOCATION` (API 32 and below) | Read SSID for auto-switching. Permission is version-dependent, with `neverForLocation` flag set on 13+ |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | Two-way talk to doorbell / intercom cameras |
| `POST_NOTIFICATIONS` | Alerts and detections |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the alert listener alive |
| `RECEIVE_BOOT_COMPLETED` | Re-start the listener after reboot |
| `WAKE_LOCK` | Keep the WS ping loop alive during doze |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional, prompted once on notification enable |
| `REQUEST_INSTALL_PACKAGES` | In-app update installer from GitHub releases |

## Requirements

- Android 10 (API 29) minimum, Android 13+ recommended.
- Frigate NVR with local and/or remote access configured. Notifications work on any Frigate version that broadcasts `reviews` / `events` over `/ws` (0.12+). Stats reading supports 0.13 through 0.17.

## Privacy

- Viewer only. All processing happens on your Frigate server. No cloud relay, no analytics, no telemetry.
- Session cookies and the `frigate_token` are stored in the app's private storage. The Frigate account password is held in `EncryptedSharedPreferences`.
- Downloaded files stay local.

## Support

If Frigate Viewer made your life easier and you'd like to say thanks, a coffee goes a long way towards keeping the app maintained. No pressure. Bug reports and PRs are equally appreciated.

<a href="https://www.buymeacoffee.com/sfortis" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## License

MIT. See [LICENSE](LICENSE).
