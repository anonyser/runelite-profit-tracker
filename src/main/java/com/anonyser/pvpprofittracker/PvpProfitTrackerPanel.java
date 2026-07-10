package com.anonyser.pvpprofittracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * Side panel, opponent-first: your current risk on top, then the focused opponent — name (green
 * when they're your Bounty Hunter target), their hiscore stats as an icon column shaped like the
 * game's own skills tab (attack..magic down the left, hitpoints top-right) with the worn-gear grid
 * nested inside that L, and icon-only BH kills / Zuk / Sol counts. Below: profit, net worth,
 * bounty crates, BH points, K/D, and the reset buttons — all as caption-left / value-right rows.
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
	private static final Color TARGET_GREEN = new Color(60, 220, 90);
	private static final Color LOSS_RED = new Color(235, 70, 60);
	private static final Color VALUE_COLOR = Color.WHITE;

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

	// Risk (top of the panel)
	private final JPanel riskHolder;
	private final JLabel riskValue = valueLabel();
	// Opponent
	private final JPanel opponentHolder;
	// Profit
	private final JPanel profitHolder;
	private final JLabel profitBaseline = valueLabel();
	private final JPanel profitBaselineRow;
	private final JLabel profitSession = valueLabel();
	private final JPanel profitSessionRow;
	// Net worth
	private final JPanel netWorthHolder;
	private final JLabel netWorthValue = valueLabel();
	private final JLabel barrelValue = valueLabel();
	private final JPanel barrelRow;
	// Crates
	private final JPanel cratesHolder;
	private final JLabel crateFlashValue = valueLabel();
	private final JPanel crateFlashRow;
	private final JLabel cratesBaseline = valueLabel();
	private final JPanel cratesBaselineRow;
	private final JLabel cratesSession = valueLabel();
	private final JPanel cratesSessionRow;
	// Points
	private final JPanel pointsHolder;
	private final JLabel pointsCurrent = valueLabel();
	private final JPanel pointsCurrentRow;
	private final JLabel pointsBaseline = valueLabel();
	private final JPanel pointsBaselineRow;
	private final JLabel pointsSession = valueLabel();
	private final JPanel pointsSessionRow;
	// K/D
	private final JPanel kdHolder;
	private final JLabel kdActual = valueLabel();
	private final JPanel kdActualRow;
	private final JLabel kdActualNote = noteLabel();
	private final JLabel kdBaseline = valueLabel();
	private final JPanel kdBaselineRow;
	private final JLabel kdSession = valueLabel();
	private final JPanel kdSessionRow;

	PvpProfitTrackerPanel(PvpProfitTrackerPlugin plugin, PvpProfitTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		flashTick.setRepeats(false);
		refreshSoon.setRepeats(false);
		setLayout(new BorderLayout());
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.NORTH);

		riskHolder = holder(section(null,
			row("If you die now", "Lost on death and applied to profit as a loss.", riskValue)));

		opponentSection = new OpponentSection();
		opponentSoon = new Timer(REBUILD_COALESCE_MS, e -> refreshOpponent());
		opponentSoon.setRepeats(false);
		opponentHolder = holder(opponentSection);

		profitBaselineRow = row("Baseline", MODES_TIP, profitBaseline);
		profitSessionRow = row("Session", MODES_TIP, profitSession);
		profitHolder = holder(section("Profit", profitBaselineRow, profitSessionRow));

		barrelRow = row("Incl. barrel", "Potions stored in your chugging barrel — counted in "
			+ "net worth; each chug books one dose of each as a consumable.", barrelValue);
		netWorthHolder = holder(section("Net worth",
			row("Bank + carried", "Informational only — never counts toward profit.", netWorthValue),
			barrelRow));

		crateFlashRow = row("Crate reward", "Added to profit — this message disappears in a few seconds.",
			crateFlashValue);
		cratesBaselineRow = row("Baseline", MODES_TIP, cratesBaseline);
		cratesSessionRow = row("Session", MODES_TIP, cratesSession);
		cratesHolder = holder(section("Bounty crates", crateFlashRow, cratesBaselineRow, cratesSessionRow));

		pointsCurrentRow = row("Current", "Your actual points balance from the game — goes "
			+ "down when you spend.", pointsCurrent);
		pointsBaselineRow = row("Baseline", MODES_TIP, pointsBaseline);
		pointsSessionRow = row("Session", MODES_TIP, pointsSession);
		pointsHolder = holder(section("Bounty Hunter points", pointsCurrentRow, pointsBaselineRow,
			pointsSessionRow));

		kdActualRow = row("Actual", "Read from the game's own stats: the Edgeville Kill "
			+ "Death Ratio window (world PvP) or the Bounty Hunter HUD, which refreshes it as you get kills.",
			kdActual);
		kdActualNote.setText("<html>From the game's stats — updates per kill at Bounty Hunter.</html>");
		kdBaselineRow = row("Baseline", MODES_TIP, kdBaseline);
		kdSessionRow = row("Session", MODES_TIP, kdSession);
		kdHolder = holder(section("Kill / Death", kdActualRow, kdActualNote, kdBaselineRow, kdSessionRow));

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

		body.add(riskHolder);
		body.add(opponentHolder);
		body.add(profitHolder);
		body.add(netWorthHolder);
		body.add(cratesHolder);
		body.add(pointsHolder);
		body.add(kdHolder);
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

		riskValue.setText(plugin.fmt(plugin.getRiskGp()));
		riskHolder.setVisible(config.showRisk());

		refreshOpponent();

		profitBaselineRow.setVisible(config.showBaselineProfit());
		profitRow(profitBaselineRow, profitBaseline, baseline);
		profitSessionRow.setVisible(config.showSessionProfit());
		profitRow(profitSessionRow, profitSession, session);
		profitHolder.setVisible(config.showBaselineProfit() || config.showSessionProfit());

		final String netWorth = plugin.netWorthDisplay();
		netWorthValue.setText(netWorth);
		netWorthValue.setForeground("Open bank first".equals(netWorth)
			? ColorScheme.LIGHT_GRAY_COLOR.darker() : VALUE_COLOR);
		barrelRow.setVisible(plugin.getBarrelGp() > 0);
		barrelValue.setText(plugin.fmt(plugin.getBarrelGp()));
		netWorthHolder.setVisible(config.showNetWorth());

		final boolean flashing = plugin.crateFlashGp() > 0;
		crateFlashRow.setVisible(flashing);
		if (flashing)
		{
			crateFlashValue.setText(plugin.fmt(plugin.crateFlashGp())
				+ " (" + plugin.crateFlashSecondsLeft() + "s)");
			crateFlashValue.setForeground(FLASH_COLOR);
			flashTick.restart();
		}
		cratesBaselineRow.setVisible(config.showBaselineCrates());
		cratesBaseline.setText(Long.toString(baseline.crates));
		cratesSessionRow.setVisible(config.showSessionCrates());
		cratesSession.setText(Long.toString(session.crates));
		cratesHolder.setVisible(flashing || config.showBaselineCrates() || config.showSessionCrates());

		pointsCurrentRow.setVisible(config.showCurrentPoints());
		pointsCurrent.setText(plugin.currentBhPointsDisplay());
		pointsBaselineRow.setVisible(config.showBaselinePoints());
		pointsBaseline.setText(Long.toString(baseline.points));
		pointsSessionRow.setVisible(config.showSessionPoints());
		pointsSession.setText(Long.toString(session.points));
		pointsHolder.setVisible(config.showCurrentPoints() || config.showBaselinePoints()
			|| config.showSessionPoints());

		kdActualRow.setVisible(config.showActualKd());
		kdActualNote.setVisible(config.showActualKd());
		kdActual.setText(actual.kills + actual.deaths > 0
			? PvpProfitTrackerPlugin.kdText(actual) : "— (visit Edgeville)");
		kdBaselineRow.setVisible(config.showBaselineKd());
		kdBaseline.setText(PvpProfitTrackerPlugin.kdText(baseline));
		kdSessionRow.setVisible(config.showSessionKd());
		kdSession.setText(PvpProfitTrackerPlugin.kdText(session));
		kdHolder.setVisible(config.showActualKd() || config.showBaselineKd() || config.showSessionKd());

		body.revalidate();
		body.repaint();
	}

	private void profitRow(JPanel rowPanel, JLabel label, Stats s)
	{
		final long profit = s.profit();
		label.setText(plugin.fmt(profit));
		label.setForeground(profit >= 0 ? config.profitColor() : config.lossColor());
		rowPanel.setToolTipText("<html>Loot keys: " + PvpProfitTrackerPlugin.gpFull(s.gainedGp)
			+ "<br>Crates: " + PvpProfitTrackerPlugin.gpFull(s.crateGp)
			+ "<br>Deaths: -" + PvpProfitTrackerPlugin.gpFull(s.lostToDeathGp)
			+ "<br>Consumables: -" + PvpProfitTrackerPlugin.gpFull(s.consumedGp) + "</html>");
	}

	/** A dark section: optional bold title with a hairline under it, then the given rows. */
	private JPanel section(String title, Component... rows)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(new EmptyBorder(7, 9, 7, 9));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (title != null)
		{
			final JLabel t = new JLabel(title);
			t.setForeground(VALUE_COLOR);
			t.setFont(t.getFont().deriveFont(Font.BOLD));
			t.setToolTipText(MODES_TIP);
			t.setAlignmentX(Component.LEFT_ALIGNMENT);
			t.setBorder(new EmptyBorder(0, 0, 4, 0));
			p.add(t);
		}
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
		gap.setPreferredSize(new Dimension(0, 6));
		gap.setOpaque(false);
		h.add(gap);
		return h;
	}

	/** A caption-left / value-right line; the tooltip covers the whole row. */
	private JPanel row(String caption, String tooltip, JLabel value)
	{
		final JPanel r = rowWith(captionLabel(null), value);
		((JLabel) ((BorderLayout) r.getLayout()).getLayoutComponent(BorderLayout.WEST)).setText(caption);
		if (tooltip != null)
		{
			r.setToolTipText(tooltip);
		}
		return r;
	}

	private JPanel rowWith(JLabel caption, JLabel value)
	{
		final JPanel r = new JPanel(new BorderLayout());
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		r.add(caption, BorderLayout.WEST);
		r.add(value, BorderLayout.EAST);
		return r;
	}

	private JLabel captionLabel(String tooltip)
	{
		final JLabel l = new JLabel();
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		if (tooltip != null)
		{
			l.setToolTipText(tooltip);
		}
		return l;
	}

	private JLabel valueLabel()
	{
		final JLabel l = new JLabel();
		l.setForeground(VALUE_COLOR);
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
	 * The opponent block: name row (green when they're your BH target), the stat icons laid out
	 * like the game's skills tab — attack..magic down the left, hitpoints top-right — with the
	 * worn-gear grid nested inside that upside-down L, then icon-only BH kills / Zuk / Sol counts.
	 * PERSISTENT like everything else: refresh() sets texts and visibility in place; the icon grid
	 * repopulates only when the item ids actually change.
	 */
	private final class OpponentSection extends JPanel
	{
		private final JLabel hint = noteLabel();
		private final JLabel nameCaption = captionLabel("Cleared automatically after ~5 minutes out of sight.");
		private final JLabel nameValue = valueLabel();
		private final JPanel nameRow;
		// The skills-tab L: combat stats down the left, hitpoints in the top-right.
		private final JPanel statsGear = new JPanel(new GridBagLayout());
		private final JLabel attackCell = statCell("attack", "Attack");
		private final JLabel strengthCell = statCell("strength", "Strength");
		private final JLabel defenceCell = statCell("defence", "Defence");
		private final JLabel rangedCell = statCell("ranged", "Ranged");
		private final JLabel prayerCell = statCell("prayer", "Prayer");
		private final JLabel magicCell = statCell("magic", "Magic");
		private final JLabel hitpointsCell = statCell("hitpoints", "Hitpoints");
		// Icon-only activity counts, using the same game sprites the core hiscore panel shows,
		// split over two centered rows so a wide count never clips at the panel edge:
		// green skull = BH kills as the hunter, red skull = as the rogue; then Zuk and Sol KC.
		private final JPanel kcRow = new JPanel();
		private final JPanel bossRow = new JPanel();
		private final JLabel bhHunterCell = kcCell("Bounty Hunter kills as the hunter (your target).");
		private final JLabel bhRogueCell = kcCell("Bounty Hunter kills as the rogue.");
		private final JLabel zukCell = kcCell("TzKal-Zuk (Inferno) kill count.");
		private final JLabel solCell = kcCell("Sol Heredit (Colosseum) kill count.");
		// Exact (unfloored) combat level from their hiscore stats.
		private final JLabel combatValue = valueLabel();
		private final JPanel combatRow;
		// Lifetime score vs this name: your kills on them – theirs on you.
		private final JLabel wlValue = valueLabel();
		private final JPanel wlRow;
		// Free-form notes that stick to this player between sessions.
		private final javax.swing.JTextArea notesArea = new javax.swing.JTextArea(3, 20);
		private final JLabel notesCaption = captionLabel(
			"Anything you type saves automatically and shows again next time you face this player.");
		private String notesFor;
		private boolean loadingNote;
		private final JLabel gearHint = noteLabel();
		private final JPanel wornGrid = newGrid();
		private final JButton clearBtn = button("Clear", plugin::clearOpponent);
		private int[] lastWornIds = {};

		OpponentSection()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(new EmptyBorder(7, 9, 7, 9));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setAlignmentX(Component.LEFT_ALIGNMENT);

			// Centered header block: "Opponent" over the name, full width, so
			// long names never crowd the caption.
			nameCaption.setText("Opponent");
			nameCaption.setHorizontalAlignment(JLabel.CENTER);
			nameValue.setHorizontalAlignment(JLabel.CENTER);
			nameValue.setFont(nameValue.getFont().deriveFont(Font.BOLD));
			nameRow = new JPanel(new BorderLayout());
			nameRow.setOpaque(false);
			nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
			nameRow.add(nameCaption, BorderLayout.NORTH);
			nameRow.add(nameValue, BorderLayout.CENTER);
			add(nameRow);

			hint.setText("<html>Right-click a player and choose <b>Inspect</b>, or get a "
				+ "Bounty Hunter target, to see their stats and gear here.</html>");
			add(hint);

			// The stats pack tightly in their own column so the gear grid's row heights can't
			// stretch them apart: attack..magic down the left, hitpoints top-right, gear inside.
			final JPanel statColumn = new JPanel();
			statColumn.setLayout(new BoxLayout(statColumn, BoxLayout.Y_AXIS));
			statColumn.setOpaque(false);
			for (final JLabel cell : new JLabel[]{attackCell, strengthCell, defenceCell, rangedCell,
				prayerCell, magicCell})
			{
				cell.setAlignmentX(Component.LEFT_ALIGNMENT);
				cell.setBorder(new EmptyBorder(2, 0, 2, 0));
				statColumn.add(cell);
			}
			statsGear.setOpaque(false);
			statsGear.setAlignmentX(Component.LEFT_ALIGNMENT);
			final GridBagConstraints c = new GridBagConstraints();
			c.anchor = GridBagConstraints.NORTHWEST;
			c.gridx = 0;
			c.gridy = 0;
			c.gridheight = 2;
			c.insets = new Insets(0, 0, 0, 8);
			statsGear.add(statColumn, c);
			c.gridheight = 1;
			c.insets = new Insets(0, 0, 2, 0);
			c.gridx = 1;
			statsGear.add(hitpointsCell, c);
			c.gridy = 1;
			statsGear.add(wornGrid, c);
			add(statsGear);

			buildCenteredRow(kcRow, bhHunterCell, bhRogueCell);
			add(kcRow);
			buildCenteredRow(bossRow, zukCell, solCell);
			add(bossRow);

			combatRow = rowWith(captionLabel(
				"Exact combat level from their hiscore stats — the game shows it floored, the decimals say how close the next level is."),
				combatValue);
			((JLabel) ((BorderLayout) combatRow.getLayout()).getLayoutComponent(BorderLayout.WEST))
				.setText("Combat");
			add(combatRow);

			wlRow = rowWith(captionLabel(
				"Times you've killed them vs times they've killed you — counted while this plugin runs, tied to this display name."),
				wlValue);
			((JLabel) ((BorderLayout) wlRow.getLayout()).getLayoutComponent(BorderLayout.WEST))
				.setText("W / L");
			add(wlRow);
			plugin.spriteIcon(bhHunterCell,
				net.runelite.client.hiscore.HiscoreSkill.BOUNTY_HUNTER_HUNTER.getSpriteId());
			plugin.spriteIcon(bhRogueCell,
				net.runelite.client.hiscore.HiscoreSkill.BOUNTY_HUNTER_ROGUE.getSpriteId());
			plugin.spriteIcon(zukCell,
				net.runelite.client.hiscore.HiscoreSkill.TZKAL_ZUK.getSpriteId());
			plugin.spriteIcon(solCell,
				net.runelite.client.hiscore.HiscoreSkill.SOL_HEREDIT.getSpriteId());

			gearHint.setText("<html>Right-click them and choose <b>Inspect</b> to view gear.</html>");
			add(gearHint);

			notesCaption.setText("Notes");
			notesCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(notesCaption);
			notesArea.setLineWrap(true);
			notesArea.setWrapStyleWord(true);
			notesArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
			notesArea.setForeground(Color.WHITE);
			notesArea.setCaretColor(Color.WHITE);
			notesArea.setBorder(new EmptyBorder(4, 4, 4, 4));
			notesArea.setAlignmentX(Component.LEFT_ALIGNMENT);
			notesArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
			notesArea.setToolTipText(
				"Anything you type saves automatically and shows again next time you face this player.");
			notesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
			{
				private void save()
				{
					if (!loadingNote && notesFor != null)
					{
						plugin.saveOpponentNote(notesFor, notesArea.getText());
					}
				}

				@Override
				public void insertUpdate(javax.swing.event.DocumentEvent e)
				{
					save();
				}

				@Override
				public void removeUpdate(javax.swing.event.DocumentEvent e)
				{
					save();
				}

				@Override
				public void changedUpdate(javax.swing.event.DocumentEvent e)
				{
					save();
				}
			});
			add(notesArea);

			add(clearBtn);
			refresh();
		}

		// The equipment-tab arrangement, row by row: helm / cape·amulet·ammo / weapon·torso·shield
		// / legs / gloves·boots·ring. Values index into VISIBLE_SLOTS; -1 = spacer, -2 = a slot
		// that exists but can never be seen on another player (ammo, ring), shown crossed out.
		private final int[] cellSlot = {
			-1, 0, -1,
			1, 2, -2,
			3, 4, 5,
			-1, 6, -1,
			7, 8, -2,
		};
		private final String[] slotNames = {
			"Head", "Cape", "Amulet", "Weapon", "Torso", "Shield", "Legs", "Gloves", "Boots",
		};

		private JPanel newGrid()
		{
			// Laid out like the game's own equipment tab (5 rows × 3 columns).
			final JPanel grid = new JPanel(new java.awt.GridLayout(5, 3, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			grid.setMaximumSize(new Dimension(3 * 40 + 4, 5 * 36 + 8));
			return grid;
		}

		/** A small skill icon + level cell for the stats L. */
		private JLabel statCell(String iconName, String skillName)
		{
			final JLabel l = new JLabel();
			l.setForeground(VALUE_COLOR);
			l.setToolTipText(skillName);
			l.setIconTextGap(4);
			final BufferedImage icon =
				ImageUtil.loadImageResource(SkillIconManager.class, "/skill_icons_small/" + iconName + ".png");
			l.setIcon(new ImageIcon(icon));
			return l;
		}

		/** An item icon + count cell for the activity row; the icon loads lazily on first data. */
		private JLabel kcCell(String tooltip)
		{
			final JLabel l = new JLabel();
			l.setForeground(VALUE_COLOR);
			l.setToolTipText(tooltip);
			l.setIconTextGap(4);
			return l;
		}

		private Component horizontalGap()
		{
			final JPanel gap = new JPanel();
			gap.setOpaque(false);
			gap.setPreferredSize(new Dimension(14, 1));
			gap.setMaximumSize(new Dimension(14, 26));
			return gap;
		}

		/** Lays the cells out horizontally, centered by glue on both sides. */
		private void buildCenteredRow(JPanel row, Component... cells)
		{
			row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			row.add(javax.swing.Box.createHorizontalGlue());
			for (int i = 0; i < cells.length; i++)
			{
				if (i > 0)
				{
					row.add(horizontalGap());
				}
				row.add(cells[i]);
			}
			row.add(javax.swing.Box.createHorizontalGlue());
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
			statsGear.setVisible(has);
			final boolean gear = has && opp.gearShown;
			gearHint.setVisible(has && !opp.gearShown);
			wornGrid.setVisible(gear);
			clearBtn.setVisible(has);
			if (!has)
			{
				kcRow.setVisible(false);
				bossRow.setVisible(false);
				combatRow.setVisible(false);
				wlRow.setVisible(false);
				notesCaption.setVisible(false);
				notesArea.setVisible(false);
				notesFor = null;
				gearHint.setVisible(false);
				populateGrid(new int[0], null, null);
				lastWornIds = new int[]{};
				revalidate();
				repaint();
				return;
			}

			nameValue.setText(opp.name + (opp.visible ? "" : " (out of sight)"));
			nameValue.setForeground(opp.bhTarget ? TARGET_GREEN : VALUE_COLOR);
			nameValue.setToolTipText(opp.bhTarget ? "Your Bounty Hunter target" : "Inspected player");
			attackCell.setText(lvl(opp.attack));
			strengthCell.setText(lvl(opp.strength));
			defenceCell.setText(lvl(opp.defence));
			rangedCell.setText(lvl(opp.ranged));
			prayerCell.setText(lvl(opp.prayer));
			magicCell.setText(lvl(opp.magic));
			hitpointsCell.setText(lvl(opp.hitpoints));
			bhHunterCell.setText(kc(opp.bhTargetKills));
			bhRogueCell.setText(kc(opp.bhRogueKills));
			zukCell.setText(kc(opp.zukKc));
			solCell.setText(kc(opp.solHereditKc));
			kcRow.setVisible(true);
			bossRow.setVisible(true);
			final double combat = combatLevel(opp);
			combatValue.setText(combat > 0 ? String.format("%.2f", combat) : "—");
			combatRow.setVisible(true);
			final int[] wl = plugin.opponentRecord(opp.name);
			wlValue.setText(wl[0] + " – " + wl[1]);
			wlValue.setForeground(
				wl[0] > wl[1] ? TARGET_GREEN : wl[1] > wl[0] ? LOSS_RED : VALUE_COLOR);
			wlRow.setVisible(true);
			notesCaption.setVisible(true);
			notesArea.setVisible(true);
			// Load this player's saved note exactly once per focus change, so
			// typing is never clobbered by the per-frame refresh.
			if (!opp.name.equals(notesFor))
			{
				loadingNote = true;
				notesArea.setText(plugin.opponentNote(opp.name));
				loadingNote = false;
				notesFor = opp.name;
			}
			if (!java.util.Arrays.equals(lastWornIds, opp.equippedIds))
			{
				lastWornIds = opp.equippedIds.clone();
				populateGrid(opp.equippedIds, opp.equippedNames, opp.equippedGe);
			}
			revalidate();
			repaint();
		}

		/** A hiscore level for display: dash until the lookup answers. */
		private String lvl(int level)
		{
			return level > 0 ? Integer.toString(level) : "—";
		}

		/**
		 * Exact combat level (the game floors it for display): 0.25 × (def + hp
		 * + ⌊prayer/2⌋) plus the best of melee 0.325 × (att + str), ranged
		 * 0.325 × ⌊3·ranged/2⌋, magic 0.325 × ⌊3·magic/2⌋. Needs all seven
		 * hiscore stats; -1 until the lookup answers.
		 */
		private double combatLevel(OpponentTracker.Snapshot o)
		{
			if (o.attack <= 0 || o.strength <= 0 || o.defence <= 0 || o.ranged <= 0
				|| o.magic <= 0 || o.hitpoints <= 0 || o.prayer <= 0)
			{
				return -1;
			}
			final double base = 0.25 * (o.defence + o.hitpoints + o.prayer / 2);
			final double melee = 0.325 * (o.attack + o.strength);
			final double ranged = 0.325 * (o.ranged + o.ranged / 2);
			final double magic = 0.325 * (o.magic + o.magic / 2);
			return base + Math.max(melee, Math.max(ranged, magic));
		}

		/** A hiscore activity score for display: dash when unranked. */
		private String kc(int score)
		{
			return score >= 0 ? Integer.toString(score) : "—";
		}

		private void populateGrid(int[] ids, String[] names, long[] prices)
		{
			wornGrid.removeAll();
			for (final int slot : cellSlot)
			{
				final JLabel cell = new JLabel();
				cell.setHorizontalAlignment(JLabel.CENTER);
				cell.setPreferredSize(new Dimension(38, 34));
				if (slot == -2)
				{
					// Ammo and ring exist on the real equipment tab but never render on another
					// player — crossed out rather than hidden so the layout reads at a glance.
					cell.setText("✕");
					cell.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
					cell.setToolTipText("Not visible on other players");
				}
				else if (slot >= 0 && ids != null && slot < ids.length && ids[slot] > 0)
				{
					cell.setToolTipText("<html>" + (names[slot] == null ? "?" : names[slot]) + "<br>"
						+ plugin.fmt(prices[slot]) + " (GE)</html>");
					plugin.itemIcon(ids[slot]).addTo(cell);
				}
				else if (slot >= 0)
				{
					cell.setToolTipText(slotNames[slot] + ": nothing visible");
				}
				wornGrid.add(cell);
			}
		}
	}
}
