package com.buddies.presence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RoomKey
{
	private RoomKey()
	{
	}

	public static String derive(String sharedKey)
	{
		String clean = sharedKey == null ? "" : sharedKey.trim();
		if (clean.isEmpty())
		{
			return "";
		}

		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(clean.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte value : digest)
			{
				hex.append(String.format("%02x", value & 0xff));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}
}

