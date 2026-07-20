'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { io: connectClient } = require('socket.io-client');
const WebSocket = require('ws');
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
  const rawClients = [];

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

  const connectRaw = async (clientOptions = {}) => {
    const socket = new WebSocket(
      `${url.replace(/^http/, 'ws')}/presence/v1`,
      clientOptions
    );
    rawClients.push(socket);
    await once(socket, 'open');
    return socket;
  };

  try {
    await callback({ application, connect, connectRaw, url });
  } finally {
    for (const client of clients) {
      client.disconnect();
    }
    for (const client of rawClients) {
      client.terminate();
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

function receiveRaw(socket, timeoutMs = 1_000) {
  return once(socket, 'message', timeoutMs).then((args) => {
    const data = Array.isArray(args) ? args[0] : args;
    return JSON.parse(data.toString('utf8'));
  });
}

function exchangeRaw(socket, message) {
  const response = receiveRaw(socket);
  socket.send(typeof message === 'string' ? message : JSON.stringify(message));
  return response;
}

function joinRaw(socket, room) {
  return exchangeRaw(socket, { type: 'join', room });
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

test('raw WebSocket validates messages and exposes an empty pre-join snapshot', async () => {
  await withServer({}, async ({ connectRaw }) => {
    const client = await connectRaw();

    assert.deepEqual(await exchangeRaw(client, { type: 'snapshot' }), {
      type: 'snapshot',
      presences: {}
    });
    assert.deepEqual(await exchangeRaw(client, {
      type: 'broadcast',
      presence: presence()
    }), { type: 'error', code: 'not_joined' });
    assert.deepEqual(await joinRaw(client, 'bad-room'), {
      type: 'error',
      code: 'invalid_room'
    });
    assert.deepEqual(await exchangeRaw(client, 'not json'), {
      type: 'error',
      code: 'invalid_message'
    });
    assert.deepEqual(await exchangeRaw(client, { type: 'snapshot', extra: true }), {
      type: 'error',
      code: 'invalid_message'
    });
    const binaryResponse = receiveRaw(client);
    client.send(Buffer.from('{}'));
    assert.deepEqual(await binaryResponse, {
      type: 'error',
      code: 'invalid_message'
    });
  });
});

test('raw broadcasts are canonical, isolated, and exclude the sender', async () => {
  await withServer({ now: () => 30_000 }, async ({ connectRaw }) => {
    const sender = await connectRaw();
    const peer = await connectRaw();
    const outsider = await connectRaw();
    assert.deepEqual(await joinRaw(sender, ROOM_A), { type: 'joined' });
    assert.deepEqual(await joinRaw(peer, ROOM_A), { type: 'joined' });
    assert.deepEqual(await joinRaw(outsider, ROOM_B), { type: 'joined' });

    let outsiderReceived = false;
    outsider.on('message', () => { outsiderReceived = true; });
    const peerPresence = receiveRaw(peer);
    const acknowledgement = await exchangeRaw(sender, {
      type: 'broadcast',
      presence: presence({
        name: ' Alice__Smith ',
        activity: '  Fishing  ',
        updatedAt: 999_999
      })
    });
    const pushed = await peerPresence;

    assert.deepEqual(acknowledgement, {
      type: 'ack',
      operation: 'broadcast',
      updatedAt: 30_000
    });
    assert.deepEqual(pushed, {
      type: 'presence',
      presence: presence({
        name: 'Alice Smith',
        activity: 'Fishing',
        updatedAt: 30_000
      })
    });
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(outsiderReceived, false);

    assert.deepEqual(await exchangeRaw(peer, { type: 'snapshot' }), {
      type: 'snapshot',
      presences: { 'Alice Smith': pushed.presence }
    });
    assert.deepEqual(await exchangeRaw(outsider, { type: 'snapshot' }), {
      type: 'snapshot',
      presences: {}
    });
  });
});

test('raw full replacements preserve explicit privacy nulls and reject partials', async () => {
  let now = 40_000;
  await withServer({ now: () => now }, async ({ connectRaw }) => {
    const sender = await connectRaw();
    const peer = await connectRaw();
    await joinRaw(sender, ROOM_A);
    await joinRaw(peer, ROOM_A);

    const firstPush = receiveRaw(peer);
    await exchangeRaw(sender, { type: 'broadcast', presence: presence() });
    await firstPush;

    now = 40_001;
    const clearedPush = receiveRaw(peer);
    await exchangeRaw(sender, {
      type: 'broadcast',
      presence: presence({ location: null, activity: null })
    });
    const cleared = await clearedPush;
    assert.equal(cleared.presence.location, null);
    assert.equal(cleared.presence.activity, null);

    const partial = presence();
    delete partial.location;
    assert.deepEqual(await exchangeRaw(sender, {
      type: 'broadcast',
      presence: partial
    }), { type: 'error', code: 'invalid_payload' });
    const snapshot = await exchangeRaw(peer, { type: 'snapshot' });
    assert.equal(snapshot.presences.Alice.location, null);
    assert.equal(snapshot.presences.Alice.activity, null);
  });
});

test('raw and Socket.IO clients share records and receive cross-transport pushes', async () => {
  let now = 50_000;
  await withServer({ now: () => now }, async ({ connect, connectRaw }) => {
    const legacy = await connect();
    const raw = await connectRaw();
    assert.deepEqual(await emitAck(legacy, 'connection-ack', ROOM_A), { ok: true });
    assert.deepEqual(await joinRaw(raw, ROOM_A), { type: 'joined' });

    const rawPush = receiveRaw(raw);
    await emitAck(legacy, 'broadcast', presence({ name: 'Legacy' }));
    assert.equal((await rawPush).presence.name, 'Legacy');

    now = 50_001;
    const legacyPush = once(legacy, 'broadcast');
    const rawAck = exchangeRaw(raw, {
      type: 'broadcast',
      presence: presence({ name: 'Raw' })
    });
    assert.equal((await legacyPush).name, 'Raw');
    assert.deepEqual(await rawAck, {
      type: 'ack',
      operation: 'broadcast',
      updatedAt: 50_001
    });

    const legacySnapshot = await emitAck(legacy, 'ping');
    const rawSnapshot = await exchangeRaw(raw, { type: 'snapshot' });
    assert.deepEqual(rawSnapshot.presences, legacySnapshot);
    assert.deepEqual(Object.keys(rawSnapshot.presences).sort(), ['Legacy', 'Raw']);
  });
});

test('room capacity is shared between raw and Socket.IO clients', async () => {
  await withServer({ maxSocketsPerRoom: 1 }, async ({ connect, connectRaw }) => {
    const legacy = await connect();
    const raw = await connectRaw();
    assert.deepEqual(await emitAck(legacy, 'connection-ack', ROOM_A), { ok: true });
    assert.deepEqual(await joinRaw(raw, ROOM_A), {
      type: 'error',
      code: 'room_capacity'
    });

    assert.deepEqual(await joinRaw(raw, ROOM_B), { type: 'joined' });
    assert.deepEqual(await emitAck(legacy, 'connection-ack', ROOM_B), {
      ok: false,
      error: 'room_capacity'
    });
    assert.deepEqual(await emitAck(legacy, 'ping'), {});
  });
});

test('raw rejoin and disconnect release previous room membership', async () => {
  await withServer({ maxSocketsPerRoom: 1 }, async ({ connectRaw }) => {
    const moving = await connectRaw();
    const roomAClient = await connectRaw();
    const roomBClient = await connectRaw();
    assert.deepEqual(await joinRaw(moving, ROOM_A), { type: 'joined' });
    assert.deepEqual(await joinRaw(moving, ROOM_B), { type: 'joined' });
    assert.deepEqual(await joinRaw(roomAClient, ROOM_A), { type: 'joined' });

    const closed = once(moving, 'close');
    moving.close();
    await closed;
    let joinResult;
    for (let attempt = 0; attempt < 5; attempt += 1) {
      joinResult = await joinRaw(roomBClient, ROOM_B);
      if (joinResult.type === 'joined') {
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.deepEqual(joinResult, { type: 'joined' });
  });
});

test('raw endpoint enforces the global connection and frame-size caps', async () => {
  await withServer({ maxConnections: 1, maxPayloadBytes: 64 },
    async ({ connectRaw, url }) => {
      const first = await connectRaw();
      const rejected = new WebSocket(
        `${url.replace(/^http/, 'ws')}/presence/v1`
      );
      rejected.on('error', () => { });
      const status = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('No upgrade rejection')), 1_000);
        rejected.once('unexpected-response', (_request, response) => {
          clearTimeout(timer);
          response.resume();
          resolve(response.statusCode);
        });
        rejected.once('open', () => {
          clearTimeout(timer);
          reject(new Error('Connection cap was not enforced'));
        });
      });
      assert.equal(status, 503);

      const closed = once(first, 'close');
      first.send('x'.repeat(3_000));
      const closeArguments = await closed;
      assert.equal(closeArguments[0], 1009);
    });
});

test('raw heartbeat terminates clients that do not answer pings', async () => {
  await withServer({ heartbeatIntervalMs: 20 }, async ({ connectRaw }) => {
    const client = await connectRaw({ autoPong: false });
    const closeArguments = await once(client, 'close', 500);
    assert.equal(closeArguments[0], 1006);
  });
});
