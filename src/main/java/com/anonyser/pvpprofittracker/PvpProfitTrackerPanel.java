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

	// A rebuild tears everything down and re-adds it; mid-fight the triggers come several times
	// a second (own risk, opponent gear, visibility), which made the panel visibly jitter. Burst
	// updates are coalesced: rebuild at most once per interval, with a trailing rebuild so the
	// last state of a burst always lands.
	private static final int REBUILD_COALESCE_MS = 300;

	private final PvpProfitTrackerPlugin plugin;
	private final PvpProfitTrackerConfig config;
	private final JPanel body = new JPanel();
	private final Timer flashTick = new Timer(1000, e -> rebuild()); // drives the crate-value countdown
	private final Timer rebuildSoon = new Timer(REBUILD_COALESCE_MS, e -> rebuild());
	private final Timer opponentSoon;
	private final OpponentSection opponentSection;
	private long lastRebuildAt;
	private long lastOpponentAt;

	PvpProfitTrackerPanel(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		flashTick.setRepeats(false);
		rebuildSoon.setRepeats(false);
		opponentSection = new OpponentSection();
		opponentSoon = new Timer(REBUILD_COALESCE_MS, e -> refreshOpponent());
		opponentSoon.setRepeats(false);
		setLayout(new BorderLayout());
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.NORTH);
		rebuild();
	}

	/** Refresh from the EDT (safe to call from game-thread event handlers). */
	void update()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (System.currentTimeMillis() - lastRebuildAt >= REBUILD_COALESCE_MS)
			{
				rebuild();
			}
			else if (!rebuildSoon.isRunning())
			{
				rebuildSoon.start();
			}
		});
	}

	/**
	 * Refresh only the opponent section, in place — the high-frequency path during fights.
	 * Coalesced like full rebuilds, but it never tears components down, so no jitter.
	 */
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
	}

	private void rebuild()
	{
		lastRebuildAt = System.currentTimeMillis();
		final Stats session = plugin.getSession();
		final Stats baseline = plugin.getBaseline();
		final Stats actual = plugin.getActual();

		body.removeAll();

		final JPanel kd = titled("Kill / Death");
		if (config.showActualKd())
		{
			kd.add(row("Actual", actual.kills + actual.deaths > 0
				? PvpProfitTrackerPlugin.kdText(actual) : "— (visit Edgeville)", null,
				"Read from the game's own stats: the Edgeville Kill Death Ratio window (world PvP) "
					+ "or the Bounty Hunter HUD, which refreshes it as you get kills."));
			kd.add(note("From the game's stats — updates per kill at Bounty Hunter."));
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

		opponentSection.refresh();
		body.add(opponentSection);
		if (opponentSection.isVisible())
		{
			body.add(gap());
		}

		if (config.showNetWorth())
		{
			final JPanel p = titled("Net worth");
			p.add(row("Bank + carried", plugin.netWorthDisplay(), null,
				"Informational only — never counts toward profit."));
			if (plugin.getBarrelGp() > 0)
			{
				p.add(row("Incl. barrel", plugin.fmt(plugin.getBarrelGp()), null,
					"Potions stored in your chugging barrel — counted in net worth; "
						+ "each chug books one dose of each as a consumable."));
			}
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
		if (config.showCurrentPoints())
		{
			points.add(row("Current", plugin.currentBhPointsDisplay(), null,
				"Your actual points balance from the game — goes down when you spend."));
		}
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

		final JLabel tip = new JLabel("<html>Actual K/D loads from the Kill Death Ratio window at "
			+ "Edgeville, or updates automatically on Bounty Hunter worlds.</html>");
		tip.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		tip.setBorder(new EmptyBorder(2, 8, 6, 8));
		tip.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(tip);

		body.revalidate();
		body.repaint();
	}

	/** A hiscore level for display: dash until the lookup answers. */
	private static String lvl(int level)
	{
		return level > 0 ? Integer.toString(level) : "—";
	}

	/** A hiscore activity score for display: dash when unranked. */
	private static String kc(int score)
	{
		return score >= 0 ? Integer.toString(score) : "—";
	}

	/**
	 * The focused-opponent section as a PERSISTENT set of components: refresh() sets label texts
	 * and row visibility in place, and the icon grids repopulate only when the item ids actually
	 * change. The old build-it-from-scratch version made the whole panel visibly jitter during
	 * fights (in-game report) — text updates on existing labels don't.
	 */
	private final class OpponentSection extends JPanel
	{
		private final JLabel hint = noteLabel();
		private final JLabel nameRow = rowLabel("Cleared automatically after ~5 minutes out of sight.");
		private final JLabel tierRow = rowLabel(
			"Bounty Hunter risk-tier icon — the game's statement of their minimum total risk.");
		private final JLabel meleeStats = rowLabel("From the hiscores — dashes until the lookup answers.");
		private final JLabel otherStats = rowLabel("From the hiscores — dashes until the lookup answers.");
		private final JLabel prayerRow = rowLabel("Drives the defensive-prayer assumption in your hit chance.");
		private final JLabel bhKillsRow = rowLabel(
			"Bounty Hunter kills from the hiscores: as Target's hunter · as rogue.");
		private final JLabel colosseumRow = rowLabel(null);
		private final JLabel zukRow = rowLabel(null);
		private final JLabel solRow = rowLabel(null);
		private final JLabel riskRow = rowLabel("Visible + previously seen gear, minus assumed "
			+ "protected items; at least the BH tier minimum when a tier icon shows.");
		private final JLabel smiteRow = rowLabel("What losing Protect Item would additionally expose.");
		private final JPanel wornGrid = newGrid();
		private final JLabel seenHeader = noteLabel();
		private final JPanel seenGrid = newGrid();
		private final JLabel protectNote = noteLabel();
		private final JLabel hitRow = rowLabel("Estimate against their hiscore levels and visible gear.");
		private final JLabel maxHitRow = rowLabel("Your current setup: gear, boosts, prayers and "
			+ "combat style. With the special-attack bar lit this shows the spec's ceiling "
			+ "for known PvP spec weapons.");
		private final JLabel assumeNote = noteLabel();
		private final JButton clearBtn = button("Clear opponent", plugin::clearOpponent);
		private int[] lastWornIds = {};
		private int[] lastSeenIds = {};

		OpponentSection()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(new EmptyBorder(6, 8, 6, 8));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			final JLabel t = new JLabel("Opponent risk");
			t.setForeground(Color.WHITE);
			t.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(t);
			hint.setText("<html>Right-click a player and choose <b>Risk</b> to track "
				+ "their visible gear and estimated risk here.</html>");
			add(hint);
			add(nameRow);
			add(tierRow);
			add(meleeStats);
			add(otherStats);
			add(prayerRow);
			add(bhKillsRow);
			add(colosseumRow);
			add(zukRow);
			add(solRow);
			add(riskRow);
			add(smiteRow);
			add(wornGrid);
			add(seenHeader);
			add(seenGrid);
			add(protectNote);
			add(hitRow);
			add(maxHitRow);
			add(assumeNote);
			add(clearBtn);
			refresh();
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

		private JPanel newGrid()
		{
			// Five icons per row (a FlowLayout would report a one-row height in this BoxLayout
			// column and clip the wrap).
			final JPanel grid = new JPanel(new java.awt.GridLayout(0, 5, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			return grid;
		}

		/** Update in place from the current snapshot/estimate. EDT only. */
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
			tierRow.setVisible(has);
			meleeStats.setVisible(has);
			otherStats.setVisible(has);
			prayerRow.setVisible(has);
			riskRow.setVisible(has);
			smiteRow.setVisible(has);
			wornGrid.setVisible(has);
			protectNote.setVisible(has);
			clearBtn.setVisible(has);
			if (!has)
			{
				bhKillsRow.setVisible(false);
				colosseumRow.setVisible(false);
				zukRow.setVisible(false);
				solRow.setVisible(false);
				seenHeader.setVisible(false);
				seenGrid.setVisible(false);
				hitRow.setVisible(false);
				maxHitRow.setVisible(false);
				assumeNote.setVisible(false);
				populateGrid(wornGrid, new int[0], null, null, "");
				lastWornIds = new int[]{};
				lastSeenIds = new int[]{};
				revalidate();
				repaint();
				return;
			}

			nameRow.setText("Opponent:  " + opp.name + (opp.visible ? "" : " (out of sight)"));
			if (opp.tier != null)
			{
				tierRow.setText("BH tier:  " + opp.tier + (opp.skulled ? " (skulled)" : ""));
			}
			else
			{
				tierRow.setText("Status:  " + (opp.skulled ? "Skulled" : "Unskulled"));
			}
			tierRow.setForeground(opp.skulled ? config.lossColor() : config.profitColor());
			meleeStats.setText("Atk / Str / Def:  " + lvl(opp.attackLevel) + " / "
				+ lvl(opp.strengthLevel) + " / " + lvl(opp.defenceLevel));
			otherStats.setText("Rng / Mag / HP:  " + lvl(opp.rangedLevel) + " / "
				+ lvl(opp.magicLevel) + " / " + lvl(opp.hitpointsLevel));
			prayerRow.setText("Prayer:  " + lvl(opp.prayerLevel));
			bhKillsRow.setVisible(opp.bhTargetKills >= 0 || opp.bhRogueKills >= 0);
			bhKillsRow.setText("BH kills:  T " + kc(opp.bhTargetKills) + "  ·  R " + kc(opp.bhRogueKills));
			colosseumRow.setVisible(opp.colosseumGlory > 0);
			colosseumRow.setText("Colosseum glory:  " + opp.colosseumGlory);
			zukRow.setVisible(opp.zukKc > 0);
			zukRow.setText("TzKal-Zuk KC:  " + opp.zukKc);
			solRow.setVisible(opp.solHereditKc > 0);
			solRow.setText("Sol Heredit KC:  " + opp.solHereditKc);
			riskRow.setText("Risk (est):  " + plugin.fmt(opp.riskGp));
			smiteRow.setText("Smite value (est):  " + plugin.fmt(opp.smiteGp));

			if (!java.util.Arrays.equals(lastWornIds, opp.equippedIds))
			{
				lastWornIds = opp.equippedIds.clone();
				populateGrid(wornGrid, opp.equippedIds, opp.equippedNames, opp.equippedGp, "worn now");
			}
			final boolean seenAny = opp.seenOnlyIds.length > 0;
			seenHeader.setVisible(seenAny);
			seenHeader.setText("Seen earlier this fight:");
			seenGrid.setVisible(seenAny);
			if (!java.util.Arrays.equals(lastSeenIds, opp.seenOnlyIds))
			{
				lastSeenIds = opp.seenOnlyIds.clone();
				populateGrid(seenGrid, opp.seenOnlyIds, opp.seenOnlyNames, opp.seenOnlyGp,
					"seen earlier this fight");
			}
			protectNote.setText("<html>" + (opp.skulled
				? "Assuming their most valuable item is protected (skulled, Protect Item assumed active)."
				: "Assuming top " + opp.keptAssumed + " items are protected because opponent appears "
					+ "unskulled with Protect Item active.") + "</html>");

			final CombatCalc.Estimate est = plugin.combatEstimate();
			final boolean hasEst = est != null && est.style != CombatCalc.Style.OTHER;
			hitRow.setVisible(hasEst);
			maxHitRow.setVisible(hasEst);
			assumeNote.setVisible(hasEst);
			if (hasEst)
			{
				hitRow.setText("Hit chance (" + est.styleName + "):  "
					+ Math.round(est.hitChance * 100) + "%");
				maxHitRow.setText((est.specShown() ? "Max hit (spec):  " : "Max hit:  ")
					+ est.maxHitText());
				assumeNote.setText("<html>Assumes opponent is using best available defensive prayer "
					+ "and potion boosts" + (est.defenceAssumed
						? ", and 99 Defence until hiscores answer." : ".") + "</html>");
			}
			revalidate();
			repaint();
		}

		private void populateGrid(JPanel grid, int[] ids, String[] names, long[] values, String context)
		{
			grid.removeAll();
			for (int i = 0; i < ids.length; i++)
			{
				if (ids[i] <= 0)
				{
					continue;
				}
				final JLabel icon = new JLabel();
				icon.setToolTipText("<html>" + (names[i] == null ? "?" : names[i]) + "<br>"
					+ plugin.fmt(values[i]) + " — " + context + "</html>");
				plugin.itemIcon(ids[i]).addTo(icon);
				grid.add(icon);
			}
		}
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
