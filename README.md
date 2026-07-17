# Poweramp Bridge

`poweramp-bridge` is an Android receiver app that accepts the existing MBRC TCP/JSON protocol and bridges it to Poweramp through the Poweramp API.

The goal is to let an existing [musicbeeremote/mbrc](https://github.com/musicbeeremote/mbrc) sender connect with minimal or no sender-side changes while controlling a device that is running Poweramp.

## Current focus

- Frontend service driven receiver app
- MBRC-compatible TCP handshake and command flow
- Poweramp playback, seek, volume, repeat, and shuffle bridging
- Single logical sender support with one broadcast socket and multiple request sockets
- On-device status and debug UI for connection diagnostics

## Compatibility notes

- This project implements compatibility with the MBRC protocol.
- It is an independent reimplementation and is not affiliated with MusicBee Remote.
- It does not bundle the original `mbrc` or `mbrc-plugin` source code.

## Known issue

- On some Xiaomi/MIUI devices, when testing with localhost or a same-device sender app alongside Poweramp, MIUI SystemUI may prioritize the sender app's MediaSession and dispatch `pause` to Poweramp.
- Current evidence indicates this is a SystemUI MediaSession arbitration issue rather than a bridge protocol failure.

## Build

GitHub Actions runs debug compilation and unit tests for every change. Pushes and manual Actions runs additionally build a release-signed APK and AAB using the repository's permanent keystore; pull requests only expose a debug APK for validation. These workflows upload artifacts only and do not publish GitHub Releases.

- Workflow: `.github/workflows/android-ci.yml`
- Distribution APK artifact: `poweramp-bridge-release-apk`
- Google Play bundle artifact: `poweramp-bridge-release-aab`
- Pull-request validation artifact: `poweramp-bridge-pr-debug-apk`

Use the release APK for installation and updates. Do not use the pull-request debug APK as a release update; its CI debug certificate is intentionally not stable.

For local builds:

```bash
./gradlew assembleDebug
```

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
