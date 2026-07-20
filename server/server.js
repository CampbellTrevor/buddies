'use strict';

const http = require('node:http');
const { Server } = require('socket.io');
const { WebSocket, WebSocketServer } = require('ws');
const {
  CapacityError,
  DEFAULT_MAX_PAYLOAD_BYTES,
  DEFAULT_MAX_RECORDS_PER_ROOM,
  DEFAULT_MAX_ROOMS,
  DEFAULT_MAX_TOTAL_RECORDS,
  DEFAULT_TTL_MS,
  PresenceStore,
  isValidRoom,
  parsePresence
} = require('./presence-store');

const DEFAULT_PORT = 3000;
const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_MAX_CONNECTIONS = 5_000;
const DEFAULT_MAX_SOCKETS_PER_ROOM = 250;
const DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000;
const PRESENCE_WEBSOCKET_PATH = '/presence/v1';

function createPresenceServer(options = {}) {
  const maxPayloadBytes = positiveInteger(
    options.maxPayloadBytes,
    DEFAULT_MAX_PAYLOAD_BYTES
  );
  const maxConnections = positiveInteger(
    options.maxConnections,
    DEFAULT_MAX_CONNECTIONS
  );
  const maxSocketsPerRoom = positiveInteger(
    options.maxSocketsPerRoom,
    DEFAULT_MAX_SOCKETS_PER_ROOM
  );
  const heartbeatIntervalMs = positiveInteger(
    options.heartbeatIntervalMs,
    DEFAULT_HEARTBEAT_INTERVAL_MS
  );
  const store = options.store || new PresenceStore({
    ttlMs: options.ttlMs,
    maxRooms: options.maxRooms,
    maxRecordsPerRoom: options.maxRecordsPerRoom,
    maxTotalRecords: options.maxTotalRecords,
    now: options.now
  });

  const httpServer = http.createServer(handleHttpRequest);
  const rawRooms = new Map();
  const rawMemberships = new WeakMap();
  let wss;
  let io;
  io = new Server(httpServer, {
    serveClient: false,
    maxHttpBufferSize: maxPayloadBytes + 2_048,
    perMessageDeflate: false,
    destroyUpgrade: false,
    allowRequest: (_request, callback) => {
      callback(null, connectionCount(io, wss) < maxConnections);
    }
  });

  wss = new WebSocketServer({
    noServer: true,
    clientTracking: true,
    maxPayload: maxPayloadBytes + 2_048,
    perMessageDeflate: false
  });

  httpServer.on('upgrade', (request, socket, head) => {
    const path = requestPath(request);
    if (path === PRESENCE_WEBSOCKET_PATH) {
      if (connectionCount(io, wss) >= maxConnections) {
        rejectUpgrade(socket, 503, 'Service Unavailable');
        return;
      }
      wss.handleUpgrade(request, socket, head, (client) => {
        wss.emit('connection', client, request);
      });
      return;
    }

    if (path !== '/socket.io/' && path !== '/socket.io') {
      rejectUpgrade(socket, 404, 'Not Found');
    }
  });

  io.on('connection', (socket) => {
    socket.on('connection-ack', (candidate, ack) => {
      leavePresenceRoom(socket);

      if (!isValidRoom(candidate)) {
        reply(ack, { ok: false, error: 'invalid_room' });
        return;
      }

      const members = io.sockets.adapter.rooms.get(candidate);
      if (memberCount(members, rawRooms.get(candidate)) >= maxSocketsPerRoom) {
        reply(ack, { ok: false, error: 'room_capacity' });
        return;
      }

      socket.join(candidate);
      socket.data.presenceRoom = candidate;
      reply(ack, { ok: true });
    });

    socket.on('ping', (ack) => {
      const room = socket.data.presenceRoom;
      reply(ack, room ? store.snapshot(room) : Object.create(null));
    });

    socket.on('broadcast', (payload, ack) => {
      const room = socket.data.presenceRoom;
      if (!room) {
        reply(ack, { ok: false, error: 'not_joined' });
        return;
      }

      const incoming = parsePresence(payload, maxPayloadBytes);
      if (!incoming) {
        reply(ack, { ok: false, error: 'invalid_payload' });
        return;
      }

      let record;
      try {
        record = store.put(room, incoming);
      } catch (error) {
        if (error instanceof CapacityError) {
          reply(ack, { ok: false, error: error.code });
          return;
        }
        reply(ack, { ok: false, error: 'invalid_payload' });
        return;
      }

      socket.to(room).emit('broadcast', record);
      broadcastRaw(rawRooms.get(room), record);
      reply(ack, { ok: true, updatedAt: record.updatedAt });
    });
  });

  wss.on('connection', (socket) => {
    socket.isAlive = true;
    socket.on('pong', () => {
      socket.isAlive = true;
    });

    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        sendRawError(socket, 'invalid_message');
        return;
      }

      const message = parseRawMessage(data);
      if (!message) {
        sendRawError(socket, 'invalid_message');
        return;
      }

      if (message.type === 'join') {
        leaveRawRoom(socket, rawRooms, rawMemberships);
        if (!isValidRoom(message.room)) {
          sendRawError(socket, 'invalid_room');
          return;
        }

        const socketMembers = io.sockets.adapter.rooms.get(message.room);
        const rawMembers = rawRooms.get(message.room);
        if (memberCount(socketMembers, rawMembers) >= maxSocketsPerRoom) {
          sendRawError(socket, 'room_capacity');
          return;
        }

        joinRawRoom(socket, message.room, rawRooms, rawMemberships);
        sendRaw(socket, { type: 'joined' });
        return;
      }

      const room = rawMemberships.get(socket);
      if (message.type === 'snapshot') {
        sendRaw(socket, {
          type: 'snapshot',
          presences: room ? store.snapshot(room) : Object.create(null)
        });
        return;
      }

      if (!room) {
        sendRawError(socket, 'not_joined');
        return;
      }

      const incoming = parsePresence(message.presence, maxPayloadBytes);
      if (!incoming) {
        sendRawError(socket, 'invalid_payload');
        return;
      }

      let record;
      try {
        record = store.put(room, incoming);
      } catch (error) {
        if (error instanceof CapacityError) {
          sendRawError(socket, error.code);
          return;
        }
        sendRawError(socket, 'invalid_payload');
        return;
      }

      broadcastRaw(rawRooms.get(room), record, socket);
      io.to(room).emit('broadcast', record);
      sendRaw(socket, {
        type: 'ack',
        operation: 'broadcast',
        updatedAt: record.updatedAt
      });
    });

    const leave = () => leaveRawRoom(socket, rawRooms, rawMemberships);
    socket.on('close', leave);
    socket.on('error', leave);
  });

  const pruneIntervalMs = positiveInteger(
    options.pruneIntervalMs,
    Math.max(1_000, Math.min(30_000, Math.floor(store.ttlMs / 2)))
  );
  const pruneTimer = setInterval(() => store.prune(), pruneIntervalMs);
  pruneTimer.unref();
  const heartbeatTimer = setInterval(() => {
    for (const socket of wss.clients) {
      if (socket.readyState !== WebSocket.OPEN) {
        continue;
      }
      if (!socket.isAlive) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      socket.ping();
    }
  }, heartbeatIntervalMs);
  heartbeatTimer.unref();

  let closing = false;
  return {
    httpServer,
    io,
    wss,
    store,
    listen(port = DEFAULT_PORT, host = DEFAULT_HOST) {
      return new Promise((resolve, reject) => {
        const onError = (error) => {
          httpServer.off('listening', onListening);
          reject(error);
        };
        const onListening = () => {
          httpServer.off('error', onError);
          resolve(httpServer.address());
        };
        httpServer.once('error', onError);
        httpServer.once('listening', onListening);
        httpServer.listen(port, host);
      });
    },
    close() {
      if (closing) {
        return Promise.resolve();
      }
      closing = true;
      clearInterval(pruneTimer);
      clearInterval(heartbeatTimer);
      for (const socket of wss.clients) {
        socket.terminate();
      }
      return new Promise((resolve) => {
        wss.close(() => io.close(resolve));
      });
    }
  };
}

function connectionCount(io, wss) {
  const socketIoConnections = io ? io.engine.clientsCount : 0;
  const rawConnections = wss ? wss.clients.size : 0;
  return socketIoConnections + rawConnections;
}

function memberCount(socketIoMembers, rawMembers) {
  return (socketIoMembers ? socketIoMembers.size : 0) +
    (rawMembers ? rawMembers.size : 0);
}

function joinRawRoom(socket, room, rawRooms, rawMemberships) {
  let members = rawRooms.get(room);
  if (!members) {
    members = new Set();
    rawRooms.set(room, members);
  }
  members.add(socket);
  rawMemberships.set(socket, room);
}

function leaveRawRoom(socket, rawRooms, rawMemberships) {
  const room = rawMemberships.get(socket);
  if (!room) {
    return;
  }
  const members = rawRooms.get(room);
  if (members) {
    members.delete(socket);
    if (members.size === 0) {
      rawRooms.delete(room);
    }
  }
  rawMemberships.delete(socket);
}

function broadcastRaw(members, record, excluded) {
  if (!members) {
    return;
  }
  const message = JSON.stringify({ type: 'presence', presence: record });
  for (const socket of members) {
    if (socket !== excluded && socket.readyState === WebSocket.OPEN) {
      socket.send(message);
    }
  }
}

function parseRawMessage(data) {
  let value;
  try {
    value = JSON.parse(data.toString('utf8'));
  } catch (_error) {
    return null;
  }
  if (!isObject(value) || typeof value.type !== 'string') {
    return null;
  }
  if (value.type === 'join') {
    return hasExactKeys(value, ['room', 'type']) ? value : null;
  }
  if (value.type === 'snapshot') {
    return hasExactKeys(value, ['type']) ? value : null;
  }
  if (value.type === 'broadcast') {
    return hasExactKeys(value, ['presence', 'type']) ? value : null;
  }
  return null;
}

function sendRaw(socket, value) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(value));
  }
}

function sendRawError(socket, code) {
  sendRaw(socket, { type: 'error', code });
}

function hasExactKeys(value, expected) {
  const keys = Object.keys(value).sort();
  return keys.length === expected.length &&
    keys.every((key, index) => key === expected[index]);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requestPath(request) {
  try {
    return new URL(request.url, 'http://localhost').pathname;
  } catch (_error) {
    return '';
  }
}

function rejectUpgrade(socket, status, reason) {
  if (!socket.writable) {
    socket.destroy();
    return;
  }
  socket.end(
    `HTTP/1.1 ${status} ${reason}\r\n` +
    'Connection: close\r\n' +
    'Content-Length: 0\r\n\r\n'
  );
}

function leavePresenceRoom(socket) {
  const previous = socket.data.presenceRoom;
  if (previous) {
    socket.leave(previous);
    delete socket.data.presenceRoom;
  }
}

function reply(callback, value) {
  if (typeof callback === 'function') {
    callback(value);
  }
}

function handleHttpRequest(request, response) {
  const path = new URL(request.url, 'http://localhost').pathname;
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.setHeader('X-Content-Type-Options', 'nosniff');

  if ((request.method === 'GET' || request.method === 'HEAD') && path === '/health') {
    response.statusCode = 200;
    const body = JSON.stringify({ status: 'ok', version: 1 });
    response.end(request.method === 'HEAD' ? undefined : body);
    return;
  }

  response.statusCode = 404;
  response.end(JSON.stringify({ error: 'not_found' }));
}

function positiveInteger(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

function envInteger(name, fallback) {
  const parsed = Number.parseInt(process.env[name], 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

if (require.main === module) {
  const application = createPresenceServer({
    ttlMs: envInteger('PRESENCE_TTL_MS', DEFAULT_TTL_MS),
    maxRooms: envInteger('MAX_ROOMS', DEFAULT_MAX_ROOMS),
    maxRecordsPerRoom: envInteger(
      'MAX_RECORDS_PER_ROOM',
      DEFAULT_MAX_RECORDS_PER_ROOM
    ),
    maxTotalRecords: envInteger('MAX_TOTAL_RECORDS', DEFAULT_MAX_TOTAL_RECORDS),
    maxPayloadBytes: envInteger('MAX_PAYLOAD_BYTES', DEFAULT_MAX_PAYLOAD_BYTES),
    maxConnections: envInteger('MAX_CONNECTIONS', DEFAULT_MAX_CONNECTIONS),
    maxSocketsPerRoom: envInteger(
      'MAX_SOCKETS_PER_ROOM',
      DEFAULT_MAX_SOCKETS_PER_ROOM
    )
  });
  const port = envInteger('PORT', DEFAULT_PORT);
  const host = process.env.HOST || '0.0.0.0';

  application.listen(port, host).then((address) => {
    const boundPort = typeof address === 'object' && address ? address.port : port;
    console.log(`Buddies presence server listening on port ${boundPort}`);
  }).catch(() => {
    console.error('Buddies presence server failed to start');
    process.exitCode = 1;
  });

  const stop = () => {
    application.close().finally(() => process.exit());
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}

module.exports = {
  createPresenceServer
};
