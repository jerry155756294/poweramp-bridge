---
name: poweramp-bridge-mbrc-sender-probing
description: Probe MBRC sender behavior, payload shapes, and sender-versus-bridge failure boundaries for `poweramp-bridge`. Use when commands appear missing, sender UI behavior is inconsistent, shell payloads need to be crafted, or protocol symptoms must be separated from sender app problems.
---

# Poweramp Bridge MBRC Sender Probing

Use this skill when the bridge may be innocent and the sender path needs to be proven or falsified with controlled MBRC payloads.

## Core Principle

Separate these cases deliberately:

1. sender UI failed to emit the intended command
2. sender emitted a command but the bridge did not parse it
3. bridge parsed it but Poweramp did not act on it

Do not collapse all three into "the connection is broken."

## Known Working Payload Rule

For this repository, command payload shape matters. A shell probe that pretends commands live in `context=\"command\"` is misleading.

Prefer a command-shaped message like:

```json
{"context":"playerplaypause","data":null}
```

The bridge has already shown that correct shell payloads can prove behavior that the sender UI sometimes fails to reproduce consistently.

## Probe Workflow

1. Confirm the sender and receiver devices are both reachable.
2. Capture a narrow receiver log window before the test.
3. Reproduce from the sender UI first if the user cares about UI behavior.
4. If ambiguous, send a controlled shell payload from the sender side.
5. Compare what the receiver logged:
   - `socket_accepted`
   - `socket_in`
   - command name
   - Poweramp result

## Repository-Specific Lessons

- `playerplaypause` has been proven end-to-end from both sender UI and shell payloads.
- `playernext` has been proven end-to-end from a correct shell payload.
- Sender app UI `next` has been observed to be inconsistent; some reproductions only showed `player` and `protocol` on the receiver, with no `playernext`.
- That pattern points to sender-side UI or sender-side flow instability more than bridge execution failure.

## What To Conclude From Logs

- If the receiver never logs `playernext`, do not claim Poweramp ignored `next`.
- If the receiver logs `playernext` and Poweramp track changes, the bridge path is healthy.
- If the receiver logs `unknowncommand` or JSON parse failures, validate the outgoing payload shape before changing bridge code.

## Pitfalls

- Do not trust a sender screenshot alone when the receiver has better protocol evidence.
- Do not reuse old payload files without checking their JSON shape.
- Do not mix probe sockets, request sockets, and broadcast sockets in explanations unless logs actually show which one failed.
