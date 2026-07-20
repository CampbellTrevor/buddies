'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { io: connectClient } = require('socket.io-client');
const { createPresenceServer } = require('../server');

const ROOM_A = 'a'.repeat(64);
const ROOM_B = 'b'.repeat(64);

function presence(overrides = {}) {
  return {
    version: 1,
    name: 'Alice',
    world: 341,
    location: { x: 3200, y: 3201, plane: 0 },
    activity: 'Fishing',
    updatedAt: 1,
    ...overrides
  };
}

async function withServer(options, callback) {
  const application = createPresenceServer(options);
  const address = await application.listen(0, '127.0.0.1');
  const url = `http://127.0.0.1:${address.port}`;
  const clients = [];

  const connect = async (clientOptions = {}) => {
    const socket = connectClient(url, {
      forceNew: true,
      reconnection: false,
      transports: ['websocket'],
      ...clientOptions
    });
    clients.push(socket);
    await once(socket, 'connect');
    return socket;
  };

  try {
    await callback({ application, connect, url });
  } finally {
    for (const client of clients) {
      client.disconnect();
    }
    await application.close();
  }
}

function once(emitter, event, timeoutMs = 1_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      emitter.off(event, handler);
      reject(new Error(`Timed out waiting for ${event}`));
    }, timeoutMs);
    const handler = (...args) => {
      clearTimeout(timer);
      resolve(args.length <= 1 ? args[0] : args);
    };
    emitter.once(event, handler);
  });
}

function emitAck(socket, event, ...args) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`No ack for ${event}`)), 1_000);
    socket.emit(event, ...args, (value) => {
      clearTimeout(timer);
      resolve(value);
    });
  });
}

test('GET /health reports readiness without exposing relay state', async () => {
  await withServer({}, async ({ url }) => {
    const response = await fetch(`${url}/health`);

    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), { status: 'ok', version: 1 });
  });
});

test('Socket.IO HTTP long-polling works beside the health endpoint', async () => {
  await withServer({}, async ({ connect }) => {
    const client = await connect({ transports: ['polling'] });

    assert.deepEqual(await emitAck(client, 'connection-ack', ROOM_A), { ok: true });
    assert.deepEqual(await emitAck(client, 'ping'), {});
  });
});

test('pre-join ping is empty and broadcasts are rejected', async () => {
  await withServer({}, async ({ connect }) => {
    const client = await connect();

    assert.deepEqual(await emitAck(client, 'ping'), {});
    assert.deepEqual(await emitAck(client, 'broadcast', presence()), {
      ok: false,
      error: 'not_joined'
    });
    assert.deepEqual(await emitAck(client, 'connection-ack', 'bad-room'), {
      ok: false,
      error: 'invalid_room'
    });
    assert.deepEqual(await emitAck(client, 'ping'), {});
  });
});

test('broadcast emits a canonical object only to joined peers in the same room', async () => {
  await withServer({ now: () => 10_000 }, async ({ connect }) => {
    const sender = await connect();
    const peer = await connect();
    const outsider = await connect();
    await emitAck(sender, 'connection-ack', ROOM_A);
    await emitAck(peer, 'connection-ack', ROOM_A);
    await emitAck(outsider, 'connection-ack', ROOM_B);

    let senderReceived = false;
    let outsiderReceived = false;
    sender.on('broadcast', () => { senderReceived = true; });
    outsider.on('broadcast', () => { outsiderReceived = true; });
    const peerBroadcast = once(peer, 'broadcast');
    const ack = await emitAck(sender, 'broadcast', JSON.stringify(presence({
      name: ' Alice__Smith ',
      activity: '  Fishing  ',
      updatedAt: 999_999
    })));
    const received = await peerBroadcast;

    assert.deepEqual(ack, { ok: true, updatedAt: 10_000 });
    assert.deepEqual(received, presence({
      name: 'Alice Smith',
      activity: 'Fishing',
      updatedAt: 10_000
    }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(senderReceived, false);
    assert.equal(outsiderReceived, false);

    const snapshot = await emitAck(peer, 'ping');
    assert.deepEqual(snapshot, { 'Alice Smith': received });
    assert.deepEqual(await emitAck(outsider, 'ping'), {});
  });
});

test('a full replacement with nulls clears previously shared private fields', async () => {
  let now = 20_000;
  await withServer({ now: () => now }, async ({ connect }) => {
    const sender = await connect();
    const peer = await connect();
    await emitAck(sender, 'connection-ack', ROOM_A);
    await emitAck(peer, 'connection-ack', ROOM_A);

    await emitAck(sender, 'broadcast', presence());
    now = 20_001;
    const clearedEvent = once(peer, 'broadcast');
    const ack = await emitAck(sender, 'broadcast', presence({
      location: null,
      activity: null
    }));
    const cleared = await clearedEvent;

    assert.equal(ack.ok, true);
    assert.equal(cleared.location, null);
    assert.equal(cleared.activity, null);
    assert.deepEqual(await emitAck(peer, 'ping'), { Alice: cleared });
  });
});

test('invalid full records are rejected without changing the snapshot', async () => {
  await withServer({}, async ({ connect }) => {
    const sender = await connect();
    await emitAck(sender, 'connection-ack', ROOM_A);
    await emitAck(sender, 'broadcast', presence());

    const partial = presence();
    delete partial.location;
    assert.deepEqual(await emitAck(sender, 'broadcast', partial), {
      ok: false,
      error: 'invalid_payload'
    });
    const snapshot = await emitAck(sender, 'ping');
    assert.equal(snapshot.Alice.location.x, 3200);
  });
});

test('joining a new room removes access to the previous room', async () => {
  await withServer({}, async ({ connect }) => {
    const client = await connect();
    await emitAck(client, 'connection-ack', ROOM_A);
    await emitAck(client, 'broadcast', presence());
    assert.equal(Object.keys(await emitAck(client, 'ping')).length, 1);

    await emitAck(client, 'connection-ack', ROOM_B);
    assert.deepEqual(await emitAck(client, 'ping'), {});
  });
});
