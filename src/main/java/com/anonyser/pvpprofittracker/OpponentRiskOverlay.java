package com.anonyser.pvpprofittracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import static net.runelite.api.MenuAction.RUNELITE_OVERLAY;

/**
 * The opponent-risk overlay — its own overlay, so it is moved, and toggled, independently of the
 * main PvP Profit overlay. Shows the focused opponent's estimated risk, smite value, protection
 * assumptions and (once known) the hit-chance/max-hit numbers, all clearly labelled as estimates.
 */
class OpponentRiskOverlay extends OverlayPanel
{
	private static final Color SKULLED_COLOR = new Color(216, 60, 62);
	private static final Color UNSKULLED_COLOR = new Color(0, 200, 83);
	private static final Color NOTE_COLOR = new Color(160, 160, 160);

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;
	private final OpponentTracker tracker;

	OpponentRiskOverlay(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config, OpponentTracker tracker)
	{
		this.plugin = plugin;
		this.config = config;
		this.tracker = tracker;
		setPosition(OverlayPosition.TOP_RIGHT);
		addMenuEntry(RUNELITE_OVERLAY, "Clear", "Opponent risk", e -> tracker.clear());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.opponentRisk() || !config.showOpponentOverlay())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(200, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text("Opponent Risk").build());

		final OpponentTracker.Snapshot s = tracker.snapshot();
		if (s == null)
		{
			note("Right-click a player and");
			note("choose Risk to track them.");
			return super.render(graphics);
		}

		line("Opponent", s.visible ? s.name : s.name + " *", null);
		if (!s.visible)
		{
			note("* out of sight — last seen state");
		}

		if (s.tier != null)
		{
			line("BH tier", s.tier + (s.skulled ? " (skulled)" : ""),
				s.skulled ? SKULLED_COLOR : UNSKULLED_COLOR);
		}
		else
		{
			line("Status", s.skulled ? "Skulled" : "Unskulled",
				s.skulled ? SKULLED_COLOR : UNSKULLED_COLOR);
		}

		line("Risk (est)", plugin.fmt(s.riskGp), null);
		line("Smite value (est)", plugin.fmt(s.smiteGp), null);
		line("Protected", "top " + s.keptAssumed + " (assumed)", null);

		if (s.skulled)
		{
			note("Assuming their best item is");
			note("protected (skulled + Protect Item).");
		}
		else
		{
			note("Assuming top " + s.keptAssumed + " items protected:");
			note("appears unskulled with");
			note("Protect Item active.");
		}
		if (s.tierFloor > s.totalSeenGp)
		{
			note("Tier icon implies more risk");
			note("than their visible gear shows.");
		}

		return super.render(graphics);
	}

	private void line(String left, String right, Color rightColor)
	{
		final LineComponent.LineComponentBuilder b = LineComponent.builder()
			.left(left)
			.right(right);
		if (rightColor != null)
		{
			b.rightColor(rightColor);
		}
		panelComponent.getChildren().add(b.build());
	}

	private void note(String text)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(text)
			.leftColor(NOTE_COLOR)
			.build());
	}
}
