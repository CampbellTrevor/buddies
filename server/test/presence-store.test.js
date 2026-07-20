'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
  CapacityError,
  PresenceStore,
  isValidRoom,
  parsePresence
} = require('../presence-store');

const ROOM_A = 'a'.repeat(64);
const ROOM_B = 'b'.repeat(64);

function presence(overrides = {}) {
  return {
    version: 1,
    name: 'Alice',
    world: 341,
    location: { x: 3200, y: 3201, plane: 0 },
    activity: 'Fishing',
    updatedAt: 1_000,
    ...overrides
  };
}

test('room keys are exactly 64 lowercase hexadecimal characters', () => {
  assert.equal(isValidRoom(ROOM_A), true);
  assert.equal(isValidRoom('0123456789abcdef'.repeat(4)), true);
  assert.equal(isValidRoom('A'.repeat(64)), false);
  assert.equal(isValidRoom('g'.repeat(64)), false);
  assert.equal(isValidRoom('a'.repeat(63)), false);
  assert.equal(isValidRoom(null), false);
});

test('parser returns a canonical strict v1 full record', () => {
  const parsed = parsePresence(JSON.stringify(presence({
    name: '  Alice__Smith\u00a0 ',
    activity: '  Fishing  '
  })));

  assert.deepEqual(parsed, presence({
    name: 'Alice Smith',
    activity: 'Fishing'
  }));
});

test('parser preserves explicit null privacy fields', () => {
  const parsed = parsePresence(presence({ location: null, activity: null }));

  assert.equal(parsed.location, null);
  assert.equal(parsed.activity, null);
  assert.equal(Object.hasOwn(parsed, 'location'), true);
  assert.equal(Object.hasOwn(parsed, 'activity'), true);
});

test('parser rejects partial, extended, malformed, and oversized records', () => {
  const withoutLocation = presence();
  delete withoutLocation.location;
  assert.equal(parsePresence(withoutLocation), null);
  assert.equal(parsePresence({ ...presence(), extra: true }), null);
  assert.equal(parsePresence(presence({ version: 2 })), null);
  assert.equal(parsePresence(presence({ name: 'x'.repeat(33) })), null);
  assert.equal(parsePresence(presence({ world: 65_536 })), null);
  assert.equal(parsePresence(presence({ updatedAt: 0 })), null);
  assert.equal(parsePresence(presence({ activity: 'x'.repeat(41) })), null);
  assert.equal(parsePresence(presence({ location: { x: 0, y: 0, plane: 4 } })), null);
  assert.equal(parsePresence(presence({
    location: { x: 0, y: 0, plane: 0, region: 1 }
  })), null);
  assert.equal(parsePresence('{not json'), null);
  assert.equal(parsePresence(presence(), 20), null);
});

test('store fully replaces a normalized name and assigns monotonic timestamps', () => {
  let now = 500;
  const store = new PresenceStore({ ttlMs: 100, now: () => now });

  const first = store.put(ROOM_A, parsePresence(presence({
    name: 'Alice_Smith',
    updatedAt: 99_999
  })));
  now = 400;
  const second = store.put(ROOM_A, parsePresence(presence({
    name: 'alice smith',
    location: null,
    activity: null,
    updatedAt: 1
  })));

  assert.equal(first.updatedAt, 500);
  assert.equal(second.updatedAt, 501);
  assert.equal(second.location, null);
  assert.equal(second.activity, null);
  assert.deepEqual(Object.keys(store.snapshot(ROOM_A)), ['alice smith']);
  assert.deepEqual(store.stats(), { rooms: 1, records: 1 });
});

test('records expire at the TTL boundary and empty rooms are removed', () => {
  let now = 1_000;
  const store = new PresenceStore({ ttlMs: 10, now: () => now });
  store.put(ROOM_A, parsePresence(presence()));

  now = 1_009;
  assert.equal(Object.keys(store.snapshot(ROOM_A)).length, 1);
  now = 1_010;
  assert.deepEqual(Object.keys(store.snapshot(ROOM_A)), []);
  assert.deepEqual(store.stats(), { rooms: 0, records: 0 });
});

test('room, per-room, and total record caps are enforced', () => {
  const roomStore = new PresenceStore({
    maxRooms: 1,
    maxRecordsPerRoom: 1,
    maxTotalRecords: 10
  });
  roomStore.put(ROOM_A, parsePresence(presence()));

  assert.throws(
    () => roomStore.put(ROOM_A, parsePresence(presence({ name: 'Bob' }))),
    (error) => error instanceof CapacityError && error.code === 'room_capacity'
  );
  assert.throws(
    () => roomStore.put(ROOM_B, parsePresence(presence({ name: 'Carol' }))),
    (error) => error instanceof CapacityError && error.code === 'server_capacity'
  );

  const totalStore = new PresenceStore({
    maxRooms: 10,
    maxRecordsPerRoom: 10,
    maxTotalRecords: 1
  });
  totalStore.put(ROOM_A, parsePresence(presence()));
  assert.throws(
    () => totalStore.put(ROOM_B, parsePresence(presence({ name: 'Bob' }))),
    (error) => error instanceof CapacityError && error.code === 'server_capacity'
  );
});

test('snapshots do not expose mutable store records', () => {
  const store = new PresenceStore();
  store.put(ROOM_A, parsePresence(presence()));

  const first = store.snapshot(ROOM_A);
  first.Alice.location.x = 1;
  first.Alice.activity = null;
  const second = store.snapshot(ROOM_A);

  assert.equal(second.Alice.location.x, 3200);
  assert.equal(second.Alice.activity, 'Fishing');
});
