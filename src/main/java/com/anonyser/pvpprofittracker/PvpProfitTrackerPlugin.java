package com.anonyser.pvpprofittracker;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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
	private long netWorthGp;
	private long bankValueGp;

	// Loot-key edge detection (count a kill on the transition to "holding a key", not on login).
	private boolean heldLootKey;
	private boolean lootKeySynced;

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

		if (config.debugLogging())
		{
			log.info("[capture] ItemContainerChanged id={}", id);
		}
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
		if (config.debugLogging())
		{
			log.info("[capture] local player death, booked loss {}", riskGp);
		}
	}

	// --- Capture helpers: only active with debug logging on, to harvest ids for the in-game session ---

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (config.debugLogging() && e.getActor() == client.getLocalPlayer())
		{
			log.info("[capture] local player animation={}", e.getActor().getAnimation());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (config.debugLogging())
		{
			log.info("[capture] VarbitChanged varbitId={} value={}", e.getVarbitId(), e.getValue());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (config.debugLogging())
		{
			log.info("[capture] WidgetLoaded groupId={}", e.getGroupId());
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
			if (config.debugLogging())
			{
				log.info("[capture] loot key received, contents valued {}", gp);
			}
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
		// v0.1: risk = everything currently carried. Kept-on-death rules refine this after capture.
		riskGp = inv + worn;
		updatePanel();
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
