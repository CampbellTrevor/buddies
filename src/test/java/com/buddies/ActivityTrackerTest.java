package com.buddies;

import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActivityTrackerTest
{
	private static final long NOW = 10_000L;

	@Test
	public void levelOnlyChangesDoNotCountAsTraining()
	{
		ActivityTracker tracker = new ActivityTracker();
		tracker.seedExperience(Skill.PRAYER, 1_000);

		tracker.observeExperience(Skill.PRAYER, 1_000, NOW);

		assertNull(tracker.getActivity(NOW));
	}

	@Test
	public void actualExperienceGainIsShortLivedTraining()
	{
		ActivityTracker tracker = new ActivityTracker();
		tracker.seedExperience(Skill.SAILING, 1_000);

		tracker.observeExperience(Skill.SAILING, 1_025, NOW);

		assertEquals("Training Sailing", tracker.getActivity(NOW));
		assertEquals("Training Sailing", tracker.getActivity(NOW + ActivityTracker.TRAINING_TIMEOUT_MILLIS - 1));
		assertNull(tracker.getActivity(NOW + ActivityTracker.TRAINING_TIMEOUT_MILLIS));
	}

	@Test
	public void combatTargetOverridesAndSuppressesSkillActivity()
	{
		ActivityTracker tracker = new ActivityTracker();
		tracker.seedExperience(Skill.RANGED, 1_000);
		tracker.observeCombat("Zulrah", NOW);

		tracker.observeExperience(Skill.RANGED, 1_100, NOW + 1_000L);

		assertEquals("Fighting Zulrah", tracker.getActivity(NOW + 1_000L));
		tracker.observeNoCombat(NOW + 2_000L);
		assertEquals("Fighting Zulrah", tracker.getActivity(NOW + 2_000L + ActivityTracker.COMBAT_GRACE_MILLIS - 1));
		assertNull(tracker.getActivity(NOW + 2_000L + ActivityTracker.COMBAT_GRACE_MILLIS));
	}

	@Test
	public void repeatedNoCombatObservationDoesNotExtendGracePeriod()
	{
		ActivityTracker tracker = new ActivityTracker();
		tracker.observeCombat("Zulrah", NOW);
		tracker.observeNoCombat(NOW + 1_000L);
		tracker.observeNoCombat(NOW + 2_000L);

		assertEquals("Fighting Zulrah", tracker.getActivity(NOW + 1_000L + ActivityTracker.COMBAT_GRACE_MILLIS - 1));
		assertNull(tracker.getActivity(NOW + 1_000L + ActivityTracker.COMBAT_GRACE_MILLIS));
	}
}
