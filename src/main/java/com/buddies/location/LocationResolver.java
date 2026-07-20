/*
 * Copyright (c) 2021, David Vorona <davidavorona@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.buddies.location;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.coords.WorldPoint;

public final class LocationResolver
{
	private static final int CLOSE_POINT_DISTANCE = 24;
	private static final int NEARBY_POINT_DISTANCE = 256;
	private static final String WORLDMAP_PACKAGE = "net.runelite.client.plugins.worldmap.";

	private static final List<String> WORLDMAP_LOCATION_CLASSES = Arrays.asList(
		"AgilityCourseLocation",
		"DungeonLocation",
		"FairyRingLocation",
		"FarmingPatchLocation",
		"FishingSpotLocation",
		"HunterAreaLocation",
		"MinigameLocation",
		"MiningSiteLocation",
		"MooringLocation",
		"RareTreeLocation",
		"RunecraftingAltarLocation",
		"TeleportLocationData",
		"TransportationPointLocation"
	);

	private static final List<NamedArea> SPECIAL_AREAS = Arrays.asList(
		area("Player-owned House", 1856, 5056, 2047, 5759),
		area("Player-owned House", 3584, 9472, 3647, 9535),
		area("Duke Sucellus (Ghorrock Prison)", 2944, 6336, 3071, 6527),
		area("God Wars Dungeon", 2816, 5248, 2943, 5375)
	);

	private static final List<NamedArea> SPECIFIC_AREAS = Arrays.asList(
		area("Grand Exchange", 3140, 3465, 3195, 3525),
		area("Varrock", 3150, 3370, 3295, 3525),
		area("Edgeville", 3060, 3470, 3125, 3525),
		area("Barbarian Village", 3060, 3390, 3120, 3455),
		area("Lumbridge", 3190, 3170, 3265, 3275),
		area("Draynor Village", 3065, 3220, 3120, 3295),
		area("Falador", 2940, 3310, 3065, 3395),
		area("Port Sarim", 3005, 3180, 3065, 3265),
		area("Rimmington", 2920, 3180, 2985, 3250),
		area("Taverley", 2870, 3400, 2945, 3485),
		area("Burthorpe", 2860, 3520, 2945, 3585),
		area("Al Kharid", 3260, 3140, 3335, 3320),
		area("Shantay Pass", 3280, 3105, 3325, 3145),
		area("Catherby", 2775, 3400, 2865, 3475),
		area("Camelot", 2720, 3465, 2785, 3525),
		area("Seers' Village", 2680, 3440, 2735, 3505),
		area("Ardougne", 2540, 3260, 2675, 3375),
		area("Tree Gnome Stronghold", 2400, 3380, 2505, 3525),
		area("Tree Gnome Village", 2480, 3140, 2555, 3205),
		area("Yanille", 2535, 3060, 2625, 3135),
		area("Castle Wars", 2425, 3060, 2485, 3125),
		area("Rellekka", 2615, 3640, 2695, 3715),
		area("Miscellania", 2490, 3820, 2585, 3895),
		area("Etceteria", 2585, 3825, 2645, 3895),
		area("Lunar Isle", 2075, 3850, 2155, 3935),
		area("Waterbirth Island", 2505, 3710, 2575, 3775),
		area("Neitiznot", 2305, 3760, 2375, 3835),
		area("Jatizso", 2380, 3770, 2445, 3835),
		area("Canifis", 3450, 3450, 3525, 3525),
		area("Mort'ton", 3455, 3240, 3525, 3315),
		area("Burgh de Rott", 3465, 3150, 3545, 3225),
		area("Port Phasmatys", 3650, 3440, 3715, 3510),
		area("Darkmeyer", 3580, 3310, 3685, 3385),
		area("Prifddinas", 2150, 3300, 2295, 3455),
		area("Lletya", 2310, 3150, 2375, 3225),
		area("Brimhaven", 2730, 3120, 2825, 3220),
		area("Musa Point", 2820, 3140, 2925, 3195),
		area("Tai Bwo Wannai", 2770, 3030, 2835, 3095),
		area("Shilo Village", 2820, 2940, 2895, 3010),
		area("Ape Atoll", 2690, 2690, 2825, 2815),
		area("Fossil Island", 3650, 3710, 3850, 3895),
		area("Hosidius", 1690, 3500, 1835, 3655),
		area("Piscarilius", 1770, 3680, 1845, 3795),
		area("Lovakengj", 1410, 3720, 1545, 3855),
		area("Shayzien", 1420, 3540, 1575, 3695),
		area("Arceuus", 1570, 3760, 1725, 3895),
		area("Kourend Castle", 1600, 3650, 1695, 3735),
		area("Wintertodt Camp", 1605, 3930, 1665, 3995),
		area("Farming Guild", 1210, 3710, 1275, 3775),
		area("Mount Karuulm", 1280, 3790, 1375, 3885),
		area("Tempoross Cove", 3100, 2920, 3175, 2995),
		area("Ferox Enclave", 3110, 3605, 3185, 3675),
		area("Mage Arena", 3080, 3910, 3145, 3975),
		area("Lava Maze", 3000, 3810, 3075, 3885)
	);

	private static final List<NamedRegion> BOSS_REGIONS = Arrays.asList(
		region("Duke Sucellus (Ghorrock Prison)", 12132),
		region("Vardorvis (The Stranglewood)", 4405),
		region("The Whisperer (Lassar Undercity)", 9571, 10595),
		region("The Leviathan (The Scar)", 8291),
		region("Phantom Muspah", 11330),
		region("Moons of Peril", 5526, 5782, 5783, 6038),
		region("Player-owned House", 7769),
		region("Sarachnis", 7322),
		region("Zulrah", 9007, 9008),
		region("Vorkath", 9023),
		region("Inferno", 9043),
		region("Tombs of Amascut (Tumeken's Warden)", 15184, 15696)
	);

	private static final List<NamedRegion> RAID_REGIONS = Arrays.asList(
		region("Chambers of Xeric", 12889, 13139, 13395),
		region("Theatre of Blood", 12611, 12612, 12613, 13123, 13125),
		region("Tombs of Amascut", 14676, 15188, 15698)
	);

	private static final List<NamedArea> RAID_AREAS = Arrays.asList(
		area("Chambers of Xeric", 1200, 3540, 1285, 3620),
		area("Chambers of Xeric", 3200, 5152, 3359, 5759),
		area("Theatre of Blood", 3640, 3180, 3715, 3265),
		area("Theatre of Blood", 3136, 4288, 3359, 4479),
		area("Tombs of Amascut", 3310, 2670, 3395, 2765),
		area("Tombs of Amascut", 3328, 9216, 3391, 9279),
		area("Tombs of Amascut", 3648, 5120, 3967, 5439)
	);

	private static final List<NamedArea> FALLBACK_AREAS = Arrays.asList(
		area("Open Sea", 900, 1100, 2300, 3200),
		area("Open Sea", 2300, 2000, 4100, 2850),
		area("Open Sea", 3900, 3000, 4300, 3711),
		area("Underground", 900, 3776, 4300, 10367),
		area("Instanced area", 900, 10368, 4300, 12600)
	);

	private static final List<NamedArea> REGIONS = Arrays.asList(
		area("Wilderness", 2940, 3520, 3400, 4030),
		area("Misthalin", 3070, 3200, 3335, 3520),
		area("Asgarnia", 2870, 3200, 3075, 3525),
		area("Kandarin", 2380, 3000, 2875, 3650),
		area("Fremennik Province", 2460, 3600, 2800, 3925),
		area("Fremennik Isles", 2260, 3725, 2465, 3925),
		area("Kharidian Desert", 3150, 2800, 3550, 3265),
		area("Morytania", 3350, 3150, 3810, 3610),
		area("Karamja", 2680, 2750, 3025, 3225),
		area("Great Kourend", 1380, 3450, 1850, 4010),
		area("Kebos Lowlands", 1200, 3500, 1405, 3900),
		area("Varlamore", 1260, 2900, 1850, 3450),
		area("Tirannwn", 2100, 3100, 2400, 3455),
		area("Feldip Hills", 2300, 2850, 2700, 3100),
		area("Piscatoris", 2200, 3350, 2425, 3650)
	);

	private static volatile List<NamedPoint> worldMapLocations;

	private LocationResolver()
	{
	}

	public static String resolve(BuddyLocation location)
	{
		if (location == null)
		{
			return "unknown";
		}

		WorldPoint point = location.toWorldPoint();
		NamedArea area = findArea(point, SPECIAL_AREAS);
		if (area != null)
		{
			return area.name;
		}

		area = findArea(point, SPECIFIC_AREAS);
		if (area != null)
		{
			return area.name;
		}

		NamedRegion bossRegion = findRegion(point, BOSS_REGIONS);
		if (bossRegion != null)
		{
			return bossRegion.name;
		}

		NamedRegion raidRegion = findRegion(point, RAID_REGIONS);
		if (raidRegion != null)
		{
			return raidRegion.name;
		}

		area = findArea(point, RAID_AREAS);
		if (area != null)
		{
			return area.name;
		}

		NamedPoint closePoint = findNearestPoint(point, CLOSE_POINT_DISTANCE);
		if (closePoint != null)
		{
			return closePoint.name;
		}

		area = findArea(point, REGIONS);
		if (area != null)
		{
			return area.name;
		}

		NamedPoint nearbyPoint = findNearestPoint(point, NEARBY_POINT_DISTANCE);
		if (nearbyPoint != null)
		{
			return "near " + nearbyPoint.name;
		}

		area = findArea(point, FALLBACK_AREAS);
		if (area != null)
		{
			return area.name;
		}

		return "unknown area";
	}

	private static NamedArea findArea(WorldPoint point, List<NamedArea> areas)
	{
		for (NamedArea area : areas)
		{
			if (area.contains(point))
			{
				return area;
			}
		}
		return null;
	}

	private static NamedRegion findRegion(WorldPoint point, List<NamedRegion> regions)
	{
		int regionId = ((point.getX() >> 6) << 8) | (point.getY() >> 6);
		for (NamedRegion region : regions)
		{
			if (region.contains(regionId))
			{
				return region;
			}
		}
		return null;
	}

	private static NamedPoint findNearestPoint(WorldPoint point, int maxDistance)
	{
		int maxDistanceSquared = maxDistance * maxDistance;
		int bestDistance = Integer.MAX_VALUE;
		NamedPoint best = null;
		for (NamedPoint namedPoint : getWorldMapLocations())
		{
			int distance = namedPoint.distanceSquaredTo(point);
			if (distance < bestDistance && distance <= maxDistanceSquared)
			{
				bestDistance = distance;
				best = namedPoint;
			}
		}
		return best;
	}

	private static List<NamedPoint> getWorldMapLocations()
	{
		List<NamedPoint> locations = worldMapLocations;
		if (locations == null)
		{
			locations = loadWorldMapLocations();
			worldMapLocations = locations;
		}
		return locations;
	}

	private static List<NamedPoint> loadWorldMapLocations()
	{
		List<NamedPoint> locations = new ArrayList<>();
		for (String className : WORLDMAP_LOCATION_CLASSES)
		{
			loadWorldMapLocations(className, locations);
		}
		return Collections.unmodifiableList(locations);
	}

	private static void loadWorldMapLocations(String className, List<NamedPoint> locations)
	{
		try
		{
			Class<?> locationClass = Class.forName(WORLDMAP_PACKAGE + className);
			Object[] constants = locationClass.getEnumConstants();
			if (constants == null)
			{
				return;
			}

			Method getLocation = getMethod(locationClass, "getLocation");
			Method getLocations = getMethod(locationClass, "getLocations");
			Method getCode = getMethod(locationClass, "getCode");
			Method getTooltip = getMethod(locationClass, "getTooltip");

			for (Object constant : constants)
			{
				String name = getLocationName(constant, getCode, getTooltip);
				if (name.isEmpty())
				{
					continue;
				}

				if (getLocation != null)
				{
					Object value = getLocation.invoke(constant);
					if (value instanceof WorldPoint)
					{
						locations.add(new NamedPoint(name, (WorldPoint) value));
					}
				}
				if (getLocations != null)
				{
					Object value = getLocations.invoke(constant);
					if (value instanceof WorldPoint[])
					{
						for (WorldPoint point : (WorldPoint[]) value)
						{
							locations.add(new NamedPoint(name, point));
						}
					}
				}
			}
		}
		catch (ReflectiveOperationException | RuntimeException | LinkageError ignored)
		{
			// The plugin still works with the curated area list if RuneLite changes these internal classes.
		}
	}

	private static Method getMethod(Class<?> clazz, String name)
	{
		try
		{
			Method method = clazz.getDeclaredMethod(name);
			method.setAccessible(true);
			return method;
		}
		catch (NoSuchMethodException ignored)
		{
			return null;
		}
	}

	private static String getLocationName(Object constant, Method getCode, Method getTooltip) throws ReflectiveOperationException
	{
		if (constant instanceof Enum)
		{
			if (getTooltip != null)
			{
				Object tooltip = getTooltip.invoke(constant);
				if (tooltip instanceof String && !((String) tooltip).isEmpty())
				{
					return (String) tooltip;
				}
			}
			if (getCode != null)
			{
				Object code = getCode.invoke(constant);
				if (code instanceof String)
				{
					return "Fairy ring " + code;
				}
			}
			String enumName = ((Enum<?>) constant).name();
			return humanizeEnumName(enumName);
		}
		return "";
	}

	private static String humanizeEnumName(String enumName)
	{
		String[] words = enumName.replaceAll("_+", " ").trim().toLowerCase(Locale.ROOT).split(" ");
		StringBuilder builder = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (builder.length() > 0)
			{
				builder.append(' ');
			}
			builder.append(formatWord(word));
		}
		return builder.toString()
			.replace("Mos Leharmless", "Mos Le'Harmless");
	}

	private static String formatWord(String word)
	{
		if ("ge".equals(word))
		{
			return "GE";
		}
		if ("gwd".equals(word))
		{
			return "GWD";
		}
		if ("ne".equals(word))
		{
			return "NE";
		}
		if ("nw".equals(word))
		{
			return "NW";
		}
		if ("se".equals(word))
		{
			return "SE";
		}
		if ("sw".equals(word))
		{
			return "SW";
		}
		if ("npc".equals(word) || "npcs".equals(word))
		{
			return word.toUpperCase(Locale.ROOT);
		}
		if ("tzhaar".equals(word))
		{
			return "TzHaar";
		}
		if ("kharedsts".equals(word))
		{
			return "Kharedst's";
		}
		if ("pharaohs".equals(word))
		{
			return "Pharaoh's";
		}
		if ("myths".equals(word))
		{
			return "Myths'";
		}
		if (word.length() == 1)
		{
			return word.toUpperCase(Locale.ROOT);
		}
		return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
	}

	private static NamedArea area(String name, int minX, int minY, int maxX, int maxY)
	{
		return new NamedArea(name, minX, minY, maxX, maxY);
	}

	private static NamedRegion region(String name, int... regionIds)
	{
		return new NamedRegion(name, regionIds);
	}

	private static final class NamedArea
	{
		private final String name;
		private final int minX;
		private final int minY;
		private final int maxX;
		private final int maxY;

		private NamedArea(String name, int minX, int minY, int maxX, int maxY)
		{
			this.name = name;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		private boolean contains(WorldPoint point)
		{
			return point.getX() >= minX
				&& point.getX() <= maxX
				&& point.getY() >= minY
				&& point.getY() <= maxY;
		}
	}

	private static final class NamedRegion
	{
		private final String name;
		private final int[] regionIds;

		private NamedRegion(String name, int[] regionIds)
		{
			this.name = name;
			this.regionIds = regionIds;
		}

		private boolean contains(int regionId)
		{
			for (int candidate : regionIds)
			{
				if (candidate == regionId)
				{
					return true;
				}
			}
			return false;
		}
	}

	private static final class NamedPoint
	{
		private final String name;
		private final WorldPoint point;

		private NamedPoint(String name, WorldPoint point)
		{
			this.name = name;
			this.point = point;
		}

		private int distanceSquaredTo(WorldPoint other)
		{
			int dx = point.getX() - other.getX();
			int dy = point.getY() - other.getY();
			return dx * dx + dy * dy;
		}
	}
}
