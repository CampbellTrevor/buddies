package com.buddies.location;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuddyLocationTest
{
	@Test
	public void validatesWorldCoordinateBounds()
	{
		assertTrue(new BuddyLocation(0, 0, -1).isValid());
		assertTrue(new BuddyLocation(16_383, 16_383, 3).isValid());
		assertFalse(new BuddyLocation(-1, 0, 0).isValid());
		assertFalse(new BuddyLocation(0, 16_384, 0).isValid());
		assertFalse(new BuddyLocation(0, 0, 4).isValid());
	}
}

