# Contributing to Phylax

Thanks for taking the time. Bug reports with logs are as valuable as pull requests.

## Where to send a pull request

Open it against `dev`. The `main` branch only receives release merges, so a PR that targets
`main` will be retargeted before review.

Keep a pull request to one change. Unrelated comment edits, log message changes or
reformatting make a small fix hard to review, and they are the first thing that gets asked
about.

## Building

The project needs JDK 21, the same version CI uses, and Android SDK Platform 35. Android
Studio provides both; on a bare command line, point `ANDROID_HOME` at your SDK. Installing on
a device also needs adb, USB debugging enabled and the device authorised.

```
./gradlew app:assembleGithubDebug     # build the debug APK
./build-install.sh                    # build and install on a connected device
./gradlew detekt                      # static analysis
```

`detekt` runs before the build in CI and fails it on any finding, so run it locally first.
Findings that predate the gate are recorded in `config/detekt/baseline.xml`. New code is
expected to be clean rather than added to that file.

There are two product flavors, `github` and `fdroid`, built from the same code. The minimum
supported Android version is 10 (API 29) and the app targets API 35.

## Writing code

Match the surrounding code. Comments in this codebase explain why something is the way it
is, usually because of an OEM behaviour or an Android constraint that is not obvious from
the code. If your change makes a comment wrong, update it. If you remove one, say why in the
pull request.

Changes to notification delivery, background service lifetime or the WebView are hard to
judge from a diff alone. Say which device and Android version you tested on, and paste the
log lines that show the behaviour before and after.

## Reporting a bug

Include:

- The app version, and where you installed it from (F-Droid, Obtainium, GitHub release).
- Your Frigate version, and how the app reaches it (direct, reverse proxy, mTLS, VPN).
- The device and Android version.
- What you expected and what happened instead.

For anything involving notifications, a log is what makes it solvable:

```
adb logcat -s FrigateAlertService:V FrigateWsClient:V FrigateAuthManager:V AlertFilter:V
```

Read the log before you paste it. It can contain your Frigate URL and the first characters
of a session token. Redact anything you would not post publicly.

## Reporting a security problem

Do not open a public issue for an authentication bypass, a way to reach a server the user has
not configured, or anything that exposes credentials, certificates or session tokens. See
[SECURITY.md](SECURITY.md).

## Privacy and scope

Phylax is a viewer for a server you own. It has no backend, no analytics and no crash
reporting, and changes that would add any of those will not be merged.
