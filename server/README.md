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

The server uses Socket.IO 4.x at the default `/socket.io/` path. A client emits:

- `connection-ack(room, callback?)` to enter one room. `room` must be exactly
  64 lowercase hexadecimal characters (the plugin sends a SHA-256 digest).
- `broadcast(record, callback?)` with either a JSON string or an object. The
  server sends the canonical object to other sockets in that room, excluding
  the sender.
- `ping(callback)` to receive the current name-keyed snapshot. The callback is
  always passed an object; it receives `{}` before a valid room is joined.

Successful broadcast acknowledgements are `{ "ok": true, "updatedAt": ... }`.
Rejected broadcasts use `{ "ok": false, "error": "..." }`.

Every broadcast is a complete version 1 replacement:

```json
{
  "version": 1,
  "name": "Player Name",
  "world": 341,
  "location": { "x": 3200, "y": 3200, "plane": 0 },
  "activity": "Fishing",
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
| `MAX_CONNECTIONS` | `5000` | Maximum simultaneous Socket.IO connections |
| `MAX_SOCKETS_PER_ROOM` | `250` | Maximum joined sockets in one room |

Expired records and empty rooms are pruned periodically and during reads and
writes. Restarting the process clears everything.

## Security and privacy

A room digest is a bearer secret, not an account or an authorization system.
Use a high-entropy shared key (at least 128 random bits), distribute it outside
the game, and rotate it if it is exposed. Do not use a memorable word as the
shared key.

The relay never logs room identifiers or presence payloads. It only prints a
generic startup line or startup failure. Put access controls and rate limits at
the reverse proxy as appropriate for a public deployment.
