package com.buddies;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(BuddiesConfig.GROUP)
public interface BuddiesConfig extends Config
{
	String GROUP = "buddies";

	@ConfigItem(
		position = 0,
		keyName = "serverAddress",
		name = "Presence server",
		description = "WebSocket server used to share activity and location"
	)
	default String serverAddress()
	{
		return "https://buddies-presence.onrender.com";
	}

	@ConfigItem(
		position = 1,
		keyName = "shareLocation",
		name = "Share location",
		description = "Share your current area with online friends who use Buddies"
	)
	default boolean shareLocation()
	{
		return true;
	}

	@ConfigItem(
		position = 2,
		keyName = "shareActivity",
		name = "Share activity",
		description = "Share recently detected combat or skill training while you are online"
	)
	default boolean shareActivity()
	{
		return true;
	}

	@Range(min = 10, max = 120)
	@ConfigItem(
		position = 3,
		keyName = "presenceFreshness",
		name = "Presence freshness",
		description = "Seconds before a shared activity or location is treated as stale"
	)
	default int presenceFreshness()
	{
		return 30;
	}
}
