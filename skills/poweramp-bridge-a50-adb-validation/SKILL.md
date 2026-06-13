---
name: poweramp-bridge-a50-adb-validation
description: Install and validate `poweramp-bridge` builds on the Samsung A50 receiver with adb, runtime inspection, and log capture. Use when a task needs real-device verification, foreground service checks, Poweramp runtime observation, or evidence from the A50 instead of emulator-only reasoning.
---

# Poweramp Bridge A50 ADB Validation

Use this skill for any request that says to install, verify, observe, or diagnose the receiver app on the real Samsung A50 device.

## Known Devices

- Receiver: `SM_A505GN` / Samsung A50
- Expected adb target: `adb-R58M90SGGNT-ca5RAT._adb-tls-connect._tcp`
- Package: `com.jerry155756294.powerampbridge`

## Workflow

1. Confirm the A50 is connected in `adb devices`.
2. Install the chosen APK, usually the latest GitHub Actions debug APK.
3. Verify the installed `versionName` and `versionCode`.
4. Bring the app to a usable state and confirm `BridgeService` is running.
5. Reproduce the user-visible action.
6. Capture device evidence: `logcat`, `dumpsys activity services`, `dumpsys media_session`, screenshots if needed.

## Runtime Checks

- Verify the package is installed before diagnosing higher-level issues.
- Verify `BridgeService` is actually running in foreground mode, not just that the app process exists.
- If a command appears ignored, separate these layers:
  1. sender emitted command
  2. bridge received protocol event
  3. bridge emitted Poweramp command
  4. Poweramp state actually changed

## Common Pitfalls

- Starting the service from adb shell may hit export or permission limitations that do not reflect the in-app path.
- A connected device does not guarantee the bridge is running.
- A successful install does not guarantee professional diagnostics mode stayed enabled after reinstall.
- A UI symptom on the sender can still be sender-side even when the receiver is healthy.

## Evidence To Collect

- installed version info
- `dumpsys activity services` for `BridgeService`
- filtered `adb logcat` covering the reproduction window
- `dumpsys media_session` when playback behavior is under question
- screenshot evidence when the user specifically cares about visible latency or UI state

## Interpretation Rules

- If A50 log shows `Protocol: socket_in:...:playerplaypause` and Poweramp state changes, the bridge path is working.
- If Poweramp changes track after a shell-sent `playernext`, treat the bridge execution path as proven even if sender UI `next` still looks flaky.
- If the log only shows `player` and `protocol` without `playernext`, suspect sender-side UI flow, sender timing, or transport initiation rather than immediately blaming Poweramp.
