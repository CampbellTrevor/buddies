# Presence Protocol V1

Buddies uses a raw RFC 6455 WebSocket at `/presence/v1`. The configured HTTP
or HTTPS server address is mapped directly to `ws` or `wss`. A room is identified
by the lowercase 64-character SHA-256 digest of the configured shared key.

Each WebSocket frame is a UTF-8 JSON object. The client first joins one room:

```json
{ "type": "join", "room": "0123456789abcdef..." }
```

The server confirms the join with `{ "type": "joined" }`. The client then uses
these messages:

| Direction | Type | Payload or result |
| --- | --- | --- |
| Client to server | `broadcast` | `{ "type": "broadcast", "presence": PresenceV1 }` |
| Client to server | `snapshot` | `{ "type": "snapshot" }` |
| Server to peer | `presence` | `{ "type": "presence", "presence": PresenceV1 }` |
| Server to client | `snapshot` | `{ "type": "snapshot", "presences": { "display name": PresenceV1 } }` |
| Server to client | `ack` | Confirms a valid broadcast and includes `updatedAt` |
| Server to client | `error` | Rejects a message with a stable `code` value |

Unknown message types, extra envelope properties, malformed JSON, and binary
messages are rejected. The server sends RFC 6455 ping frames for connection
liveness; OkHttp answers those automatically. The client reconnects with a
bounded exponential delay and rejoins before sending presence.

## Record

```json
{
  "version": 1,
  "name": "Player Name",
  "world": 302,
  "location": { "x": 3200, "y": 3200, "plane": 0 },
  "activity": "Training Fishing",
  "updatedAt": 1784500000000
}
```

All six properties are required. `location` and `activity` are nullable. Null is
an explicit privacy tombstone, not an omitted partial update. The server
validates the record, replaces the previous value in full, and assigns a
monotonic `updatedAt` timestamp.

## Compatibility

The relay temporarily also accepts the earlier Socket.IO event contract at
`/socket.io/` so development builds already in use can continue communicating
during rollout. Both endpoints share the same records and broadcasts. The
RuneLite plugin itself uses only OkHttp and the raw `/presence/v1` endpoint.
