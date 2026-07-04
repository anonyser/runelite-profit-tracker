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

		if (config.opponentRisk())
		{
			addOpponentSection();
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

	/**
	 * The focused-opponent section: name, skull/tier, estimated risk and smite value, the gear
	 * icons (worn now + seen earlier in the fight), the assumptions, and the hit-chance estimate.
	 * Everything shown comes from the tracker's snapshot — names and values were resolved on the
	 * client thread; only the async icons are requested here.
	 */
	private void addOpponentSection()
	{
		final OpponentTracker.Snapshot opp = plugin.opponentSnapshot();
		final JPanel o = titled("Opponent risk");
		if (opp == null)
		{
			o.add(note("Right-click a player and choose <b>Risk</b> to track "
				+ "their visible gear and estimated risk here."));
			body.add(o);
			body.add(gap());
			return;
		}

		o.add(row("Opponent", opp.name + (opp.visible ? "" : " (out of sight)"), null,
			"Cleared automatically after ~5 minutes out of sight."));
		if (opp.tier != null)
		{
			o.add(row("BH tier", opp.tier + (opp.skulled ? " (skulled)" : ""),
				opp.skulled ? config.lossColor() : config.profitColor(),
				"Bounty Hunter risk-tier icon — the game's statement of their minimum total risk."));
		}
		else
		{
			o.add(row("Status", opp.skulled ? "Skulled" : "Unskulled",
				opp.skulled ? config.lossColor() : config.profitColor(), null));
		}
		// Combat-stat preview (hiscores). Levels first, then the PvP/PvM records that read a
		// player at a glance — records hide when unranked, levels show a dash until the lookup.
		o.add(row("Atk / Str / Def",
			lvl(opp.attackLevel) + " / " + lvl(opp.strengthLevel) + " / " + lvl(opp.defenceLevel),
			null, "From the hiscores — dashes until the lookup answers."));
		o.add(row("Rng / Mag / HP",
			lvl(opp.rangedLevel) + " / " + lvl(opp.magicLevel) + " / " + lvl(opp.hitpointsLevel),
			null, "From the hiscores — dashes until the lookup answers."));
		o.add(row("Prayer", lvl(opp.prayerLevel), null,
			"Drives the defensive-prayer assumption in your hit chance."));
		if (opp.bhTargetKills >= 0 || opp.bhRogueKills >= 0)
		{
			o.add(row("BH kills", "T " + kc(opp.bhTargetKills) + "  ·  R " + kc(opp.bhRogueKills),
				null, "Bounty Hunter kills from the hiscores: as Target's hunter · as rogue."));
		}
		if (opp.colosseumGlory > 0)
		{
			o.add(row("Colosseum glory", Integer.toString(opp.colosseumGlory), null, null));
		}
		if (opp.zukKc > 0)
		{
			o.add(row("TzKal-Zuk KC", Integer.toString(opp.zukKc), null, null));
		}
		if (opp.solHereditKc > 0)
		{
			o.add(row("Sol Heredit KC", Integer.toString(opp.solHereditKc), null, null));
		}

		o.add(row("Risk (est)", plugin.fmt(opp.riskGp), null,
			"Visible + previously seen gear, minus assumed protected items; "
				+ "at least the BH tier minimum when a tier icon shows."));
		o.add(row("Smite value (est)", plugin.fmt(opp.smiteGp), null,
			"What losing Protect Item would additionally expose."));

		o.add(gearGrid(opp.equippedIds, opp.equippedNames, opp.equippedGp, "worn now"));
		if (opp.seenOnlyIds.length > 0)
		{
			o.add(note("Seen earlier this fight:"));
			o.add(gearGrid(opp.seenOnlyIds, opp.seenOnlyNames, opp.seenOnlyGp, "seen earlier this fight"));
		}

		o.add(note(opp.skulled
			? "Assuming their most valuable item is protected (skulled, Protect Item assumed active)."
			: "Assuming top " + opp.keptAssumed + " items are protected because opponent appears "
				+ "unskulled with Protect Item active."));

		final CombatCalc.Estimate est = plugin.combatEstimate();
		if (est != null && est.style != CombatCalc.Style.OTHER)
		{
			o.add(row("Hit chance (" + est.styleName + ")", Math.round(est.hitChance * 100) + "%", null,
				"Estimate against their hiscore levels and visible gear."));
			o.add(row(est.specShown() ? "Max hit (spec)" : "Max hit", est.maxHitText(), null,
				"Your current setup: gear, boosts, prayers and combat style."
					+ " With the special-attack bar lit this shows the spec's ceiling"
					+ " for known PvP spec weapons."));
			o.add(note("Assumes opponent is using best available defensive prayer and potion boosts"
				+ (est.defenceAssumed ? ", and 99 Defence until hiscores answer." : ".")));
		}

		o.add(button("Clear opponent", plugin::clearOpponent));
		body.add(o);
		body.add(gap());
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
	 * Item icons, five per row (a FlowLayout would report a one-row height inside this BoxLayout
	 * column and clip the wrap); each icon's tooltip carries the name and value.
	 */
	private JPanel gearGrid(int[] ids, String[] names, long[] values, String context)
	{
		final JPanel grid = new JPanel(new java.awt.GridLayout(0, 5, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		return grid;
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
