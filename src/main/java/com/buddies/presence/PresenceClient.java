package com.buddies.presence;

import com.buddies.model.BuddyPresence;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PresenceClient implements AutoCloseable
{
	private static final Logger LOG = LoggerFactory.getLogger(PresenceClient.class);
	private static final String ENDPOINT_PATH = "/presence/v1";
	private static final long JOIN_TIMEOUT_MS = 15_000L;
	private static final long MIN_RECONNECT_DELAY_MS = 1_000L;
	private static final long MAX_RECONNECT_DELAY_MS = 10_000L;

	private final WebSocket.Factory webSocketFactory;
	private final Gson gson;
	private final ScheduledExecutorService scheduler;
	private final Consumer<PresenceStatus> statusListener;
	private final Consumer<BuddyPresence> presenceListener;
	private final AtomicReference<PresenceStatus> status = new AtomicReference<>(PresenceStatus.DISABLED);

	private WebSocket socket;
	private URI endpoint;
	private String room;
	private boolean enabled;
	private long generation;
	private long reconnectDelayMs = MIN_RECONNECT_DELAY_MS;
	private ScheduledFuture<?> reconnectFuture;
	private ScheduledFuture<?> joinTimeoutFuture;

	public PresenceClient(
		WebSocket.Factory webSocketFactory,
		Gson gson,
		ScheduledExecutorService scheduler,
		Consumer<PresenceStatus> statusListener,
		Consumer<BuddyPresence> presenceListener)
	{
		this.webSocketFactory = Objects.requireNonNull(webSocketFactory);
		this.gson = Objects.requireNonNull(gson).newBuilder().serializeNulls().create();
		this.scheduler = Objects.requireNonNull(scheduler);
		this.statusListener = Objects.requireNonNull(statusListener);
		this.presenceListener = Objects.requireNonNull(presenceListener);
	}

	public synchronized void connect(String serverAddress, String sharedKey)
	{
		stopConnection();
		URI server = parseServerAddress(serverAddress);
		String nextRoom = RoomKey.derive(sharedKey);
		if (server == null || nextRoom.isEmpty())
		{
			setStatus(PresenceStatus.DISABLED);
			return;
		}

		URI nextEndpoint = toWebSocketEndpoint(server);
		if (nextEndpoint == null)
		{
			setStatus(PresenceStatus.DISABLED);
			return;
		}

		endpoint = nextEndpoint;
		room = nextRoom;
		enabled = true;
		reconnectDelayMs = MIN_RECONNECT_DELAY_MS;
		setStatus(PresenceStatus.CONNECTING);
		openSocket(generation);
	}

	public synchronized void broadcast(BuddyPresence presence)
	{
		if (socket == null || status.get() != PresenceStatus.CONNECTED)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "broadcast");
		message.add("presence", gson.toJsonTree(presence));
		socket.send(gson.toJson(message));
	}

	public synchronized void requestSnapshot()
	{
		if (socket == null || status.get() != PresenceStatus.CONNECTED)
		{
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "snapshot");
		socket.send(gson.toJson(message));
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
		stopConnection();
		setStatus(PresenceStatus.DISABLED);
	}

	private synchronized void openSocket(long expectedGeneration)
	{
		if (!enabled || expectedGeneration != generation)
		{
			return;
		}

		Request request = new Request.Builder().url(endpoint.toString()).build();
		try
		{
			socket = webSocketFactory.newWebSocket(request, new Listener(expectedGeneration));
		}
		catch (RuntimeException ex)
		{
			LOG.debug("Unable to start Buddies presence connection", ex);
			socket = null;
			setStatus(PresenceStatus.DISCONNECTED);
			scheduleReconnect(expectedGeneration);
		}
	}

	private synchronized void onOpen(long expectedGeneration, WebSocket source)
	{
		if (!isCurrent(expectedGeneration, source))
		{
			source.cancel();
			return;
		}

		JsonObject message = new JsonObject();
		message.addProperty("type", "join");
		message.addProperty("room", room);
		if (!source.send(gson.toJson(message)))
		{
			onDisconnected(expectedGeneration, source, null);
			source.cancel();
			return;
		}
		scheduleJoinTimeout(expectedGeneration, source);
	}

	private void onMessage(long expectedGeneration, WebSocket source, String text)
	{
		JsonObject message;
		try
		{
			JsonElement root = new JsonParser().parse(text);
			if (!root.isJsonObject())
			{
				return;
			}
			message = root.getAsJsonObject();
		}
		catch (RuntimeException ex)
		{
			LOG.debug("Ignoring malformed Buddies WebSocket message", ex);
			return;
		}

		JsonElement typeElement = message.get("type");
		if (typeElement == null || !typeElement.isJsonPrimitive())
		{
			return;
		}

		String type;
		try
		{
			type = typeElement.getAsString();
		}
		catch (RuntimeException ex)
		{
			return;
		}

		switch (type)
		{
			case "joined":
				onJoined(expectedGeneration, source);
				break;
			case "presence":
				if (isCurrentConnection(expectedGeneration, source))
				{
					handlePayload(message.get("presence"));
				}
				break;
			case "snapshot":
				if (isCurrentConnection(expectedGeneration, source))
				{
					handlePayload(message.get("presences"));
				}
				break;
			case "error":
				onServerError(expectedGeneration, source, message);
				break;
			default:
				break;
		}
	}

	private synchronized void onJoined(long expectedGeneration, WebSocket source)
	{
		if (!isCurrent(expectedGeneration, source))
		{
			return;
		}
		cancelJoinTimeout();
		reconnectDelayMs = MIN_RECONNECT_DELAY_MS;
		setStatus(PresenceStatus.CONNECTED);
	}

	private synchronized void onServerError(
		long expectedGeneration,
		WebSocket source,
		JsonObject message)
	{
		if (!isCurrent(expectedGeneration, source))
		{
			return;
		}

		String code = jsonString(message.get("code"));
		LOG.debug("Buddies presence server rejected a message: {}", code);
		if (status.get() != PresenceStatus.CONNECTED)
		{
			onDisconnected(expectedGeneration, source, null);
			source.cancel();
		}
	}

	private synchronized void onDisconnected(
		long expectedGeneration,
		WebSocket source,
		Throwable failure)
	{
		if (!isCurrent(expectedGeneration, source))
		{
			return;
		}

		if (failure != null)
		{
			LOG.debug("Buddies presence connection failed", failure);
		}
		cancelJoinTimeout();
		socket = null;
		setStatus(PresenceStatus.DISCONNECTED);
		scheduleReconnect(expectedGeneration);
	}

	private synchronized void scheduleReconnect(long expectedGeneration)
	{
		if (!enabled || expectedGeneration != generation || reconnectFuture != null)
		{
			return;
		}

		long delay = reconnectDelayMs;
		reconnectDelayMs = Math.min(MAX_RECONNECT_DELAY_MS, reconnectDelayMs * 2L);
		try
		{
			reconnectFuture = scheduler.schedule(
				() -> reconnect(expectedGeneration), delay, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex)
		{
			LOG.debug("Unable to schedule Buddies presence reconnect", ex);
		}
	}

	private synchronized void scheduleJoinTimeout(long expectedGeneration, WebSocket source)
	{
		cancelJoinTimeout();
		try
		{
			joinTimeoutFuture = scheduler.schedule(() ->
			{
				synchronized (PresenceClient.this)
				{
					joinTimeoutFuture = null;
					if (!isCurrent(expectedGeneration, source)
						|| status.get() == PresenceStatus.CONNECTED)
					{
						return;
					}
					onDisconnected(expectedGeneration, source, null);
					source.cancel();
				}
			}, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex)
		{
			LOG.debug("Unable to schedule Buddies presence join timeout", ex);
		}
	}

	private synchronized void reconnect(long expectedGeneration)
	{
		reconnectFuture = null;
		if (!enabled || expectedGeneration != generation)
		{
			return;
		}
		setStatus(PresenceStatus.CONNECTING);
		openSocket(expectedGeneration);
	}

	void handlePayload(Object payload)
	{
		if (payload == null)
		{
			return;
		}

		try
		{
			JsonElement root = payload instanceof JsonElement
				? (JsonElement) payload
				: new JsonParser().parse(payload.toString());
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
			JsonElement name = object.get("name");
			if (name != null && name.isJsonPrimitive())
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

	private synchronized void stopConnection()
	{
		enabled = false;
		generation++;
		if (reconnectFuture != null)
		{
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
		cancelJoinTimeout();
		if (socket != null)
		{
			socket.cancel();
			socket = null;
		}
		endpoint = null;
		room = null;
	}

	private void cancelJoinTimeout()
	{
		if (joinTimeoutFuture != null)
		{
			joinTimeoutFuture.cancel(false);
			joinTimeoutFuture = null;
		}
	}

	private synchronized boolean isCurrentConnection(long expectedGeneration, WebSocket source)
	{
		return isCurrent(expectedGeneration, source) && status.get() == PresenceStatus.CONNECTED;
	}

	private boolean isCurrent(long expectedGeneration, WebSocket source)
	{
		return enabled && expectedGeneration == generation && source == socket;
	}

	private void setStatus(PresenceStatus next)
	{
		PresenceStatus previous = status.getAndSet(next);
		if (previous != next)
		{
			statusListener.accept(next);
		}
	}

	private static String jsonString(JsonElement element)
	{
		try
		{
			return element != null && element.isJsonPrimitive() ? element.getAsString() : "unknown";
		}
		catch (RuntimeException ex)
		{
			return "unknown";
		}
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

	static URI toWebSocketEndpoint(URI server)
	{
		String scheme = "https".equalsIgnoreCase(server.getScheme()) ? "wss" : "ws";
		String path = server.getPath();
		path = path == null ? "" : path.replaceAll("/+$", "");
		if (!path.endsWith(ENDPOINT_PATH))
		{
			path += ENDPOINT_PATH;
		}
		try
		{
			return new URI(scheme, null, server.getHost(), server.getPort(), path, null, null);
		}
		catch (URISyntaxException ex)
		{
			return null;
		}
	}

	private final class Listener extends WebSocketListener
	{
		private final long expectedGeneration;

		private Listener(long expectedGeneration)
		{
			this.expectedGeneration = expectedGeneration;
		}

		@Override
		public void onOpen(WebSocket webSocket, Response response)
		{
			PresenceClient.this.onOpen(expectedGeneration, webSocket);
		}

		@Override
		public void onMessage(WebSocket webSocket, String text)
		{
			PresenceClient.this.onMessage(expectedGeneration, webSocket, text);
		}

		@Override
		public void onClosing(WebSocket webSocket, int code, String reason)
		{
			webSocket.close(code, reason);
		}

		@Override
		public void onClosed(WebSocket webSocket, int code, String reason)
		{
			PresenceClient.this.onDisconnected(expectedGeneration, webSocket, null);
		}

		@Override
		public void onFailure(WebSocket webSocket, Throwable throwable, Response response)
		{
			PresenceClient.this.onDisconnected(expectedGeneration, webSocket, throwable);
		}
	}
}
