/handoff

# Poweramp Bridge Migration Handoff

## Project
- Name: `poweramp-bridge`
- Type: Android receiver app for MBRC-compatible remote control of Poweramp
- Repo role: independent reimplementation, not a code copy of `mbrc` or `mbrc-plugin`
- License: MIT

## Goal
- Accept the existing MBRC TCP/JSON protocol from a sender device
- Bridge sender commands and status expectations to Poweramp on Android
- Keep sender-side changes minimal or unnecessary

## Tech Stack
- Kotlin
- Android SDK 36 / minSdk 26
- Jetpack Compose + Material 3
- Kotlin Coroutines + StateFlow
- DataStore Preferences
- Raw TCP sockets
- Moshi-based JSON codec
- Timber
- Gradle Kotlin DSL
- GitHub Actions for debug APK artifacts

## Architecture
- `BridgeService`
  - foreground service
  - owns listener lifecycle, notification, and state sync loop
- `MbrcProtocolServer`
  - TCP accept loop
  - line-based JSON read/write
  - protocol event logging
- `ProtocolSessionManager`
  - handshake state machine
  - logical client ownership
  - broadcast/request/probe socket rules
- `MbrcProtocolAdapter`
  - MBRC command routing
  - response shaping
  - empty/legal fallback behavior for unsupported features
- `PowerampGateway`
  - Poweramp intent API wrapper
  - status/track/mode receiver
  - position sync and seek translation
- `BridgeStateRepository`
  - single source of truth for UI, connection state, playback, logs, and errors
  - now also mirrors key protocol/command/Poweramp events into `logcat`
- `MainActivity`
  - settings tab
  - status tab
  - debug tab

## Current Protocol Decisions
- Primary path is protocol v4
- One logical sender at a time
- One logical sender may hold:
  - one broadcast socket
  - multiple request sockets
  - probe sockets for `verifyconnection`
- Same sender may replace stale broadcast socket
- Different sender is rejected with `single_client_only`
- Unsupported list/library features should return legal empty responses when possible
- Unsupported control features should return `commandunavailable`

## Latest Verified Code / CI State
- Branch: `codex/github-actions-apk`
- Latest relevant commit: `61e678e`
- Commit message: `Add logcat diagnostics for bridge events`
- GitHub Actions workflow: `.github/workflows/android-ci.yml`
- Latest verified run:
  - run id: `27399392409`
  - status: success
  - artifact name: `poweramp-bridge-debug-apk`
  - local artifact path: `_ci_apk/run_27399392409/app-debug.apk`

## Current Device State

### Receiver
- Device: `SM_A505GN` / A50
- ADB id: `adb-R58M90SGGNT-ca5RAT._adb-tls-connect._tcp`
- Package: `com.jerry155756294.powerampbridge`
- Installed version: `0.1.1` / `versionCode 2`
- Current status:
  - app launched
  - professional diagnostics mode re-enabled after reinstall
  - foreground `BridgeService` confirmed via `dumpsys activity services`
  - Poweramp app available on device and used for live verification

### Sender
- Device: `RMX1921`
- ADB id: `adb-3749d43-aKYYD1._adb-tls-connect._tcp`
- Sender app package: `com.kelsos.mbrc`

## Implemented
- Foreground receiver app
- MBRC-like handshake: `player -> protocol -> init`
- Request socket and probe socket handling
- Poweramp mapping for:
  - play / pause / playpause
  - next / previous / stop
  - seek
  - volume
  - repeat
  - shuffle
- Playback status and now-playing sync back to sender
- Position ticker while playing
- Cover status path
- Current IP display in UI
- Disconnect diagnostics:
  - recent protocol events
  - disconnect category
  - socket role
  - handshake state
  - last inbound context
  - last outbound context
- Additional logcat diagnostics from `BridgeStateRepository`:
  - `Protocol: ...`
  - `Command: ...`
  - `Poweramp: ...`
  - `Disconnect: ...`
- README and LICENSE
- GitHub Actions debug APK artifact build

## Real-Device Findings

### Confirmed Good
- RMX1921 sender app connects to A50 bridge successfully.
- A50 receives and logs:
  - `player`
  - `protocol`
  - `init`
  - request commands such as `nowplayingdetails`
- Sender app UI `play/pause` has now been observed working end-to-end:
  - bridge logs `playerplaypause`
  - `Command: playerplaypause: true`
  - Poweramp logs `Status changed (STATUS_CHANGED): paused`
- Sender phone shell payload `playerplaypause` works end-to-end.
- Sender phone shell payload `playernext` works end-to-end.
  - A50 log shows `Protocol: socket_in:...:playernext`
  - A50 log shows `Command: playernext:`
  - A50 log shows `Poweramp: Track changed (TRACK_CHANGED): ...`
  - `dumpsys media_session` confirms track metadata changed from:
    - `XNOR XNOR XNOR, Frums, Metacontinues`
    - to `Yeh Hua Dam (Aka_ Look at the Owl), Darkie, Cambodian Cassette Archives...`

### Still Fragile / Open
- Sender app UI `next` is still inconsistent.
  - In some runs the sender remains on `Now playing` but bridge only sees `player / protocol`, with no `playernext`.
  - This currently looks more like sender-side UI / touch / flow instability than a bridge execution failure, because shell `playernext` is confirmed good.
- Queue semantics remain shallow.
- Library/list compatibility remains mostly legal-empty.
- Cover interoperability still needs more sender verification.
- Local `testDebugUnitTest` still fails with test initialization / class loading issues.

## Important Evidence Files In Workspace
- CI artifact:
  - `_ci_apk/run_27399392409/app-debug.apk`
- Sender shell payloads:
  - `_ci_apk/sender_playerplaypause_payload.jsonl`
  - `_ci_apk/sender_playernext_payload.jsonl`
- Verified shell `playerplaypause` evidence:
  - `_ci_apk/a50_011_after_shell_play3_media.txt`
  - `_ci_apk/a50_011_after_shell_play3_logcat.txt`
- Verified sender app UI `play/pause` evidence:
  - `_ci_apk/a50_011_after_ui_play2_logcat.txt`
- Verified shell `playernext` evidence:
  - `_ci_apk/a50_before_shell_next_media.txt`
  - `_ci_apk/a50_after_shell_next_media.txt`
  - `_ci_apk/a50_after_shell_next_logcat.txt`
- Sender UI snapshots:
  - `_ci_apk/rmx_011_before.png`
  - `_ci_apk/rmx_011_after_ui_next2.png`

## Build / Test Status
- Verified app build path:
  - GitHub Actions build only for release verification
- Local Gradle state remains useful for development:
  - `./gradlew compileDebugKotlin`
  - `./gradlew assembleDebug`
- Current local test issue:
  - `./gradlew testDebugUnitTest`
  - still fails during test initialization / class loading

## Recommended Next Steps
1. Continue real-device investigation of sender app UI `next` now that bridge-side `playernext` is proven good via shell.
2. Use the new logcat diagnostics as the primary truth source for sender UI command emission.
3. Tighten queue/list behavior so sender UI cannot duplicate or misread items.
4. Keep validating cover behavior against the real sender.
5. Fix the local unit-test class loading issue so protocol changes can be regression tested normally.

## Notes For The Next Agent / Engineer
- Treat `BridgeStateRepository` as the source of truth for UI and diagnostics.
- Keep protocol logic in `ProtocolSessionManager`.
- Keep Poweramp-specific behavior in `PowerampGateway`.
- Prefer evidence from:
  - `adb logcat`
  - `dumpsys media_session`
  - sender/receiver screenshots
  over assumptions from UI alone.
- When testing sender app UI, compare it against a shell payload from the same sender phone to separate sender UI bugs from bridge bugs.
- Do not stage `_ci_apk` or other local-only artifacts unless explicitly requested.
