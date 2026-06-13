---
name: poweramp-bridge-cover-state
description: Improve `poweramp-bridge` cover loading, cover status signaling, and cover diagnostics without treating normal cover misses as fatal errors. Use when `nowplayingcover` is flaky, sender cover updates are inconsistent, or Poweramp cover-loading behavior needs a clearer state machine.
---

# Poweramp Bridge Cover State

Read `../poweramp-bridge-roadmap-dispatch/references/implementation-contract.md` before editing.

## Primary Files

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampGateway.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/BridgeStateRepository.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/BridgeModels.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol/MbrcProtocolAdapter.kt`
- cover-related tests under `app/src/test/kotlin`

## Goals

- Use one shared cover state for both status-only and payload replies.
- Make cover transitions visible as `loading`, `ready`, `missing`, or `error`.
- Avoid surfacing ordinary cover misses as bridge-wide failures.

## Required Changes

- Replace implicit cover-cache interpretation with an explicit cover state model.
- Make `currentCoverStatus()` and `currentCoverPayload()` read from the same state.
- Keep asynchronous loading, but emit a diagnostic state transition when loading starts and when it finishes.
- Preserve sender compatibility:
  - ready cover must still return `status=200` with base64
  - unavailable cover must still return `status=404` with `cover=null`
- Record high-signal event strings with track id and elapsed time when possible.

## Keep In Mind

- Do not remove `coverSignalRevision` style signaling unless you replace it with an equivalent sender-visible update path.
- Do not set `lastError` for ordinary `missing` results.
- Keep bitmap decode and scaling defensive; errors belong in diagnostics, not crashes.

## Expected Evidence

- Cover state transition tests.
- Adapter tests proving reply shape stays compatible.
- Clear diagnostic wording for loading, missing, ready, and error cases.
