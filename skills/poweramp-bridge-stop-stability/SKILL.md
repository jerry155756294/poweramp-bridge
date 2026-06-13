---
name: poweramp-bridge-stop-stability
description: Stabilize `Settings -> Stop Bridge`, service teardown, and manual-stop-versus-autoStart behavior in `poweramp-bridge`. Use when the bridge may restart itself after a manual stop, crash during service teardown, or need clearer stop diagnostics in the UI and notification state.
---

# Poweramp Bridge Stop Stability

Read `../poweramp-bridge-roadmap-dispatch/references/implementation-contract.md` before editing.

## Primary Files

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/MainActivity.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/BridgeService.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/BridgeStateRepository.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/bridge/BridgeModels.kt`

## Goals

- Make manual stop deterministic and crash-free.
- Prevent `autoStart=true` from immediately reviving the service after a manual stop.
- Expose enough stop-state information for UI and diagnostics.

## Required Changes

- Add an explicit manual-stop or stopping state owned by repository/UI state rather than inferred from service death.
- Gate `MainActivity` auto-start behavior on that state.
- Make `BridgeService` stop flow idempotent:
  - stop accepting new work
  - cancel ticker and latency jobs
  - stop protocol server
  - stop `PowerampGateway`
  - publish final stopped state
- Tolerate partial initialization and repeated stop calls in `onDestroy()`.

## Keep In Mind

- Do not weaken foreground-service startup behavior for normal starts.
- Do not push stop-only state into protocol classes.
- Do not require a persistent user preference for the manual-stop override; keep it in-process only.

## Expected Evidence

- A unit-testable rule for manual stop.
- Updated status/debug UI signals showing whether the bridge is stopping or manually held stopped.
- Notes about any remaining real-device-only validation gaps.
