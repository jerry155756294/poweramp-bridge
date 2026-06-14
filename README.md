# Poweramp Bridge

`poweramp-bridge` is an Android receiver app that accepts the existing MBRC TCP/JSON protocol and bridges it to Poweramp through the Poweramp API.

The goal is to let an existing MBRC sender connect with minimal or no sender-side changes while controlling a device that is running Poweramp.

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

Recommended test setups:

- Realme sender -> Redmi or other receiver device
- PC or raw socket client -> bridge
- Any setup that avoids the sender app creating an active MediaSession on the same MIUI device as Poweramp

## Build

The repository includes a GitHub Actions workflow that builds a debug APK artifact on pushes and pull requests.

- Workflow: `.github/workflows/android-ci.yml`
- Artifact: `poweramp-bridge-debug-apk`

For local builds:

```bash
./gradlew assembleDebug
```

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
