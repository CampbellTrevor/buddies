# Presence Protocol V1

Buddies uses Socket.IO at `/socket.io/` and a lowercase 64-character SHA-256
room ID derived from the configured shared key.

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

`location` and `activity` are nullable. Null is an explicit privacy tombstone,
not an omitted partial update. The server validates the record, replaces the
previous value in full, and assigns a monotonic `updatedAt` timestamp.

## Events

| Event | Client payload | Server result |
| --- | --- | --- |
| `connection-ack` | Room ID | Joins one application room |
| `broadcast` | JSON string or object | Emits canonical object to room peers and acknowledges |
| `ping` | Ack callback | Returns `{ "display name": PresenceV1 }` snapshot |

The event names match the useful portion of the earlier GIMP server contract,
but Buddies uses a strict and much smaller schema.
