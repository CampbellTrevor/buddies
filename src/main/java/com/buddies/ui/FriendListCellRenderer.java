package com.buddies.ui;

import com.buddies.model.Buddy;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class FriendListCellRenderer extends JPanel implements ListCellRenderer<Buddy>
{
	private static final Color ONLINE = new Color(46, 204, 113);
	private static final Color OFFLINE = new Color(105, 105, 105);

	private final JLabel nameLabel = new JLabel();
	private final JLabel worldLabel = new JLabel();
	private boolean online;

	FriendListCellRenderer()
	{
		setLayout(new BorderLayout(6, 0));
		setBorder(new EmptyBorder(5, 22, 5, 7));
		setPreferredSize(new Dimension(210, 34));

		nameLabel.setFont(FontManager.getRunescapeFont());
		worldLabel.setFont(FontManager.getRunescapeSmallFont());
		worldLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		add(nameLabel, BorderLayout.CENTER);
		add(worldLabel, BorderLayout.EAST);
	}

	@Override
	public Component getListCellRendererComponent(
		JList<? extends Buddy> list,
		Buddy buddy,
		int index,
		boolean selected,
		boolean focused)
	{
		online = buddy.isOnline();
		nameLabel.setText(buddy.getName());
		worldLabel.setText(online ? "W" + buddy.getWorld() : "Offline");

		Color background = selected
			? ColorScheme.DARKER_GRAY_HOVER_COLOR
			: ColorScheme.DARKER_GRAY_COLOR;
		Color foreground = selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR;
		setBackground(background);
		nameLabel.setForeground(foreground);
		worldLabel.setForeground(online ? ONLINE : OFFLINE);
		return this;
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		super.paintComponent(graphics);
		graphics.setColor(online ? ONLINE : OFFLINE);
		graphics.fillOval(8, Math.max(0, (getHeight() - 8) / 2), 8, 8);
	}
}

