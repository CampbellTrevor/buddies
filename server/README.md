# Buddies presence server

This directory contains the optional, ephemeral presence relay used by the
Buddies RuneLite plugin. It keeps state only in memory and does not require a
database.

## Run locally

Node.js 18 or newer is required.

```sh
npm ci
npm test
npm start
```

The server listens on `0.0.0.0:3000` by default. Configure the plugin's
**Presence server** setting with the reachable HTTP or HTTPS address. A reverse
proxy should provide TLS when the server is reachable over the internet.

`GET /health` returns a small readiness response. It deliberately does not
include room, connection, or presence data.

## Wire protocol

The plugin opens a raw RFC 6455 WebSocket at `/presence/v1`. Every application
message is one UTF-8 JSON object:

- `{ "type": "join", "room": "..." }` enters one room. The room must be
  exactly 64 lowercase hexadecimal characters. A successful join returns
  `{ "type": "joined" }`.
- `{ "type": "broadcast", "presence": { ... } }` replaces the sender's
  record. Room peers receive `{ "type": "presence", "presence": { ... } }`.
- `{ "type": "snapshot" }` returns `{ "type": "snapshot", "presences": {
  ... } }`. A client that has not joined receives an empty snapshot.

A valid broadcast also receives an `ack` message containing its server-owned
`updatedAt`. Rejections receive `{ "type": "error", "code": "..." }`.
Unknown message types, additional envelope properties, malformed JSON, and
binary messages are rejected.

The relay temporarily retains Socket.IO 4.x at `/socket.io/` for older
development clients. That endpoint uses `connection-ack`, `broadcast`, and
`ping`; it shares records, room limits, and peer broadcasts with `/presence/v1`.
The RuneLite plugin no longer includes or uses a Socket.IO dependency.

Every broadcast is a complete version 1 replacement:

```json
{
  "version": 1,
  "name": "Player Name",
  "world": 341,
  "location": { "x": 3200, "y": 3200, "plane": 0 },
  "activity": "Training Fishing",
  "updatedAt": 1784500000000
}
```

All six properties are required and additional properties are rejected.
`location` and `activity` may be explicit `null` values. Those nulls replace
and clear previously shared values; omitting either property is rejected. The
server replaces the submitted `updatedAt` with a monotonically increasing
server timestamp.

Names are normalized by converting underscores and non-breaking spaces to
ordinary spaces and collapsing whitespace. The normalized limit is 32
characters. Worlds are integers from 0 through 65535. Location coordinates
must use integer `x` and `y` values from 0 through 16383 and a `plane` from -1
through 3. Activity text is trimmed and limited to 40 characters.

## Limits

The defaults can be changed with environment variables:

| Variable | Default | Purpose |
| --- | ---: | --- |
| `HOST` | `0.0.0.0` | Listen address |
| `PORT` | `3000` | Listen port |
| `PRESENCE_TTL_MS` | `90000` | Time before an unrefreshed record expires |
| `MAX_PAYLOAD_BYTES` | `16384` | Maximum UTF-8 record size |
| `MAX_ROOMS` | `1000` | Maximum rooms holding live records |
| `MAX_RECORDS_PER_ROOM` | `200` | Maximum live names per room |
| `MAX_TOTAL_RECORDS` | `10000` | Maximum live records across all rooms |
| `MAX_CONNECTIONS` | `5000` | Maximum simultaneous connections across both endpoints |
| `MAX_SOCKETS_PER_ROOM` | `250` | Maximum joined sockets in one room across both endpoints |

The raw WebSocket endpoint sends ping frames every 30 seconds and terminates
connections that do not answer. Expired records and empty rooms are pruned
periodically and during reads and writes. Restarting the process clears
everything.

## Security and privacy

A room digest is a bearer secret, not an account or an authorization system.
Use a high-entropy shared key (at least 128 random bits), distribute it outside
the game, and rotate it if it is exposed. Do not use a memorable word as the
shared key.

The relay never logs room identifiers or presence payloads. It only prints a
generic startup line or startup failure. Put access controls and rate limits at
the reverse proxy as appropriate for a public deployment.
