package com.buddies.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.buddies.location.BuddyLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BuddyDirectoryTest
{
	private static final long NOW = 1_000_000L;

	@Test
	public void reconcilesAndSortsRuneLiteFriends()
	{
		BuddyDirectory directory = new BuddyDirectory();
		Map<String, Integer> first = new LinkedHashMap<>();
		first.put("Zed", 0);
		first.put("alice", 302);
		first.put("Bob", 301);

		assertTrue(directory.syncFriends(first, NOW));
		List<Buddy> buddies = directory.snapshot();
		assertEquals(3, buddies.size());
		assertEquals("alice", buddies.get(0).getName());
		assertEquals("Bob", buddies.get(1).getName());
		assertEquals("Zed", buddies.get(2).getName());

		Map<String, Integer> second = new LinkedHashMap<>();
		second.put("Zed", 444);
		second.put("Bob", 0);
		assertTrue(directory.syncFriends(second, NOW + 10));
		assertNull(directory.find("alice"));
		assertEquals(2, directory.size());
		assertEquals("Zed", directory.snapshot().get(0).getName());
	}

	@Test
	public void normalizesSpacesUnderscoresCaseAndNbsp()
	{
		BuddyDirectory directory = new BuddyDirectory();
		Map<String, Integer> friends = new LinkedHashMap<>();
		friends.put("Wise\u00a0Old__Man", 302);
		directory.syncFriends(friends, NOW);

		assertTrue(directory.isFriend("wise old man"));
		assertTrue(directory.isFriend("WISE_OLD_MAN"));
		assertEquals(1, directory.size());
	}

	@Test
	public void rejectsUnknownInvalidAndOfflinePresence()
	{
		BuddyDirectory directory = onlineDirectory();
		assertFalse(directory.applyPresence(presence("Unknown", NOW, new BuddyLocation(3200, 3200, 0)), NOW));
		assertFalse(directory.applyPresence(presence("Alice", NOW, new BuddyLocation(-1, 3200, 0)), NOW));

		Map<String, Integer> offline = new LinkedHashMap<>();
		offline.put("Alice", 0);
		directory.syncFriends(offline, NOW + 1);
		assertFalse(directory.applyPresence(presence("Alice", NOW + 2, new BuddyLocation(3200, 3200, 0)), NOW + 2));
		assertNull(directory.find("Alice").getLocation());
	}

	@Test
	public void onlineRosterIsAuthoritativeAndOfflineClearsPresence()
	{
		BuddyDirectory directory = onlineDirectory();
		assertTrue(directory.applyPresence(
			new BuddyPresence("Alice", 999, new BuddyLocation(3200, 3200, 0), "Fishing", NOW), NOW));
		Buddy buddy = directory.find("Alice");
		assertEquals(302, buddy.getWorld());
		assertTrue(buddy.hasFreshLocation(NOW, 30_000));

		Map<String, Integer> offline = new LinkedHashMap<>();
		offline.put("Alice", 0);
		directory.syncFriends(offline, NOW + 1);
		buddy = directory.find("Alice");
		assertFalse(buddy.isOnline());
		assertEquals(NOW + 1, buddy.getLastSeenAt());
		assertNull(buddy.getLocation());
		assertNull(buddy.getActivity());
		assertFalse(buddy.hasFreshPresence(NOW + 1, 30_000));
	}

	@Test
	public void duplicatesDoNotRefreshAndNullIsAnExplicitClear()
	{
		BuddyDirectory directory = onlineDirectory();
		BuddyPresence initial = presence("Alice", NOW, new BuddyLocation(3200, 3200, 0));
		assertTrue(directory.applyPresence(initial, NOW));
		assertFalse(directory.applyPresence(initial, NOW + 20_000));
		assertEquals(NOW, directory.find("Alice").getPresenceReceivedAt());

		BuddyPresence clear = new BuddyPresence("Alice", 302, null, null, NOW + 1);
		assertTrue(directory.applyPresence(clear, NOW + 1));
		Buddy buddy = directory.find("Alice");
		assertNull(buddy.getLocation());
		assertNull(buddy.getActivity());
		assertFalse(buddy.hasFreshLocation(NOW + 1, 30_000));
	}

	@Test
	public void freshnessRequiresBothRecentSourceAndReceipt()
	{
		BuddyDirectory directory = onlineDirectory();
		directory.applyPresence(presence("Alice", NOW, new BuddyLocation(3200, 3200, 0)), NOW + 5_000);
		Buddy buddy = directory.find("Alice");
		assertTrue(buddy.hasFreshPresence(NOW + 30_000, 30_000));
		assertFalse(buddy.hasFreshPresence(NOW + 30_001, 30_000));
	}

	@Test
	public void modestFutureServerClockSkewStillDisplaysFreshPresence()
	{
		BuddyDirectory directory = onlineDirectory();
		assertTrue(directory.applyPresence(
			presence("Alice", NOW + 1_000, new BuddyLocation(3200, 3200, 0)), NOW));
		assertTrue(directory.find("Alice").hasFreshPresence(NOW, 30_000));
	}

	private static BuddyDirectory onlineDirectory()
	{
		BuddyDirectory directory = new BuddyDirectory();
		Map<String, Integer> friends = new LinkedHashMap<>();
		friends.put("Alice", 302);
		directory.syncFriends(friends, NOW);
		return directory;
	}

	private static BuddyPresence presence(String name, long updatedAt, BuddyLocation location)
	{
		return new BuddyPresence(name, 302, location, "Fishing", updatedAt);
	}
}
