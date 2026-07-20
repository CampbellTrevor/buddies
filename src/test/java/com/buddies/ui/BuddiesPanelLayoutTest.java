package com.buddies.ui;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuddiesPanelLayoutTest
{
	@Test
	public void activityLabelsSupportNewAndLegacyPayloads()
	{
		assertEquals("Fighting Zulrah", BuddiesPanel.displayActivity("Fighting Zulrah"));
		assertEquals("Training Sailing", BuddiesPanel.displayActivity("Training Sailing"));
		assertEquals("Training Fishing", BuddiesPanel.displayActivity("Fishing"));
	}

	@Test
	public void hiscoreTabsFillTheDetailsWidth() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			BuddiesPanel panel = new BuddiesPanel(null, null);
			JPanel content = (JPanel) panel.getComponent(0);
			JPanel details = (JPanel) content.getComponent(4);
			JTabbedPane tabs = (JTabbedPane) details.getComponent(4);

			details.setVisible(true);
			panel.setSize(242, 900);
			layoutTree(panel);

			assertEquals(0, tabs.getX());
			assertEquals(details.getWidth(), tabs.getWidth());
			assertEquals(details.getHeight(), tabs.getY() + tabs.getHeight());
			assertTrue(tabs.getHeight() > 430);
		});
	}

	private static void layoutTree(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layoutTree((Container) child);
			}
		}
	}
}
