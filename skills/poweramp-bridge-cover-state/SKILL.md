---
name: poweramp-bridge-cover-state
description: Diagnose or regress-test Poweramp cover replies and their sender-visible state transitions in `poweramp-bridge`.
---

# Poweramp Bridge Cover State

Use this skill when `nowplayingcover` or library-cover behavior is stale, missing, repeatedly reloaded, or incompatible with a sender.

## Current design

- `PowerampGateway` owns cover loading and payload construction.
- `BridgeStateRepository` carries the sender-visible cover revision used by `BridgeService` to announce changes.
- The normal sender contract is `status=200` with base64 cover data when ready, or `status=404` with `cover=null` when unavailable.
- Ordinary misses are diagnostics, not global bridge failures. A repeated track broadcast must not repeatedly discard an unchanged cover.

## Investigation order

1. Identify the track id/path and whether it actually changed.
2. Check receiver diagnostics for loading, ready, missing, or error evidence.
3. Compare `currentCoverStatus()` with `currentCoverPayload()`; they must describe the same cached result.
4. Confirm the adapter reply shape before attributing a sender display issue to Poweramp.
5. Add a focused regression test for the observed transition.

## Guardrails

- Keep decoding and scaling defensive.
- Do not set global `lastError` for a normal missing cover.
- Preserve the revision-based sender notification path when changing cache behavior.
