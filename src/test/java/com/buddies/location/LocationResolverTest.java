package com.buddies.location;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LocationResolverTest
{
	@Test
	public void resolvesSpecificAndInstancedAreas()
	{
		assertEquals("Grand Exchange", resolve(3165, 3490, 0));
		assertEquals("Player-owned House", resolve(1942, 5741, 1));
		assertEquals("Duke Sucellus (Ghorrock Prison)", resolve(3030, 6441, 0));
		assertEquals("God Wars Dungeon", resolve(2917, 5312, 0));
	}

	@Test
	public void resolvesRaidsBeforeBroadFallbacks()
	{
		assertEquals("Chambers of Xeric", resolve(1232, 3573, 0));
		assertEquals("Theatre of Blood", resolve(3200, 4320, 0));
		assertEquals("Tombs of Amascut", resolve(3671, 5398, 1));
		assertEquals("Tombs of Amascut (Tumeken's Warden)", resolve(3803, 5157, 1));
	}

	@Test
	public void handlesNullAndBroadMapSpace()
	{
		assertEquals("unknown", LocationResolver.resolve(null));
		assertEquals("Open Sea", resolve(960, 2048, 0));
		assertEquals("Underground", resolve(2560, 5760, 0));
	}

	private static String resolve(int x, int y, int plane)
	{
		return LocationResolver.resolve(new BuddyLocation(x, y, plane));
	}
}

