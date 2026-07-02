package com.anonyser.pvpprofittracker;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "PvP Profit Tracker",
	description = "Tracks real PvP profit — loot-key gains minus what you lose on death and consumables — "
		+ "with K/D, risk and net worth",
	tags = {"pvp", "profit", "loot", "kill", "death", "wilderness", "bounty", "risk"}
)
public class PvpProfitTrackerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PvpProfitTrackerPlugin.class);

	// Persistence keys (stored per RuneScape profile so each account keeps its own tallies).
	private static final String K_SINCE = "sinceEnabled";
	private static final String K_TRACKED = "tracked";
	private static final String K_OVERALL = "overall";
	private static final String K_ENABLED_AT = "enabledAt";

	// PvP loot keys (held in the inventory) and the Deadman containers their contents live in.
	private static final int[] LOOT_KEYS = {
		ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2,
		ItemID.WILDY_LOOT_KEY3, ItemID.WILDY_LOOT_KEY4,
	};
	private static final int[] LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4,
	};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PvpProfitTrackerConfig config;

	// Overlay and panel are created manually (they reference the plugin) to avoid circular DI.
	private PvpProfitTrackerOverlay overlay;
	private PvpProfitTrackerPanel panel;
	private NavigationButton navButton;

	// Tracking scopes. session/sinceEnabled/tracked hold full profit; overall is K/D only (Edgeville import).
	private final Stats session = new Stats();
	private final Stats sinceEnabled = new Stats();
	private final Stats tracked = new Stats();
	private final Stats overall = new Stats();
	private Instant sessionStart;
	private long enabledAtMillis;
	private boolean loaded;

	// Live, display-only derived values.
	private long riskGp;
	private long riskOffset; // game's exact death value minus our estimate — learned from the death screen
	private long netWorthGp;
	private long bankValueGp;

	// Loot-key edge detection (count a kill on the transition to "holding a key", not on login).
	private boolean heldLootKey;
	private boolean lootKeySynced;
	private int deathDumpCountdown;

	@Override
	protected void startUp()
	{
		sessionStart = Instant.now();
		overlay = new PvpProfitTrackerOverlay(this, config);
		overlayManager.add(overlay);
		panel = new PvpProfitTrackerPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("PvP Profit Tracker")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// If enabled while already logged in, load this profile's saved tallies now.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			load();
		}
	}

	@Override
	protected void shutDown()
	{
		save();
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		overlay = null;
		panel = null;
		navButton = null;
		heldLootKey = false;
		lootKeySynced = false;
		loaded = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			lootKeySynced = false;
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		// Fires once the account's profile is known (after login / on account switch) — load its tallies.
		load();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		final int id = e.getContainerId();

		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			recomputeLiveValues();
			if (id == InventoryID.INV)
			{
				detectLootKeyPickup();
			}
		}
		else if (id == InventoryID.BANK)
		{
			bankValueGp = value(e.getItemContainer());
			recomputeLiveValues();
		}

		capture("ItemContainerChanged id=" + id);
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		final Player me = client.getLocalPlayer();
		if (me == null || e.getActor() != me)
		{
			return;
		}
		// v0.1: book the current at-risk value as the loss. The precise kept-on-death engine
		// (keep top 3 / 4 with Protect Item / 0 when skulled) lands after the in-game capture session.
		recordDeath(riskGp);
		capture("local player death, booked loss " + riskGp);
	}

	// --- Capture helpers: only active with debug logging on, to harvest ids for the in-game session ---

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (e.getActor() == client.getLocalPlayer())
		{
			capture("local player animation=" + e.getActor().getAnimation());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		capture("WidgetLoaded groupId=" + e.getGroupId());
		if (e.getGroupId() == InterfaceID.DEATHKEEP)
		{
			deathDumpCountdown = 3; // dump a few ticks later, once the game has populated the values
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (deathDumpCountdown > 0 && --deathDumpCountdown == 0)
		{
			dumpDeathKeep();
			calibrateRisk();
		}
	}

	/** Append a line to ~/.runelite/pvp-capture.log (and the client log) when debug logging is on. */
	private void capture(String msg)
	{
		if (!config.debugLogging())
		{
			return;
		}
		log.info("[capture] {}", msg);
		final File f = new File(System.getProperty("user.home"), ".runelite/pvp-capture.log");
		try (FileWriter w = new FileWriter(f, true))
		{
			w.write(LocalTime.now().withNano(0) + "  " + msg + System.lineSeparator());
		}
		catch (IOException ignored)
		{
			// best-effort capture logging
		}
	}

	/** Dump the game's Items Kept on Death data so Risk can be wired to the exact value it shows. */
	private void dumpDeathKeep()
	{
		capture("=== ITEMS KEPT ON DEATH ===");
		final ItemContainer kept = client.getItemContainer(InventoryID.DEATHKEEP);
		if (kept != null)
		{
			for (final Item it : kept.getItems())
			{
				if (it.getId() > 0)
				{
					capture(String.format("kept: id=%d qty=%d name=%s ge=%d",
						it.getId(), it.getQuantity(), itemName(it.getId()), itemManager.getItemPrice(it.getId())));
				}
			}
		}
		for (int child = 0; child < 80; child++)
		{
			dumpWidget(client.getWidget(InterfaceID.DEATHKEEP, child), String.valueOf(child), 0);
		}
		for (final int cid : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			final ItemContainer c = client.getItemContainer(cid);
			if (c == null)
			{
				continue;
			}
			for (final Item it : c.getItems())
			{
				if (it.getId() > 0)
				{
					capture(String.format("carry(%d): id=%d qty=%d name=%s ge=%d storeValue=%d",
						cid, it.getId(), it.getQuantity(), itemName(it.getId()),
						itemManager.getItemPrice(it.getId()), itemManager.getItemComposition(it.getId()).getPrice()));
				}
			}
		}
	}

	private void dumpWidget(Widget w, String tag, int depth)
	{
		if (w == null || depth > 4)
		{
			return;
		}
		final String t = w.getText();
		final int itemId = w.getItemId();
		if ((t != null && !t.trim().isEmpty()) || itemId > 0)
		{
			final String text = t == null ? "" : t.replaceAll("<[^>]*>", "").trim();
			capture("widget[" + tag + "] text=\"" + text + "\""
				+ (itemId > 0 ? " itemId=" + itemId + " qty=" + w.getItemQuantity() + " name=" + itemName(itemId) : ""));
		}
		for (final Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids != null)
			{
				for (int i = 0; i < kids.length; i++)
				{
					dumpWidget(kids[i], tag + "." + i, depth + 1);
				}
			}
		}
	}

	private String itemName(int id)
	{
		try
		{
			return itemManager.getItemComposition(id).getName();
		}
		catch (RuntimeException e)
		{
			return "?";
		}
	}

	private void detectLootKeyPickup()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INV);
		boolean has = false;
		if (inv != null)
		{
			for (final int key : LOOT_KEYS)
			{
				if (inv.contains(key))
				{
					has = true;
					break;
				}
			}
		}

		if (!lootKeySynced)
		{
			heldLootKey = has;
			lootKeySynced = true;
			return;
		}

		if (has && !heldLootKey)
		{
			final long gp = valueLootKeyContents();
			recordKill(gp);
			capture("loot key received, contents valued " + gp);
		}
		heldLootKey = has;
	}

	private long valueLootKeyContents()
	{
		long total = 0;
		for (final int container : LOOT_KEY_CONTAINERS)
		{
			total += value(client.getItemContainer(container));
		}
		return total;
	}

	private void recordKill(long gp)
	{
		session.addKill(gp);
		sinceEnabled.addKill(gp);
		tracked.addKill(gp);
		overall.kills++;
		save();
		updatePanel();
	}

	private void recordDeath(long lostGp)
	{
		session.addDeath(lostGp);
		sinceEnabled.addDeath(lostGp);
		tracked.addDeath(lostGp);
		overall.deaths++;
		save();
		updatePanel();
	}

	private void recomputeLiveValues()
	{
		final long inv = value(client.getItemContainer(InventoryID.INV));
		final long worn = value(client.getItemContainer(InventoryID.WORN));
		netWorthGp = bankValueGp + inv + worn;
		updateRisk();
	}

	/**
	 * Sum the GE value of a container. {@link ItemManager#getItemPrice} is variation-aware, so ornamented
	 * and Bounty Hunter corrupted items are priced at their real base value, not the untradeable shell.
	 */
	private long value(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		long total = 0;
		for (final Item item : container.getItems())
		{
			final int itemId = item.getId();
			final int qty = item.getQuantity();
			if (itemId <= 0 || qty <= 0)
			{
				continue;
			}
			total += (long) itemManager.getItemPrice(itemId) * qty;
		}
		return total;
	}

	/** Displayed risk: our live, real-state estimate plus the calibration learned from the death screen. */
	private void updateRisk()
	{
		riskGp = Math.max(0, estimateRisk() + riskOffset);
		updatePanel();
	}

	/**
	 * When the Items Kept on Death screen is open, snap our estimate to the game's exact "Guide risk value"
	 * by learning the offset (the untradeable death-value gap I can't derive). After this, Risk tracks live
	 * from your real state — no need to keep the screen open, and toggling its scenarios won't affect it.
	 */
	private void calibrateRisk()
	{
		final Long game = readGuideRiskValue();
		if (game != null)
		{
			riskOffset = game - estimateRisk();
			capture("calibrated riskOffset=" + riskOffset + " (game=" + game + ")");
			updateRisk();
		}
	}

	/** The game's current "Guide risk value" from the death screen, or null if it isn't open. */
	private Long readGuideRiskValue()
	{
		for (int child = 0; child < 40; child++)
		{
			final Widget w = client.getWidget(InterfaceID.DEATHKEEP, child);
			Long v = parseGuideRisk(w);
			if (v == null && w != null && w.getStaticChildren() != null)
			{
				for (final Widget k : w.getStaticChildren())
				{
					v = parseGuideRisk(k);
					if (v != null)
					{
						break;
					}
				}
			}
			if (v != null)
			{
				return v;
			}
		}
		return null;
	}

	private Long parseGuideRisk(Widget w)
	{
		if (w == null || w.getText() == null || !w.getText().toLowerCase().contains("guide risk value"))
		{
			return null;
		}
		final String digits = w.getText().replaceAll("[^0-9]", "");
		if (digits.isEmpty())
		{
			return null;
		}
		try
		{
			return Long.parseLong(digits);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Fallback estimate before the game's value has been read: everything carried minus the items kept on
	 * death (3 / 4 with Protect Item / 0 skulled / 1 skulled+Protect Item), pricing untradeables at store
	 * value. Approximate — the game's own "Guide risk value" is exact.
	 */
	private long estimateRisk()
	{
		final List<long[]> items = new ArrayList<>(); // [perItemDeathValue, stackValue, itemId]
		long total = 0;
		for (final int cid : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			final ItemContainer c = client.getItemContainer(cid);
			if (c == null)
			{
				continue;
			}
			for (final Item it : c.getItems())
			{
				final int id = it.getId();
				final int qty = it.getQuantity();
				if (id <= 0 || qty <= 0)
				{
					continue;
				}
				final long per = deathValue(id);
				if (per <= 0)
				{
					continue;
				}
				final long stack = per * qty;
				total += stack;
				items.add(new long[]{per, stack, id});
			}
		}
		items.sort((a, b) -> Long.compare(b[0], a[0]));
		final int kept = keptCount();
		long protectedValue = 0;
		final StringBuilder keptItems = new StringBuilder();
		for (int i = 0; i < items.size() && i < kept; i++)
		{
			protectedValue += items.get(i)[1];
			keptItems.append(itemName((int) items.get(i)[2])).append('(').append(items.get(i)[1]).append(") ");
		}
		final long risk = Math.max(0, total - protectedValue);
		capture("risk skull=" + isSkulled() + " prot=" + client.isPrayerActive(Prayer.PROTECT_ITEM)
			+ " kept=" + kept + " total=" + total + " risk=" + risk + " keptItems=[" + keptItems.toString().trim() + "]");
		return risk;
	}

	private int keptCount()
	{
		final int base = isSkulled() ? 0 : 3;
		return base + (client.isPrayerActive(Prayer.PROTECT_ITEM) ? 1 : 0);
	}

	private int skullIcon()
	{
		final Player me = client.getLocalPlayer();
		return me == null ? -1 : me.getSkullIcon();
	}

	private boolean isSkulled()
	{
		return skullIcon() != -1;
	}

	/** Per-item value the game uses on death: GE price if tradeable, else the item's store value. */
	private long deathValue(int id)
	{
		final long ge = itemManager.getItemPrice(id);
		if (ge > 0)
		{
			return ge;
		}
		try
		{
			return Math.max(0, itemManager.getItemComposition(id).getPrice());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	// --- Persistence (per RuneScape profile) ---

	private void load()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return; // no active profile (e.g. on the login screen) — don't overwrite in-memory state
		}
		sinceEnabled.copyFrom(configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_SINCE, Stats.class));
		tracked.copyFrom(configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_TRACKED, Stats.class));
		overall.copyFrom(configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_OVERALL, Stats.class));
		enabledAtMillis = parseLong(configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_ENABLED_AT));
		if (enabledAtMillis <= 0)
		{
			enabledAtMillis = System.currentTimeMillis();
			configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_ENABLED_AT, enabledAtMillis);
		}
		loaded = true;
		updatePanel();
	}

	private void save()
	{
		if (!loaded)
		{
			return; // avoid persisting defaults before we've loaded the profile's real tallies
		}
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_SINCE, sinceEnabled);
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_TRACKED, tracked);
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_OVERALL, overall);
	}

	private static long parseLong(String s)
	{
		if (s == null)
		{
			return 0;
		}
		try
		{
			return Long.parseLong(s.trim());
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	// --- Reset actions (wired to the panel buttons) ---

	public void resetSession()
	{
		session.reset();
		sessionStart = Instant.now();
		updatePanel();
	}

	public void resetTracked()
	{
		tracked.reset();
		save();
		updatePanel();
	}

	private void updatePanel()
	{
		if (panel != null)
		{
			panel.update();
		}
	}

	// --- Accessors for the overlay / panel ---

	Stats getSession()
	{
		return session;
	}

	Stats getSinceEnabled()
	{
		return sinceEnabled;
	}

	Stats getTracked()
	{
		return tracked;
	}

	Stats getOverall()
	{
		return overall;
	}

	long getRiskGp()
	{
		return riskGp;
	}

	long getNetWorthGp()
	{
		return netWorthGp;
	}

	String sessionDuration()
	{
		if (sessionStart == null)
		{
			return "0:00";
		}
		final long secs = Duration.between(sessionStart, Instant.now()).getSeconds();
		final long h = secs / 3600;
		final long m = (secs % 3600) / 60;
		final long s = secs % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

	String enabledSince()
	{
		if (enabledAtMillis <= 0)
		{
			return "—";
		}
		return Instant.ofEpochMilli(enabledAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
	}

	/** Compact gp formatting: 1.2M / 12.3K / 950. */
	static String gp(long v)
	{
		final long a = Math.abs(v);
		if (a >= 10_000_000)
		{
			return (v / 1_000_000) + "M";
		}
		if (a >= 1_000_000)
		{
			return String.format("%.1fM", v / 1_000_000.0);
		}
		if (a >= 100_000)
		{
			return (v / 1000) + "K";
		}
		if (a >= 1000)
		{
			return String.format("%.1fK", v / 1000.0);
		}
		return Long.toString(v);
	}

	/** Exact gp with thousands separators, e.g. 1,378,016. */
	static String gpFull(long v)
	{
		return String.format("%,d", v);
	}

	private static BufferedImage icon()
	{
		final BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 200, 83));
		g.fillOval(1, 1, 22, 22);
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 15));
		final FontMetrics fm = g.getFontMetrics();
		final String s = "$";
		g.drawString(s, (24 - fm.stringWidth(s)) / 2, (24 - fm.getHeight()) / 2 + fm.getAscent());
		g.dispose();
		return img;
	}

	@Provides
	PvpProfitTrackerConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(PvpProfitTrackerConfig.class);
	}
}
