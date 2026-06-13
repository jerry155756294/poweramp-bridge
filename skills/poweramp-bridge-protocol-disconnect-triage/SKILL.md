---
name: poweramp-bridge-protocol-disconnect-triage
description: Classify `poweramp-bridge` socket disconnects, handshake transitions, reconnect replacements, and misleading `peer_closed` symptoms. Use when MBRC sender connections are unstable, request and broadcast sockets are being confused, or disconnect logs need to be translated into actual bridge behavior.
---

# Poweramp Bridge Protocol Disconnect Triage

Use this skill when a report sounds like "the connection suddenly dropped" and you need to decide whether the bridge, sender, or a normal short-lived socket caused it.

## Primary Files

- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol/ProtocolSessionManager.kt`
- `app/src/main/kotlin/com/jerry155756294/powerampbridge/protocol/MbrcProtocolServer.kt`
- `BridgeStateRepository` disconnect and protocol event history

## First Split

Always ask which socket role you are looking at:

- probe socket
- request socket
- broadcast socket

Do not reason about all disconnections as if they were the main long-lived sender connection.

## Known Good Classification Goals

Prefer categories that answer intent, not just mechanics:

- probe completed normally
- request completed normally
- request peer closed after command
- broadcast replaced by same client
- broadcast closed before player
- broadcast closed during handshake
- broadcast closed after init
- socket read error
- sender rejected because of `single_client_only`

## Triage Workflow

1. Identify the socket role.
2. Check whether handshake reached `player`, `protocol`, or `init`.
3. Inspect the last inbound and outbound protocol context.
4. Check whether another socket from the same client replaced this one.
5. Separate expected close paths from true read or parse failures.

## Interpretation Rules

- `verifyconnection` and similar probe flows can be short-lived and healthy.
- A request socket closing after answering a command should not be narrated like a crash.
- Broadcast replacement by the same client is expected churn, not proof of instability by itself.
- `Stream closed`, `Socket closed`, `Connection reset`, and `Broken pipe` can be expected close signatures depending on context.
- If the app process crashed, downstream socket closures are symptoms, not root cause.

## Common Pitfalls

- Do not diagnose sender instability from one `peer_closed` string without context.
- Do not collapse handshake violations and expected request completion into the same warning bucket.
- Do not ignore same-client replacement behavior when reconnect pressure is high.
- Do not treat socket warnings as the primary bug if Poweramp broadcast handling may have killed the process first.
