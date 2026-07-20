package com.buddies.ui;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;

final class HiscoreNameRenderer extends DefaultTableCellRenderer
{
	private final HiscoreTableModel model;
	private final SpriteManager spriteManager;
	private final Map<Integer, ImageIcon> icons = new HashMap<>();
	private final Set<Integer> pending = new HashSet<>();

	HiscoreNameRenderer(HiscoreTableModel model, SpriteManager spriteManager)
	{
		this.model = model;
		this.spriteManager = spriteManager;
		setOpaque(true);
	}

	@Override
	public Component getTableCellRendererComponent(
		JTable table,
		Object value,
		boolean selected,
		boolean focused,
		int row,
		int column)
	{
		JLabel label = (JLabel) super.getTableCellRendererComponent(
			table, value, selected, focused, row, column);
		HiscoreSkill skill = model.getSkill(table.convertRowIndexToModel(row));
		label.setToolTipText(skill.getName());
		label.setIconTextGap(5);
		label.setBackground(selected ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);

		int spriteId = skill.getSpriteId();
		label.setIcon(icons.get(spriteId));
		if (spriteId >= 0 && !icons.containsKey(spriteId) && pending.add(spriteId))
		{
			spriteManager.getSpriteAsync(spriteId, 0, sprite -> cacheSprite(table, spriteId, sprite));
		}
		return label;
	}

	private void cacheSprite(JTable table, int spriteId, BufferedImage sprite)
	{
		SwingUtilities.invokeLater(() ->
		{
			pending.remove(spriteId);
			if (sprite != null)
			{
				BufferedImage icon = ImageUtil.resizeImage(ImageUtil.resizeCanvas(sprite, 25, 25), 18, 18);
				icons.put(spriteId, new ImageIcon(icon));
				table.repaint();
			}
		});
	}
}

