package com.buddies.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class BuddiesIcon
{
	private BuddiesIcon()
	{
	}

	public static BufferedImage create()
	{
		return create(18);
	}

	public static BufferedImage create(int size)
	{
		if (size <= 0)
		{
			throw new IllegalArgumentException("Icon size must be positive");
		}
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.scale(size / 18.0, size / 18.0);
			graphics.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

			graphics.setColor(new Color(74, 184, 204));
			graphics.fillOval(2, 2, 6, 6);
			graphics.fillArc(-1, 8, 12, 10, 0, 180);

			graphics.setColor(new Color(220, 138, 0));
			graphics.fillOval(10, 2, 6, 6);
			graphics.fillArc(7, 8, 12, 10, 0, 180);

			graphics.setColor(new Color(225, 225, 225));
			graphics.drawOval(2, 2, 6, 6);
			graphics.drawOval(10, 2, 6, 6);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
