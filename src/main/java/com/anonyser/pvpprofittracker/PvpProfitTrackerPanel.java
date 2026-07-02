package com.anonyser.pvpprofittracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: the tracked values grouped in display order (K/D, profit, risk, net worth, crates,
 * points), baseline reset buttons, and the Edgeville K/D tip. Section titles carry a tooltip
 * explaining the Actual / Session / Baseline modes.
 */
class PvpProfitTrackerPanel extends PluginPanel
{
	private static final String MODES_TIP = "<html><b>Actual</b> — your true in-game value.<br>"
		+ "<b>Session</b> — this session only, resets on restart.<br>"
		+ "<b>Baseline</b> — long-term tally, keeps saving until you reset it.</html>";
	private static final Color FLASH_COLOR = new Color(255, 200, 60);

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;
	private final JPanel body = new JPanel();
	private final Timer flashTick = new Timer(1000, e -> rebuild()); // drives the crate-value countdown

	PvpProfitTrackerPanel(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		flashTick.setRepeats(false);
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
		final Stats session = plugin.getSession();
		final Stats baseline = plugin.getBaseline();
		final Stats actual = plugin.getActual();

		body.removeAll();

		final JPanel kd = titled("Kill / Death");
		if (config.showActualKd())
		{
			kd.add(row("Actual", actual.kills + actual.deaths > 0
				? PvpProfitTrackerPlugin.kdText(actual) : "— (visit Edgeville)", null,
				"The game's Kill Death Ratio window counts world PvP only, not Bounty Hunter."));
			kd.add(note("World PvP only — Bounty Hunter kills don't count here."));
		}
		if (config.showBaselineKd())
		{
			kd.add(row("Baseline", PvpProfitTrackerPlugin.kdText(baseline), null, MODES_TIP));
		}
		if (config.showSessionKd())
		{
			kd.add(row("Session (" + plugin.sessionDuration() + ")",
				PvpProfitTrackerPlugin.kdText(session), null, MODES_TIP));
		}
		addIfUsed(kd);

		final JPanel profit = titled("Profit");
		if (config.showBaselineProfit())
		{
			profit.add(profitRow("Baseline", baseline));
		}
		if (config.showSessionProfit())
		{
			profit.add(profitRow("Session", session));
		}
		addIfUsed(profit);

		if (config.showRisk())
		{
			final JPanel p = titled("Risk");
			p.add(row("If you die now", plugin.fmt(plugin.getRiskGp()), null,
				"Lost on death and applied to profit as a loss."));
			body.add(p);
			body.add(gap());
		}

		if (config.showNetWorth())
		{
			final JPanel p = titled("Net worth");
			p.add(row("Bank + carried", plugin.netWorthDisplay(), null,
				"Informational only — never counts toward profit."));
			body.add(p);
			body.add(gap());
		}

		final JPanel crates = titled("Bounty crates");
		if (plugin.crateFlashGp() > 0)
		{
			crates.add(row("Crate reward", plugin.fmt(plugin.crateFlashGp())
				+ " (" + plugin.crateFlashSecondsLeft() + "s)", FLASH_COLOR,
				"Added to profit — this message disappears in a few seconds."));
			flashTick.restart();
		}
		if (config.showBaselineCrates())
		{
			crates.add(row("Baseline", String.valueOf(baseline.crates), null, MODES_TIP));
		}
		if (config.showSessionCrates())
		{
			crates.add(row("Session", String.valueOf(session.crates), null, MODES_TIP));
		}
		addIfUsed(crates);

		final JPanel points = titled("Bounty Hunter points");
		if (config.showBaselinePoints())
		{
			points.add(row("Baseline", String.valueOf(baseline.points), null, MODES_TIP));
		}
		if (config.showSessionPoints())
		{
			points.add(row("Session", String.valueOf(session.points), null, MODES_TIP));
		}
		addIfUsed(points);

		final JPanel resets = titled("Resets");
		resets.add(button("Reset session", plugin::resetSession));
		resets.add(button("Reset baseline K/D", plugin::resetBaselineKd));
		resets.add(button("Reset baseline profit", plugin::resetBaselineProfit));
		resets.add(button("Reset baseline crates", plugin::resetBaselineCrates));
		resets.add(button("Reset baseline points", plugin::resetBaselinePoints));
		body.add(resets);
		body.add(gap());

		final JLabel tip = new JLabel("<html>To get the most accurate Kill Death Ratio, go to "
			+ "Edgeville and open the Kill Death Ratio window.</html>");
		tip.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		tip.setBorder(new EmptyBorder(2, 8, 6, 8));
		tip.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(tip);

		body.revalidate();
		body.repaint();
	}

	/** Add a section only if any rows were enabled for it (the title label is child 0). */
	private void addIfUsed(JPanel section)
	{
		if (section.getComponentCount() > 1)
		{
			body.add(section);
			body.add(gap());
		}
	}

	private JLabel profitRow(String label, Stats s)
	{
		final long profit = s.profit();
		final Color color = profit >= 0 ? config.profitColor() : config.lossColor();
		final String breakdown = "<html>Loot keys: " + PvpProfitTrackerPlugin.gpFull(s.gainedGp)
			+ "<br>Crates: " + PvpProfitTrackerPlugin.gpFull(s.crateGp)
			+ "<br>Deaths: -" + PvpProfitTrackerPlugin.gpFull(s.lostToDeathGp)
			+ "<br>Consumables: -" + PvpProfitTrackerPlugin.gpFull(s.consumedGp) + "</html>";
		return row(label, plugin.fmt(profit), color, breakdown);
	}

	private JPanel titled(String title)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		final JLabel t = new JLabel(title);
		t.setForeground(Color.WHITE);
		t.setToolTipText(MODES_TIP);
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(t);
		return p;
	}

	private JLabel row(String left, String right, Color rightColor, String tooltip)
	{
		final JLabel l = new JLabel(left + ":  " + right);
		l.setForeground(rightColor != null ? rightColor : ColorScheme.LIGHT_GRAY_COLOR);
		if (tooltip != null)
		{
			l.setToolTipText(tooltip);
		}
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A small, dimmed explanatory line under a row. */
	private JLabel note(String text)
	{
		final JLabel l = new JLabel("<html>" + text + "</html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 2f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JButton button(String label, Runnable action)
	{
		final JButton b = new JButton(label);
		b.addActionListener(e -> action.run());
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		return b;
	}

	private Component gap()
	{
		final JPanel g = new JPanel();
		g.setPreferredSize(new java.awt.Dimension(0, 6));
		g.setOpaque(false);
		return g;
	}
}
