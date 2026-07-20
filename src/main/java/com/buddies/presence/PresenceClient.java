package com.buddies.presence;

import com.buddies.model.BuddyPresence;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.Polling;
import io.socket.engineio.client.transports.WebSocket;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PresenceClient implements AutoCloseable
{
	private static final Logger LOG = LoggerFactory.getLogger(PresenceClient.class);
	private static final String EVENT_JOIN = "connection-ack";
	private static final String EVENT_BROADCAST = "broadcast";
	private static final String EVENT_PING = "ping";

	private final Gson gson;
	private final Consumer<PresenceStatus> statusListener;
	private final Consumer<BuddyPresence> presenceListener;
	private final AtomicReference<PresenceStatus> status = new AtomicReference<>(PresenceStatus.DISABLED);

	private Socket socket;
	private String room;

	public PresenceClient(
		Gson gson,
		Consumer<PresenceStatus> statusListener,
		Consumer<BuddyPresence> presenceListener)
	{
		this.gson = Objects.requireNonNull(gson).newBuilder().serializeNulls().create();
		this.statusListener = Objects.requireNonNull(statusListener);
		this.presenceListener = Objects.requireNonNull(presenceListener);
	}

	public synchronized void connect(String serverAddress, String sharedKey)
	{
		closeSocket();
		room = RoomKey.derive(sharedKey);
		URI server = parseServerAddress(serverAddress);
		if (server == null || room.isEmpty())
		{
			setStatus(PresenceStatus.DISABLED);
			return;
		}

		setStatus(PresenceStatus.CONNECTING);
		IO.Options options = IO.Options.builder()
			.setForceNew(true)
			.setMultiplex(false)
			.setTransports(new String[]{Polling.NAME, WebSocket.NAME})
			.setUpgrade(true)
			.setReconnection(true)
			.setReconnectionAttempts(Integer.MAX_VALUE)
			.setReconnectionDelay(1_000)
			.setReconnectionDelayMax(10_000)
			.setTimeout(15_000)
			.build();

		Socket next = IO.socket(server, options);
		socket = next;
		next.on(Socket.EVENT_CONNECT, args -> onConnected(next));
		next.on(Socket.EVENT_DISCONNECT, args -> onDisconnected(next));
		next.on(Socket.EVENT_CONNECT_ERROR, args -> onConnectError(next, args));
		next.on(EVENT_BROADCAST, args -> handlePayload(firstArgument(args)));
		next.connect();
	}

	public synchronized void broadcast(BuddyPresence presence)
	{
		if (socket == null || !socket.connected())
		{
			return;
		}
		socket.emit(EVENT_BROADCAST, encode(presence), (Ack) args ->
		{
			// The acknowledgement only confirms server receipt.
		});
	}

	public synchronized void requestSnapshot()
	{
		if (socket == null || !socket.connected())
		{
			return;
		}
		socket.emit(EVENT_PING, (Ack) args -> handlePayload(firstArgument(args)));
	}

	public PresenceStatus getStatus()
	{
		return status.get();
	}

	String encode(BuddyPresence presence)
	{
		return gson.toJson(presence);
	}

	@Override
	public synchronized void close()
	{
		closeSocket();
		room = null;
		setStatus(PresenceStatus.DISABLED);
	}

	private synchronized void onConnected(Socket source)
	{
		if (source != socket)
		{
			return;
		}
		source.emit(EVENT_JOIN, room);
		setStatus(PresenceStatus.CONNECTED);
	}

	private synchronized void onDisconnected(Socket source)
	{
		if (source == socket)
		{
			setStatus(PresenceStatus.DISCONNECTED);
		}
	}

	private synchronized void onConnectError(Socket source, Object[] args)
	{
		if (source != socket)
		{
			return;
		}
		Object error = firstArgument(args);
		LOG.debug("Buddies presence connection failed: {}", error);
		setStatus(PresenceStatus.DISCONNECTED);
	}

	void handlePayload(Object payload)
	{
		if (payload == null)
		{
			return;
		}

		try
		{
			JsonElement root = new JsonParser().parse(payload instanceof String
				? (String) payload
				: payload.toString());
			if (root.isJsonArray())
			{
				for (JsonElement element : root.getAsJsonArray())
				{
					emitPresence(element);
				}
				return;
			}

			if (!root.isJsonObject())
			{
				return;
			}
			JsonObject object = root.getAsJsonObject();
			if (object.has("name"))
			{
				emitPresence(object);
				return;
			}
			for (Map.Entry<String, JsonElement> entry : object.entrySet())
			{
				emitPresence(entry.getValue());
			}
		}
		catch (RuntimeException ex)
		{
			LOG.debug("Ignoring malformed Buddies presence payload", ex);
		}
	}

	private void emitPresence(JsonElement element)
	{
		if (element == null || !element.isJsonObject())
		{
			return;
		}
		try
		{
			BuddyPresence presence = gson.fromJson(element, BuddyPresence.class);
			presenceListener.accept(presence);
		}
		catch (RuntimeException ex)
		{
			LOG.debug("Ignoring malformed Buddies presence entry", ex);
		}
	}

	private synchronized void closeSocket()
	{
		if (socket != null)
		{
			socket.off();
			socket.disconnect();
			socket.close();
			socket = null;
		}
	}

	private void setStatus(PresenceStatus next)
	{
		PresenceStatus previous = status.getAndSet(next);
		if (previous != next)
		{
			statusListener.accept(next);
		}
	}

	private static Object firstArgument(Object[] args)
	{
		return args == null || args.length == 0 ? null : args[0];
	}

	static URI parseServerAddress(String address)
	{
		if (address == null || address.trim().isEmpty())
		{
			return null;
		}
		String value = address.trim().replaceAll("/+$", "");
		if (value.matches("(?i)^[a-z][a-z0-9+.-]*://.*") && !value.matches("(?i)^https?://.*"))
		{
			return null;
		}
		if (!value.matches("(?i)^https?://.*"))
		{
			value = "http://" + value;
		}
		try
		{
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
				? uri
				: null;
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}
}
