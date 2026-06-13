---
name: poweramp-bridge-radio-sync
description: Implement Poweramp-backed `radiostations` listing and stream playback mapping in `poweramp-bridge`. Use when MBRC radio replies should come from Poweramp streams, radio selections should trigger real stream playback, and adapter diagnostics must show whether list or play behavior failed.
---

# Poweramp Bridge Radio Sync

Read `../poweramp-bridge-roadmap-dispatch/references/implementation-contract.md` before editing.

## Primary Files

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampController.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/PowerampGateway.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol/MbrcProtocolAdapter.kt`
- `app/src/main/java/com/maxmpz/poweramp/player/PowerampAPI.java`
- `app/src/main/java/com/maxmpz/poweramp/player/TableDefs.kt`
- radio-related tests under `app/src/test/kotlin`

## Goals

- Return Poweramp stream/radio rows from `radiostations`.
- Dispatch selected radio items to real Poweramp stream playback.
- Make failures visible as either list-query failures or play-dispatch failures.

## Required Changes

- Extend `PowerampController` with radio list and stream play APIs.
- Query `content://com.maxmpz.audioplayer.data/streams`.
- Preserve sender-meaningful item fields such as name and playable identifier.
- Prefer playback by Poweramp-native identifier or provider URI; fall back to URL playback only when needed.
- Replace sender-safe noop handling for relevant radio-play contexts with real playback handling.
- If sender context mapping is uncertain, log all candidate contexts with enough detail to finish device verification later.

## Keep In Mind

- Do not create a separate long-lived bridge-owned radio database.
- Do not fake successful playback if dispatch fails.
- Keep the page reply shape identical to other paged MBRC responses.

## Expected Evidence

- Tests for non-empty and empty `radiostations` replies.
- Tests for playback dispatch success and failure replies.
- A short note in code or diagnostics describing which contexts now trigger radio playback.
