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
 * Live damage race for the current fight: your total in green, theirs in red — fed by the same
 * recorder the breakdowns use, so vengeance and recoil damage land on the right side. Movable and
 * resizable (the text scales with the box). Outside a fight it renders nothing, except while Alt
 * is held, when it shows its empty box so there is something to grab and place.
 */
class FightDamageOverlay extends Overlay
{
	private static final Color MINE = new Color(60, 220, 90);
	private static final Color THEIRS = new Color(235, 70, 60);
	private static final Dimension DEFAULT_SIZE = new Dimension(120, 30);

	private final Client client;
	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;

	FightDamageOverlay(Client client, PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setMovable(true);
		setResizable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showDamageOverlay())
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
			// A plain vector font with antialiasing: the game's bitmap font turns blocky scaled up.
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			final float fontSize = Math.max(10f, Math.min(size.height - 8f, size.width / 5f));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontSize)));
			final FontMetrics fm = g.getFontMetrics();
			final String mine = Integer.toString(hud.mine);
			final String sep = " · ";
			final String theirs = Integer.toString(hud.theirs);
			final int total = fm.stringWidth(mine) + fm.stringWidth(sep) + fm.stringWidth(theirs);
			int x = Math.max(2, (size.width - total) / 2);
			final int y = (size.height + fm.getAscent() - fm.getDescent()) / 2;
			x = drawWithShadow(g, mine, x, y, MINE);
			x = drawWithShadow(g, sep, x, y, Color.WHITE);
			drawWithShadow(g, theirs, x, y, THEIRS);
		}
		else
		{
			// Placing mode with no fight: name the box so it's clear what will show here.
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
			final FontMetrics fm = g.getFontMetrics();
			final String label = "Damage dealt";
			final int x = Math.max(2, (size.width - fm.stringWidth(label)) / 2);
			final int y = (size.height + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(Color.LIGHT_GRAY);
			g.drawString(label, x, y);
		}
		return size;
	}

	private static int drawWithShadow(Graphics2D g, String s, int x, int y, Color color)
	{
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(color);
		g.drawString(s, x, y);
		return x + g.getFontMetrics().stringWidth(s);
	}
}
