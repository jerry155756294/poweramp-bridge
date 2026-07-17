# Poweramp Bridge

`poweramp-bridge` is an Android receiver that accepts the MBRC TCP/JSON protocol and bridges it to Poweramp through the Poweramp API. It lets an existing [musicbeeremote/mbrc](https://github.com/musicbeeremote/mbrc) sender control a Poweramp device with minimal or no sender-side changes.

The project is an independent compatibility implementation. It does not bundle or reuse `mbrc` or `mbrc-plugin` source code and is not affiliated with MusicBee Remote.

## Current capabilities

- Foreground Android receiver with a TCP MBRC listener and multicast discovery responder.
- Protocol v4 handshake with one logical sender, one broadcast socket, and short-lived request/probe sockets.
- Playback, seek, volume, repeat, shuffle, ratings, lyrics, covers, queue and playlist controls.
- Poweramp-backed library browsing, queue data, playlist data, and radio-station discovery.
- On-device connection status, diagnostics, and logcat evidence for protocol, command, Poweramp, and disconnect events.

## Compatibility boundaries

- Queue, radio, library, and cover paths are implemented but still need broad real-sender verification.
- Sender UI behavior and bridge execution are diagnosed separately. A shell payload or receiver log can prove the bridge path even when a sender UI action does not emit its expected command.
- Multi-sender coordination, shared-token authentication, and full MusicBee feature parity are outside the current scope.

## Same-device media-session caveat

On some Android builds, a same-device sender or SystemUI MediaSession can take media-button priority and cause Poweramp to receive `pause`. Treat that as a device media-session arbitration question until receiver protocol and Poweramp logs show a bridge command caused it.

## Build artifacts

GitHub Actions is the authoritative build path when CI output is requested.

- Workflow: `.github/workflows/android-ci.yml`
- Push/manual CI artifacts: `poweramp-bridge-release-apk`, `poweramp-bridge-release-aab`
- Pull-request validation artifact: `poweramp-bridge-pr-debug-apk`
- Manual release workflow: `.github/workflows/android-release.yml`

Use a signed release APK for installation or updates. The pull-request debug APK is for validation only and is not a stable update target.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
