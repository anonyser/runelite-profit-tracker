package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.SkullIcon;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
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
		+ "with K/D, risk and net worth. Details and baseline resets on the side panel ($ icon)",
	tags = {"pvp", "profit", "loot", "kill", "death", "wilderness", "bounty", "risk"}
)
public class PvpProfitTrackerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PvpProfitTrackerPlugin.class);

	// Persistence keys (stored per RuneScape profile so each account keeps its own tallies).
	private static final String K_BASELINE = "baseline";
	private static final String K_ACTUAL = "actual";
	private static final String K_NET_WORTH = "netWorth";
	private static final String K_BARREL = "barrel";

	// PvP loot keys (held in the inventory) and the Deadman containers their contents live in.
	private static final int[] LOOT_KEYS = {
		ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2,
		ItemID.WILDY_LOOT_KEY3, ItemID.WILDY_LOOT_KEY4,
	};
	private static final int[] LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4,
	};

	// Tiered Bounty Hunter reward crates (from kills) — these are what the crate counters count.
	private static final int[] TIER_CRATES = {
		ItemID.BH_CRATE,
		ItemID.BH_EP_CRATE_2, ItemID.BH_EP_CRATE_3, ItemID.BH_EP_CRATE_4, ItemID.BH_EP_CRATE_5,
		ItemID.BH_EP_CRATE_6, ItemID.BH_EP_CRATE_7, ItemID.BH_EP_CRATE_8, ItemID.BH_EP_CRATE_9,
		ItemID.BH_EP_CRATE_10,
	};
	// Every openable BH crate, including the supply crates that drop out of reward crates and
	// loot — those are valued as loot rather than counted (they aren't kill-crates).
	private static final int[] ALL_CRATES = {
		ItemID.BH_CRATE,
		ItemID.BH_EP_CRATE_2, ItemID.BH_EP_CRATE_3, ItemID.BH_EP_CRATE_4, ItemID.BH_EP_CRATE_5,
		ItemID.BH_EP_CRATE_6, ItemID.BH_EP_CRATE_7, ItemID.BH_EP_CRATE_8, ItemID.BH_EP_CRATE_9,
		ItemID.BH_EP_CRATE_10,
		ItemID.BH_SUPPLY_CRATE, ItemID.BH_SUPPLY_CRATE_MANTA_RAY, ItemID.BH_SUPPLY_CRATE_ANGLERFISH,
	};

	// The Kill Death Ratio window is matched by its text, not a hardcoded id, so the exact
	// interface doesn't have to be known up front. Kills/deaths are integers; the ratio is not.
	private static final Pattern KILLS_RE = Pattern.compile("kills?\\s*:?\\s*([\\d,]+)");
	private static final Pattern DEATHS_RE = Pattern.compile("deaths?\\s*:?\\s*([\\d,]+)");

	// Whole words only — otherwise "crater", "hitpoints" and "pointless" spam the capture log.
	private static final Pattern CHAT_CAPTURE_RE =
		Pattern.compile("\\b(bounty|emblems?|points?|crates?)\\b", Pattern.CASE_INSENSITIVE);

	// The eat/drink animation (confirmed in-game against food and potions alike).
	private static final int CONSUME_ANIMATION = 829;

	// Trailing dose marker in potion names, e.g. "Saradomin brew(4)".
	private static final Pattern DOSES_RE = Pattern.compile("\\((\\d)\\)\\s*$");

	// Drinking from the chugging barrel (confirmed in-game; distinct from the eat/drink 829).
	private static final int CHUG_ANIMATION = 11645;

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

	@Inject
	private Gson gson;

	// Untradeable repair-on-death costs (item name -> cost), loaded from reclaim-costs.csv.
	private final Map<String, Long> repairCosts = new HashMap<>();

	// Overlay and panel are created manually (they reference the plugin) to avoid circular DI.
	private PvpProfitTrackerOverlay overlay;
	private PvpProfitTrackerPanel panel;
	private NavigationButton navButton;

	// Tracking modes. session resets on restart; baseline persists until reset; actual is the
	// player's true in-game K/D (imported at Edgeville, kept counting from there).
	private final Stats session = new Stats();
	private final Stats baseline = new Stats();
	private final Stats actual = new Stats();
	private Instant sessionStart;
	private String loadedProfileKey; // RS profile whose tallies are live; null until first load

	// Live, display-only derived values.
	private long riskGp;
	private long netWorthGp;
	private long bankValueGp;
	private long lastRecordedNetWorth = -1; // persisted from a previous login; -1 = never recorded
	private boolean bankOpenedThisLogin;
	private boolean bankInterfaceOpen;

	// Loot-key edge detection (count a kill on the transition to "holding a key", not on login).
	private int heldLootKeyCount;
	private boolean lootKeySynced;
	private long pendingLootValue; // value in the loot chest, snapshotted while open, realized on claim

	// Bounty Hunter crate detection: inventory diffed against the previous tick's snapshot.
	private final Map<Integer, Integer> lastInventory = new HashMap<>();
	private boolean inventorySynced;
	private long crateFlashGp;
	private long crateFlashUntil;

	// Bounty Hunter points, read from the game's own points varp (delta-tracked after a login sync).
	private int lastBhPoints;
	private boolean bhPointsSynced;
	private int ticksSinceLogin;

	// Chugging barrel state: the device's contents are a real item container
	// (PREPOT_DEVICE_INV), but it only transmits while banking — so contents sync from the
	// container and each chug books off the chug animation, with the next bank visit
	// correcting any drift. Persisted per profile so the value survives relogs.
	private final Map<Integer, Integer> barrelDoses = new HashMap<>();
	private long barrelGp;

	// Consumable detection: an inventory drop right after the eat/drink animation is a consume,
	// unless it's the death tick wiping the inventory (that loss is booked as the death).
	private int lastConsumeTick = -10;
	private int lastDeathTick = -10;

	private int lastSkullIcon = -2; // last seen skull-slot icon, only for capture logging

	private int deathDumpCountdown;
	private int lootDumpCountdown;
	private int kdScanGroup = -1;
	private int kdScanCountdown;

	@Override
	protected void startUp()
	{
		loadRepairCosts();
		sessionStart = Instant.now();
		overlay = new PvpProfitTrackerOverlay(this, config);
		overlayManager.add(overlay);
		panel = new PvpProfitTrackerPanel(this, config);
		navButton = NavigationButton.builder()
			.tooltip("PvP Profit Tracker")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// If enabled while already logged in, load this profile's saved tallies now (self-guarded).
		load();
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
		heldLootKeyCount = 0;
		lootKeySynced = false;
		inventorySynced = false;
		lastInventory.clear();
		bhPointsSynced = false;
		bankInterfaceOpen = false;
		bankOpenedThisLogin = false;
		loadedProfileKey = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			lootKeySynced = false;
			inventorySynced = false;
			bankOpenedThisLogin = false;
			bankInterfaceOpen = false;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			// The profile-changed event can fire before the state reaches LOGGED_IN — load from
			// whichever happens last so the tallies always arm (self-guarded against re-loads).
			ticksSinceLogin = 0;
			load();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		// Fires once the account's profile is known (after login / on account switch) — load its tallies.
		bhPointsSynced = false;
		bankOpenedThisLogin = false;
		bankValueGp = 0;
		load();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		final int id = e.getContainerId();

		if (isLootContainer(id))
		{
			final long v = valueLootKeyContents();
			if (v > 0)
			{
				pendingLootValue = v; // snapshot the unclaimed loot while it's present; ignore clears
			}
		}
		else if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			recomputeLiveValues();
			if (id == InventoryID.INV)
			{
				detectLootKeyPickup();
			}
			// Diff runs on both containers: the snapshot spans inventory + equipment, so a
			// weapon swap right after eating nets to zero instead of booking as a meal.
			detectCrates();
		}
		else if (id == InventoryID.BANK)
		{
			bankValueGp = value(e.getItemContainer());
			bankOpenedThisLogin = true;
			recomputeLiveValues();
		}
		else if (id == InventoryID.PREPOT_DEVICE_INV)
		{
			handleBarrelChanged(e.getItemContainer());
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
		// Book the current at-risk value as the loss — it shows up as negative profit.
		lastDeathTick = client.getTickCount();
		// Never diff the inventory across a death: the wipe-and-restore shuffle looks like
		// crates being opened and items appearing. Resync fresh from the next change instead.
		inventorySynced = false;
		recordDeath(riskGp);
		capture("local player death, booked loss " + riskGp);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		// The game keeps the player's Bounty Hunter points in a varp — track its increases so kills,
		// streak/threshold bonuses and emblem turn-ins all count with the game's own numbers.
		if (e.getVarbitId() != -1 || e.getVarpId() != VarPlayerID.BH_2023_POINTS)
		{
			return;
		}
		final int now = e.getValue();
		// The login varp burst can transmit a zero before the real total — booking that
		// difference invents points out of thin air. Sync-only until the login settles.
		if (ticksSinceLogin < 5)
		{
			bhPointsSynced = true;
			lastBhPoints = now;
			return;
		}
		if (bhPointsSynced)
		{
			final int delta = now - lastBhPoints;
			if (delta > 0) // decreases are shop spending, not a gain to track
			{
				session.addPoints(delta);
				baseline.addPoints(delta);
				save();
				updatePanel();
				capture("bounty hunter points +" + delta + " (now " + now + ")");
			}
		}
		else
		{
			bhPointsSynced = true; // first transmit after login is the existing total, not a gain
		}
		lastBhPoints = now;
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// Capture-only: cross-check the points varp and harvest crate/emblem message formats.
		if (!config.debugLogging() || e.getMessage() == null)
		{
			return;
		}
		if (CHAT_CAPTURE_RE.matcher(e.getMessage()).find())
		{
			capture("chat[" + e.getType() + "] " + e.getMessage());
		}
	}

	// --- Capture helpers: only active with debug logging on, to harvest ids for the in-game session ---

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (e.getActor() == client.getLocalPlayer())
		{
			if (e.getActor().getAnimation() == CONSUME_ANIMATION)
			{
				lastConsumeTick = client.getTickCount();
			}
			else if (e.getActor().getAnimation() == CHUG_ANIMATION)
			{
				bookChug();
			}
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
		else if (e.getGroupId() == InterfaceID.WILDY_LOOT_CHEST)
		{
			lootDumpCountdown = 2;
		}
		else if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = true;
		}
		// Any opened interface might be the Kill Death Ratio window — scan it once its text has loaded.
		kdScanGroup = e.getGroupId();
		kdScanCountdown = 2;
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (ticksSinceLogin < Integer.MAX_VALUE)
		{
			ticksSinceLogin++;
		}
		// Keep risk current with prayer/skull changes too, not just inventory changes (e.g. getting smited).
		updateRisk();
		if (deathDumpCountdown > 0 && --deathDumpCountdown == 0)
		{
			dumpDeathKeep();
		}
		if (lootDumpCountdown > 0 && --lootDumpCountdown == 0)
		{
			dumpLootChest();
		}
		if (kdScanCountdown > 0 && --kdScanCountdown == 0)
		{
			maybeImportActualKd(kdScanGroup);
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

	/** Dump the Wilderness Loot Chest contents + value so we can wire realizing the gain on claim. */
	private void dumpLootChest()
	{
		capture("=== WILDERNESS LOOT CHEST ===");
		long total = 0;
		for (final int container : LOOT_KEY_CONTAINERS)
		{
			final ItemContainer c = client.getItemContainer(container);
			if (c == null)
			{
				continue;
			}
			for (final Item it : c.getItems())
			{
				if (it.getId() > 0)
				{
					final long v = wealthValue(it.getId()) * it.getQuantity();
					total += v;
					capture(String.format("loot(%d): id=%d qty=%d name=%s value=%d",
						container, it.getId(), it.getQuantity(), itemName(it.getId()), v));
				}
			}
		}
		capture("loot chest total value = " + total);
		final ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv != null)
		{
			for (final int key : LOOT_KEYS)
			{
				if (inv.contains(key))
				{
					capture("holding loot key id=" + key);
				}
			}
		}
	}

	// --- Actual K/D import (the Kill Death Ratio window at Edgeville) ---

	/**
	 * Scan a just-opened interface for the Kill Death Ratio window and import its kills/deaths as the
	 * Actual K/D. Matched by text ("kill", "death", "ratio") so it works without knowing the widget id.
	 */
	private void maybeImportActualKd(int groupId)
	{
		if (groupId < 0 || groupId == InterfaceID.BANKMAIN || groupId == InterfaceID.DEATHKEEP
			|| groupId == InterfaceID.WILDY_LOOT_CHEST)
		{
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for (int child = 0; child < 80; child++)
		{
			collectText(client.getWidget(groupId, child), sb, 0);
		}
		final String text = sb.toString().toLowerCase();
		if (!text.contains("ratio") || !text.contains("kill") || !text.contains("death"))
		{
			return;
		}
		final Matcher k = KILLS_RE.matcher(text);
		final Matcher d = DEATHS_RE.matcher(text);
		if (!k.find() || !d.find())
		{
			capture("kd-import: ratio window (group " + groupId + ") but no kills/deaths parsed: "
				+ text.replace('\n', '|'));
			return;
		}
		actual.kills = Long.parseLong(k.group(1).replace(",", ""));
		actual.deaths = Long.parseLong(d.group(1).replace(",", ""));
		save();
		updatePanel();
		capture("kd-import: actual K/D " + actual.kills + "/" + actual.deaths + " from group " + groupId);
	}

	private void collectText(Widget w, StringBuilder sb, int depth)
	{
		if (w == null || depth > 4)
		{
			return;
		}
		final String t = w.getText();
		if (t != null && !t.trim().isEmpty())
		{
			sb.append(t.replaceAll("<[^>]*>", " ").trim()).append('\n');
		}
		for (final Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids != null)
			{
				for (final Widget kid : kids)
				{
					collectText(kid, sb, depth + 1);
				}
			}
		}
	}

	// --- Kill / loot-key / crate detection ---

	private void detectLootKeyPickup()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INV);
		int count = 0;
		if (inv != null)
		{
			for (final int key : LOOT_KEYS)
			{
				count += inv.count(key);
			}
		}

		if (!lootKeySynced)
		{
			heldLootKeyCount = count;
			lootKeySynced = true;
			return;
		}

		if (count > heldLootKeyCount)
		{
			// One kill per new loot key — the loot value is realized when you claim the chest.
			final int kills = count - heldLootKeyCount;
			for (int i = 0; i < kills; i++)
			{
				recordKill();
			}
			capture("kill: +" + kills + " loot key(s), now holding " + count);
		}
		else if (count < heldLootKeyCount && pendingLootValue > 0)
		{
			// Claimed loot — realize the snapshotted chest value once, then reset.
			recordGain(pendingLootValue);
			capture("claimed loot, realized gain " + pendingLootValue);
			pendingLootValue = 0;
		}
		heldLootKeyCount = count;
	}

	/**
	 * Diff the inventory against the previous snapshot to track Bounty Hunter crates: a crate arriving
	 * counts as received; a crate leaving alongside new items is an open — the new items' value is the
	 * crate reward, booked into profit. Skipped while the bank is open (deposits aren't opens).
	 */
	private void detectCrates()
	{
		final Map<Integer, Integer> now = new HashMap<>();
		for (final int cid : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			final ItemContainer c = client.getItemContainer(cid);
			if (c == null)
			{
				continue;
			}
			for (final Item it : c.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					now.merge(it.getId(), it.getQuantity(), Integer::sum);
				}
			}
		}

		// Anything that happens with the bank open is shuffling, not acquisition — a withdrawn
		// crate is not a new crate, and a deposited one was not opened.
		if (inventorySynced && !bankInterfaceOpen)
		{
			// Only tiered kill-crates count as received; supply crates are loot, not kill-crates.
			final int tierDelta = crateCount(now, TIER_CRATES) - crateCount(lastInventory, TIER_CRATES);
			if (tierDelta > 0)
			{
				session.addCrates(tierDelta);
				baseline.addCrates(tierDelta);
				save();
				updatePanel();
				capture("received " + tierDelta + " bounty crate(s)");
			}

			// An open = any crate consumed while items appear. A crate's face value never books
			// to profit anywhere — not at receive, not in a loot claim — so booking the full
			// contents here counts each crate exactly once whether it was earned, bought with
			// points, or found inside another crate (gained crates book when THEY are opened).
			boolean crateConsumed = false;
			for (final int id : ALL_CRATES)
			{
				if (now.getOrDefault(id, 0) < lastInventory.getOrDefault(id, 0))
				{
					crateConsumed = true;
					break;
				}
			}
			if (crateConsumed)
			{
				long reward = 0;
				for (final Map.Entry<Integer, Integer> en : now.entrySet())
				{
					final int dq = en.getValue() - lastInventory.getOrDefault(en.getKey(), 0);
					if (dq > 0 && !isCrate(en.getKey()))
					{
						reward += wealthValue(en.getKey()) * dq;
					}
				}
				if (reward > 0)
				{
					session.addCrateValue(reward);
					baseline.addCrateValue(reward);
					crateFlashGp = reward;
					crateFlashUntil = System.currentTimeMillis() + 5_000;
					save();
					updatePanel();
					capture("opened bounty crate, reward " + reward);
				}
			}

		}
		else if (!inventorySynced)
		{
			inventorySynced = true;
		}

		// Consumables: an inventory drop right after the eat/drink animation books the net value
		// used up as a loss. The animation — not the bank guard — separates eating from
		// depositing, so topping up HP at the bank still counts; the unit cap keeps a same-tick
		// bulk deposit from booking as a meal. Dose transitions pair naturally (brew(4) out,
		// brew(3) in = one dose, net zero units), and the death-tick inventory wipe is excluded
		// (that loss books as the death).
		if (inventorySynced
			&& client.getTickCount() - lastConsumeTick <= 1
			&& client.getTickCount() - lastDeathTick > 2)
		{
			long down = 0;
			int unitsDown = 0;
			for (final Map.Entry<Integer, Integer> en : lastInventory.entrySet())
			{
				final int dq = now.getOrDefault(en.getKey(), 0) - en.getValue();
				if (dq < 0)
				{
					down += wealthValue(en.getKey()) * -dq;
					unitsDown += -dq;
				}
			}
			long up = 0;
			int unitsUp = 0;
			for (final Map.Entry<Integer, Integer> en : now.entrySet())
			{
				final int dq = en.getValue() - lastInventory.getOrDefault(en.getKey(), 0);
				if (dq > 0)
				{
					up += wealthValue(en.getKey()) * dq;
					unitsUp += dq;
				}
			}
			final long consumed = down - up;
			final int units = unitsDown - unitsUp;
			if (consumed > 0 && units <= 2 && inPvpContext())
			{
				session.addConsumed(consumed);
				baseline.addConsumed(consumed);
				save();
				updatePanel();
				capture("consumed " + consumed + " gp of supplies");
			}
			else if (consumed > 0)
			{
				capture("consume skipped: gp=" + consumed + " units=" + units + " pvp=" + inPvpContext());
			}
		}
		lastInventory.clear();
		lastInventory.putAll(now);
	}

	// --- Chugging barrel (pre-pot device) ---

	/**
	 * Sync the barrel's contents from its item container (transmits while banking). Contents
	 * only — chugs are booked from the animation, since the container is silent in the field.
	 */
	private void handleBarrelChanged(ItemContainer c)
	{
		final Map<Integer, Integer> now = new HashMap<>();
		if (c != null)
		{
			for (final Item it : c.getItems())
			{
				if (it.getId() > 0 && it.getQuantity() > 0)
				{
					now.merge(it.getId(), it.getQuantity(), Integer::sum);
					capture("barrel: id=" + it.getId() + " qty=" + it.getQuantity()
						+ " name=" + itemName(it.getId()));
				}
			}
		}
		final long oldGp = barrelGp;
		barrelDoses.clear();
		barrelDoses.putAll(now);
		recomputeBarrelGp();
		saveBarrel();
		capture("barrel value " + oldGp + " -> " + barrelGp);
		recomputeLiveValues();
		updatePanel();
	}

	/** One chug = one dose of every stored potion: book it and shrink the tracked contents. */
	private void bookChug()
	{
		if (barrelDoses.isEmpty())
		{
			capture("chug detected but barrel contents unknown — open your bank once to sync");
			return;
		}
		long value = 0;
		for (final Map.Entry<Integer, Integer> en : barrelDoses.entrySet())
		{
			if (en.getValue() > 0)
			{
				value += perDoseValue(en.getKey());
				en.setValue(en.getValue() - 1);
			}
		}
		recomputeBarrelGp();
		saveBarrel();
		recomputeLiveValues();
		if (value > 0 && inPvpContext())
		{
			session.addConsumed(value);
			baseline.addConsumed(value);
			save();
			capture("chugged barrel: one dose of each, " + value + " gp");
		}
		else if (value > 0)
		{
			capture("chug outside PvP context, not booked (" + value + " gp)");
		}
		updatePanel();
	}

	/** Value of a single dose: the potion item's wealth value split by its dose count. */
	private long perDoseValue(int itemId)
	{
		final long v = wealthValue(itemId);
		final int doses = dosesInName(itemId);
		return doses > 1 ? v / doses : v;
	}

	private int dosesInName(int itemId)
	{
		try
		{
			final Matcher m = DOSES_RE.matcher(itemManager.getItemComposition(itemId).getName());
			return m.find() ? Integer.parseInt(m.group(1)) : 0;
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private void recomputeBarrelGp()
	{
		long total = 0;
		for (final Map.Entry<Integer, Integer> en : barrelDoses.entrySet())
		{
			total += perDoseValue(en.getKey()) * en.getValue();
		}
		barrelGp = total;
	}

	private void saveBarrel()
	{
		if (!profileReady())
		{
			return;
		}
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<Integer, Integer> en : barrelDoses.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(en.getKey()).append(':').append(en.getValue());
		}
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_BARREL, sb.toString());
	}

	private void loadBarrel()
	{
		barrelDoses.clear();
		final String s = configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_BARREL);
		if (s != null && !s.isEmpty())
		{
			for (final String part : s.split(","))
			{
				final int colon = part.indexOf(':');
				if (colon > 0)
				{
					try
					{
						barrelDoses.put(Integer.parseInt(part.substring(0, colon).trim()),
							Integer.parseInt(part.substring(colon + 1).trim()));
					}
					catch (NumberFormatException ignored)
					{
						// skip a malformed entry
					}
				}
			}
		}
		recomputeBarrelGp();
	}

	/** In the Wilderness or on a PvP/BH world — where kills, deaths and consumables count. */
	private boolean inPvpContext()
	{
		if (!config.pvpOnly())
		{
			return true;
		}
		final EnumSet<WorldType> world = client.getWorldType();
		return client.getVarbitValue(Varbits.IN_WILDERNESS) == 1
			|| client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1
			|| world.contains(WorldType.PVP)
			|| world.contains(WorldType.BOUNTY);
	}

	private static int crateCount(Map<Integer, Integer> inv, int[] ids)
	{
		int n = 0;
		for (final int id : ids)
		{
			n += inv.getOrDefault(id, 0);
		}
		return n;
	}

	private static boolean isCrate(int id)
	{
		for (final int c : ALL_CRATES)
		{
			if (c == id)
			{
				return true;
			}
		}
		return false;
	}

	private long valueLootKeyContents()
	{
		long total = 0;
		for (final int container : LOOT_KEY_CONTAINERS)
		{
			final ItemContainer c = client.getItemContainer(container);
			if (c == null)
			{
				continue;
			}
			for (final Item it : c.getItems())
			{
				// Crates in the chest are skipped — their contents book when they're opened.
				if (it.getId() > 0 && it.getQuantity() > 0 && !isCrate(it.getId()))
				{
					total += wealthValue(it.getId()) * it.getQuantity();
				}
			}
		}
		return total;
	}

	private boolean isLootContainer(int id)
	{
		for (final int c : LOOT_KEY_CONTAINERS)
		{
			if (c == id)
			{
				return true;
			}
		}
		return false;
	}

	private void recordKill()
	{
		session.addKill();
		baseline.addKill();
		actual.kills++; // keeps the imported figure current between Edgeville imports
		save();
		updatePanel();
	}

	private void recordGain(long gp)
	{
		session.addGain(gp);
		baseline.addGain(gp);
		save();
		updatePanel();
	}

	private void recordDeath(long lostGp)
	{
		session.addDeath(lostGp);
		baseline.addDeath(lostGp);
		actual.deaths++;
		save();
		updatePanel();
	}

	private void recomputeLiveValues()
	{
		final long inv = value(client.getItemContainer(InventoryID.INV));
		final long worn = value(client.getItemContainer(InventoryID.WORN));
		if (bankOpenedThisLogin)
		{
			// Barrel contents are invisible to the containers, so their value is added here.
			netWorthGp = bankValueGp + inv + worn + barrelGp;
			lastRecordedNetWorth = netWorthGp;
			if (profileReady())
			{
				configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_NET_WORTH, netWorthGp);
			}
		}
		updateRisk();
	}

	/** Wealth value of a container (for net worth / gains): GE for tradeables, high-alch for untradeables. */
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
			total += wealthValue(itemId) * qty;
		}
		return total;
	}

	/**
	 * Per-item wealth value: GE price (variation-aware, so ornamented/BH-corrupted items price at their
	 * real base), or the high-alch value (store value × 0.6) for untradeables — matching Dude, Where's My
	 * Stuff. This is a net-worth figure; the death/risk value is separate (see deathValue).
	 */
	private long wealthValue(int id)
	{
		final long ge = itemManager.getItemPrice(id);
		if (ge > 0)
		{
			return ge;
		}
		try
		{
			return Math.max(0, itemManager.getItemComposition(id).getHaPrice());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	/** Displayed risk: value you'd lose if you died right now. Fully live — recomputed on every change. */
	private void updateRisk()
	{
		final Player me = client.getLocalPlayer();
		final int icon = me == null ? -1 : me.getSkullIcon();
		if (icon != lastSkullIcon)
		{
			lastSkullIcon = icon;
			capture("skull icon -> " + icon
				+ " (risk treats as " + (isSkullIconSkulled(icon) ? "skulled" : "unskulled") + ")");
		}
		final long newRisk = computeRisk();
		if (newRisk != riskGp)
		{
			riskGp = newRisk;
			updatePanel();
		}
	}

	/**
	 * Value you'd lose if you died right now: everything carried, minus the items kept on death — the 3 most
	 * valuable (4 with Protect Item, 0 when skulled, 1 skulled + Protect Item). Ranked and valued by
	 * {@link #deathValue}, with a kept stack protected in full. Recomputes on inventory, prayer and skull
	 * changes, so it's fully live and never jumps.
	 */
	private long computeRisk()
	{
		final List<long[]> items = new ArrayList<>(); // [perItemValue, stackValue]
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
				items.add(new long[]{per, stack});
			}
		}
		items.sort((a, b) -> Long.compare(b[0], a[0]));
		final int kept = keptCount();
		long protectedValue = 0;
		for (int i = 0; i < items.size() && i < kept; i++)
		{
			protectedValue += items.get(i)[1];
		}
		return Math.max(0, total - protectedValue);
	}

	private int keptCount()
	{
		final int base = isSkulled() ? 0 : 3;
		return base + (client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1 ? 1 : 0);
	}

	private boolean isSkulled()
	{
		final Player me = client.getLocalPlayer();
		return me != null && isSkullIconSkulled(me.getSkullIcon());
	}

	/**
	 * Only a genuine PK skull means "keep nothing on death". The skull-icon slot is also used
	 * for loot-key counts, the high-risk-world marker and the fight-pit skull — none of which
	 * change what you keep. Unknown icon ids (the game adds new combined ones faster than the
	 * API names them — 30/31 seen in-game while skulled) are treated as skulled, the safer
	 * direction for a risk estimate.
	 */
	private static boolean isSkullIconSkulled(int icon)
	{
		switch (icon)
		{
			case SkullIcon.NONE:
			case SkullIcon.SKULL_FIGHT_PIT:
			case SkullIcon.SKULL_HIGH_RISK:
			case SkullIcon.LOOT_KEYS_ONE:
			case SkullIcon.LOOT_KEYS_TWO:
			case SkullIcon.LOOT_KEYS_THREE:
			case SkullIcon.LOOT_KEYS_FOUR:
			case SkullIcon.LOOT_KEYS_FIVE:
				return false;
			default:
				return true;
		}
	}

	/**
	 * Per-item value lost on death: GE price if tradeable, else the untradeable's repair-on-death cost
	 * (from {@code reclaim-costs.csv}, matched by name), else its store value as a fallback.
	 */
	private long deathValue(int id)
	{
		final long ge = itemManager.getItemPrice(id);
		if (ge > 0)
		{
			return ge;
		}
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(id);
			final Long repair = repairCosts.get(comp.getName().toLowerCase());
			if (repair != null)
			{
				return repair;
			}
			return Math.max(0, comp.getPrice());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	/** Load the untradeable repair-on-death costs from the bundled CSV (item name -> cost). */
	private void loadRepairCosts()
	{
		repairCosts.clear();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
			getClass().getResourceAsStream("reclaim-costs.csv"), java.nio.charset.StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				final int comma = line.lastIndexOf(',');
				if (comma <= 0)
				{
					continue;
				}
				try
				{
					repairCosts.put(line.substring(0, comma).trim().toLowerCase(),
						Long.parseLong(line.substring(comma + 1).trim()));
				}
				catch (NumberFormatException ignored)
				{
					// skip a malformed line
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Could not load reclaim-costs.csv", e);
		}
	}

	// --- Persistence (per RuneScape profile) ---

	private void load()
	{
		final String profile = configManager.getRSProfileKey();
		if (profile == null || profile.equals(loadedProfileKey))
		{
			return; // no profile known yet, or this profile's tallies are already live — don't clobber them
		}
		baseline.copyFrom(readStats(K_BASELINE));
		actual.copyFrom(readStats(K_ACTUAL));
		final String nw = configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_NET_WORTH);
		lastRecordedNetWorth = nw == null ? -1 : parseLong(nw);
		loadBarrel();
		loadedProfileKey = profile;
		updatePanel();
	}

	private Stats readStats(String key)
	{
		final String json = configManager.getRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, key);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, Stats.class);
		}
		catch (RuntimeException e)
		{
			log.warn("Could not parse saved '{}' tallies", key, e);
			return null;
		}
	}

	/** True once this profile's tallies are loaded and the same profile is still active. */
	private boolean profileReady()
	{
		return loadedProfileKey != null && loadedProfileKey.equals(configManager.getRSProfileKey());
	}

	private void save()
	{
		if (!profileReady())
		{
			return; // never loaded (don't persist defaults) or the profile changed — don't write to the wrong one
		}
		// Stored as explicit JSON — ConfigManager round-trips strings reliably; arbitrary objects it does not.
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_BASELINE, gson.toJson(baseline));
		configManager.setRSProfileConfiguration(PvpProfitTrackerConfig.GROUP, K_ACTUAL, gson.toJson(actual));
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

	public void resetBaselineKd()
	{
		baseline.resetKd();
		save();
		updatePanel();
	}

	public void resetBaselineProfit()
	{
		baseline.resetProfit();
		save();
		updatePanel();
	}

	public void resetBaselineCrates()
	{
		baseline.resetCrates();
		save();
		updatePanel();
	}

	public void resetBaselinePoints()
	{
		baseline.resetPoints();
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

	Stats getBaseline()
	{
		return baseline;
	}

	Stats getActual()
	{
		return actual;
	}

	long getRiskGp()
	{
		return riskGp;
	}

	/** Value of the potions stored in the chugging barrel (0 until its configure UI is opened once). */
	long getBarrelGp()
	{
		return barrelGp;
	}

	/** Net worth for display: live once the bank was opened, else the last recorded value, else a prompt. */
	String netWorthDisplay()
	{
		if (bankOpenedThisLogin)
		{
			return fmt(netWorthGp);
		}
		if (lastRecordedNetWorth >= 0)
		{
			return fmt(lastRecordedNetWorth) + " (saved)"; // last recorded value; live again on bank open
		}
		return "Open bank first";
	}

	/** The player's actual Bounty Hunter points balance from the game's varp, or "—" before login. */
	String currentBhPointsDisplay()
	{
		return bhPointsSynced ? Integer.toString(lastBhPoints) : "—";
	}

	/** Crate reward currently being flashed on the panel, or 0 when the 5-second window has passed. */
	long crateFlashGp()
	{
		return System.currentTimeMillis() < crateFlashUntil ? crateFlashGp : 0;
	}

	int crateFlashSecondsLeft()
	{
		final long ms = crateFlashUntil - System.currentTimeMillis();
		return ms > 0 ? (int) Math.ceil(ms / 1000.0) : 0;
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

	/** Format a gp value per the user's number-format setting (full or compact). */
	String fmt(long v)
	{
		return config.gpFormat().format(v);
	}

	/** Exact gp with thousands separators, e.g. 1,378,016 (used in breakdown tooltips). */
	static String gpFull(long v)
	{
		return String.format("%,d", v);
	}

	/** K/D display: kills/deaths with the ratio, e.g. 12/3 (4.00). */
	static String kdText(Stats s)
	{
		return s.kills + "/" + s.deaths + " (" + String.format("%.2f", s.kd()) + ")";
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
