# AGENTS.md

## Project Summary

`poweramp-bridge` is an Android receiver app that speaks the MBRC TCP/JSON protocol on the network side and bridges commands/state to Poweramp on the device side.

Primary goal:
- Let an existing MBRC sender app connect with minimal or no sender-side changes.

Non-goals for the current generation:
- Reusing `mbrc` or `mbrc-plugin` source code directly
- Implementing MusicBee-specific features that do not map cleanly to Poweramp
- Adding a cloud relay or internet-facing server

This repository is an independent reimplementation. It targets LAN / Tailscale style remote control, with the Android device acting as the receiver.

## Tech Stack

- Platform: Android
- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Concurrency: Kotlin Coroutines
- State management: `StateFlow`
- Persistence: Jetpack DataStore Preferences
- Networking: raw TCP sockets, line-delimited JSON messages
- JSON codec: Moshi-backed local protocol codec
- Logging: Timber
- Build: Gradle Kotlin DSL
- CI: GitHub Actions debug APK build artifact

Important project settings:
- `minSdk = 26`
- `targetSdk = 36`
- `compileSdk = 36`
- Java / Kotlin target: 17

## High-Level Architecture

Main runtime flow:
1. `BridgeService` runs as a foreground service.
2. `MbrcProtocolServer` accepts MBRC-compatible TCP socket connections.
3. `ProtocolSessionManager` owns socket/session/handshake rules.
4. `MbrcProtocolAdapter` translates MBRC messages into internal bridge actions and responses.
5. `PowerampGateway` talks to Poweramp via intents, broadcasts, and position sync hooks.
6. `BridgeStateRepository` is the single UI/state source for service status, connection state, playback, logs, and errors.
7. `MainActivity` exposes settings, status, and debug screens.

Key source areas:
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/data`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/MainActivity.kt`

## Current Architecture Decisions

### 1. Independent protocol implementation
- The project intentionally does not import or bundle `mbrc` / `mbrc-plugin` code.
- Compatibility is achieved by reimplementing the observed MBRC handshake and command semantics in Kotlin.

### 2. Receiver-first Android app
- The Android device is the receiver and Poweramp host.
- The app is service-driven; the activity is for configuration and diagnostics only.

### 3. Single logical sender model
- Current design allows one logical sender at a time.
- One sender may hold:
  - one broadcast socket
  - multiple request/probe sockets
- Different senders are rejected with `single_client_only`.

### 4. Protocol v4 as the main path
- Protocol version `4` is treated as the standard path.
- Legacy payloads are tolerated where practical, mainly for compatibility fallback.

### 5. Foreground service required
- Connection handling and Poweramp observation are built around a foreground service.
- This is necessary for background stability and user-visible lifecycle control.

### 6. Degrade unsupported features instead of faking them
- Queue/library/list features that are not yet mapped return legal empty responses where possible.
- Unsupported control actions return `commandunavailable`.
- The bridge avoids inventing metadata that Poweramp cannot supply.

### 7. On-device observability is a product feature
- Status and debug UI are considered core tooling, not optional extras.
- Recent connection rejection, disconnect, protocol event, command, and Poweramp event history should remain visible on-device.

## Current Implementation Status

Implemented:
- Foreground `BridgeService`
- TCP listener and line-based JSON protocol server
- MBRC-like handshake flow: `player -> protocol -> init`
- Request/probe socket behavior including `verifyconnection`
- Single logical sender session management
- Poweramp control mapping:
  - play / pause / playpause
  - next / previous / stop
  - seek
  - volume
  - repeat
  - shuffle
- State broadcast back to sender:
  - player status/state
  - repeat/shuffle/volume
  - now playing track/details/position
  - plugin version
- Position ticker while actively playing
- Poweramp cover payload path
- Local IP display in UI
- Protocol diagnostics:
  - recent protocol events
  - disconnect summaries
  - disconnect category / socket role / handshake state / last in/out context
- Logcat diagnostics for:
  - protocol events
  - received commands
  - disconnect summaries
  - high-signal Poweramp events
- GitHub Actions debug APK artifact workflow
- MIT license and repository README
- Real-device sender/receiver verification on Android phones

Partially implemented / fragile:
- Queue semantics
- Library browsing responses
- Cover compatibility may still need real-world sender verification
- Connection stability under repeated reconnect pressure
- Sender app command reliability is still uneven:
  - `play/pause` has been verified end-to-end from the real sender app
  - `playernext` has been verified end-to-end from a correct shell MBRC payload sent on the real sender phone
  - sender app UI `next` is still inconsistent and needs more investigation
- Some local unit test execution currently fails at test initialization, even though app compilation and debug APK assembly succeed

Not implemented yet:
- Discovery
- Shared token authentication
- Multi-sender coordination
- Full Poweramp queue/library provider mapping
- Release signing / release distribution flow

## Known Constraints and Risks

- MBRC compatibility is behavior-driven, not code-shared, so regressions are easiest to introduce in handshake/session edge cases.
- Poweramp intent/broadcast behavior can differ from MusicBee semantics, especially around repeat/queue/list features.
- The bridge is sensitive to sender reconnect timing because the sender may open/close multiple sockets in short bursts.
- Some existing files in the repo have had encoding issues in the past; prefer UTF-8 safe edits and verify UI text after changes.
- The working tree may include local artifacts or unrelated files; avoid staging `_gh_artifacts` or other local-only files.

## Build, Test, and CI Notes

Useful commands:

```bash
./gradlew compileDebugKotlin
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Current reality:
- `compileDebugKotlin` passes
- `assembleDebug` passes
- `testDebugUnitTest` currently has a test initialization / class loading issue in local runs and should be treated separately from app logic regressions
- For shared verification and APK output, prefer GitHub Actions over local builds.
- Treat GitHub Actions as the default build path for future APK generation unless the user explicitly asks for a local build.

CI:
- Workflow file: `.github/workflows/android-ci.yml`
- Output artifact: `poweramp-bridge-debug-apk`
- Default expectation:
  - use GitHub Actions for `compileDebugKotlin`
  - use GitHub Actions for `testDebugUnitTest`
  - use GitHub Actions for debug APK generation
- Latest verified CI build:
  - branch: `codex/github-actions-apk`
  - commit: `61e678e`
  - run id: `27399392409`
  - local artifact path: `_ci_apk/run_27399392409/app-debug.apk`

## Current Device Verification Snapshot

- Receiver:
  - device: `SM_A505GN` / A50
  - package: `com.jerry155756294.powerampbridge`
  - installed version: `0.1.1` / `versionCode 2`
  - foreground `BridgeService` confirmed running
  - professional diagnostics mode manually re-enabled after reinstall
- Sender:
  - device: `RMX1921`
  - sender app package: `com.kelsos.mbrc`
- Confirmed working paths:
  - sender app UI `play/pause` -> bridge receives `playerplaypause` -> Poweramp changes state
  - sender phone shell payload `playerplaypause` -> bridge executes successfully
  - sender phone shell payload `playernext` -> bridge executes successfully and Poweramp changes track
- Still under investigation:
  - sender app UI `next` does not yet reliably emit `playernext` in bridge diagnostics

## Current Debug Workflow

- Preserve the current `systemui pause` evidence on:
  - branch: `debug/current-systemui-pause`
  - tag: `bug-systemui-pause-current`
- Treat `codex/github-actions-apk` at `0b9552b` as the current evidence build:
  - sender echo pause is already being suppressed
  - sender-facing observation hold is partially effective
  - the remaining high-value signal is still `PSMediaSessionHelper.Callback onPause com.android.systemui`
- Use `test/rollback-before-stop-stability` at `08acbe8` as the rollback verification baseline.
- The current suspected regression point is `c097713` `Stabilize manual bridge stop flow`.
- Do not discard the current evidence build when testing rollback branches; keep evidence and rollback experiments on separate branches or worktrees.
- Prefer GitHub Actions artifacts for APK generation during this investigation.
- Avoid local Gradle builds unless the user explicitly asks for them; local RAM pressure and Windows-local test instability are active constraints.
- For this bug, treat these log sequences as the highest-value evidence:
  - `playerplaypause` or `playerplay`
  - Poweramp enters `playing`
  - `PSMediaSessionHelper.Callback onPause com.android.systemui`
  - Poweramp returns to `paused`
- If rollback `08acbe8` still reproduces the same pause path, shift the next experiment toward notification / media session A/B testing instead of adding more sender guards.

## Suggested Near-Term Priorities

1. Use the new protocol diagnostics to classify real disconnect cases on-device.
2. Stabilize reconnect behavior for the MBRC sender broadcast socket lifecycle.
3. Tighten queue/list semantics so the sender does not duplicate entries.
4. Finish sender app UI verification for `next` and other transport controls now that raw logcat diagnostics exist.
5. Improve Poweramp library / cover interoperability with real-device verification.
6. Fix the local unit test runner issue so protocol behavior can be regression-tested normally.

## Collaboration Notes For Future Agents

- Treat `BridgeStateRepository` as the source of truth for anything the UI needs to show.
- Keep protocol/session rules in `ProtocolSessionManager`; avoid scattering handshake logic across service/UI layers.
- Keep Poweramp-specific integration inside `PowerampGateway`; do not leak raw Poweramp extras through the rest of the app unless necessary.
- Prefer observability first when debugging socket problems.
- When changing socket/session behavior, always verify:
  - one broadcast + multiple request sockets
  - same-sender broadcast replacement
  - different-sender rejection
  - `verifyconnection` before handshake
