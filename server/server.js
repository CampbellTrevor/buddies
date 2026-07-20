'use strict';

const http = require('node:http');
const { Server } = require('socket.io');
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
  const store = options.store || new PresenceStore({
    ttlMs: options.ttlMs,
    maxRooms: options.maxRooms,
    maxRecordsPerRoom: options.maxRecordsPerRoom,
    maxTotalRecords: options.maxTotalRecords,
    now: options.now
  });

  const httpServer = http.createServer(handleHttpRequest);
  let io;
  io = new Server(httpServer, {
    serveClient: false,
    maxHttpBufferSize: maxPayloadBytes + 2_048,
    perMessageDeflate: false,
    allowRequest: (_request, callback) => {
      callback(null, !io || io.engine.clientsCount < maxConnections);
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
      if (members && members.size >= maxSocketsPerRoom) {
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
      reply(ack, { ok: true, updatedAt: record.updatedAt });
    });
  });

  const pruneIntervalMs = positiveInteger(
    options.pruneIntervalMs,
    Math.max(1_000, Math.min(30_000, Math.floor(store.ttlMs / 2)))
  );
  const pruneTimer = setInterval(() => store.prune(), pruneIntervalMs);
  pruneTimer.unref();

  let closing = false;
  return {
    httpServer,
    io,
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
      return new Promise((resolve) => io.close(resolve));
    }
  };
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
