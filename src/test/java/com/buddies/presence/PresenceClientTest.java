package com.buddies.presence;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.buddies.model.BuddyPresence;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PresenceClientTest
{
	@Test
	public void privacyFieldsAreSerializedAsExplicitNulls()
	{
		PresenceClient client = new PresenceClient(new Gson(), status -> { }, presence -> { });
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
	}

	@Test
	public void malformedSnapshotEntryDoesNotBlockValidEntries()
	{
		List<BuddyPresence> received = new ArrayList<>();
		PresenceClient client = new PresenceClient(new Gson(), status -> { }, received::add);
		client.handlePayload("{\"bad\":{\"version\":\"not-a-number\"},"
			+ "\"Alice\":{\"version\":1,\"name\":\"Alice\",\"world\":302,"
			+ "\"location\":null,\"activity\":null,\"updatedAt\":123}}");

		assertEquals(1, received.size());
		assertEquals("Alice", received.get(0).getName());
	}
}
