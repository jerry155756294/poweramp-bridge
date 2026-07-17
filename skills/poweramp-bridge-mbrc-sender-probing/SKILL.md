---
name: poweramp-bridge-mbrc-sender-probing
description: Separate MBRC sender UI emission, bridge parsing, and Poweramp execution with controlled payloads and receiver evidence.
---

# Poweramp Bridge MBRC Sender Probing

Use this skill when a sender action appears ignored or a protocol context needs to be confirmed without guessing which side failed.

## Evidence boundary

Treat these as separate questions:

1. Did the sender emit the intended context?
2. Did the bridge receive and parse it?
3. Did `PowerampGateway` dispatch it?
4. Did Poweramp change state?

A sender screenshot alone answers none of the latter three reliably.

## Probe sequence

1. Capture a narrow receiver log window.
2. Reproduce once from the sender UI.
3. Inspect receiver `socket_in`, command, and Poweramp-event records.
4. If emission is ambiguous, send one controlled shell payload from the sender side using the real command context, for example:

```json
{"context":"playerplaypause","data":null}
```

5. Compare the controlled result with the sender UI result and state the boundary that the evidence proves.

## Guardrails

- Do not assume commands live in a generic `context="command"` envelope.
- Do not reuse an old payload without validating its JSON shape and target connection.
- Keep request, probe, and broadcast sockets distinct in the conclusion.
- Treat a media-session pause as a device/SystemUI question unless the protocol log shows a bridge pause command.
