---
name: poweramp-bridge-protocol-disconnect-triage
description: Classify MBRC socket closure, handshake, sender ownership, and reconnect behavior in `poweramp-bridge`.
---

# Poweramp Bridge Protocol Disconnect Triage

Use this skill when a sender reports a disconnect, reconnect loop, or handshake failure.

## Primary code

- `protocol/ProtocolSessionManager.kt`
- `protocol/MbrcProtocolServer.kt`
- `bridge/BridgeStateRepository.kt`

## Triage sequence

1. Identify the socket role: probe, request, or long-lived broadcast.
2. Record the furthest handshake step: before `player`, `player`, `protocol`, or `init`.
3. Inspect last inbound/outbound contexts and whether another same-sender broadcast replaced the socket.
4. Distinguish expected completion from read failure, parse failure, or `single_client_only` rejection.
5. If all sockets close together, check process and Poweramp broadcast evidence before blaming the network layer.

## Interpretation

- A short-lived `verifyconnection` probe can close successfully.
- A request socket closing after its reply is normal.
- Same-sender broadcast replacement is expected reconnect churn.
- `Stream closed`, `Socket closed`, `Connection reset`, and `Broken pipe` need socket-role context before being treated as errors.
