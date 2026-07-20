package com.buddies.presence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.buddies.model.BuddyPresence;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Test;

public class PresenceClientTest
{
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	@After
	public void shutDown()
	{
		scheduler.shutdownNow();
	}

	@Test
	public void privacyFieldsAreSerializedAsExplicitNulls()
	{
		PresenceClient client = newClient(new FakeWebSocketFactory(), status -> { }, presence -> { });
		String json = client.encode(new BuddyPresence("Alice", 302, null, null, 123));
		assertTrue(json.contains("\"location\":null"));
		assertTrue(json.contains("\"activity\":null"));
	}

	@Test
	public void parsesOnlyHttpServerAddresses()
	{
		assertTrue(PresenceClient.parseServerAddress("localhost:3000").toString().startsWith("http://"));
		assertTrue(PresenceClient.parseServerAddress("https://example.com/").toString().startsWith("https://"));
		assertNull(PresenceClient.parseServerAddress("ftp://example.com"));
		assertNull(PresenceClient.parseServerAddress(" "));
		assertEquals("ws://localhost:3000/presence/v1", PresenceClient.toWebSocketEndpoint(
			PresenceClient.parseServerAddress("localhost:3000")).toString());
		assertEquals("wss://example.com/base/presence/v1", PresenceClient.toWebSocketEndpoint(
			PresenceClient.parseServerAddress("https://example.com/base/")).toString());
	}

	@Test
	public void malformedSnapshotEntryDoesNotBlockValidEntries()
	{
		List<BuddyPresence> received = new ArrayList<>();
		PresenceClient client = newClient(new FakeWebSocketFactory(), status -> { }, received::add);
		client.handlePayload("{\"bad\":{\"version\":\"not-a-number\"},"
			+ "\"Alice\":{\"version\":1,\"name\":\"Alice\",\"world\":302,"
			+ "\"location\":null,\"activity\":null,\"updatedAt\":123}}");

		assertEquals(1, received.size());
		assertEquals("Alice", received.get(0).getName());
	}

	@Test
	public void snapshotCanContainAPlayerNamedName()
	{
		List<BuddyPresence> received = new ArrayList<>();
		PresenceClient client = newClient(new FakeWebSocketFactory(), status -> { }, received::add);
		client.handlePayload("{\"name\":{\"version\":1,\"name\":\"Name\",\"world\":302,"
			+ "\"location\":null,\"activity\":null,\"updatedAt\":123}}");

		assertEquals(1, received.size());
		assertEquals("Name", received.get(0).getName());
	}

	@Test
	public void exchangesVersionedWebSocketMessages()
	{
		FakeWebSocketFactory factory = new FakeWebSocketFactory();
		List<PresenceStatus> statuses = new ArrayList<>();
		List<BuddyPresence> received = new ArrayList<>();
		PresenceClient client = newClient(factory, statuses::add, received::add);

		client.connect("https://example.com", "shared secret");
		assertEquals(PresenceStatus.CONNECTING, client.getStatus());
		assertEquals("/presence/v1", factory.request.url().encodedPath());
		assertTrue(factory.request.url().isHttps());

		factory.open();
		JsonObject join = parse(factory.socket.messages.get(0));
		assertEquals("join", join.get("type").getAsString());
		assertEquals(RoomKey.derive("shared secret"), join.get("room").getAsString());
		assertEquals(PresenceStatus.CONNECTING, client.getStatus());

		factory.message("{\"type\":\"joined\"}");
		assertEquals(PresenceStatus.CONNECTED, client.getStatus());

		client.broadcast(new BuddyPresence("Alice", 302, null, null, 123));
		client.requestSnapshot();
		assertEquals("broadcast", parse(factory.socket.messages.get(1)).get("type").getAsString());
		assertEquals("snapshot", parse(factory.socket.messages.get(2)).get("type").getAsString());

		factory.message("{\"type\":\"presence\",\"presence\":{"
			+ "\"version\":1,\"name\":\"Bob\",\"world\":303,"
			+ "\"location\":null,\"activity\":null,\"updatedAt\":124}}");
		factory.message("{\"type\":\"snapshot\",\"presences\":{\"Carol\":{"
			+ "\"version\":1,\"name\":\"Carol\",\"world\":304,"
			+ "\"location\":null,\"activity\":null,\"updatedAt\":125}}}");

		assertEquals(2, received.size());
		assertEquals("Bob", received.get(0).getName());
		assertEquals("Carol", received.get(1).getName());
		assertTrue(statuses.contains(PresenceStatus.CONNECTED));

		client.close();
		assertTrue(factory.socket.cancelled);
		assertEquals(PresenceStatus.DISABLED, client.getStatus());
	}

	private PresenceClient newClient(
		WebSocket.Factory factory,
		java.util.function.Consumer<PresenceStatus> statusListener,
		java.util.function.Consumer<BuddyPresence> presenceListener)
	{
		return new PresenceClient(factory, new Gson(), scheduler, statusListener, presenceListener);
	}

	private static JsonObject parse(String json)
	{
		return new JsonParser().parse(json).getAsJsonObject();
	}

	private static final class FakeWebSocketFactory implements WebSocket.Factory
	{
		private Request request;
		private WebSocketListener listener;
		private FakeWebSocket socket;

		@Override
		public WebSocket newWebSocket(Request request, WebSocketListener listener)
		{
			this.request = request;
			this.listener = listener;
			this.socket = new FakeWebSocket(request);
			return socket;
		}

		private void open()
		{
			listener.onOpen(socket, null);
		}

		private void message(String message)
		{
			listener.onMessage(socket, message);
		}
	}

	private static final class FakeWebSocket implements WebSocket
	{
		private final Request request;
		private final List<String> messages = new ArrayList<>();
		private boolean cancelled;

		private FakeWebSocket(Request request)
		{
			this.request = request;
		}

		@Override
		public Request request()
		{
			return request;
		}

		@Override
		public long queueSize()
		{
			return 0;
		}

		@Override
		public boolean send(String text)
		{
			messages.add(text);
			return true;
		}

		@Override
		public boolean send(ByteString bytes)
		{
			return false;
		}

		@Override
		public boolean close(int code, String reason)
		{
			return true;
		}

		@Override
		public void cancel()
		{
			cancelled = true;
		}
	}
}
