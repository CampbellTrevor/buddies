package com.buddies.ui;

import com.buddies.BuddiesPlugin;
import com.buddies.location.BuddyLocation;
import com.buddies.location.LocationResolver;
import com.buddies.model.Buddy;
import com.buddies.presence.PresenceStatus;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import net.runelite.api.Experience;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class BuddiesPanel extends PluginPanel
{
	private static final String FRIENDS = "friends";
	private static final String EMPTY = "empty";
	private static final Color ONLINE = new Color(46, 204, 113);

	private final BuddiesPlugin plugin;
	private final DefaultListModel<Buddy> friendModel = new DefaultListModel<>();
	private final JList<Buddy> friendList = new JList<>(friendModel);
	private final JPanel friendCards = new JPanel(new CardLayout());
	private final JPanel detailsPanel = new JPanel();
	private final JLabel connectionLabel = new JLabel("Sync off");
	private final JLabel usernameLabel = new JLabel();
	private final JLabel worldLabel = new JLabel();
	private final JLabel activityLabel = new JLabel();
	private final JLabel locationLabel = new JLabel();
	private final JLabel hiscoreStateLabel = new JLabel(" ");
	private final JLabel statsSummaryLabel = new JLabel("Combat --    Total --");
	private final HiscoreTableModel statsModel = new HiscoreTableModel(HiscoreTableModel.View.STATS);
	private final HiscoreTableModel highscoresModel = new HiscoreTableModel(HiscoreTableModel.View.HIGHSCORES);
	private final Map<String, HiscoreResult> hiscoreCache = new HashMap<>();
	private final Set<String> loadingHiscores = new HashSet<>();

	private volatile String selectedBuddyName;
	private long freshnessMillis;
	private boolean applyingFriends;

	@Inject
	public BuddiesPanel(BuddiesPlugin plugin, SpriteManager spriteManager)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.add(buildHeader());
		content.add(Box.createVerticalStrut(8));
		content.add(buildFriendList());
		content.add(Box.createVerticalStrut(8));
		content.add(buildDetails(spriteManager));
		add(content, BorderLayout.CENTER);
	}

	public void updateFriends(List<Buddy> buddies, long freshness)
	{
		List<Buddy> snapshot = new ArrayList<>(buddies);
		SwingUtilities.invokeLater(() -> applyFriends(snapshot, freshness));
	}

	public void refreshBuddy(Buddy buddy, long freshness)
	{
		SwingUtilities.invokeLater(() ->
		{
			freshnessMillis = freshness;
			if (buddy != null && buddy.getName().equalsIgnoreCase(selectedBuddyName))
			{
				applyBuddySummary(buddy);
			}
		});
	}

	public void setPresenceStatus(PresenceStatus status)
	{
		SwingUtilities.invokeLater(() ->
		{
			switch (status)
			{
				case CONNECTED:
					connectionLabel.setText("Live");
					connectionLabel.setForeground(ONLINE);
					break;
				case CONNECTING:
					connectionLabel.setText("Connecting");
					connectionLabel.setForeground(ColorScheme.BRAND_ORANGE);
					break;
				case DISCONNECTED:
					connectionLabel.setText("Offline");
					connectionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					break;
				default:
					connectionLabel.setText("Sync off");
					connectionLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			}
		});
	}

	public void setHiscoresLoading(String name)
	{
		SwingUtilities.invokeLater(() ->
		{
			loadingHiscores.add(key(name));
			if (name.equalsIgnoreCase(selectedBuddyName) && !hiscoreCache.containsKey(key(name)))
			{
				statsModel.setResult(null);
				highscoresModel.setResult(null);
				statsSummaryLabel.setText("Combat --    Total --");
				hiscoreStateLabel.setText("Loading hiscores...");
			}
		});
	}

	public void setHiscores(String name, HiscoreResult result, String error)
	{
		SwingUtilities.invokeLater(() ->
		{
			String key = key(name);
			loadingHiscores.remove(key);
			if (result != null)
			{
				hiscoreCache.put(key, result);
			}
			if (name.equalsIgnoreCase(selectedBuddyName))
			{
				applyHiscores(result, error);
			}
		});
	}

	public String getSelectedBuddyName()
	{
		return selectedBuddyName;
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Buddies");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		connectionLabel.setFont(FontManager.getRunescapeSmallFont());
		connectionLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		connectionLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		JButton refresh = new JButton("Refresh");
		refresh.setToolTipText("Refresh friends and selected hiscores");
		refresh.setFocusable(false);
		refresh.addActionListener(event -> plugin.refreshSelectedBuddy());

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		right.setBackground(ColorScheme.DARK_GRAY_COLOR);
		right.add(connectionLabel);
		right.add(refresh);
		header.add(title, BorderLayout.WEST);
		header.add(right, BorderLayout.EAST);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
		return header;
	}

	private JPanel buildFriendList()
	{
		friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		friendList.setCellRenderer(new FriendListCellRenderer());
		friendList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		friendList.setFixedCellHeight(34);
		friendList.addListSelectionListener(event ->
		{
			if (!event.getValueIsAdjusting() && !applyingFriends)
			{
				selectBuddy(friendList.getSelectedValue(), true);
			}
		});

		JScrollPane scroll = new JScrollPane(friendList);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.setPreferredSize(new Dimension(220, 142));
		scroll.getVerticalScrollBar().setUnitIncrement(12);

		JLabel empty = new JLabel("No friends available", SwingConstants.CENTER);
		empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		empty.setFont(FontManager.getRunescapeFont());
		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emptyPanel.add(empty, BorderLayout.CENTER);
		emptyPanel.setPreferredSize(new Dimension(220, 72));

		friendCards.setBackground(ColorScheme.DARK_GRAY_COLOR);
		friendCards.setAlignmentX(LEFT_ALIGNMENT);
		friendCards.add(scroll, FRIENDS);
		friendCards.add(emptyPanel, EMPTY);
		friendCards.setMaximumSize(new Dimension(Integer.MAX_VALUE, scroll.getPreferredSize().height));
		return friendCards;
	}

	private JPanel buildDetails(SpriteManager spriteManager)
	{
		detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
		detailsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		detailsPanel.setAlignmentX(LEFT_ALIGNMENT);
		detailsPanel.add(buildSummary());
		detailsPanel.add(Box.createVerticalStrut(6));

		hiscoreStateLabel.setFont(FontManager.getRunescapeSmallFont());
		hiscoreStateLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		hiscoreStateLabel.setAlignmentX(LEFT_ALIGNMENT);
		detailsPanel.add(hiscoreStateLabel);
		detailsPanel.add(Box.createVerticalStrut(3));
		detailsPanel.add(buildHiscoreTabs(spriteManager));
		detailsPanel.setVisible(false);
		return detailsPanel;
	}

	private JPanel buildSummary()
	{
		JPanel summary = new JPanel();
		summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
		summary.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		summary.setBorder(new EmptyBorder(8, 9, 8, 9));
		summary.setAlignmentX(LEFT_ALIGNMENT);

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		usernameLabel.setFont(FontManager.getRunescapeBoldFont());
		usernameLabel.setForeground(Color.WHITE);
		worldLabel.setFont(FontManager.getRunescapeFont());
		worldLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		titleRow.add(usernameLabel, BorderLayout.CENTER);
		titleRow.add(worldLabel, BorderLayout.EAST);

		activityLabel.setFont(FontManager.getRunescapeSmallFont());
		locationLabel.setFont(FontManager.getRunescapeSmallFont());
		activityLabel.setAlignmentX(LEFT_ALIGNMENT);
		locationLabel.setAlignmentX(LEFT_ALIGNMENT);
		activityLabel.setBorder(new EmptyBorder(5, 0, 0, 0));
		locationLabel.setBorder(new EmptyBorder(3, 0, 0, 0));
		summary.add(titleRow);
		summary.add(activityLabel);
		summary.add(locationLabel);
		summary.setMaximumSize(new Dimension(Integer.MAX_VALUE, summary.getPreferredSize().height));
		return summary;
	}

	private JTabbedPane buildHiscoreTabs(SpriteManager spriteManager)
	{
		JTable statsTable = buildTable(statsModel, spriteManager);
		JTable highscoresTable = buildTable(highscoresModel, spriteManager);

		JPanel stats = new JPanel(new BorderLayout());
		stats.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsSummaryLabel.setBorder(new EmptyBorder(6, 7, 6, 7));
		statsSummaryLabel.setFont(FontManager.getRunescapeBoldFont());
		statsSummaryLabel.setForeground(ColorScheme.BRAND_ORANGE);
		stats.add(statsSummaryLabel, BorderLayout.NORTH);
		stats.add(tableScroll(statsTable), BorderLayout.CENTER);

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Stats", stats);
		tabs.addTab("Highscores", tableScroll(highscoresTable));
		tabs.setPreferredSize(new Dimension(220, 430));
		tabs.setMinimumSize(new Dimension(180, 300));
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		tabs.setAlignmentX(LEFT_ALIGNMENT);
		return tabs;
	}

	private JTable buildTable(HiscoreTableModel model, SpriteManager spriteManager)
	{
		JTable table = new JTable(model);
		table.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		table.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		table.setSelectionBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		table.setSelectionForeground(Color.WHITE);
		table.setGridColor(ColorScheme.DARK_GRAY_COLOR);
		table.setShowVerticalLines(false);
		table.setRowHeight(24);
		table.setAutoCreateRowSorter(true);
		table.getTableHeader().setReorderingAllowed(false);
		TableColumn nameColumn = table.getColumnModel().getColumn(0);
		TableColumn valueColumn = table.getColumnModel().getColumn(1);
		TableColumn rankColumn = table.getColumnModel().getColumn(2);
		nameColumn.setPreferredWidth(108);
		nameColumn.setMinWidth(80);
		valueColumn.setPreferredWidth(48);
		valueColumn.setMinWidth(42);
		rankColumn.setPreferredWidth(56);
		rankColumn.setMinWidth(48);
		nameColumn.setCellRenderer(new HiscoreNameRenderer(model, spriteManager));

		DefaultTableCellRenderer numberRenderer = new DefaultTableCellRenderer();
		numberRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
		numberRenderer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		valueColumn.setCellRenderer(numberRenderer);
		rankColumn.setCellRenderer(numberRenderer);
		return table;
	}

	private JScrollPane tableScroll(JTable table)
	{
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(14);
		return scroll;
	}

	private void applyFriends(List<Buddy> buddies, long freshness)
	{
		freshnessMillis = freshness;
		String previous = selectedBuddyName;
		applyingFriends = true;
		friendModel.clear();
		for (Buddy buddy : buddies)
		{
			friendModel.addElement(buddy);
		}

		if (buddies.isEmpty())
		{
			selectedBuddyName = null;
			friendList.clearSelection();
			detailsPanel.setVisible(false);
			((CardLayout) friendCards.getLayout()).show(friendCards, EMPTY);
			applyingFriends = false;
			return;
		}

		((CardLayout) friendCards.getLayout()).show(friendCards, FRIENDS);
		int selectedIndex = 0;
		if (previous != null)
		{
			for (int index = 0; index < buddies.size(); index++)
			{
				if (buddies.get(index).getName().equalsIgnoreCase(previous))
				{
					selectedIndex = index;
					break;
				}
			}
		}
		friendList.setSelectedIndex(selectedIndex);
		friendList.ensureIndexIsVisible(selectedIndex);
		applyingFriends = false;
		selectBuddy(buddies.get(selectedIndex), !buddies.get(selectedIndex).getName().equalsIgnoreCase(previous));
	}

	private void selectBuddy(Buddy buddy, boolean requestHiscores)
	{
		if (buddy == null)
		{
			return;
		}
		selectedBuddyName = buddy.getName();
		detailsPanel.setVisible(true);
		applyBuddySummary(buddy);

		HiscoreResult cached = hiscoreCache.get(key(buddy.getName()));
		if (cached != null)
		{
			applyHiscores(cached, null);
		}
		else
		{
			statsModel.setResult(null);
			highscoresModel.setResult(null);
			statsSummaryLabel.setText("Combat --    Total --");
			hiscoreStateLabel.setText("Loading hiscores...");
			if (requestHiscores || !loadingHiscores.contains(key(buddy.getName())))
			{
				plugin.requestHiscores(buddy.getName());
			}
		}
	}

	private void applyBuddySummary(Buddy buddy)
	{
		usernameLabel.setText(buddy.getName());
		worldLabel.setText(buddy.isOnline() ? "W" + buddy.getWorld() : "Offline");
		worldLabel.setForeground(buddy.isOnline() ? ONLINE : ColorScheme.MEDIUM_GRAY_COLOR);

		long now = System.currentTimeMillis();
		String activity = buddy.isOnline()
			? buddy.hasFreshPresence(now, freshnessMillis) && buddy.getActivity() != null
				? displayActivity(buddy.getActivity())
				: "In game"
			: "Inactive";
		activityLabel.setText(pair("Activity", activity));

		BuddyLocation location = buddy.getLocation();
		String locationText;
		if (!buddy.isOnline())
		{
			locationText = "Offline";
		}
		else if (buddy.hasFreshLocation(now, freshnessMillis))
		{
			locationText = LocationResolver.resolve(location);
		}
		else
		{
			locationText = "Not shared";
		}
		locationLabel.setText(pair("Location", locationText));
		locationLabel.setToolTipText(buddy.hasFreshLocation(now, freshnessMillis)
			? "Tile " + location.getX() + ", " + location.getY() + ", " + location.getPlane()
			: null);
	}

	static String displayActivity(String activity)
	{
		return activity.startsWith("Training ")
			|| activity.startsWith("Fighting ")
			|| "In combat".equals(activity)
			? activity
			: "Training " + activity;
	}

	private void applyHiscores(HiscoreResult result, String error)
	{
		statsModel.setResult(result);
		highscoresModel.setResult(result);
		if (result == null)
		{
			statsSummaryLabel.setText("Combat --    Total --");
			hiscoreStateLabel.setText(error == null ? "No hiscore data" : error);
			return;
		}
		hiscoreStateLabel.setText(" ");
		statsSummaryLabel.setText("Combat " + combatLevel(result) + "    Total " + totalLevel(result));
	}

	private static String combatLevel(HiscoreResult result)
	{
		Skill attack = result.getSkill(HiscoreSkill.ATTACK);
		Skill strength = result.getSkill(HiscoreSkill.STRENGTH);
		Skill defence = result.getSkill(HiscoreSkill.DEFENCE);
		Skill hitpoints = result.getSkill(HiscoreSkill.HITPOINTS);
		Skill magic = result.getSkill(HiscoreSkill.MAGIC);
		Skill ranged = result.getSkill(HiscoreSkill.RANGED);
		Skill prayer = result.getSkill(HiscoreSkill.PRAYER);
		if (attack == null || strength == null || defence == null || hitpoints == null
			|| magic == null || ranged == null || prayer == null)
		{
			return "--";
		}
		return Integer.toString(Experience.getCombatLevel(
			attack.getLevel(), strength.getLevel(), defence.getLevel(), hitpoints.getLevel(),
			magic.getLevel(), ranged.getLevel(), prayer.getLevel()));
	}

	private static String totalLevel(HiscoreResult result)
	{
		Skill overall = result.getSkill(HiscoreSkill.OVERALL);
		return overall == null || overall.getLevel() < 0 ? "--" : Integer.toString(overall.getLevel());
	}

	private static String pair(String key, String value)
	{
		return "<html><span style='color:#a5a5a5'>" + escape(key) + ": </span>"
			+ "<span style='color:white'>" + escape(value) + "</span></html>";
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String key(String name)
	{
		return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
	}
}
