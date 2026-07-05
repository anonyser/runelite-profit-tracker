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
 * Side panel: the tracked values grouped in display order (K/D, profit, risk, opponent, net worth,
 * crates, points), baseline reset buttons, and the Edgeville K/D tip. Section titles carry a
 * tooltip explaining the Actual / Session / Baseline modes.
 *
 * Every component is PERSISTENT: refresh passes set label texts and row visibility in place, and
 * nothing is ever torn down and re-added. The original build-from-scratch version visibly
 * jittered whenever anything updated — and mid-fight (eating, gear swaps, opponent changes) that
 * was several times a second. Text updates on existing labels don't flicker.
 */
class PvpProfitTrackerPanel extends PluginPanel
{
	private static final String MODES_TIP = "<html><b>Actual</b> — your true in-game value.<br>"
		+ "<b>Session</b> — this session only, resets on restart.<br>"
		+ "<b>Baseline</b> — long-term tally, keeps saving until you reset it.</html>";
	private static final Color FLASH_COLOR = new Color(255, 200, 60);

	// Refresh bursts are coalesced: at most one per interval, with a trailing refresh so the last
	// state of a burst always lands.
	private static final int REBUILD_COALESCE_MS = 300;

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;
	private final JPanel body = new JPanel();
	private final Timer flashTick = new Timer(1000, e -> refresh()); // drives the crate-value countdown
	private final Timer refreshSoon = new Timer(REBUILD_COALESCE_MS, e -> refresh());
	private final Timer opponentSoon;
	private final OpponentSection opponentSection;
	private long lastRefreshAt;
	private long lastOpponentAt;

	// K/D
	private final JPanel kdHolder;
	private final JLabel kdActual = rowLabel("Read from the game's own stats: the Edgeville Kill "
		+ "Death Ratio window (world PvP) or the Bounty Hunter HUD, which refreshes it as you get kills.");
	private final JLabel kdActualNote = noteLabel();
	private final JLabel kdBaseline = rowLabel(MODES_TIP);
	private final JLabel kdSession = rowLabel(MODES_TIP);
	// Profit
	private final JPanel profitHolder;
	private final JLabel profitBaseline = rowLabel(null);
	private final JLabel profitSession = rowLabel(null);
	// Risk
	private final JPanel riskHolder;
	private final JLabel riskRow = rowLabel("Lost on death and applied to profit as a loss.");
	// Opponent
	private final JPanel opponentHolder;
	// Net worth
	private final JPanel netWorthHolder;
	private final JLabel netWorthRow = rowLabel("Informational only — never counts toward profit.");
	private final JLabel barrelRow = rowLabel("Potions stored in your chugging barrel — counted in "
		+ "net worth; each chug books one dose of each as a consumable.");
	// Crates
	private final JPanel cratesHolder;
	private final JLabel crateFlashRow = rowLabel("Added to profit — this message disappears in a few seconds.");
	private final JLabel cratesBaseline = rowLabel(MODES_TIP);
	private final JLabel cratesSession = rowLabel(MODES_TIP);
	// Points
	private final JPanel pointsHolder;
	private final JLabel pointsCurrent = rowLabel("Your actual points balance from the game — goes "
		+ "down when you spend.");
	private final JLabel pointsBaseline = rowLabel(MODES_TIP);
	private final JLabel pointsSession = rowLabel(MODES_TIP);

	PvpProfitTrackerPanel(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		flashTick.setRepeats(false);
		refreshSoon.setRepeats(false);
		setLayout(new BorderLayout());
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.NORTH);

		kdActualNote.setText("<html>From the game's stats — updates per kill at Bounty Hunter.</html>");
		kdHolder = holder(section("Kill / Death", kdActual, kdActualNote, kdBaseline, kdSession));
		profitHolder = holder(section("Profit", profitBaseline, profitSession));
		riskHolder = holder(section("Risk", riskRow));

		opponentSection = new OpponentSection();
		opponentSoon = new Timer(REBUILD_COALESCE_MS, e -> refreshOpponent());
		opponentSoon.setRepeats(false);
		opponentHolder = holder(opponentSection);

		netWorthHolder = holder(section("Net worth", netWorthRow, barrelRow));
		cratesHolder = holder(section("Bounty crates", crateFlashRow, cratesBaseline, cratesSession));
		pointsHolder = holder(section("Bounty Hunter points", pointsCurrent, pointsBaseline, pointsSession));

		final JPanel resets = section("Resets",
			button("Reset session", plugin::resetSession),
			button("Reset baseline K/D", plugin::resetBaselineKd),
			button("Reset baseline profit", plugin::resetBaselineProfit),
			button("Reset baseline crates", plugin::resetBaselineCrates),
			button("Reset baseline points", plugin::resetBaselinePoints));

		final JLabel tip = new JLabel("<html>Actual K/D loads from the Kill Death Ratio window at "
			+ "Edgeville, or updates automatically on Bounty Hunter worlds.</html>");
		tip.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		tip.setBorder(new EmptyBorder(2, 8, 6, 8));
		tip.setAlignmentX(Component.LEFT_ALIGNMENT);

		body.add(kdHolder);
		body.add(profitHolder);
		body.add(riskHolder);
		body.add(opponentHolder);
		body.add(netWorthHolder);
		body.add(cratesHolder);
		body.add(pointsHolder);
		body.add(holder(resets));
		body.add(tip);

		refresh();
	}

	/** Refresh from the EDT (safe to call from game-thread event handlers). */
	void update()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (System.currentTimeMillis() - lastRefreshAt >= REBUILD_COALESCE_MS)
			{
				refresh();
			}
			else if (!refreshSoon.isRunning())
			{
				refreshSoon.start();
			}
		});
	}

	/** Refresh only the opponent section — the highest-frequency path during fights. */
	void updateOpponent()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (System.currentTimeMillis() - lastOpponentAt >= REBUILD_COALESCE_MS)
			{
				refreshOpponent();
			}
			else if (!opponentSoon.isRunning())
			{
				opponentSoon.start();
			}
		});
	}

	private void refreshOpponent()
	{
		lastOpponentAt = System.currentTimeMillis();
		opponentSection.refresh();
		opponentHolder.setVisible(opponentSection.isVisible());
	}

	/** Update every section's texts and visibility in place. EDT only. */
	private void refresh()
	{
		lastRefreshAt = System.currentTimeMillis();
		final Stats session = plugin.getSession();
		final Stats baseline = plugin.getBaseline();
		final Stats actual = plugin.getActual();

		kdActual.setVisible(config.showActualKd());
		kdActualNote.setVisible(config.showActualKd());
		kdActual.setText("Actual:  " + (actual.kills + actual.deaths > 0
			? PvpProfitTrackerPlugin.kdText(actual) : "— (visit Edgeville)"));
		kdBaseline.setVisible(config.showBaselineKd());
		kdBaseline.setText("Baseline:  " + PvpProfitTrackerPlugin.kdText(baseline));
		kdSession.setVisible(config.showSessionKd());
		kdSession.setText("Session (" + plugin.sessionDuration() + "):  "
			+ PvpProfitTrackerPlugin.kdText(session));
		kdHolder.setVisible(config.showActualKd() || config.showBaselineKd() || config.showSessionKd());

		profitBaseline.setVisible(config.showBaselineProfit());
		profitRow(profitBaseline, "Baseline", baseline);
		profitSession.setVisible(config.showSessionProfit());
		profitRow(profitSession, "Session", session);
		profitHolder.setVisible(config.showBaselineProfit() || config.showSessionProfit());

		riskRow.setText("If you die now:  " + plugin.fmt(plugin.getRiskGp()));
		riskHolder.setVisible(config.showRisk());

		refreshOpponent();

		netWorthRow.setText("Bank + carried:  " + plugin.netWorthDisplay());
		barrelRow.setVisible(plugin.getBarrelGp() > 0);
		barrelRow.setText("Incl. barrel:  " + plugin.fmt(plugin.getBarrelGp()));
		netWorthHolder.setVisible(config.showNetWorth());

		final boolean flashing = plugin.crateFlashGp() > 0;
		crateFlashRow.setVisible(flashing);
		if (flashing)
		{
			crateFlashRow.setText("Crate reward:  " + plugin.fmt(plugin.crateFlashGp())
				+ " (" + plugin.crateFlashSecondsLeft() + "s)");
			crateFlashRow.setForeground(FLASH_COLOR);
			flashTick.restart();
		}
		cratesBaseline.setVisible(config.showBaselineCrates());
		cratesBaseline.setText("Baseline:  " + baseline.crates);
		cratesSession.setVisible(config.showSessionCrates());
		cratesSession.setText("Session:  " + session.crates);
		cratesHolder.setVisible(flashing || config.showBaselineCrates() || config.showSessionCrates());

		pointsCurrent.setVisible(config.showCurrentPoints());
		pointsCurrent.setText("Current:  " + plugin.currentBhPointsDisplay());
		pointsBaseline.setVisible(config.showBaselinePoints());
		pointsBaseline.setText("Baseline:  " + baseline.points);
		pointsSession.setVisible(config.showSessionPoints());
		pointsSession.setText("Session:  " + session.points);
		pointsHolder.setVisible(config.showCurrentPoints() || config.showBaselinePoints()
			|| config.showSessionPoints());

		body.revalidate();
		body.repaint();
	}

	private void profitRow(JLabel label, String name, Stats s)
	{
		final long profit = s.profit();
		label.setText(name + ":  " + plugin.fmt(profit));
		label.setForeground(profit >= 0 ? config.profitColor() : config.lossColor());
		label.setToolTipText("<html>Loot keys: " + PvpProfitTrackerPlugin.gpFull(s.gainedGp)
			+ "<br>Crates: " + PvpProfitTrackerPlugin.gpFull(s.crateGp)
			+ "<br>Deaths: -" + PvpProfitTrackerPlugin.gpFull(s.lostToDeathGp)
			+ "<br>Consumables: -" + PvpProfitTrackerPlugin.gpFull(s.consumedGp) + "</html>");
	}

	/** A dark titled section holding the given persistent components. */
	private JPanel section(String title, Component... rows)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(new EmptyBorder(6, 8, 6, 8));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel t = new JLabel(title);
		t.setForeground(Color.WHITE);
		t.setToolTipText(MODES_TIP);
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(t);
		for (final Component row : rows)
		{
			p.add(row);
		}
		return p;
	}

	/** Wraps a section with its trailing gap so both show and hide together. */
	private JPanel holder(JPanel sectionPanel)
	{
		final JPanel h = new JPanel();
		h.setLayout(new BoxLayout(h, BoxLayout.Y_AXIS));
		h.setOpaque(false);
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		h.add(sectionPanel);
		final JPanel gap = new JPanel();
		gap.setPreferredSize(new java.awt.Dimension(0, 6));
		gap.setOpaque(false);
		h.add(gap);
		return h;
	}

	private JLabel rowLabel(String tooltip)
	{
		final JLabel l = new JLabel();
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		if (tooltip != null)
		{
			l.setToolTipText(tooltip);
		}
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JLabel noteLabel()
	{
		final JLabel l = new JLabel();
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

	/**
	 * The gear-inspect section as a PERSISTENT set of components: refresh() sets label texts and
	 * row visibility in place, and the icon grid repopulates only when the item ids actually
	 * change. Equipment Inspector parity per the Hub review of 1.1.0: currently worn gear with
	 * the GE price of each item, and (pending the reviewer's answer) their total.
	 */
	private final class OpponentSection extends JPanel
	{
		private final JLabel hint = noteLabel();
		private final JLabel nameRow = rowLabel("Cleared automatically after ~5 minutes out of sight.");
		private final JPanel wornGrid = newGrid();
		private final JLabel totalRow = rowLabel("Sum of the visible items' GE prices — the same "
			+ "total Equipment Inspector shows.");
		private final JButton clearBtn = button("Clear", plugin::clearOpponent);
		private int[] lastWornIds = {};

		OpponentSection()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(new EmptyBorder(6, 8, 6, 8));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			final JLabel t = new JLabel("Opponent gear");
			t.setForeground(Color.WHITE);
			t.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(t);
			hint.setText("<html>Right-click a player and choose <b>Inspect</b>, or get a "
				+ "Bounty Hunter target, to see their worn gear here.</html>");
			add(hint);
			add(nameRow);
			add(wornGrid);
			add(totalRow);
			add(clearBtn);
			refresh();
		}

		private JPanel newGrid()
		{
			// Five icons per row (a FlowLayout would report a one-row height in this BoxLayout
			// column and clip the wrap).
			final JPanel grid = new JPanel(new java.awt.GridLayout(0, 5, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			return grid;
		}

		/** Update in place from the current snapshot. EDT only. */
		void refresh()
		{
			final boolean enabled = config.opponentRisk();
			setVisible(enabled);
			if (!enabled)
			{
				return;
			}
			final OpponentTracker.Snapshot opp = plugin.opponentSnapshot();
			final boolean has = opp != null;
			hint.setVisible(!has);
			nameRow.setVisible(has);
			wornGrid.setVisible(has);
			totalRow.setVisible(has);
			clearBtn.setVisible(has);
			if (!has)
			{
				populateGrid(new int[0], null, null);
				lastWornIds = new int[]{};
				revalidate();
				repaint();
				return;
			}

			nameRow.setText(opp.name + (opp.visible ? "" : " (out of sight)"));
			if (!java.util.Arrays.equals(lastWornIds, opp.equippedIds))
			{
				lastWornIds = opp.equippedIds.clone();
				populateGrid(opp.equippedIds, opp.equippedNames, opp.equippedGe);
			}
			totalRow.setText("Total (GE):  " + plugin.fmt(opp.totalGe));
			revalidate();
			repaint();
		}

		private void populateGrid(int[] ids, String[] names, long[] prices)
		{
			wornGrid.removeAll();
			for (int i = 0; i < ids.length; i++)
			{
				if (ids[i] <= 0)
				{
					continue;
				}
				final JLabel icon = new JLabel();
				icon.setToolTipText("<html>" + (names[i] == null ? "?" : names[i]) + "<br>"
					+ plugin.fmt(prices[i]) + " (GE)</html>");
				plugin.itemIcon(ids[i]).addTo(icon);
				wornGrid.add(icon);
			}
		}
	}
}
