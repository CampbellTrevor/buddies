'use strict';

const PRESENCE_VERSION = 1;
const DEFAULT_TTL_MS = 90_000;
const DEFAULT_MAX_ROOMS = 1_000;
const DEFAULT_MAX_RECORDS_PER_ROOM = 200;
const DEFAULT_MAX_TOTAL_RECORDS = 10_000;
const DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024;

const RECORD_KEYS = [
  'activity',
  'location',
  'name',
  'updatedAt',
  'version',
  'world'
];
const LOCATION_KEYS = ['plane', 'x', 'y'];
const ROOM_PATTERN = /^[0-9a-f]{64}$/;

class CapacityError extends Error {
  constructor(code) {
    super(code);
    this.name = 'CapacityError';
    this.code = code;
  }
}

class PresenceStore {
  constructor(options = {}) {
    this.ttlMs = positiveInteger(options.ttlMs, DEFAULT_TTL_MS);
    this.maxRooms = positiveInteger(options.maxRooms, DEFAULT_MAX_ROOMS);
    this.maxRecordsPerRoom = positiveInteger(
      options.maxRecordsPerRoom,
      DEFAULT_MAX_RECORDS_PER_ROOM
    );
    this.maxTotalRecords = positiveInteger(
      options.maxTotalRecords,
      DEFAULT_MAX_TOTAL_RECORDS
    );
    this.now = typeof options.now === 'function' ? options.now : Date.now;
    this.rooms = new Map();
    this.recordCount = 0;
    this.lastUpdatedAt = 0;
  }

  put(room, incoming) {
    if (!isValidRoom(room)) {
      throw new TypeError('invalid_room');
    }

    const now = this.#readNow();
    this.#pruneRoom(room, now);

    let records = this.rooms.get(room);
    const key = normalizeName(incoming.name);
    const isReplacement = records ? records.has(key) : false;

    if (!records) {
      if (this.rooms.size >= this.maxRooms) {
        throw new CapacityError('server_capacity');
      }
      records = new Map();
      this.rooms.set(room, records);
    }

    if (!isReplacement && records.size >= this.maxRecordsPerRoom) {
      if (records.size === 0) {
        this.rooms.delete(room);
      }
      throw new CapacityError('room_capacity');
    }
    if (!isReplacement && this.recordCount >= this.maxTotalRecords) {
      if (records.size === 0) {
        this.rooms.delete(room);
      }
      throw new CapacityError('server_capacity');
    }

    const updatedAt = Math.max(now, this.lastUpdatedAt + 1);
    this.lastUpdatedAt = updatedAt;
    const record = cloneRecord({ ...incoming, updatedAt });
    records.set(key, {
      expiresAt: now + this.ttlMs,
      record
    });
    if (!isReplacement) {
      this.recordCount += 1;
    }
    return cloneRecord(record);
  }

  snapshot(room) {
    if (!isValidRoom(room)) {
      return Object.create(null);
    }

    const now = this.#readNow();
    this.#pruneRoom(room, now);
    const result = Object.create(null);
    const records = this.rooms.get(room);
    if (!records) {
      return result;
    }

    for (const { record } of records.values()) {
      result[record.name] = cloneRecord(record);
    }
    return result;
  }

  prune(at = this.#readNow()) {
    for (const room of this.rooms.keys()) {
      this.#pruneRoom(room, at);
    }
  }

  stats() {
    this.prune();
    return {
      records: this.recordCount,
      rooms: this.rooms.size
    };
  }

  #pruneRoom(room, at) {
    const records = this.rooms.get(room);
    if (!records) {
      return;
    }

    for (const [name, entry] of records) {
      if (entry.expiresAt <= at) {
        records.delete(name);
        this.recordCount -= 1;
      }
    }
    if (records.size === 0) {
      this.rooms.delete(room);
    }
  }

  #readNow() {
    const value = Math.floor(Number(this.now()));
    return Number.isSafeInteger(value) && value >= 0 ? value : Date.now();
  }
}

function parsePresence(payload, maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES) {
  let encoded;
  if (typeof payload === 'string') {
    encoded = payload;
  } else {
    try {
      encoded = JSON.stringify(payload);
    } catch (_error) {
      return null;
    }
  }

  if (
    typeof encoded !== 'string' ||
    Buffer.byteLength(encoded, 'utf8') > maxPayloadBytes
  ) {
    return null;
  }

  let value = payload;
  if (typeof payload === 'string') {
    try {
      value = JSON.parse(payload);
    } catch (_error) {
      return null;
    }
  }

  if (!isObject(value) || !hasExactKeys(value, RECORD_KEYS)) {
    return null;
  }
  if (value.version !== PRESENCE_VERSION) {
    return null;
  }

  const name = cleanName(value.name);
  if (name === null) {
    return null;
  }
  if (!isIntegerInRange(value.world, 0, 65_535)) {
    return null;
  }
  if (!Number.isSafeInteger(value.updatedAt) || value.updatedAt <= 0) {
    return null;
  }

  const location = cleanLocation(value.location);
  if (location === undefined) {
    return null;
  }
  const activity = cleanActivity(value.activity);
  if (activity === undefined) {
    return null;
  }

  return {
    version: PRESENCE_VERSION,
    name,
    world: value.world,
    location,
    activity,
    updatedAt: value.updatedAt
  };
}

function cleanName(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const clean = value
    .replace(/\u00a0/g, ' ')
    .replace(/_/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
  return clean.length > 0 && clean.length <= 32 ? clean : null;
}

function normalizeName(value) {
  return cleanName(value).toLowerCase();
}

function cleanLocation(value) {
  if (value === null) {
    return null;
  }
  if (!isObject(value) || !hasExactKeys(value, LOCATION_KEYS)) {
    return undefined;
  }
  if (
    !isIntegerInRange(value.x, 0, 16_383) ||
    !isIntegerInRange(value.y, 0, 16_383) ||
    !isIntegerInRange(value.plane, -1, 3)
  ) {
    return undefined;
  }
  return { x: value.x, y: value.y, plane: value.plane };
}

function cleanActivity(value) {
  if (value === null) {
    return null;
  }
  if (typeof value !== 'string') {
    return undefined;
  }
  const clean = value.trim();
  if (clean.length > 40) {
    return undefined;
  }
  return clean.length === 0 ? null : clean;
}

function cloneRecord(record) {
  return {
    version: record.version,
    name: record.name,
    world: record.world,
    location: record.location === null ? null : { ...record.location },
    activity: record.activity,
    updatedAt: record.updatedAt
  };
}

function hasExactKeys(value, expected) {
  const keys = Object.keys(value).sort();
  return (
    keys.length === expected.length &&
    keys.every((key, index) => key === expected[index])
  );
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isIntegerInRange(value, minimum, maximum) {
  return Number.isSafeInteger(value) && value >= minimum && value <= maximum;
}

function isValidRoom(value) {
  return typeof value === 'string' && ROOM_PATTERN.test(value);
}

function positiveInteger(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

module.exports = {
  CapacityError,
  DEFAULT_MAX_PAYLOAD_BYTES,
  DEFAULT_MAX_RECORDS_PER_ROOM,
  DEFAULT_MAX_ROOMS,
  DEFAULT_MAX_TOTAL_RECORDS,
  DEFAULT_TTL_MS,
  PRESENCE_VERSION,
  PresenceStore,
  isValidRoom,
  parsePresence
};
