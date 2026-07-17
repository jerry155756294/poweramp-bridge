# AGENTS.md

## Project summary

`poweramp-bridge` is an Android receiver that reimplements the MBRC TCP/JSON protocol and maps it to Poweramp on the same device. The Android device is the receiver; the activity is for configuration and diagnostics while `BridgeService` owns runtime work.

Primary goal: let an existing MBRC sender connect with minimal or no sender-side changes over LAN or Tailscale-style networks.

Non-goals: importing `mbrc` or `mbrc-plugin` source, cloud relays, multi-sender coordination, shared-token authentication, and full MusicBee-only feature parity.

## Stack and project settings

- Kotlin, Jetpack Compose, Material 3, coroutines, `StateFlow`, DataStore, Moshi, Timber, and raw TCP sockets.
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`, Java/Kotlin target 17.
- GitHub Actions is the release-quality build path when the user asks for CI output.

## Architecture boundaries

1. `BridgeService` owns the foreground lifecycle, listener lifecycle, discovery responder, state broadcasts, and notification state.
2. `MbrcProtocolServer` accepts line-delimited JSON sockets; `ProtocolSessionManager` owns handshake, socket-role, and sender-ownership rules.
3. `MbrcProtocolAdapter` translates MBRC contexts and shapes sender-visible replies.
4. `PowerampGateway` and `PowerampController` contain all Poweramp intents, broadcasts, provider queries, and playback mapping.
5. `BridgeStateRepository` is the source of truth for UI status, playback, diagnostics, and recent events.
6. `MainActivity` renders configuration and diagnostics; it must not own protocol rules.

Keep protocol/session rules out of UI code and Poweramp-specific extras out of unrelated layers.

## Current behavior

- Protocol v4 is the primary path. The expected handshake is `player -> protocol -> init`.
- One logical sender may have one broadcast socket plus multiple request/probe sockets. A different sender is rejected with `single_client_only`; a same-sender broadcast replacement is normal.
- Implemented compatibility paths include transport controls, seek, volume, repeat, shuffle, ratings, lyrics, cover replies, playlists, queue/list replies, library browsing, radio-station listing, and multicast discovery.
- Provider-backed data remains capability-sensitive. Return legal empty or unavailable replies when Poweramp data cannot be read; never invent metadata or fake successful playback.

## What still needs evidence

- Broad real-sender verification for queue, radio selection, library drill-downs, and cover compatibility.
- Sender UI controls must be distinguished from protocol emission and Poweramp execution with receiver evidence.
- Reconnect behavior should always be validated under one broadcast socket plus short-lived request/probe sockets.
- Full multi-sender, authentication, and complete MusicBee parity are not planned current features.

## Validation and CI

- For a requested GitHub Actions build, use `gh.exe`, inspect the final completed run, and report its run id and artifact name.
- `.github/workflows/android-ci.yml` compiles and tests; non-PR runs upload signed `poweramp-bridge-release-apk` and `poweramp-bridge-release-aab`, while PRs upload `poweramp-bridge-pr-debug-apk`.
- `.github/workflows/android-release.yml` is the manual signed-release workflow.
- Do not present local Gradle output as the release gate when CI was requested. Do not stage `_ci_apk`, `_gh_artifacts`, `.kotlin`, or other local evidence.
- For runtime diagnoses, prefer a narrow screenshot/logcat window and only the ADB commands needed to establish the command path: sender emission, bridge receipt, Poweramp dispatch, and observed Poweramp state.

## Change checklist

When changing sockets or session behavior, verify:

- one broadcast plus multiple request/probe sockets;
- same-sender broadcast replacement;
- different-sender rejection;
- `verifyconnection` before handshake; and
- process survival when Poweramp broadcasts arrive during teardown.

When changing provider-backed replies, preserve paged payload shape: `{total, offset, limit, data}`. Add or update focused regression tests even when live-device evidence is also needed.
