package com.anonyser.pvpprofittracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * On-screen overlay. Lines are grouped in a fixed order — K/D (actual, baseline, session), profit
 * (baseline, session), risk, net worth — with each line toggleable in the config.
 */
class PvpProfitTrackerOverlay extends OverlayPanel
{
	private static final Color FLASH_COLOR = new Color(255, 200, 60);
	private static final Color PROTECT_ON_COLOR = new Color(0, 200, 83);
	private static final Color PROTECT_OFF_COLOR = new Color(216, 60, 62);

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;

	@Inject
	PvpProfitTrackerOverlay(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(185, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text("PvP Profit").build());

		// Crate reward flash — mirrored here because mid-fight nobody is watching the side panel.
		if (plugin.crateFlashGp() > 0)
		{
			addLine("Crate reward", plugin.fmt(plugin.crateFlashGp())
				+ " (" + plugin.crateFlashSecondsLeft() + "s)", FLASH_COLOR);
		}

		if (config.showActualKd())
		{
			final Stats a = plugin.getActual();
			addLine("K/D (actual)",
				a.kills + a.deaths > 0 ? PvpProfitTrackerPlugin.kdText(a) : "—", null);
		}
		if (config.showBaselineKd())
		{
			addLine("K/D (baseline)", PvpProfitTrackerPlugin.kdText(plugin.getBaseline()), null);
		}
		if (config.showSessionKd())
		{
			addLine("K/D (session)", PvpProfitTrackerPlugin.kdText(plugin.getSession()), null);
		}
		if (config.showBaselineProfit())
		{
			addProfitLine("Profit (baseline)", plugin.getBaseline().profit());
		}
		if (config.showSessionProfit())
		{
			addProfitLine("Profit (session)", plugin.getSession().profit());
		}
		if (config.showRisk())
		{
			String risk = plugin.fmt(plugin.getRiskGp());
			Color riskColor = null;
			if (config.showProtectItem())
			{
				// Protect Item at a glance: neutral outside the Wilderness, green/red inside —
				// where walking in with it off is the mistake this exists to catch.
				final boolean on = plugin.protectItemOn();
				risk += on ? " (On)" : " (Off)";
				if (plugin.inWilderness())
				{
					riskColor = on ? PROTECT_ON_COLOR : PROTECT_OFF_COLOR;
				}
			}
			addLine("Risk", risk, riskColor);
		}
		if (config.showNetWorth())
		{
			addLine("Net worth", plugin.netWorthDisplay(), null);
		}
		if (config.showBaselineCrates())
		{
			addLine("Crates (baseline)", Long.toString(plugin.getBaseline().crates), null);
		}
		if (config.showSessionCrates())
		{
			addLine("Crates (session)", Long.toString(plugin.getSession().crates), null);
		}
		if (config.showCurrentPoints())
		{
			addLine("Points (current)", plugin.currentBhPointsDisplay(), null);
		}
		if (config.showBaselinePoints())
		{
			addLine("Points (baseline)", Long.toString(plugin.getBaseline().points), null);
		}
		if (config.showSessionPoints())
		{
			addLine("Points (session)", Long.toString(plugin.getSession().points), null);
		}

		return super.render(graphics);
	}

	private void addLine(String left, String right, Color rightColor)
	{
		// Only set the colour when one is given — passing null overrides the builder's white default.
		final LineComponent.LineComponentBuilder b = LineComponent.builder()
			.left(left)
			.right(right);
		if (rightColor != null)
		{
			b.rightColor(rightColor);
		}
		panelComponent.getChildren().add(b.build());
	}

	private void addProfitLine(String left, long profit)
	{
		addLine(left, plugin.fmt(profit), profit >= 0 ? config.profitColor() : config.lossColor());
	}
}
