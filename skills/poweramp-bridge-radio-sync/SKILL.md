---
name: poweramp-bridge-radio-sync
description: Diagnose and complete Poweramp-backed radio-station listing and sender-side radio playback semantics.
---

# Poweramp Bridge Radio Sync

Use this skill when `radiostations` is empty, duplicated, incomplete, or a sender cannot turn a listed station into playback.

## Current design

`PowerampGateway.readRadioStations()` aggregates and normalizes stations from Poweramp streams, playlists, library files, active category entries, and HTTP queue entries. It records a source-count diagnostic for each query. `MbrcProtocolAdapter` returns stations in the normal paged response shape.

## Remaining verification boundary

Station listing is implemented. Sender-specific selection/play contexts still require real sender evidence before adding a dispatch mapping. Do not claim radio playback works merely because the station list is populated.

## Investigation order

1. Confirm Poweramp data access is available.
2. Capture the radio source-count diagnostic and inspect the paged reply.
3. Confirm the sender emits a selection/play context when a station is chosen.
4. Map only that observed context to an existing Poweramp-native URI or path dispatch.
5. Verify the resulting Poweramp track/category change and add an adapter regression test.

## Guardrails

- Keep Poweramp as the source of truth; do not create a bridge-owned radio database.
- Preserve real station identifiers, names, and URLs/paths.
- Return an explicit unavailable response rather than fake successful playback.
