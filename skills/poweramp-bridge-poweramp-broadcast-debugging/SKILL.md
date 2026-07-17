---
name: poweramp-bridge-poweramp-broadcast-debugging
description: Diagnose Poweramp broadcast parsing, playback-state changes, and process-survival issues inside `PowerampGateway`.
---

# Poweramp Bridge Poweramp Broadcast Debugging

Use this skill when the receiver receives a command but Poweramp behavior, state propagation, or process stability is unclear.

## Primary code

- `bridge/PowerampGateway.kt`
- `bridge/BridgeService.kt`
- `bridge/BridgeStateRepository.kt`

## Investigation order

1. Identify the exact incoming Poweramp action and the receiver event immediately before it.
2. Separate noisy position sync from track, status, mode, or audio-route changes.
3. If a value is malformed, record the extra key and runtime type; Poweramp numeric extras must be parsed tolerantly.
4. If sockets close afterwards, first establish whether the bridge process survived. A process crash makes later disconnects secondary symptoms.
5. Confirm whether the event was a direct bridge command effect, a Poweramp-side change, or SystemUI/media-session arbitration.

## Guardrails

- Keep high-signal events visible above `TPOS_SYNC` noise.
- Do not infer dispatch latency from a later unrelated state event.
- Do not reintroduce deprecated large-object broadcast handling without a concrete compatibility need.
- Add a regression test for mixed numeric extras or a teardown race when one is found.
