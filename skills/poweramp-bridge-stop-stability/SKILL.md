---
name: poweramp-bridge-stop-stability
description: Regress-test and diagnose manual BridgeService stop, restart, and teardown behavior in `poweramp-bridge`.
---

# Poweramp Bridge Stop Stability

Use this skill when Settings stop/restart behavior, foreground notification state, or a teardown race regresses.

## Current design

- `BridgeStateRepository` owns `manualStopActive` plus stopping/stopped summaries for UI and notifications.
- `BridgeService` makes stop handling idempotent, cancels runtime jobs, stops the protocol server and Poweramp gateway, and publishes final state.
- A manual stop is an in-process override; a normal fresh start clears it.

## Regression checks

1. Stop from the in-app control while the service is fully running.
2. Repeat the stop request and verify no crash or duplicate teardown.
3. Stop during partial startup or an active socket session.
4. Start again and verify the manual-stop state is cleared only for the new normal start.
5. Check that notification and diagnostics agree with service state.

## Guardrails

- Do not move stop-only UI state into protocol classes.
- Do not weaken normal foreground startup to solve a stop-only bug.
- Treat socket closures after deliberate teardown as expected unless process evidence shows otherwise.
