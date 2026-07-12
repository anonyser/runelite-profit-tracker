package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.SkullIcon;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.hiscore.HiscoreManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "PvP Profit Tracker",
	description = "Tracks real PvP profit — loot-key gains minus what you lose on death and consumables — "
		+ "with K/D, risk and net worth. Details and baseline resets on the side panel (skulls icon)",
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
	// Global (not per-profile): what a skull-slot icon id means is the game's, not the character's.
	private static final String K_SKULL_ICON_MAP = "skullIconMap";
	// Global: item ids the death screen proved the game protects ahead of our valuation, with the
	// highest value each was seen protected over — a floor on the game's own ranking value for it,
	// NOT a claim it outranks everything (a bigger item still wins the protected slot).
	private static final String K_KEEP_PRIORITY = "keepPriorityFloors";
	// No known members: the one suspected case (Crimson kisten) turned out to be a genuinely
	// tradeable 11M weapon the price feed didn't know yet. The learning stays as armor.
	private static final long[][] KEEP_PRIORITY_DEFAULTS = {};

	// Bounty Hunter ancient-warrior gear is activated by feeding it coins; on a PvP death the
	// killer receives the activation fee and the owner keeps the inactive item — so the fee IS
	// the item's risked value (and its wealth: deactivating refunds the coins). The live price
	// feed knows none of these (untradeable), so without this table they'd fall back to store
	// values and understate risk by millions. Fees as of the 29 Jan 2025 rebalance; a user
	// price override still wins over this table. The inactive weapon forms (27902, 27906, …)
	// stay unlisted — they hold no coins. BH imbues (dark bow/anchor/mace/dagger, 27853–27867)
	// are a different mechanic and are deliberately not here.
	private static final Map<Integer, Long> BH_ACTIVATION_FEES = new HashMap<>();
	static
	{
		// Every fee verified against the item's own wiki page (2026-07-03).
		BH_ACTIVATION_FEES.put(27900, 5_000_000L);  // Vesta's spear (bh)
		BH_ACTIVATION_FEES.put(27904, 10_000_000L); // Vesta's longsword (bh)
		BH_ACTIVATION_FEES.put(27908, 10_000_000L); // Statius's warhammer (bh)
		BH_ACTIVATION_FEES.put(27912, 5_000_000L);  // Morrigan's throwing axe (bh)
		BH_ACTIVATION_FEES.put(27916, 10_000_000L); // Morrigan's javelin (bh)
		BH_ACTIVATION_FEES.put(27920, 5_000_000L);  // Zuriel's staff (bh)
		for (int id = 27831; id <= 27841; id++)     // armour, 5M apiece
		{
			BH_ACTIVATION_FEES.put(id, 5_000_000L);
		}
		for (int id = 27842; id <= 27852; id++)     // corrupted armour, 1.5M apiece
		{
			BH_ACTIVATION_FEES.put(id, 1_500_000L);
		}
	}

	// Bounty Hunter worlds replace the skull slot with risk-tier icons — one band of ids for
	// unskulled players, one for skulled, none of them named in the API. Verified in-game:
	// 21–24 unskulled and 29–32 skulled (2026-07-02); on 2026-07-04 a tracked opponent went
	// skulled icon 28 → died → unskulled icon 21 with a sub-200k kit both sides, anchoring
	// bronze at 21/28 — so the bands are 21–25 unskulled / 28–32 skulled (+7, not the +8 first
	// guessed). 25 (unskulled red) is whitelisted from that scheme; if the inference is ever
	// wrong the death screen re-teaches it (see calibrateSkullFromDeathScreen).
	private static final int BH_UNSKULLED_FIRST = 21;
	private static final int BH_UNSKULLED_LAST = 25;

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

	// The eat/drink animation (confirmed in-game against food and potions alike).
	private static final int CONSUME_ANIMATION = 829;

	// Trailing dose marker in potion names, e.g. "Saradomin brew(4)".
	private static final Pattern DOSES_RE = Pattern.compile("\\((\\d)\\)\\s*$");

	// The BH hub's target readout: "Name (combatLevel)" — format captured live 2026-07-04.
	private static final Pattern BH_TARGET_LINE = Pattern.compile("(.+) \\((\\d{1,3})\\)");

	// One-time in-game note after an update ships — keep in step with the build.gradle version.
	private static final String PLUGIN_VERSION = "1.1.1";
	private static final String K_ANNOUNCED = "announcedVersion";

	// Drinking from the chugging barrel (confirmed in-game; distinct from the eat/drink 829).
	private static final int CHUG_ANIMATION = 11645;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

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

	@Inject
	private HiscoreManager hiscoreManager;

	@Inject
	private net.runelite.client.game.SpriteManager spriteManager;

	@Inject
	private net.runelite.client.chat.ChatMessageManager chatMessageManager;

	// Untradeable repair-on-death costs (item name -> cost), loaded from reclaim-costs.csv.
	private final Map<String, Long> repairCosts = new HashMap<>();

	// Overlay and panel are created manually (they reference the plugin) to avoid circular DI.
	private PvpProfitTrackerOverlay overlay;
	private PvpProfitTrackerPanel panel;
	private NavigationButton navButton;

	// Focused-player gear inspect (right-click "Inspect" on a player, or a BH target).
	private OpponentTracker opponentTracker;
	private CombatCalc combatCalc;
	// Written on the client thread each tick; volatile so the overlay/panel read a coherent one.
	private volatile CombatCalc.MaxHitInfo maxHitInfo;
	// Auto-focus: the target name last read off the BH HUD, and one still awaiting a scene match.
	private String lastBhTargetName;
	private String pendingAutoFocusName;
	private boolean pendingUpdateNote;
	// The target hub interface, self-discovered by its "No Target" idle text (falls back to the
	// PVP_ICONS group until seen). Probe fields mirror the K/D scan's load-then-wait pattern.
	private int bhHudGroup = -1;
	private int bhHudProbeGroup = -1;
	private int bhHudProbeCountdown;
	private int lastBhHudTextHash;
	private int lastBhHudDumpTick = -1000;

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

	// Skull-slot icon ids whose skulled/unskulled reading was corrected by the game's own Items
	// Kept on Death screen (icon id -> skulled). Persisted globally; see calibrateSkullFromDeathScreen.
	private final Map<Integer, Boolean> skullIconLearned = new HashMap<>();

	// Ranking floors for items the game's death screen kept while our valuation ranked them below
	// a lost item. Kept-prediction ranks by max(deathValue, floor). Persisted globally; learned
	// in calibrateSkullFromDeathScreen.
	private final Map<Integer, Long> keepPriorityFloor = new HashMap<>();

	// User-supplied prices (item id -> gp) that take precedence over the live price feed — for
	// brand-new items the feed doesn't know yet, or prices the user disagrees with.
	private final Map<Integer, Long> priceOverrides = new HashMap<>();

	private boolean deathKeepCalibrated = true; // false only while an open death screen awaits a read
	private int kdScanGroup = -1;
	private int kdScanCountdown;

	@Override
	protected void startUp()
	{
		try
		{
			loadRepairCosts();
			loadSkullIconMap();
			loadKeepPriority();
			loadPriceOverrides();
			sessionStart = Instant.now();
			overlay = new PvpProfitTrackerOverlay(this, config);
			overlayManager.add(overlay);
			opponentTracker = new OpponentTracker(client, hiscoreManager, this, this::updateOpponentPanel);
			combatCalc = new CombatCalc(client, itemManager);
			panel = new PvpProfitTrackerPanel(this, config);
			navButton = NavigationButton.builder()
				.tooltip("PvP Profit Tracker")
				.icon(icon())
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);

			// If enabled while already logged in, load this profile's saved tallies now
			// (self-guarded). Must run on the client thread: the plugin-list toggle calls
			// startUp on the EDT, and the barrel recompute prices items through the client's
			// item cache, which asserts client-thread.
			clientThread.invoke(this::load);
		}
		catch (Error | RuntimeException e)
		{
			log.error("startUp failed", e);
			throw e;
		}
	}

	@Override
	protected void shutDown()
	{
		try
		{
			save();
			overlayManager.remove(overlay);
			clientToolbar.removeNavigation(navButton);
			overlay = null;
			opponentTracker = null;
			combatCalc = null;
			maxHitInfo = null;
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
		catch (Error | RuntimeException e)
		{
			log.error("shutDown failed", e);
			throw e;
		}
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
			deathKeepCalibrated = true; // stop any pending death-screen read
			lastBhTargetName = null;
			pendingAutoFocusName = null;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			// The profile-changed event can fire before the state reaches LOGGED_IN — load from
			// whichever happens last so the tallies always arm (self-guarded against re-loads).
			ticksSinceLogin = 0;
			load();
			pendingUpdateNote = !PLUGIN_VERSION.equals(
				configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, K_ANNOUNCED));
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
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		final Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		final String focused = opponentTracker == null ? null : opponentTracker.focusedName();
		if (e.getActor() == me)
		{
			// Book the current at-risk value as the loss — it shows up as negative profit.
			lastDeathTick = client.getTickCount();
			// Never diff the inventory across a death: the wipe-and-restore shuffle looks like
			// crates being opened and items appearing. Resync fresh from the next change instead.
			inventorySynced = false;
			recordDeath(riskGp);
			// W/L vs the focused opponent: only count the loss while they're actually
			// in sight — dying somewhere else isn't their kill.
			if (focused != null)
			{
				final OpponentTracker.Snapshot opp = opponentTracker.snapshot();
				if (opp != null && opp.visible)
				{
					bumpOpponentRecord(focused, false);
				}
			}
			return;
		}
		// Their death while focused = a win for you (name match is exact).
		if (focused != null && e.getActor() instanceof Player
			&& focused.equals(OpponentTracker.sanitizedName((Player) e.getActor())))
		{
			bumpOpponentRecord(focused, true);
		}
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
			}
		}
		else
		{
			bhPointsSynced = true; // first transmit after login is the existing total, not a gain
		}
		lastBhPoints = now;
	}

	/**
	 * Add a "Risk" option to every other player on an open right-click menu. Selecting it focuses
	 * that player in the opponent-risk overlay/panel. Added only when the menu is already open, so
	 * it can never hijack a left click — and inserted directly BELOW that player's topmost option:
	 * Attack must stay first (in-game feedback — an entry above Attack invites deadly misclicks).
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened e)
	{
		if (opponentTracker == null || !config.opponentRisk())
		{
			return;
		}
		// Entries render top-down from the END of the array, so a player's topmost option is
		// their highest index. Inserting AT that index puts Risk immediately below it.
		final MenuEntry[] entries = e.getMenuEntries();
		final Map<Player, Integer> topEntry = new HashMap<>();
		for (int i = 0; i < entries.length; i++)
		{
			final Player p = entries[i].getPlayer();
			if (p != null && p != client.getLocalPlayer())
			{
				topEntry.put(p, i);
			}
		}
		// Insert highest index first: every later insertion happens strictly below, so the
		// remaining recorded indexes stay valid.
		final List<Map.Entry<Player, Integer>> order = new ArrayList<>(topEntry.entrySet());
		order.sort((a, b) -> b.getValue() - a.getValue());
		for (final Map.Entry<Player, Integer> en : order)
		{
			final Player p = en.getKey();
			client.getMenu().createMenuEntry(en.getValue())
				.setOption("Inspect")
				.setTarget(entries[en.getValue()].getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(me ->
				{
					opponentTracker.focus(p);
					updateOpponentPanel();
					// Inspecting a player also opens the side panel for the hiscore lookup, the same
					// way an assigned Bounty Hunter target auto-opens it.
					SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
				});
		}
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged e)
	{
		// Fires when a player's composition changes — the live gear-swap signal for the opponent.
		if (opponentTracker != null && config.opponentRisk() && opponentTracker.isFocused(e.getPlayer()))
		{
			opponentTracker.refresh(e.getPlayer());
			updateOpponentPanel();
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned e)
	{
		if (opponentTracker != null)
		{
			opponentTracker.markDespawned(e.getPlayer());
		}
	}

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
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.DEATHKEEP)
		{
			deathKeepCalibrated = false; // calibrate every tick until the kept box is readable
		}
		else if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = true;
		}
		// Any opened interface might be the Kill Death Ratio window — scan it once its text has loaded.
		kdScanGroup = e.getGroupId();
		kdScanCountdown = 2;
		// It might also be the BH target hub — recognisable idle ("No Target") when no target is up.
		bhHudProbeGroup = e.getGroupId();
		bhHudProbeCountdown = 2;
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = false;
		}
		else if (e.getGroupId() == InterfaceID.DEATHKEEP)
		{
			deathKeepCalibrated = true;
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
		if (opponentTracker != null && config.opponentRisk())
		{
			opponentTracker.onTick();
			if (config.autoFocusTarget())
			{
				maybeAutoFocusBhTarget();
			}
		}
		// The player's OWN max hit, re-read every tick: gear, prayers and boosts all change
		// without a single dedicated event.
		maxHitInfo = combatCalc != null && config.showMaxHit() ? combatCalc.ownMaxHit() : null;
		if (!deathKeepCalibrated)
		{
			deathKeepCalibrated = calibrateSkullFromDeathScreen();
		}
		if (kdScanCountdown > 0 && --kdScanCountdown == 0)
		{
			maybeImportActualKd(kdScanGroup);
		}
		if (pendingUpdateNote && ticksSinceLogin >= 4)
		{
			pendingUpdateNote = false;
			announceUpdate();
		}
		if (bhHudProbeCountdown > 0 && --bhHudProbeCountdown == 0 && bhHudGroup < 0)
		{
			final StringBuilder sb = new StringBuilder();
			for (int child = 0; child < 80; child++)
			{
				collectText(client.getWidget(bhHudProbeGroup, child), sb, 0);
			}
			if (sb.toString().toLowerCase().contains("no target"))
			{
				bhHudGroup = bhHudProbeGroup;
				log.debug("BH target hub found: interface {}", bhHudGroup);
			}
		}
	}

	String itemName(int id)
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

	/**
	 * One in-game chat note per shipped version, the way many plugins announce updates — sent a
	 * few ticks after login so it doesn't get lost in the login burst, then never again for this
	 * version (global config key).
	 */
	private void announceUpdate()
	{
		final String message = new net.runelite.client.chat.ChatMessageBuilder()
			.append(net.runelite.client.chat.ChatColorType.HIGHLIGHT)
			.append("PvP Profit Tracker " + PLUGIN_VERSION + ": ")
			.append(net.runelite.client.chat.ChatColorType.NORMAL)
			.append("right-click Inspect on a player now opens the side panel for the hiscore "
				+ "lookup (it used to only open when you got a Bounty Hunter target), and the "
				+ "special attack max hit is fixed for a few BH weapons: Statius warhammer, "
				+ "barrelchest anchor and abyssal dagger.")
			.build();
		chatMessageManager.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(net.runelite.api.ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, K_ANNOUNCED, PLUGIN_VERSION);
	}

	// --- Auto-focus a new Bounty Hunter target ---

	/**
	 * The BH HUD (interface 90, PVP_ICONS) carries the target's name, but the API names no child
	 * for it — so every text in the group is scanned: a "Target: name" line wins outright, else
	 * any line that equals the name of another player in the scene. On a NEW name the plugin's
	 * side panel opens immediately (the PvP Performance Tracker behaviour Mark asked to inherit),
	 * and the player is focused as soon as they can be matched in the scene.
	 */
	private void maybeAutoFocusBhTarget()
	{
		// Until the hub is pinned, periodically sweep every LOADED interface for its idle text —
		// the WidgetLoaded probe misses a hub that loaded before the plugin was watching, and an
		// in-game session proved the group-90 assumption alone isn't enough.
		if (bhHudGroup < 0 && client.getTickCount() % 5 == 0)
		{
			for (final Widget root : client.getWidgetRoots())
			{
				if (root == null)
				{
					continue;
				}
				final StringBuilder rootText = new StringBuilder();
				collectText(root, rootText, 0);
				if (rootText.toString().toLowerCase().contains("no target"))
				{
					bhHudGroup = WidgetUtil.componentToInterface(root.getId());
					log.debug("BH target hub found by sweep: interface {}", bhHudGroup);
					break;
				}
			}
		}
		final StringBuilder sb = new StringBuilder();
		final int group = bhHudGroup >= 0 ? bhHudGroup : InterfaceID.PVP_ICONS;
		for (int child = 0; child < 80; child++)
		{
			collectText(client.getWidget(group, child), sb, 0);
		}
		final String hudText = sb.toString();
		// The hub idles as "No Target" with dashes (screenshot-verified) — that is the explicit
		// no-target state, which also re-arms detection for the next assignment.
		if (hudText.toLowerCase().contains("no target"))
		{
			lastBhTargetName = null;
			return;
		}
		String name = null;
		final Player me = client.getLocalPlayer();
		final String myName = me == null || me.getName() == null ? ""
			: Text.toJagexName(Text.removeTags(me.getName()));
		for (final String rawLine : hudText.split("\n"))
		{
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("-"))
			{
				continue;
			}
			if (line.toLowerCase().startsWith("target"))
			{
				line = line.replaceFirst("(?i)target:?", "").trim();
				if (line.isEmpty() || "none".equalsIgnoreCase(line))
				{
					continue;
				}
			}
			// The target readout is "Name (combatLevel)" — live-captured format. Trusting it
			// needs no scene presence: an assignment was once watched expire while the target
			// stayed beyond render distance the whole walk in.
			final Matcher m = BH_TARGET_LINE.matcher(line);
			if (m.matches())
			{
				final String candidate = Text.toJagexName(m.group(1).trim());
				if (!candidate.equalsIgnoreCase(myName))
				{
					name = candidate;
					break;
				}
			}
			// Fallback: any hub line that IS the name of a player in the scene.
			final int paren = line.indexOf(" (");
			final String candidate = paren > 0 ? line.substring(0, paren) : line;
			if (scenePlayerNamed(candidate) != null)
			{
				name = Text.toJagexName(candidate);
				break;
			}
		}
		if (name == null)
		{
			// Nothing parseable. Record what the hub shows — but hash only the non-timer lines
			// and rate-limit to one dump a minute: a lobby countdown once changed the raw text
			// every tick and dumped with it.
			final StringBuilder candidates = new StringBuilder();
			for (final String rawLine : hudText.split("\n"))
			{
				final String line = rawLine.trim();
				if (!line.isEmpty() && !line.startsWith("-") && !line.matches(".*\\d+:\\d+.*"))
				{
					candidates.append(line).append('|');
				}
			}
			final int hash = candidates.toString().hashCode();
			if (hash != lastBhHudTextHash && client.getTickCount() - lastBhHudDumpTick > 100
				&& candidates.length() > 0)
			{
				lastBhHudTextHash = hash;
				lastBhHudDumpTick = client.getTickCount();
				log.debug("BH hud text (no target parsed, interface {}): {}", group, candidates);
			}
			lastBhTargetName = null; // no readable target — re-arm for the next assignment
			return;
		}
		if (!name.equals(lastBhTargetName))
		{
			lastBhTargetName = name;
			pendingAutoFocusName = name;
			log.debug("BH target detected on the HUD: {}", name);
			// Only jump the sidebar when this is genuinely a new opponent — a target walking
			// out of render range and back must not keep re-opening the panel mid-fight.
			if (!name.equals(opponentTracker.focusedName()))
			{
				opponentTracker.focusName(name); // hiscore preview starts before they render
				updateOpponentPanel();
				SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
			}
		}
		if (pendingAutoFocusName != null)
		{
			final Player p = scenePlayerNamed(pendingAutoFocusName);
			if (p != null)
			{
				pendingAutoFocusName = null;
				opponentTracker.focus(p);
				updateOpponentPanel();
				log.debug("BH target auto-focused: {}", name);
			}
		}
	}

	private Player scenePlayerNamed(String name)
	{
		final String jagex = Text.toJagexName(name);
		final Player me = client.getLocalPlayer();
		for (final Player p : client.getTopLevelWorldView().players())
		{
			if (p != null && p != me && p.getName() != null
				&& Text.toJagexName(Text.removeTags(p.getName())).equalsIgnoreCase(jagex))
			{
				return p;
			}
		}
		return null;
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
			return;
		}
		actual.kills = Long.parseLong(k.group(1).replace(",", ""));
		actual.deaths = Long.parseLong(d.group(1).replace(",", ""));
		save();
		updatePanel();
		log.debug("imported actual K/D {}/{} from interface {}", actual.kills, actual.deaths, groupId);
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
		}
		else if (count < heldLootKeyCount && pendingLootValue > 0)
		{
			// Claimed loot — realize the snapshotted chest value once, then reset.
			recordGain(pendingLootValue);
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
				}
			}
		}
		barrelDoses.clear();
		barrelDoses.putAll(now);
		recomputeBarrelGp();
		saveBarrel();
		recomputeLiveValues();
		updatePanel();
	}

	/** One chug = one dose of every stored potion: book it and shrink the tracked contents. */
	private void bookChug()
	{
		if (barrelDoses.isEmpty())
		{
			return; // contents unknown until a bank visit transmits the container
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
		return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
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
		final long ge = feedPrice(id);
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

	/**
	 * Price for an item: the user's configured override if set (their price wins — covers
	 * brand-new items the feed doesn't know yet, seen with the Crimson kisten pricing at 0 for
	 * days, and prices the user disagrees with), else the live feed price, else the built-in
	 * Bounty Hunter activation fee (activated gear embodies the coins fed into it).
	 */
	private long feedPrice(int id)
	{
		final Long override = priceOverrides.get(id);
		if (override != null)
		{
			return override;
		}
		final long ge = itemManager.getItemPrice(id);
		if (ge > 0)
		{
			return ge;
		}
		return BH_ACTIVATION_FEES.getOrDefault(id, 0L);
	}

	/** Displayed risk: value you'd lose if you died right now. Fully live — recomputed on every change. */
	private void updateRisk()
	{
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
		final List<long[]> items = rankedCarriedItems();
		long total = 0;
		for (final long[] it : items)
		{
			total += it[1];
		}
		final int kept = keptCount();
		long protectedValue = 0;
		for (int i = 0; i < items.size() && i < kept; i++)
		{
			protectedValue += items.get(i)[1];
		}
		return Math.max(0, total - protectedValue);
	}

	/**
	 * Carried items as [rankValue, stackValue, itemId], sorted in the order the game protects
	 * them. rankValue = max(per-item death value, learned keep-priority floor) — the game values
	 * some untradeables far above any price we can see; stackValue stays our real loss value.
	 * Zero-value items are dropped unless a floor says the game may protect them — then they
	 * still consume a kept slot.
	 */
	private List<long[]> rankedCarriedItems()
	{
		final List<long[]> items = new ArrayList<>();
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
				final long rank = Math.max(per, keepPriorityFloor.getOrDefault(id, 0L));
				if (rank <= 0)
				{
					continue;
				}
				items.add(new long[]{rank, per * qty, id});
			}
		}
		items.sort((a, b) -> Long.compare(b[0], a[0]));
		return items;
	}

	private int keptCount()
	{
		final int base = isSkulled() ? 0 : 3;
		return base + (protectItemOn() ? 1 : 0);
	}

	/** Learned keep-priority floor for an item (0 when none) — see rankedCarriedItems. */
	long keepFloor(int id)
	{
		return keepPriorityFloor.getOrDefault(id, 0L);
	}

	/** Focused-player gear/stat view for the side panel (EDT-safe snapshot; null = no focus). */
	OpponentTracker.Snapshot opponentSnapshot()
	{
		final OpponentTracker t = opponentTracker;
		return t == null ? null : t.snapshot();
	}

	/** The player's own current max hit (null when the line is hidden). */
	CombatCalc.MaxHitInfo maxHitInfo()
	{
		return maxHitInfo;
	}

	/** Item sprite for the side panel — async, so safe to request from the EDT. */
	net.runelite.client.util.AsyncBufferedImage itemIcon(int id)
	{
		return itemManager.getImage(id);
	}

	/** Async-loads a game sprite onto a panel label — the same icons the core hiscore panel shows. */
	void spriteIcon(javax.swing.JLabel label, int spriteId)
	{
		spriteManager.addSpriteTo(label, spriteId, 0);
	}

	// ---- per-opponent notes: keyed by sanitized player name, kept in config so
	// they survive restarts and come back the next time you face that player ----

	private static String noteKey(String playerName)
	{
		return "oppnote_" + playerName.toLowerCase().replaceAll("[^a-z0-9]", "_");
	}

	/** The saved note for this player, or an empty string. */
	String opponentNote(String playerName)
	{
		final String note =
			configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, noteKey(playerName));
		return note == null ? "" : note;
	}

	/** Save (or clear, when blank) the note for this player. Safe from the EDT. */
	void saveOpponentNote(String playerName, String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			configManager.unsetConfiguration(PvpProfitTrackerConfig.GROUP, noteKey(playerName));
		}
		else
		{
			configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, noteKey(playerName), text);
		}
	}

	private static String wlKey(String playerName)
	{
		return "oppwl_" + playerName.toLowerCase().replaceAll("[^a-z0-9]", "_");
	}

	/** Your lifetime record against this player: [your kills on them, their kills on you]. */
	int[] opponentRecord(String playerName)
	{
		final String s =
			configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, wlKey(playerName));
		if (s == null)
		{
			return new int[]{0, 0};
		}
		final String[] parts = s.split("/");
		try
		{
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
		}
		catch (NumberFormatException | ArrayIndexOutOfBoundsException ex)
		{
			return new int[]{0, 0};
		}
	}

	private void bumpOpponentRecord(String playerName, boolean win)
	{
		final int[] record = opponentRecord(playerName);
		if (win)
		{
			record[0]++;
		}
		else
		{
			record[1]++;
		}
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, wlKey(playerName),
			record[0] + "/" + record[1]);
		if (panel != null)
		{
			panel.updateOpponent();
		}
	}

	/** Plain GE price for the gear-inspect view — Equipment Inspector parity, no overrides. */
	long gePrice(int id)
	{
		return Math.max(0, itemManager.getItemPrice(id));
	}

	/** Drop the focused opponent. Safe from any thread (the panel's Clear button runs on the EDT). */
	void clearOpponent()
	{
		clientThread.invoke(() ->
		{
			if (opponentTracker != null)
			{
				opponentTracker.clear();
			}
			maxHitInfo = null;
		});
	}

	/**
	 * Panel hiscore lookup: focus a typed name, stats only — no gear view, no target colouring.
	 * Safe from any thread (the panel's lookup field runs on the EDT).
	 */
	void lookupOpponent(String name)
	{
		clientThread.invoke(() ->
		{
			if (opponentTracker != null)
			{
				opponentTracker.lookupName(name);
			}
		});
	}

	/** Whether the Protect Item prayer is active right now. Game-thread only (varbit read). */
	boolean protectItemOn()
	{
		return client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1;
	}

	/** Whether the player is inside the Wilderness. Game-thread only (varbit read). */
	boolean inWilderness()
	{
		return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
	}

	private boolean isSkulled()
	{
		final Player me = client.getLocalPlayer();
		return me != null && isSkullIconSkulled(me.getSkullIcon());
	}

	/**
	 * Only a genuine PK skull means "keep nothing on death". The skull-icon slot is also used
	 * for loot-key counts, the high-risk-world marker, the fight-pit skull — and, on Bounty
	 * Hunter worlds, risk-tier icons with separate unskulled and skulled bands (unnamed in the
	 * API; see BH_UNSKULLED_FIRST). Ids the game's own Items Kept on Death screen has
	 * contradicted are taken from the learned map instead. Anything still unknown is treated
	 * as skulled, the safer direction for the risk value.
	 */
	boolean isSkullIconSkulled(int icon)
	{
		final Boolean learned = skullIconLearned.get(icon);
		if (learned != null)
		{
			return learned;
		}
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
				return icon < BH_UNSKULLED_FIRST || icon > BH_UNSKULLED_LAST;
		}
	}

	/** Icon ids the API names — their meaning is known, so the death screen never overrides them. */
	private static boolean isNamedSkullIcon(int icon)
	{
		switch (icon)
		{
			case SkullIcon.NONE:
			case SkullIcon.SKULL:
			case SkullIcon.SKULL_FIGHT_PIT:
			case SkullIcon.SKULL_HIGH_RISK:
			case SkullIcon.FORINTHRY_SURGE:
			case SkullIcon.SKULL_DEADMAN:
			case SkullIcon.LOOT_KEYS_ONE:
			case SkullIcon.LOOT_KEYS_TWO:
			case SkullIcon.LOOT_KEYS_THREE:
			case SkullIcon.LOOT_KEYS_FOUR:
			case SkullIcon.LOOT_KEYS_FIVE:
			case SkullIcon.FORINTHRY_SURGE_DEADMAN:
			case SkullIcon.FORINTHRY_SURGE_KEYS_ONE:
			case SkullIcon.FORINTHRY_SURGE_KEYS_TWO:
			case SkullIcon.FORINTHRY_SURGE_KEYS_THREE:
			case SkullIcon.FORINTHRY_SURGE_KEYS_FOUR:
			case SkullIcon.FORINTHRY_SURGE_KEYS_FIVE:
				return true;
			default:
				return false;
		}
	}

	/**
	 * The Items Kept on Death screen is the game's own statement of what would be kept right now:
	 * with 5+ items carried, 3/4 kept means unskulled and 0/1 means skulled. Use it to verify the
	 * skull reading behind the Risk line and to correct icon ids the API doesn't name (the Bounty
	 * Hunter risk-tier icons). Tried every tick while the screen is open (the widgets can take a
	 * few ticks to populate and quick peeks close the screen fast); returns true once a read
	 * happened, false to retry next tick. A reading whose Protect Item half disagrees with the
	 * real prayer varbit is discarded as not-current-state.
	 */
	private boolean calibrateSkullFromDeathScreen()
	{
		final Player me = client.getLocalPlayer();
		if (me == null)
		{
			return true;
		}
		final int icon = me.getSkullIcon();
		int carried = 0;
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
					carried++;
				}
			}
		}
		final List<Integer> gameKept = deathScreenKeptItemIds();
		if (gameKept == null)
		{
			return false; // kept box not populated yet — retry next tick while the screen is open
		}
		if (carried < 5)
		{
			return true; // too few items to pin the skull state (kept counts are ambiguous)
		}
		final int kept = gameKept.size();
		final boolean protect = client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1;
		final Boolean skulled;
		if (kept == (protect ? 4 : 3))
		{
			skulled = false;
		}
		else if (kept == (protect ? 1 : 0))
		{
			skulled = true;
		}
		else
		{
			skulled = null; // doesn't match the live Protect Item state — stale or what-if display
		}
		if (skulled == null)
		{
			return true;
		}
		if (!isNamedSkullIcon(icon) && skulled != isSkullIconSkulled(icon))
		{
			skullIconLearned.put(icon, skulled);
			saveSkullIconMap();
			log.debug("death screen: learned skull icon {} = {}", icon, skulled ? "skulled" : "unskulled");
			updateRisk();
		}
		learnKeepPriority(gameKept);
		return true;
	}

	/**
	 * Learn the game's ranking floors: an item in the kept box that our predicted kept set missed
	 * must rank above the best item the game passed over (e.g. the Crimson kisten, kept over a
	 * 1.19M atlatl despite a 240k store value — so its floor is 1,186,537, not infinity: a still
	 * bigger item would win the protected slot back).
	 */
	private void learnKeepPriority(List<Integer> gameKept)
	{
		final List<long[]> ranked = rankedCarriedItems();
		final int slots = keptCount();
		final Set<Integer> predicted = new HashSet<>();
		for (int i = 0; i < ranked.size() && i < slots; i++)
		{
			predicted.add((int) ranked.get(i)[2]);
		}
		long bestLost = 0; // the strongest rank the game passed over — a learned item beats it
		for (final long[] it : ranked)
		{
			if (!gameKept.contains((int) it[2]))
			{
				bestLost = Math.max(bestLost, it[0]);
			}
		}
		boolean changed = false;
		for (final int id : gameKept)
		{
			if (predicted.contains(id))
			{
				continue;
			}
			final long floor = bestLost + 1;
			final Long known = keepPriorityFloor.get(id);
			if (known == null || known < floor)
			{
				keepPriorityFloor.put(id, floor);
				log.debug("death screen: learned keep-priority {} ({}) ranks above {}", id, itemName(id), bestLost);
				changed = true;
			}
		}
		if (changed)
		{
			saveKeepPriority();
			updateRisk();
		}
	}

	/** Item ids in the death screen's "Items that are KEPT" box; null if the box wasn't found. */
	private List<Integer> deathScreenKeptItemIds()
	{
		for (int child = 0; child < 80; child++)
		{
			final List<Integer> ids = keptIdsIn(client.getWidget(InterfaceID.DEATHKEEP, child), 0);
			if (ids != null)
			{
				return ids;
			}
		}
		return null;
	}

	/**
	 * The kept box's item ids if this subtree is the KEPT section (its text mentions KEPT but not
	 * the GRAVESTONE list beside it), else the first hit among its children; null for no hit.
	 * Colour tags become spaces in collectText, so whitespace is normalised before matching.
	 */
	private List<Integer> keptIdsIn(Widget w, int depth)
	{
		if (w == null || depth > 4)
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		collectText(w, sb, 0);
		final String text = sb.toString().replaceAll("\\s+", " ");
		if (text.contains("Items that are KEPT") && !text.contains("GRAVESTONE"))
		{
			final List<Integer> ids = new ArrayList<>();
			collectItemIds(w, ids, 0);
			return ids;
		}
		for (final Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null)
			{
				continue;
			}
			for (final Widget kid : kids)
			{
				final List<Integer> ids = keptIdsIn(kid, depth + 1);
				if (ids != null)
				{
					return ids;
				}
			}
		}
		return null;
	}

	private void collectItemIds(Widget w, List<Integer> ids, int depth)
	{
		if (w == null || depth > 4)
		{
			return;
		}
		if (w.getItemId() > 0)
		{
			ids.add(w.getItemId());
		}
		for (final Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null)
			{
				continue;
			}
			for (final Widget kid : kids)
			{
				collectItemIds(kid, ids, depth + 1);
			}
		}
	}

	private void loadSkullIconMap()
	{
		skullIconLearned.clear();
		final String s = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, K_SKULL_ICON_MAP);
		if (s == null || s.isEmpty())
		{
			return;
		}
		for (final String part : s.split(","))
		{
			final String[] kv = part.split("=");
			if (kv.length != 2)
			{
				continue;
			}
			try
			{
				skullIconLearned.put(Integer.parseInt(kv[0].trim()), "s".equals(kv[1].trim()));
			}
			catch (NumberFormatException ignored)
			{
				// hand-edited config — skip the bad entry
			}
		}
	}

	private void saveSkullIconMap()
	{
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<Integer, Boolean> e : skullIconLearned.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append('=').append(e.getValue() ? 's' : 'u');
		}
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, K_SKULL_ICON_MAP, sb.toString());
	}

	private void loadPriceOverrides()
	{
		priceOverrides.clear();
		final String s = config.priceOverrides();
		if (s == null || s.isEmpty())
		{
			return;
		}
		for (final String part : s.split(","))
		{
			final String[] kv = part.split("=");
			if (kv.length != 2)
			{
				continue;
			}
			try
			{
				priceOverrides.put(Integer.parseInt(kv[0].trim()), Long.parseLong(kv[1].trim()));
			}
			catch (NumberFormatException ignored)
			{
				// malformed entry — skip it
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!PvpProfitTrackerConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			loadPriceOverrides();
			if (opponentTracker != null && !config.opponentRisk())
			{
				opponentTracker.clear();
				updateOpponentPanel();
			}
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				updateRisk();
			}
		});
	}

	private void loadKeepPriority()
	{
		keepPriorityFloor.clear();
		for (final long[] d : KEEP_PRIORITY_DEFAULTS)
		{
			keepPriorityFloor.put((int) d[0], d[1]);
		}
		final String s = configManager.getConfiguration(PvpProfitTrackerConfig.GROUP, K_KEEP_PRIORITY);
		if (s == null || s.isEmpty())
		{
			return;
		}
		for (final String part : s.split(","))
		{
			final String[] kv = part.split("=");
			if (kv.length != 2)
			{
				continue;
			}
			try
			{
				final int id = Integer.parseInt(kv[0].trim());
				final long floor = Long.parseLong(kv[1].trim());
				final Long known = keepPriorityFloor.get(id);
				if (known == null || known < floor)
				{
					keepPriorityFloor.put(id, floor);
				}
			}
			catch (NumberFormatException ignored)
			{
				// hand-edited config — skip the bad entry
			}
		}
	}

	private void saveKeepPriority()
	{
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<Integer, Long> e : keepPriorityFloor.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		configManager.setConfiguration(PvpProfitTrackerConfig.GROUP, K_KEEP_PRIORITY, sb.toString());
	}

	/**
	 * Per-item value lost on death: GE price if tradeable, else the untradeable's repair-on-death cost
	 * (from {@code reclaim-costs.csv}, matched by name), else its store value as a fallback.
	 */
	long deathValue(int id)
	{
		final long ge = feedPrice(id);
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

	/** Refresh only the panel's opponent section — the high-frequency, jitter-free path. */
	private void updateOpponentPanel()
	{
		if (panel != null)
		{
			panel.updateOpponent();
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
		return ImageUtil.loadImageResource(PvpProfitTrackerPlugin.class, "icon.png");
	}

	@Provides
	PvpProfitTrackerConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(PvpProfitTrackerConfig.class);
	}
}
