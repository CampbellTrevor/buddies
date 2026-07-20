# Buddies

Buddies is a focused RuneLite friends panel. It uses RuneLite's own friends
list for identity and online worlds, and keeps the shared presence layer small
and replaceable.

## Features

- Friend list with RuneLite-authoritative online status and world
- Shared recent combat/training activity and named location for friends running Buddies
- Stats tab with skill levels and ranks from the official OSRS hiscores
- Highscores tab with activities, boss scores, and ranks
- Instanced and sailing-aware local location capture
- No world-map rendering in this first version

Stats, highscores, and online status work without a Buddies server. Activity
and location require both players to:

1. Have each other on their RuneLite/OSRS friends lists.
2. Run Buddies.
3. Configure the same shared room key.

The shared key is hashed before it is sent as a room ID. Use a long random key;
it is still a bearer secret, not account authentication.

## Build

```powershell
.\gradlew.bat test
```

Launch a development RuneLite client:

```powershell
.\gradlew.bat run -DbuddiesDev=true
```

## Presence Server

The plugin defaults to the hosted presence relay at
[`https://buddies-presence.onrender.com`](https://buddies-presence.onrender.com/health).
It runs on Render's free tier, so the first connection after an idle period can
take about a minute while the service wakes up.

The bundled server keeps only short-lived in-memory presence and does not
persist locations:

```powershell
cd server
npm install
npm test
npm start
```

Give each buddy the same `Shared room key`. The `Presence server` setting can
point to a separately hosted relay when needed. The production Render setup is
defined in [`render.yaml`](render.yaml). See [the protocol](docs/PRESENCE_PROTOCOL.md)
and [architecture notes](docs/ARCHITECTURE.md) for deployment and trust details.

To include the optional Java-to-Node live transport test while the server is
running:

```powershell
$env:BUDDIES_INTEGRATION_URL = 'http://127.0.0.1:3000'
.\gradlew.bat test --tests com.buddies.presence.PresenceClientIntegrationTest
```

## Project Shape

Buddies grew out of our experimental location-display changes in a locally
modified GIMP Tracker checkout. The curated place-name resolver was developed
as part of those local changes and is not a feature of upstream GIMP Tracker;
that experiment was the progenitor of Buddies.

The implementation deliberately leaves behind GIMP Tracker's group model,
world-map rendering, pings, notes, and custom task system. The resolver is the
only substantial implementation carried forward from our local experiment.
