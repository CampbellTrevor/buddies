package com.buddies.model;

import com.buddies.location.BuddyLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BuddyDirectory
{
	private static final int MAX_ACTIVITY_LENGTH = 40;
	private static final long MAX_FUTURE_SKEW_MILLIS = 5 * 60_000L;

	private final Map<String, Buddy> buddies = new LinkedHashMap<>();

	public synchronized boolean syncFriends(Map<String, Integer> friendWorlds, long now)
	{
		boolean changed = false;
		Set<String> incoming = new HashSet<>();

		for (Map.Entry<String, Integer> entry : friendWorlds.entrySet())
		{
			String name = sanitizeDisplayName(entry.getKey());
			if (name.isEmpty())
			{
				continue;
			}

			String key = normalizeName(name);
			incoming.add(key);
			int world = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
			Buddy buddy = buddies.get(key);
			if (buddy == null)
			{
				buddies.put(key, new Buddy(name, world));
				changed = true;
			}
			else if (buddy.getWorld() != world)
			{
				buddy.setWorld(world, now);
				changed = true;
			}
		}

		if (buddies.keySet().removeIf(key -> !incoming.contains(key)))
		{
			changed = true;
		}
		return changed;
	}

	public synchronized boolean applyPresence(BuddyPresence presence, long receivedAt)
	{
		if (!isValidPresence(presence, receivedAt))
		{
			return false;
		}

		Buddy buddy = buddies.get(normalizeName(presence.getName()));
		if (buddy == null || !buddy.isOnline() || presence.getUpdatedAt() <= buddy.getPresenceSourceAt())
		{
			return false;
		}

		BuddyLocation location = presence.getLocation();
		if (location != null && !location.isValid())
		{
			return false;
		}

		buddy.applyPresence(new BuddyPresence(
			buddy.getName(),
			presence.getWorld(),
			location,
			sanitizeActivity(presence.getActivity()),
			presence.getUpdatedAt()), receivedAt);
		return true;
	}

	public synchronized void clearPresence()
	{
		for (Buddy buddy : buddies.values())
		{
			buddy.clearPresence();
		}
	}

	public synchronized List<Buddy> snapshot()
	{
		List<Buddy> snapshot = new ArrayList<>();
		for (Buddy buddy : buddies.values())
		{
			snapshot.add(buddy.copy());
		}
		snapshot.sort(Comparator
			.comparing(Buddy::isOnline).reversed()
			.thenComparing(Buddy::getName, String.CASE_INSENSITIVE_ORDER));
		return snapshot;
	}

	public synchronized Buddy find(String name)
	{
		Buddy buddy = buddies.get(normalizeName(name));
		return buddy == null ? null : buddy.copy();
	}

	public synchronized boolean isFriend(String name)
	{
		return buddies.containsKey(normalizeName(name));
	}

	public synchronized int size()
	{
		return buddies.size();
	}

	private static boolean isValidPresence(BuddyPresence presence, long receivedAt)
	{
		return presence != null
			&& presence.getVersion() == BuddyPresence.CURRENT_VERSION
			&& presence.getName() != null
			&& !sanitizeDisplayName(presence.getName()).isEmpty()
			&& presence.getUpdatedAt() > 0
			&& presence.getUpdatedAt() <= receivedAt + MAX_FUTURE_SKEW_MILLIS;
	}

	static String normalizeName(String name)
	{
		return sanitizeDisplayName(name).toLowerCase(Locale.ROOT);
	}

	private static String sanitizeDisplayName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.replace('\u00a0', ' ')
			.replace('_', ' ')
			.trim()
			.replaceAll("\\s+", " ");
	}

	private static String sanitizeActivity(String activity)
	{
		if (activity == null)
		{
			return null;
		}
		String clean = activity.trim();
		if (clean.isEmpty())
		{
			return null;
		}
		return clean.length() <= MAX_ACTIVITY_LENGTH
			? clean
			: clean.substring(0, MAX_ACTIVITY_LENGTH);
	}
}
