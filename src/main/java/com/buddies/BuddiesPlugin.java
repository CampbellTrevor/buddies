package com.buddies;

import com.buddies.location.BuddyLocation;
import com.buddies.model.Buddy;
import com.buddies.model.BuddyDirectory;
import com.buddies.model.BuddyPresence;
import com.buddies.presence.PresenceClient;
import com.buddies.presence.PresenceStatus;
import com.buddies.ui.BuddiesIcon;
import com.buddies.ui.BuddiesPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Friend;
import net.runelite.api.FriendContainer;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldEntity;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Buddies",
	description = "Friends list activity, location, stats, and highscores"
)
public class BuddiesPlugin extends Plugin
{
	private static final Logger LOG = LoggerFactory.getLogger(BuddiesPlugin.class);
	private static final int BROADCAST_TICKS = 8;
	private static final int SNAPSHOT_TICKS = 50;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private BuddiesConfig config;

	@Inject
	private Gson gson;

	@Inject
	private HiscoreClient hiscoreClient;

	@Inject
	private OkHttpClient okHttpClient;

	private final BuddyDirectory directory = new BuddyDirectory();
	private final Set<String> hiscoreRequests = ConcurrentHashMap.newKeySet();
	private final ActivityTracker activityTracker = new ActivityTracker();

	private volatile ScheduledExecutorService worker;
	private volatile PresenceClient presenceClient;
	private volatile BuddiesPanel panel;
	private NavigationButton navigationButton;
	private BuddyLocation localLocation;
	private BuddyLocation lastBroadcastLocation;
	private String currentActivity;
	private int lastBroadcastWorld;
	private int tickCounter;
	private volatile boolean forceBroadcast;

	@Override
	protected void startUp()
	{
		worker = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "buddies-worker");
			thread.setDaemon(true);
			return thread;
		});
		panel = injector.getInstance(BuddiesPanel.class);
		BufferedImage icon = BuddiesIcon.create();
		navigationButton = NavigationButton.builder()
			.tooltip("Buddies")
			.icon(icon)
			.priority(15)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loadLoggedInState();
		}
		else
		{
			panel.updateFriends(Collections.emptyList(), freshnessMillis());
			panel.setPresenceStatus(PresenceStatus.DISABLED);
		}
	}

	@Override
	protected void shutDown()
	{
		stopPresence();
		hiscoreRequests.clear();
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		panel = null;
		directory.syncFriends(Collections.emptyMap(), System.currentTimeMillis());
		localLocation = null;
		lastBroadcastLocation = null;
		activityTracker.reset();
		currentActivity = null;
		tickCounter = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			loadLoggedInState();
		}
		else if (state == GameState.LOGIN_SCREEN
			|| state == GameState.HOPPING
			|| state == GameState.CONNECTION_LOST)
		{
			stopPresence();
			directory.syncFriends(Collections.emptyMap(), System.currentTimeMillis());
			if (panel != null)
			{
				panel.updateFriends(directory.snapshot(), freshnessMillis());
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		tickCounter++;
		boolean friendsChanged = refreshFriends();
		Player localPlayer = client.getLocalPlayer();
		localLocation = localPlayer == null ? null : readLocation(localPlayer);
		long now = System.currentTimeMillis();
		observeCombat(localPlayer, now);
		updateCurrentActivity(now);
		if (friendsChanged && panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
		else if (panel != null && tickCounter % BROADCAST_TICKS == 0)
		{
			String selected = panel.getSelectedBuddyName();
			panel.refreshBuddy(selected == null ? null : directory.find(selected), freshnessMillis());
		}

		if (presenceClient != null && presenceClient.getStatus() == PresenceStatus.CONNECTED)
		{
			if (forceBroadcast || tickCounter % BROADCAST_TICKS == 0 || hasLocalPresenceChanged())
			{
				broadcastLocalPresence();
			}
			if (tickCounter % SNAPSHOT_TICKS == 0)
			{
				presenceClient.requestSnapshot();
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			long now = System.currentTimeMillis();
			activityTracker.observeExperience(event.getSkill(), event.getXp(), now);
			updateCurrentActivity(now);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.FRIENDS_UPDATE && refreshFriends() && panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BuddiesConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("serverAddress".equals(event.getKey()) || "roomKey".equals(event.getKey()))
		{
			restartPresence();
		}
		else if ("shareLocation".equals(event.getKey()) || "shareActivity".equals(event.getKey()))
		{
			forceBroadcast = true;
			broadcastLocalPresence();
		}
		else if ("presenceFreshness".equals(event.getKey()) && panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
	}

	public void requestHiscores(String name)
	{
		String requestKey = name == null ? "" : name.toLowerCase(Locale.ROOT);
		if (name == null || worker == null || !directory.isFriend(name) || !hiscoreRequests.add(requestKey))
		{
			return;
		}
		if (panel != null)
		{
			panel.setHiscoresLoading(name);
		}

		try
		{
			hiscoreClient.lookupAsync(name, HiscoreEndpoint.NORMAL)
				.orTimeout(15, TimeUnit.SECONDS)
				.whenComplete((result, exception) ->
				{
				hiscoreRequests.remove(requestKey);
				String error = null;
				if (exception != null)
				{
					LOG.debug("Unable to load hiscores for {}", name, exception);
					error = "Hiscores unavailable";
				}
				else if (result == null)
				{
					error = "No hiscore data";
				}

				BuddiesPanel currentPanel = panel;
				if (currentPanel != null && directory.isFriend(name))
				{
					currentPanel.setHiscores(name, result, error);
				}
				});
		}
		catch (RuntimeException ex)
		{
			hiscoreRequests.remove(requestKey);
			LOG.debug("Unable to start hiscore lookup for {}", name, ex);
			if (panel != null)
			{
				panel.setHiscores(name, null, "Hiscores unavailable");
			}
		}
	}

	public void refreshSelectedBuddy()
	{
		clientThread.invokeLater(() ->
		{
			refreshFriends();
			if (panel != null)
			{
				panel.updateFriends(directory.snapshot(), freshnessMillis());
				String selected = panel.getSelectedBuddyName();
				if (selected != null)
				{
					requestHiscores(selected);
				}
			}
		});
	}

	long freshnessMillis()
	{
		return Math.max(10, config.presenceFreshness()) * 1_000L;
	}

	private void loadLoggedInState()
	{
		resetActivityTracking();
		refreshFriends();
		if (panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
		restartPresence();
	}

	private void resetActivityTracking()
	{
		activityTracker.reset();
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				activityTracker.seedExperience(skill, client.getSkillExperience(skill));
			}
		}
		currentActivity = null;
	}

	private void observeCombat(Player localPlayer, long now)
	{
		if (localPlayer == null)
		{
			return;
		}

		Actor target = localPlayer.getInteracting();
		if (isAttackableNpc(target))
		{
			activityTracker.observeCombat(target.getName(), now);
		}
		else
		{
			activityTracker.observeNoCombat(now);
		}
	}

	private static boolean isAttackableNpc(Actor actor)
	{
		if (!(actor instanceof NPC))
		{
			return false;
		}

		NPC npc = (NPC) actor;
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		if (composition == null || composition.getActions() == null)
		{
			return false;
		}
		for (String action : composition.getActions())
		{
			if ("Attack".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	private void updateCurrentActivity(long now)
	{
		String nextActivity = activityTracker.getActivity(now);
		if (!java.util.Objects.equals(currentActivity, nextActivity))
		{
			currentActivity = nextActivity;
			forceBroadcast = true;
		}
	}

	private boolean refreshFriends()
	{
		Map<String, Integer> friends = new LinkedHashMap<>();
		FriendContainer container = client.getFriendContainer();
		if (container == null || container.getCount() < 0)
		{
			return false;
		}
		Friend[] members = container.getMembers();
		if (members == null)
		{
			return false;
		}
		for (Friend friend : members)
		{
			if (friend != null && friend.getName() != null)
			{
				friends.put(friend.getName(), friend.getWorld());
			}
		}
		return directory.syncFriends(friends, System.currentTimeMillis());
	}

	private void restartPresence()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			stopPresence();
			return;
		}

		stopPresence();
		directory.clearPresence();
		if (panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
		ScheduledExecutorService currentWorker = worker;
		if (currentWorker == null)
		{
			return;
		}
		presenceClient = new PresenceClient(
			okHttpClient,
			gson,
			currentWorker,
			this::onPresenceStatus,
			this::onPresence);
		presenceClient.connect(config.serverAddress(), config.roomKey());
	}

	private void stopPresence()
	{
		if (presenceClient != null)
		{
			presenceClient.close();
			presenceClient = null;
		}
	}

	private void onPresenceStatus(PresenceStatus status)
	{
		if (panel != null)
		{
			panel.setPresenceStatus(status);
		}
		if (status == PresenceStatus.CONNECTED)
		{
			forceBroadcast = true;
			clientThread.invokeLater(this::broadcastLocalPresence);
			PresenceClient connectedClient = presenceClient;
			ScheduledExecutorService currentWorker = worker;
			if (connectedClient != null && currentWorker != null)
			{
				currentWorker.schedule(() ->
				{
					if (presenceClient == connectedClient)
					{
						connectedClient.requestSnapshot();
					}
				}, 750, TimeUnit.MILLISECONDS);
			}
		}
	}

	private void onPresence(BuddyPresence presence)
	{
		if (directory.applyPresence(presence, System.currentTimeMillis()) && panel != null)
		{
			panel.updateFriends(directory.snapshot(), freshnessMillis());
		}
	}

	private void broadcastLocalPresence()
	{
		PresenceClient currentClient = presenceClient;
		Player localPlayer = client.getLocalPlayer();
		if (currentClient == null
			|| currentClient.getStatus() != PresenceStatus.CONNECTED
			|| localPlayer == null)
		{
			return;
		}

		BuddyLocation sharedLocation = config.shareLocation() ? localLocation : null;
		String sharedActivity = config.shareActivity() ? currentActivity : null;
		int world = client.getWorld();
		currentClient.broadcast(new BuddyPresence(
			localPlayer.getName(),
			world,
			sharedLocation,
			sharedActivity,
			System.currentTimeMillis()));
		lastBroadcastLocation = sharedLocation;
		lastBroadcastWorld = world;
		forceBroadcast = false;
	}

	private boolean hasLocalPresenceChanged()
	{
		BuddyLocation sharedLocation = config.shareLocation() ? localLocation : null;
		return !java.util.Objects.equals(lastBroadcastLocation, sharedLocation)
			|| lastBroadcastWorld != client.getWorld();
	}

	private BuddyLocation readLocation(Player player)
	{
		WorldPoint point;
		if (client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT) == 1)
		{
			point = fromSailingLocal(player.getLocalLocation());
		}
		else if (player.getWorldView().isInstance())
		{
			point = WorldPoint.fromLocalInstance(client, player.getLocalLocation());
		}
		else
		{
			point = player.getWorldLocation();
		}
		return point == null ? null : new BuddyLocation(point);
	}

	private WorldPoint fromSailingLocal(LocalPoint point)
	{
		if (point == null)
		{
			return null;
		}
		WorldEntity entity = client.getTopLevelWorldView().worldEntities().byIndex(point.getWorldView());
		if (entity == null)
		{
			return null;
		}
		LocalPoint transformed = entity.transformToMainWorld(point);
		return transformed == null ? null : WorldPoint.fromLocal(
			client.getTopLevelWorldView(),
			transformed.getX(),
			transformed.getY(),
			client.getTopLevelWorldView().getPlane());
	}

	@Provides
	BuddiesConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(BuddiesConfig.class);
	}

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BuddiesPlugin.class);
		RuneLite.main(args);
	}
}
