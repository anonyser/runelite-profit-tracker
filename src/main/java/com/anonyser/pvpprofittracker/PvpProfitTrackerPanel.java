package com.anonyser.pvpprofittracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/** Side panel showing the live values and each tracking scope, with per-scope reset buttons. */
class PvpProfitTrackerPanel extends PluginPanel
{
	private final PvpProfitTrackerPlugin plugin;
	private final JPanel body = new JPanel();

	PvpProfitTrackerPanel(PvpProfitTrackerPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.NORTH);
		rebuild();
	}

	/** Refresh from the EDT (safe to call from game-thread event handlers). */
	void update()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		body.removeAll();
		body.add(liveBlock());
		body.add(gap());
		body.add(scopeBlock("Session (" + plugin.sessionDuration() + ")", plugin.getSession(), plugin::resetSession, "Reset session"));
		body.add(gap());
		body.add(scopeBlock("Since enabled (" + plugin.enabledSince() + ")", plugin.getSinceEnabled(), null, null));
		body.add(gap());
		body.add(scopeBlock("Tracked", plugin.getTracked(), plugin::resetTracked, "Reset tracked"));
		body.add(gap());
		body.add(scopeBlock("Overall K/D", plugin.getOverall(), null, null));
		body.revalidate();
		body.repaint();
	}

	private JPanel liveBlock()
	{
		JPanel p = titled("Now");
		p.add(row("Net worth", PvpProfitTrackerPlugin.gp(plugin.getNetWorthGp()), null));
		p.add(row("Risk if you die", PvpProfitTrackerPlugin.gp(plugin.getRiskGp()), null));
		return p;
	}

	private JPanel scopeBlock(String title, Stats s, Runnable reset, String resetLabel)
	{
		JPanel p = titled(title);
		final long profit = s.profit();
		final Color profitColor = profit >= 0 ? new Color(0, 200, 83) : new Color(216, 60, 62);
		p.add(row("Profit", PvpProfitTrackerPlugin.gp(profit), profitColor));
		p.add(row("Kills / Deaths", s.kills + " / " + s.deaths, null));
		p.add(row("K/D", String.format("%.2f", s.kd()), null));
		p.add(row("Gained", PvpProfitTrackerPlugin.gp(s.gainedGp), null));
		p.add(row("Lost to deaths", PvpProfitTrackerPlugin.gp(s.lostToDeathGp), null));
		p.add(row("Consumables", PvpProfitTrackerPlugin.gp(s.consumedGp), null));
		if (reset != null)
		{
			JButton b = new JButton(resetLabel);
			b.addActionListener(e -> reset.run());
			b.setAlignmentX(Component.LEFT_ALIGNMENT);
			p.add(b);
		}
		return p;
	}

	private JPanel titled(String title)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel t = new JLabel(title);
		t.setForeground(Color.WHITE);
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(t);
		return p;
	}

	private JLabel row(String left, String right, Color rightColor)
	{
		JLabel l = new JLabel(left + ":  " + right);
		l.setForeground(rightColor != null ? rightColor : ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private Component gap()
	{
		JPanel g = new JPanel();
		g.setPreferredSize(new java.awt.Dimension(0, 6));
		g.setOpaque(false);
		return g;
	}
}
