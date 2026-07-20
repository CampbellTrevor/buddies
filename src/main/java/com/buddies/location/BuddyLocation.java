package com.buddies.location;

import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

public final class BuddyLocation
{
	private final int x;
	private final int y;
	private final int plane;

	public BuddyLocation(WorldPoint point)
	{
		this(point.getX(), point.getY(), point.getPlane());
	}

	public BuddyLocation(int x, int y, int plane)
	{
		this.x = x;
		this.y = y;
		this.plane = plane;
	}

	public int getX()
	{
		return x;
	}

	public int getY()
	{
		return y;
	}

	public int getPlane()
	{
		return plane;
	}

	public boolean isValid()
	{
		return x >= 0 && x <= 16_383
			&& y >= 0 && y <= 16_383
			&& plane >= -1 && plane <= 3;
	}

	public WorldPoint toWorldPoint()
	{
		return new WorldPoint(x, y, plane);
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof BuddyLocation))
		{
			return false;
		}
		BuddyLocation that = (BuddyLocation) other;
		return x == that.x && y == that.y && plane == that.plane;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(x, y, plane);
	}
}

