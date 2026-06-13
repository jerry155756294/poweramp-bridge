---
name: poweramp-bridge-roadmap-dispatch
description: Coordinate the staged implementation roadmap for `poweramp-bridge` across multiple agents. Use when work must be split into stop-service stability, cover state handling, queue synchronization, and radio/stream synchronization without interface collisions.
---

# Poweramp Bridge Roadmap Dispatch

Use this skill to split the roadmap into parallel-safe workstreams.

## Dispatch Order

1. Run `poweramp-bridge-stop-stability` first.
2. Run `poweramp-bridge-cover-state` after the stop-stability agent has settled the new UI/service state names.
3. Run `poweramp-bridge-queue-sync` and `poweramp-bridge-radio-sync` only after the shared `PowerampController` expansion plan is agreed.

## Shared Contract

Read `references/implementation-contract.md` before assigning any worker skill.

## Ownership Split

- Agent A: `poweramp-bridge-stop-stability`
- Agent B: `poweramp-bridge-cover-state`
- Agent C: `poweramp-bridge-queue-sync`
- Agent D: `poweramp-bridge-radio-sync`

If you want fewer agents, combine queue + radio into one worker before combining any other pair.

## Merge Rules

- Let the stop-stability agent land `BridgeUiState` service-stop fields first.
- Let the queue agent introduce shared queue/radio paging data types only if the radio agent agrees to reuse them unchanged.
- Keep MBRC context names unchanged.
- Preserve page payload shape as `{total, offset, limit, data}`.
- Prefer diagnostics that explain the active source or failure path over silent fallbacks.

## Validation Handoff

- Stop-stability agent must report a deterministic manual-stop rule.
- Cover agent must report the final cover state machine and event strings.
- Queue agent must report query columns, fallback behavior, and payload examples.
- Radio agent must report list-query behavior, play-dispatch behavior, and which MBRC contexts now trigger stream playback.
