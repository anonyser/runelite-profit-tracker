package com.anonyser.pvpprofittracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Pops up when you and a focused opponent die within a few ticks of each other (a "double death").
 * It stays on screen until you say how much you looted back: right-click gives "Enter recovered"
 * (opens a number entry that adds to session + baseline profit) and "Dismiss" (nothing recovered).
 * Movable, so it can be parked out of the way of the fight.
 */
class DoubleDeathOverlay extends OverlayPanel
{
	static final String ENTER = "Enter recovered";
	static final String DISMISS = "Dismiss";
	static final String TARGET = "Double death";

	private static final Color ALERT = new Color(255, 80, 80);
	private static final Color ALERT_DIM = new Color(150, 40, 40);

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;

	DoubleDeathOverlay(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setMovable(true);
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, ENTER, TARGET));
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, DISMISS, TARGET));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final String name = plugin.pendingDoubleDeathName();
		if (name == null || !config.showDoubleDeath())
		{
			return null;
		}
		// Flash the title so it is hard to miss mid-fight.
		final boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(180, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("DOUBLE DEATH")
			.color(on ? ALERT : ALERT_DIM)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("vs")
			.right(name)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("looted back?")
			.right("→ side panel")
			.build());
		return super.render(graphics);
	}
}
