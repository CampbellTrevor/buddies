package com.buddies;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

final class ActivityTracker
{
	static final long TRAINING_TIMEOUT_MILLIS = 30_000L;
	static final long COMBAT_GRACE_MILLIS = 5_000L;
	private static final int MAX_ACTIVITY_LENGTH = 40;

	private final Map<Skill, Integer> experience = new EnumMap<>(Skill.class);
	private String trainingActivity;
	private long trainingObservedAt = -1L;
	private String combatActivity;
	private boolean combatActive;
	private long combatEndedAt = -1L;

	void seedExperience(Skill skill, int xp)
	{
		if (skill != null)
		{
			experience.put(skill, xp);
		}
	}

	void observeExperience(Skill skill, int xp, long now)
	{
		if (skill == null)
		{
			return;
		}

		Integer previous = experience.put(skill, xp);
		if (previous == null || xp <= previous || skill == Skill.HITPOINTS || skill == Skill.OVERALL)
		{
			return;
		}

		if (!hasCombatActivity(now))
		{
			trainingActivity = limit("Training " + skill.getName());
			trainingObservedAt = now;
		}
	}

	void observeCombat(String targetName, long now)
	{
		String cleanName = targetName == null ? "" : targetName.trim();
		combatActivity = cleanName.isEmpty()
			? "In combat"
			: limit("Fighting " + cleanName);
		combatActive = true;
		combatEndedAt = -1L;
		trainingActivity = null;
		trainingObservedAt = -1L;
	}

	void observeNoCombat(long now)
	{
		if (combatActive)
		{
			combatActive = false;
			combatEndedAt = now;
		}
	}

	String getActivity(long now)
	{
		if (hasCombatActivity(now))
		{
			return combatActivity;
		}
		return isFresh(trainingObservedAt, now, TRAINING_TIMEOUT_MILLIS) ? trainingActivity : null;
	}

	void reset()
	{
		experience.clear();
		trainingActivity = null;
		trainingObservedAt = -1L;
		combatActivity = null;
		combatActive = false;
		combatEndedAt = -1L;
	}

	private boolean hasCombatActivity(long now)
	{
		return combatActivity != null
			&& (combatActive || isFresh(combatEndedAt, now, COMBAT_GRACE_MILLIS));
	}

	private static boolean isFresh(long observedAt, long now, long timeout)
	{
		return observedAt >= 0L
			&& now >= observedAt
			&& now - observedAt < timeout;
	}

	private static String limit(String activity)
	{
		return activity.length() <= MAX_ACTIVITY_LENGTH
			? activity
			: activity.substring(0, MAX_ACTIVITY_LENGTH);
	}
}
