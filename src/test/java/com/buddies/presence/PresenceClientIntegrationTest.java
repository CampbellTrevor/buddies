package com.buddies.presence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.buddies.location.BuddyLocation;
import com.buddies.model.BuddyPresence;
import com.google.gson.Gson;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PresenceClientIntegrationTest
{
	@Test
	public void javaClientExchangesPresenceWithLiveServer() throws Exception
	{
		String url = System.getenv("BUDDIES_INTEGRATION_URL");
		assumeTrue("Set BUDDIES_INTEGRATION_URL to run the live presence test", url != null && !url.isEmpty());

		CountDownLatch connected = new CountDownLatch(2);
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<BuddyPresence> peerPresence = new AtomicReference<>();
		PresenceClient sender = new PresenceClient(new Gson(),
			status -> countConnected(status, connected), presence -> { });
		PresenceClient peer = new PresenceClient(new Gson(),
			status -> countConnected(status, connected), presence ->
			{
				peerPresence.set(presence);
				received.countDown();
			});

		try
		{
			sender.connect(url, "integration-test-room-key");
			peer.connect(url, "integration-test-room-key");
			assertTrue("Clients did not connect", connected.await(10, TimeUnit.SECONDS));

			BuddyPresence payload = new BuddyPresence(
				"Java Client",
				302,
				new BuddyLocation(3200, 3201, 0),
				"Fishing",
				System.currentTimeMillis());
			for (int attempt = 0; attempt < 5 && received.getCount() > 0; attempt++)
			{
				sender.broadcast(payload);
				received.await(250, TimeUnit.MILLISECONDS);
			}

			assertEquals("Peer did not receive Java client presence", 0, received.getCount());
			BuddyPresence actual = peerPresence.get();
			assertNotNull(actual);
			assertEquals("Java Client", actual.getName());
			assertEquals(302, actual.getWorld());
			assertEquals(new BuddyLocation(3200, 3201, 0), actual.getLocation());
			assertEquals("Fishing", actual.getActivity());
			assertTrue(actual.getUpdatedAt() > 0);
		}
		finally
		{
			sender.close();
			peer.close();
		}
	}

	private static void countConnected(PresenceStatus status, CountDownLatch latch)
	{
		if (status == PresenceStatus.CONNECTED)
		{
			latch.countDown();
		}
	}
}

