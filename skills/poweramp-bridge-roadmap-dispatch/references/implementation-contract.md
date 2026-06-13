# Implementation Contract

Use this file as the shared contract across the roadmap worker skills.

## Locked Product Decisions

- Treat manual `Stop Bridge` as higher priority than `autoStart`.
- Keep that manual-stop override only for the current app process lifetime.
- Treat Poweramp as the source of truth for queue and radio/stream data.
- Keep `NowPlayingList` and `NowPlayingQueue` aligned unless real sender evidence proves otherwise.
- Do not rename existing MBRC protocol contexts.

## Shared Interface Expectations

- `BridgeUiState` may gain:
  - a service stop/stopping summary
  - a cover state summary
  - lightweight queue/radio diagnostics summaries
- `PowerampController` may gain:
  - queue page read APIs
  - radio/station page read APIs
  - stream/radio play APIs
- `MbrcProtocolAdapter` must keep page replies shaped as:
  - `total`
  - `offset`
  - `limit`
  - `data`

## Diagnostics Policy

- Log the active source for queue and radio replies.
- Log explicit cover states such as `loading`, `ready`, `missing`, and `error`.
- Keep temporary cover misses out of global bridge error state.
- Prefer sender-safe failures such as `commandunavailable` or shaped payload errors over fake success replies.

## Testing Minimums

- Add or update unit tests for each new behavior.
- Preserve existing protocol adapter tests unless the new behavior intentionally replaces a stub.
- Treat `testDebugUnitTest` instability as a known repo issue; still add tests even if the runner needs separate follow-up.
