package com.buddies.presence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class RoomKeyTest
{
	@Test
	public void derivesStableSha256RoomIds()
	{
		assertEquals(
			"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
			RoomKey.derive("hello"));
		assertEquals(RoomKey.derive("hello"), RoomKey.derive("  hello  "));
		assertEquals(64, RoomKey.derive("shared secret").length());
		assertNotEquals(RoomKey.derive("Secret"), RoomKey.derive("secret"));
		assertEquals("", RoomKey.derive("   "));
	}
}

