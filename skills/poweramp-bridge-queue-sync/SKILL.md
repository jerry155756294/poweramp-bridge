---
name: poweramp-bridge-queue-sync
description: Verify and regress-test the existing Poweramp-backed MBRC now-playing queue and list behavior.
---

# Poweramp Bridge Queue Sync

Use this skill when `nowplayingqueue`, `nowplayinglist`, queue playback, or queue paging looks wrong in a sender.

## Current design

- `PowerampGateway.readNowPlayingItems()` prefers the active Poweramp playback category and falls back to queue rows.
- `readQueueItems()` queries Poweramp's queue provider using real queue and file identifiers.
- `MbrcProtocolAdapter` returns the standard paged shape: `{total, offset, limit, data}`.
- `nowplayinglistplay` dispatches into the active category when available, otherwise falls back to the current track path.

## Regression checks

1. Verify a non-empty active category and a non-empty queue.
2. Verify empty queue with a current-track fallback.
3. Verify empty queue and no current track produces a legal empty page.
4. Verify item identifiers and paths are Poweramp-backed rather than invented.
5. Verify sender selection maps to the intended zero-based queue position.

## Guardrails

- Do not store full queue contents in `BridgeUiState`.
- Preserve page shape and sender-visible item fields.
- Use provider/receiver evidence before changing queue behavior for a sender-only symptom.
