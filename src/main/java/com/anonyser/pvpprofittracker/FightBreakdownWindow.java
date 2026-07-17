package com.anonyser.pvpprofittracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.api.SpriteID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * A separate window for reading one fight's hit-by-hit breakdown — the side panel is too narrow
 * for time, weapon, damage and HP columns. A normal decorated, resizable frame (it's for reading,
 * not a HUD), reused for every fight; closing hides it and the plugin disposes it on shutdown.
 * EDT-only and deliberately client-free: it renders plain {@link FightLog} data, with item icons
 * supplied through an async-safe lookup.
 */
class FightBreakdownWindow extends JFrame
{
	private static final String KEY_X = "fightWindowX";
	private static final String KEY_Y = "fightWindowY";
	private static final String KEY_W = "fightWindowW";
	private static final String KEY_H = "fightWindowH";
	private static final int DEFAULT_W = 460;
	private static final int DEFAULT_H = 480;

	private static final Color YOU = new Color(60, 220, 90);
	private static final Color THEM = new Color(235, 70, 60);
	private static final Color DRAW = new Color(255, 200, 60);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR.darker();

	private static final int RING_OF_RECOIL = 2550;

	private final ConfigManager configManager;
	private final IntFunction<AsyncBufferedImage> icons;
	private final BiConsumer<JLabel, Integer> spriteIcon; // (label, spriteId) — async, EDT-safe
	private final JLabel title = new JLabel();
	private final JLabel subtitle = new JLabel();
	private final JLabel totals = new JLabel();
	private final JPanel rows = new JPanel(new GridBagLayout());
	private final JPanel headerRow = new JPanel(new GridBagLayout());
	private JScrollPane scroll;

	// Fixed pixel widths for EVERY column. The pinned header is a SEPARATE panel from the rows,
	// so both grids must size identically — any content-derived width (a long weapon name) makes
	// the two drift apart and the header captions stop lining up with their columns.
	private static final int[] COL_WIDTHS = {40, 80, 170, 36, 52};

	FightBreakdownWindow(ConfigManager configManager, IntFunction<AsyncBufferedImage> icons,
		BiConsumer<JLabel, Integer> spriteIcon)
	{
		this.configManager = configManager;
		this.icons = icons;
		this.spriteIcon = spriteIcon;
		setTitle("Fight breakdown");
		setDefaultCloseOperation(HIDE_ON_CLOSE);

		final JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		subtitle.setForeground(MUTED);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		totals.setForeground(Color.WHITE);
		totals.setFont(FontManager.getRunescapeSmallFont());
		totals.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);
		header.add(subtitle);
		header.add(totals);

		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		rows.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
		// The rows panel rides NORTH inside a width-tracking wrapper: rows keep their natural
		// height when the window is taller than the log, and the wrapper never grows wider than
		// the viewport, so the damage/HP columns can't be clipped off past a missing scrollbar.
		final ScrollableWidthPanel wrap = new ScrollableWidthPanel();
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(rows, BorderLayout.NORTH);
		scroll = new JScrollPane(wrap,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Blit scrolling smears the async-loaded item icons into artifacts; repaint fully instead.
		scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
		// The column captions ride the scroll pane's column header, so they stay pinned while
		// the rows scroll underneath them.
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
		buildHeaderRow();
		scroll.setColumnHeaderView(headerRow);

		setLayout(new BorderLayout());
		add(header, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		loadBounds();
		// Bounds are saved when the window goes away, NOT per move/resize event: a drag fires
		// dozens of componentMoved events a second, and every config write posts a ConfigChanged
		// that the plugin would busily react to.
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentHidden(ComponentEvent e)
			{
				saveBounds();
			}
		});
	}

	@Override
	public void dispose()
	{
		if (isShowing())
		{
			saveBounds();
		}
		super.dispose();
	}

	/** A BorderLayout panel that always lays out at the viewport's width inside a scroll pane. */
	private static final class ScrollableWidthPanel extends JPanel implements javax.swing.Scrollable
	{
		ScrollableWidthPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** Fill the window with this log and bring it up. EDT only. */
	void show(FightLog log)
	{
		final Color outcomeColor = "Draw".equals(log.outcome) ? DRAW
			: "No result".equals(log.outcome) ? Color.WHITE
			: ("Win".equals(log.outcome) ? YOU : THEM);
		title.setText(log.outcome + " vs " + log.name);
		title.setForeground(outcomeColor);
		subtitle.setText("<html>Started " + FightHistory.exact(log.startTs)
			+ " &nbsp;·&nbsp; lasted " + FightLog.clock(log.duration) + "</html>");
		int mine = 0;
		int theirs = 0;
		int vengeMine = 0;
		int vengeTheirs = 0;
		int recoilMine = 0;
		int recoilTheirs = 0;
		for (final FightLog.Entry e : log.hits)
		{
			if (e.me)
			{
				mine += e.amount;
			}
			else
			{
				theirs += e.amount;
			}
			if ("venge".equals(e.tag))
			{
				if (e.me)
				{
					vengeMine += e.amount;
				}
				else
				{
					vengeTheirs += e.amount;
				}
			}
			else if ("recoil".equals(e.tag))
			{
				if (e.me)
				{
					recoilMine += e.amount;
				}
				else
				{
					recoilTheirs += e.amount;
				}
			}
		}
		totals.setText("<html>You dealt <b><font color='#3cdc5a'>" + mine + "</font></b>"
			+ " &nbsp;·&nbsp; " + log.name + " dealt <b><font color='#eb463c'>" + theirs
			+ "</font></b><br>venge: you <b>" + vengeMine + "</b> · them <b>" + vengeTheirs
			+ "</b> &nbsp;—&nbsp; recoil: you <b>" + recoilMine + "</b> · them <b>"
			+ recoilTheirs + "</b></html>");

		rows.removeAll();
		final GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		for (final FightLog.Entry e : log.hits)
		{
			addRow(c, log.name, e);
			c.gridy++;
		}
		rows.revalidate();
		rows.repaint();
		// The reused viewport keeps the previous fight's scroll offset — a fresh log reads from
		// the top. After the revalidate settles, or the reset itself gets clamped away.
		javax.swing.SwingUtilities.invokeLater(() ->
			scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
		setVisible(true);
		toFront();
	}

	private void buildHeaderRow()
	{
		final GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		final String[] caps = {"Time", "Who", "Weapon", "Hit", null};
		for (int i = 0; i < caps.length; i++)
		{
			final JLabel l;
			if (caps[i] == null)
			{
				// The HP column's caption is the game's own hitpoints icon.
				l = new JLabel(new ImageIcon(ImageUtil.loadImageResource(
					SkillIconManager.class, "/skill_icons_small/hitpoints.png")));
				// Icon-only labels self-center; the numbers below are left-aligned — match them.
				l.setHorizontalAlignment(SwingConstants.LEFT);
				l.setToolTipText("HP after the hit");
			}
			else
			{
				l = new JLabel(caps[i]);
				l.setForeground(MUTED);
				l.setFont(FontManager.getRunescapeSmallFont());
			}
			placeIn(headerRow, l, c, i, i == 2 ? 1.0 : 0);
		}
	}

	private void addRow(GridBagConstraints c, String oppName, FightLog.Entry e)
	{
		final JLabel time = new JLabel(FightLog.clock(e.t));
		time.setForeground(MUTED);

		final JLabel who = new JLabel(e.me ? "You" : oppName);
		who.setForeground(e.me ? YOU : THEM);

		final JPanel weapon = new JPanel();
		weapon.setLayout(new BoxLayout(weapon, BoxLayout.X_AXIS));
		weapon.setOpaque(false);
		if (e.tag != null)
		{
			// A reflection isn't a weapon hit — show what actually caused it: the ring or the spell.
			final JLabel tag = new JLabel();
			tag.setPreferredSize(new Dimension(26, 24));
			tag.setMinimumSize(new Dimension(26, 24));
			tag.setHorizontalAlignment(SwingConstants.CENTER);
			if ("venge".equals(e.tag))
			{
				spriteIcon.accept(tag, SpriteID.SPELL_VENGEANCE);
				tag.setToolTipText("Vengeance");
			}
			else
			{
				final AsyncBufferedImage img = icons.apply(RING_OF_RECOIL);
				if (img != null)
				{
					img.addTo(tag);
				}
				tag.setToolTipText("Ring of recoil");
			}
			weapon.add(tag);
		}
		else
		{
			if (e.weapon > 0)
			{
				final JLabel icon = new JLabel();
				icon.setPreferredSize(new Dimension(26, 24));
				icon.setMinimumSize(new Dimension(26, 24));
				icon.setHorizontalAlignment(SwingConstants.CENTER);
				final AsyncBufferedImage img = icons.apply(e.weapon);
				if (img != null)
				{
					img.addTo(icon);
				}
				weapon.add(icon);
			}
			final JLabel name = new JLabel(e.wname != null ? e.wname : "—");
			name.setForeground(Color.WHITE);
			weapon.add(name);
			if (e.spec)
			{
				final JLabel spec = new JLabel(" spec");
				spec.setForeground(DRAW);
				spec.setFont(spec.getFont().deriveFont(Font.BOLD));
				weapon.add(spec);
			}
		}

		final JLabel hit = new JLabel(Integer.toString(e.amount));
		hit.setForeground(e.amount > 0 ? Color.WHITE : MUTED);
		hit.setFont(FontManager.getRunescapeBoldFont());

		final JLabel hp = new JLabel(e.hp >= 0 ? Integer.toString(e.hp) : "—");
		hp.setForeground(MUTED);
		hp.setToolTipText(e.me ? "Their HP after this hit (from their health bar)"
			: "Your HP after this hit");

		place(time, c, 0, 0);
		place(who, c, 1, 0);
		place(weapon, c, 2, 1.0);
		place(hit, c, 3, 0);
		place(hp, c, 4, 0);
	}

	private void place(JComponent comp, GridBagConstraints c, int x, double weight)
	{
		placeIn(rows, comp, c, x, weight);
	}

	private void placeIn(JPanel target, JComponent comp, GridBagConstraints c, int x, double weight)
	{
		if (COL_WIDTHS[x] > 0)
		{
			// Fixed column width, shared with the pinned header so the two grids line up.
			final Dimension d = new Dimension(COL_WIDTHS[x], comp.getPreferredSize().height);
			comp.setPreferredSize(d);
			comp.setMinimumSize(d);
		}
		c.gridx = x;
		c.weightx = weight;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(2, x == 0 ? 0 : 8, 2, 0);
		target.add(comp, c);
	}

	private void loadBounds()
	{
		final Integer x = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, KEY_X, Integer.class);
		final Integer y = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, KEY_Y, Integer.class);
		final Integer w = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, KEY_W, Integer.class);
		final Integer h = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, KEY_H, Integer.class);
		final int useW = w != null && w >= 260 ? w : DEFAULT_W;
		final int useH = h != null && h >= 200 ? h : DEFAULT_H;
		setSize(useW, useH);
		if (x != null && y != null && onAnyScreen(new Rectangle(x, y, useW, 40)))
		{
			setLocation(x, y);
		}
		else
		{
			setLocationRelativeTo(null); // stale monitor layout — recenter rather than vanish
		}
	}

	/** True when the rectangle touches ANY display — getMaximumWindowBounds only knows the primary. */
	private static boolean onAnyScreen(Rectangle r)
	{
		for (final java.awt.GraphicsDevice d
			: GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
		{
			if (d.getDefaultConfiguration().getBounds().intersects(r))
			{
				return true;
			}
		}
		return false;
	}

	private void saveBounds()
	{
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, KEY_X, getX());
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, KEY_Y, getY());
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, KEY_W, getWidth());
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, KEY_H, getHeight());
	}
}
