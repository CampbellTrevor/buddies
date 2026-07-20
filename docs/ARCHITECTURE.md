# Architecture

## Sources of truth

| Data | Source |
| --- | --- |
| Friend identity | RuneLite `FriendContainer` |
| Online status and world | RuneLite `Friend.getWorld()` |
| Skill levels and ranks | OSRS hiscores through RuneLite `HiscoreClient` |
| Activity evidence | Local attackable-NPC interaction and actual XP increases |
| Shared activity and location | Short-lived Buddies presence record |

Remote payloads cannot add users to the panel or mark an offline RuneLite
friend online. `BuddyDirectory` accepts shared presence only for an existing,
currently-online roster entry.

## Client flow

1. `FRIENDS_UPDATE` triggers a roster snapshot; game ticks reconcile as a
   fallback because RuneLite exposes no complete friend-status event set.
2. The selected friend's hiscores are fetched asynchronously and cached by
   RuneLite.
3. Game ticks capture the local immutable location DTO on the client thread.
4. A small Socket.IO client sends a full presence record roughly every five
   seconds and consumes snapshots/broadcasts off the client thread.
5. Swing mutations are marshalled to the event dispatch thread.

The client rejects duplicate or older server timestamps so repeatedly fetching
one cached record cannot keep it fresh. Freshness checks both local receipt time
and the server-owned source timestamp.

Activity is a local heuristic, not an authoritative Jagex activity feed. An
attackable NPC target is reported as combat and takes precedence over XP drops.
Training requires an actual XP increase, so boosted-level changes do not count.
Combat clears five seconds after interaction ends, and training clears after
thirty seconds without another XP gain.

## Privacy boundaries

Presence records are full replacements. Turning off location or activity sends
an explicit JSON `null`, clearing the prior server value and peer cache. Going
offline clears cached shared presence locally. The bundled server expires all
records and holds no persistent database.

Room possession is the synchronization authorization boundary. Client-side
friend filtering protects the UI but cannot prevent a room member from reading
or spoofing room data. Remote deployments should use TLS and high-entropy room
keys.
