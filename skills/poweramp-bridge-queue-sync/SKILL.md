---
name: poweramp-bridge-queue-sync
description: Replace stubbed `nowplayingqueue` and `nowplayinglist` replies in `poweramp-bridge` with Poweramp-backed queue synchronization. Use when MBRC queue responses must reflect the real Poweramp queue, fall back to the current track when the queue is empty, and emit diagnostics about which source was used.
---

# Poweramp Bridge Queue Sync

Read `../poweramp-bridge-roadmap-dispatch/references/implementation-contract.md` before editing.

## Primary Files

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampController.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampGateway.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol/MbrcProtocolAdapter.kt`
- `app/src/main/java/com/maxmpz/poweramp/player/TableDefs.kt`
- queue-related tests under `app/src/test/kotlin`

## Goals

- Back `NowPlayingQueue` and `NowPlayingList` with real Poweramp queue data.
- Fall back cleanly to the current track when the Poweramp queue is empty.
- Keep MBRC page payload shape unchanged.

## Required Changes

- Extend `PowerampController` with queue read APIs.
- Query `content://com.maxmpz.audioplayer.data/queue` from `PowerampGateway`.
- Map queue rows into a stable bridge-side item shape that preserves Poweramp ids instead of inventing fake identifiers.
- Make `NowPlayingList` mirror `NowPlayingQueue` unless evidence proves they diverge.
- Emit diagnostics indicating whether the reply came from:
  - Poweramp queue
  - current-track fallback
  - empty fallback

## Keep In Mind

- Prefer queue row id and real file id when both are available.
- Avoid storing full queue contents in `BridgeUiState`.
- Do not silently keep the old one-track stub once queue querying is available.

## Expected Evidence

- Tests for:
  - non-empty queue
  - empty queue with current track
  - empty queue without current track
- A documented item mapping that future radio work can mirror when appropriate.
