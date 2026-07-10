package com.anonyser.pvpprofittracker;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreManager;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks one focused player — chosen with the right-click "Inspect" option or a Bounty Hunter
 * target assignment — and shows their currently worn equipment with GE prices, the same
 * information Equipment Inspector has displayed for years. Nothing more: per the Plugin Hub
 * review of the 1.1.0 submission, no risk estimation, no drop/smite math, no stat lookups and
 * no previously-seen item pool. Current visible gear only.
 */
class OpponentTracker
{
	private static final Logger log = LoggerFactory.getLogger(OpponentTracker.class);

	/** Equipment slots that render on another player — the only gear that is ever visible. */
	static final KitType[] VISIBLE_SLOTS = {
		KitType.HEAD, KitType.CAPE, KitType.AMULET, KitType.WEAPON,
		KitType.TORSO, KitType.SHIELD, KitType.LEGS, KitType.HANDS, KitType.BOOTS,
	};

	/** Ticks without a sighting before the focus is dropped (~5 minutes). */
	private static final int FORGET_TICKS = 500;

	private final Client client;
	private final HiscoreManager hiscoreManager;
	private final PvpProfitTrackerPlugin plugin;
	private final Runnable onChange; // fired when the published view materially changes

	// All state is written on the client thread only. Readers off it use the snapshot.
	private String name; // sanitized display name of the focused player; null = no focus
	private final Map<KitType, Integer> equipped = new EnumMap<>(KitType.class);
	private HiscoreResult hiscore;
	private int lastSeenTick;
	private boolean visible;
	// Gear shows only after a deliberate right-click Inspect, mirroring Equipment Inspector's
	// manual flow. A Bounty Hunter target assignment pulls the hiscores alone.
	private boolean gearEnabled;
	// True when this focus came from a Bounty Hunter target assignment (the panel shows the name
	// in green). Inspecting the same player keeps it; focusing someone else resets it.
	private boolean bhTarget;

	/** Immutable view of the current gear, safe to read from the EDT (side panel). */
	private volatile Snapshot snapshot;
	private int lastChangeHash;

	OpponentTracker(Client client, HiscoreManager hiscoreManager, PvpProfitTrackerPlugin plugin,
		Runnable onChange)
	{
		this.client = client;
		this.hiscoreManager = hiscoreManager;
		this.plugin = plugin;
		this.onChange = onChange;
	}

	Snapshot snapshot()
	{
		return snapshot;
	}

	String focusedName()
	{
		return name;
	}

	/** Manual right-click Inspect: focus a player with the gear view enabled. */
	void focus(Player p)
	{
		final String pName = sanitizedName(p);
		if (pName == null)
		{
			return;
		}
		if (!pName.equals(name))
		{
			clear();
			name = pName;
			log.debug("gear inspect focused: {}", pName);
		}
		gearEnabled = true;
		refresh(p);
	}

	/**
	 * A Bounty Hunter target assignment: pull the hiscores by name, stats only. The gear view
	 * stays off until the player is deliberately right-clicked and inspected.
	 */
	void focusName(String targetName)
	{
		final String jagex = Text.toJagexName(targetName);
		if (jagex.equals(name))
		{
			return;
		}
		clear();
		name = jagex;
		bhTarget = true;
		lastSeenTick = client.getTickCount();
		log.debug("target focused by name, stats only: {}", jagex);
		recompute();
	}

	void clear()
	{
		name = null;
		equipped.clear();
		hiscore = null;
		visible = false;
		gearEnabled = false;
		bhTarget = false;
		snapshot = null;
		if (lastChangeHash != 0)
		{
			lastChangeHash = 0;
			onChange.run();
		}
	}

	boolean isFocused(Player p)
	{
		final String pName = sanitizedName(p);
		return name != null && name.equals(pName);
	}

	/** Re-read the focused player's currently worn gear. Client thread only. */
	void refresh(Player p)
	{
		final PlayerComposition comp = p.getPlayerComposition();
		if (gearEnabled && comp != null)
		{
			equipped.clear();
			for (final KitType slot : VISIBLE_SLOTS)
			{
				final int id = comp.getEquipmentId(slot);
				if (id > 0)
				{
					equipped.put(slot, id);
				}
			}
		}
		lastSeenTick = client.getTickCount();
		visible = true;
		recompute();
	}

	void markDespawned(Player p)
	{
		if (isFocused(p))
		{
			visible = false;
			recompute();
		}
	}

	/**
	 * Per-tick upkeep: re-find the player in the scene (their gear can change while no event
	 * fires for us) and drop a focus that has been out of sight long enough.
	 */
	void onTick()
	{
		if (name == null)
		{
			return;
		}
		if (client.getTickCount() - lastSeenTick > FORGET_TICKS)
		{
			clear();
			return;
		}
		Player found = null;
		for (final Player p : client.getTopLevelWorldView().players())
		{
			if (p != null && isFocused(p))
			{
				found = p;
				break;
			}
		}
		if (found != null)
		{
			refresh(found);
		}
		else if (visible)
		{
			visible = false;
			recompute();
		}
		if (hiscore == null)
		{
			// The public hiscores, same information as the core client's right-click Lookup.
			// Returns the cached result, or null while the background fetch is still out.
			hiscore = hiscoreManager.lookupAsync(name, HiscoreEndpoint.NORMAL);
			if (hiscore != null)
			{
				log.debug("hiscores answered for {}", name);
				recompute();
			}
		}
	}

	private int hiscoreLevel(HiscoreSkill skill)
	{
		if (hiscore == null)
		{
			return -1;
		}
		final net.runelite.client.hiscore.Skill s = hiscore.getSkill(skill);
		return s == null ? -1 : s.getLevel();
	}

	private static String sanitizedName(Player p)
	{
		final String n = p.getName();
		return n == null ? null : Text.toJagexName(Text.removeTags(n));
	}

	/** Rebuild the published snapshot from current state. Client thread only. */
	private void recompute()
	{
		if (name == null)
		{
			snapshot = null;
			return;
		}

		// Names and GE prices are resolved here, on the client thread — the side panel builds on
		// the EDT, where the item cache must not be touched.
		final int[] ids = new int[VISIBLE_SLOTS.length];
		final String[] names = new String[VISIBLE_SLOTS.length];
		final long[] prices = new long[VISIBLE_SLOTS.length];
		for (int i = 0; i < VISIBLE_SLOTS.length; i++)
		{
			final Integer id = equipped.get(VISIBLE_SLOTS[i]);
			ids[i] = id == null ? -1 : id;
			if (ids[i] > 0)
			{
				names[i] = plugin.itemName(ids[i]);
				prices[i] = plugin.gePrice(ids[i]);
			}
		}

		snapshot = new Snapshot(name, visible, gearEnabled, bhTarget, ids, names, prices,
			hiscoreLevel(HiscoreSkill.ATTACK), hiscoreLevel(HiscoreSkill.STRENGTH),
			hiscoreLevel(HiscoreSkill.DEFENCE), hiscoreLevel(HiscoreSkill.RANGED),
			hiscoreLevel(HiscoreSkill.MAGIC), hiscoreLevel(HiscoreSkill.HITPOINTS),
			hiscoreLevel(HiscoreSkill.PRAYER), hiscoreLevel(HiscoreSkill.BOUNTY_HUNTER_HUNTER),
			hiscoreLevel(HiscoreSkill.BOUNTY_HUNTER_ROGUE), hiscoreLevel(HiscoreSkill.COLOSSEUM_GLORY),
			hiscoreLevel(HiscoreSkill.TZKAL_ZUK), hiscoreLevel(HiscoreSkill.SOL_HEREDIT));

		final int changeHash = java.util.Objects.hash(name, visible, gearEnabled, bhTarget,
			java.util.Arrays.hashCode(ids), hiscore != null);
		if (changeHash != lastChangeHash)
		{
			lastChangeHash = changeHash;
			onChange.run();
		}
	}

	/** One coherent read of the gear view; the fields are final so the EDT can't see a torn state. */
	static final class Snapshot
	{
		final String name;
		final boolean visible;
		final boolean gearShown;      // false until the player is manually right-click Inspected
		final boolean bhTarget;       // true when this focus came from a BH target assignment
		final int[] equippedIds;      // VISIBLE_SLOTS order, -1 = empty
		final String[] equippedNames; // parallel to equippedIds (null where empty)
		final long[] equippedGe;      // parallel to equippedIds, plain GE price per item — no
		                              // total anywhere; any adding up is the player's own
		// Public hiscore levels/scores, -1 until the lookup answers (or unranked).
		final int attack;
		final int strength;
		final int defence;
		final int ranged;
		final int magic;
		final int hitpoints;
		final int prayer;
		final int bhTargetKills;
		final int bhRogueKills;
		final int colosseumGlory;
		final int zukKc;
		final int solHereditKc;

		Snapshot(String name, boolean visible, boolean gearShown, boolean bhTarget, int[] equippedIds,
			String[] equippedNames, long[] equippedGe, int attack, int strength,
			int defence, int ranged, int magic, int hitpoints, int prayer, int bhTargetKills,
			int bhRogueKills, int colosseumGlory, int zukKc, int solHereditKc)
		{
			this.name = name;
			this.visible = visible;
			this.gearShown = gearShown;
			this.bhTarget = bhTarget;
			this.equippedIds = equippedIds;
			this.equippedNames = equippedNames;
			this.equippedGe = equippedGe;
			this.attack = attack;
			this.strength = strength;
			this.defence = defence;
			this.ranged = ranged;
			this.magic = magic;
			this.hitpoints = hitpoints;
			this.prayer = prayer;
			this.bhTargetKills = bhTargetKills;
			this.bhRogueKills = bhRogueKills;
			this.colosseumGlory = colosseumGlory;
			this.zukKc = zukKc;
			this.solHereditKc = solHereditKc;
		}
	}
}
