package com.anonyser.pvpprofittracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * The fight opponent's current hitpoints, live as the health bar moves: green while they're above
 * half, red below, white when unknown. Shows the number, the percent, or both (config). Movable
 * and resizable; outside a fight it renders nothing except while Alt is held, when the empty box
 * shows so it can be placed.
 */
class OpponentHpOverlay extends Overlay
{
	private static final Color HIGH = new Color(60, 220, 90);
	private static final Color LOW = new Color(235, 70, 60);
	private static final Dimension DEFAULT_SIZE = new Dimension(110, 30);

	private final Client client;
	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;

	OpponentHpOverlay(Client client, PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setMovable(true);
		setResizable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showOppHpOverlay())
		{
			return null;
		}
		final PvpProfitTrackerPlugin.FightHud hud = plugin.fightHud();
		final boolean placing = client.isKeyPressed(KeyCode.KC_ALT);
		if (hud == null && !placing)
		{
			return null;
		}
		final Dimension size = getPreferredSize() != null ? getPreferredSize() : DEFAULT_SIZE;
		g.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		g.fillRect(0, 0, size.width, size.height);
		if (hud != null)
		{
			final String text = hpText(hud);
			final Color color = hud.hpPct < 0 ? Color.WHITE : hud.hpPct >= 50 ? HIGH : LOW;
			// A plain vector font with antialiasing: the game's bitmap font turns blocky scaled up.
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			final float fontSize = Math.max(10f, Math.min(size.height - 8f, size.width / 5f));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontSize)));
			final FontMetrics fm = g.getFontMetrics();
			final int x = Math.max(2, (size.width - fm.stringWidth(text)) / 2);
			final int y = (size.height + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(Color.BLACK);
			g.drawString(text, x + 1, y + 1);
			g.setColor(color);
			g.drawString(text, x, y);
		}
		else
		{
			// Placing mode with no fight: name the box so it's clear what will show here.
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
			final FontMetrics fm = g.getFontMetrics();
			final String label = "Opponent HP";
			final int x = Math.max(2, (size.width - fm.stringWidth(label)) / 2);
			final int y = (size.height + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(Color.LIGHT_GRAY);
			g.drawString(label, x, y);
		}
		return size;
	}

	/** The number needs their hiscore Hitpoints level; the percent only needs the health bar. */
	private String hpText(PvpProfitTrackerPlugin.FightHud hud)
	{
		final String hp = hud.hpVal >= 0 ? Integer.toString(hud.hpVal) : "?";
		final String pct = hud.hpPct >= 0 ? hud.hpPct + "%" : "?";
		switch (config.oppHpStyle())
		{
			case HITPOINTS:
				return hp;
			case PERCENT:
				return pct;
			case BOTH:
			default:
				return hp + " (" + pct + ")";
		}
	}
}
