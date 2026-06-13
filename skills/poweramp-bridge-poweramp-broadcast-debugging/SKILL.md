---
name: poweramp-bridge-poweramp-broadcast-debugging
description: Diagnose `PowerampGateway` broadcast handling, action-specific crashes, latency noise, and log interpretation in `poweramp-bridge`. Use when playback state updates are unstable, Poweramp broadcasts may be crashing the process, or debug output needs to be mapped back to specific Poweramp actions.
---

# Poweramp Bridge Poweramp Broadcast Debugging

Use this skill when the likely fault line is inside `PowerampGateway` rather than the MBRC socket layer.

## Where To Look

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampGateway.kt`
- `PowerampBroadcastDiagnostics` in the same file
- `BridgeStateRepository` event history and logcat mirroring

## First Assumption

Treat sudden connection loss and bridge silence as possible process crash fallout when Poweramp broadcast handling is in question. Do not start by blaming sender disconnects if the app may have died.

## Action Mapping

The current high-signal broadcast actions are:

- `TRACK_CHANGED`
- `TRACK_CHANGED_EXPLICIT`
- `STATUS_CHANGED`
- `STATUS_CHANGED_EXPLICIT`
- `PLAYING_MODE_CHANGED`
- `TPOS_SYNC`

When debugging, always identify which action branch was entered before talking about the failure.

## Numeric Parsing Rule

Do not trust Poweramp extras to stay on one numeric type. Prefer tolerant readers that accept:

- `Long`
- `Int`
- numeric `String`
- missing values

`extractDurationMs()` is a known example where strict `getLong()` assumptions were dangerous.

## Interpretation Rules

- `TPOS_SYNC` is noisy and should not dominate the diagnosis.
- High latency in the UI may reflect a bad completion heuristic, not slow command dispatch.
- If a crash reappears, capture:
  1. last Poweramp action label
  2. extras key/type summary
  3. throwable type and message

## Common Pitfalls

- Do not re-enable deprecated large-object broadcast paths without a strong reason.
- Do not treat every disconnect as a socket problem if the app process may have crashed.
- Do not infer command latency from unrelated later events; distinguish dispatch latency from effect confirmation latency.
- Do not let `TPOS_SYNC` noise bury `TRACK_CHANGED` or `STATUS_CHANGED`.

## Good Outcomes

- Broadcast parsing never throws on mixed numeric types.
- Debug evidence can answer which Poweramp action caused the failure.
- Position sync remains available without flooding the useful signal.
