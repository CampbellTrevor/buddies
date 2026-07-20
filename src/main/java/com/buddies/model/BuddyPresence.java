package com.buddies.model;

import com.buddies.location.BuddyLocation;

public final class BuddyPresence
{
	public static final int CURRENT_VERSION = 1;

	private int version;
	private String name;
	private int world;
	private BuddyLocation location;
	private String activity;
	private long updatedAt;

	@SuppressWarnings("unused")
	private BuddyPresence()
	{
		// Gson constructor.
	}

	public BuddyPresence(String name, int world, BuddyLocation location, String activity, long updatedAt)
	{
		this.version = CURRENT_VERSION;
		this.name = name;
		this.world = world;
		this.location = location;
		this.activity = activity;
		this.updatedAt = updatedAt;
	}

	public int getVersion()
	{
		return version;
	}

	public String getName()
	{
		return name;
	}

	public int getWorld()
	{
		return world;
	}

	public BuddyLocation getLocation()
	{
		return location;
	}

	public String getActivity()
	{
		return activity;
	}

	public long getUpdatedAt()
	{
		return updatedAt;
	}
}

