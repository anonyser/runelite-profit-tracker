package com.anonyser.pvpprofittracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class PvpProfitTrackerOverlay extends OverlayPanel
{
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

		final Stats s = plugin.getSession();
		final long profit = s.profit();
		final Color profitColor = profit >= 0 ? config.profitColor() : config.lossColor();

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(165, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text("PvP Profit").build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Profit")
			.right(PvpProfitTrackerPlugin.gp(profit))
			.rightColor(profitColor)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("K/D")
			.right(s.kills + "/" + s.deaths + "  (" + String.format("%.2f", s.kd()) + ")")
			.build());

		if (config.showRisk())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Risk")
				.right(PvpProfitTrackerPlugin.gp(plugin.getRiskGp()))
				.build());
		}

		if (config.showNetWorth())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Net worth")
				.right(PvpProfitTrackerPlugin.gp(plugin.getNetWorthGp()))
				.build());
		}

		return super.render(graphics);
	}
}
