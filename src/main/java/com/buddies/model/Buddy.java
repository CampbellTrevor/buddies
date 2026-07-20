package com.buddies.model;

import com.buddies.location.BuddyLocation;

public final class Buddy
{
	private final String name;
	private int world;
	private BuddyLocation location;
	private String activity;
	private long presenceReceivedAt;
	private long presenceSourceAt;
	private long lastSeenAt;

	Buddy(String name, int world)
	{
		this.name = name;
		this.world = Math.max(0, world);
	}

	private Buddy(Buddy source)
	{
		name = source.name;
		world = source.world;
		location = source.location;
		activity = source.activity;
		presenceReceivedAt = source.presenceReceivedAt;
		presenceSourceAt = source.presenceSourceAt;
		lastSeenAt = source.lastSeenAt;
	}

	Buddy copy()
	{
		return new Buddy(this);
	}

	void setWorld(int newWorld, long now)
	{
		newWorld = Math.max(0, newWorld);
		if (world > 0 && newWorld == 0)
		{
			lastSeenAt = now;
			clearPresence();
		}
		world = newWorld;
	}

	void applyPresence(BuddyPresence presence, long receivedAt)
	{
		location = presence.getLocation();
		activity = presence.getActivity();
		presenceSourceAt = presence.getUpdatedAt();
		presenceReceivedAt = receivedAt;
	}

	void clearPresence()
	{
		location = null;
		activity = null;
		presenceSourceAt = 0;
		presenceReceivedAt = 0;
	}

	public String getName()
	{
		return name;
	}

	public int getWorld()
	{
		return world;
	}

	public boolean isOnline()
	{
		return world > 0;
	}

	public BuddyLocation getLocation()
	{
		return location;
	}

	public String getActivity()
	{
		return activity;
	}

	public long getPresenceReceivedAt()
	{
		return presenceReceivedAt;
	}

	public long getPresenceSourceAt()
	{
		return presenceSourceAt;
	}

	public long getLastSeenAt()
	{
		return lastSeenAt;
	}

	public boolean hasFreshPresence(long now, long maxAgeMillis)
	{
		return isOnline()
			&& presenceReceivedAt > 0
			&& presenceSourceAt > 0
			&& now >= presenceReceivedAt
			&& now - presenceReceivedAt <= maxAgeMillis
			&& Math.max(0, now - presenceSourceAt) <= maxAgeMillis;
	}

	public boolean hasFreshLocation(long now, long maxAgeMillis)
	{
		return location != null && hasFreshPresence(now, maxAgeMillis);
	}
}
