package com.anonyser.pvpprofittracker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
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
 * Tracks one focused opponent — chosen with the right-click "Risk" option — and estimates what
 * they are risking from what is visible: worn equipment (another player's inventory can never be
 * seen and the plugin does not try), every item seen equipped during the fight, their skull icon,
 * and the Bounty Hunter tier bands. Items stay in the pool after being unequipped — a weapon
 * switched away mid-fight is still carried, and still part of their risk. Every protected-slot
 * and prayer assumption is an estimate and is labelled as such where it is shown.
 */
class OpponentTracker
{
	private static final Logger log = LoggerFactory.getLogger(OpponentTracker.class);

	/** Equipment slots that render on another player — the only gear that is ever visible. */
	static final KitType[] VISIBLE_SLOTS = {
		KitType.HEAD, KitType.CAPE, KitType.AMULET, KitType.WEAPON,
		KitType.TORSO, KitType.SHIELD, KitType.LEGS, KitType.HANDS, KitType.BOOTS,
	};

	// Bounty Hunter risk-tier icons replace the skull slot on BH worlds. 21–24 (unskulled) and
	// 29–32 (skulled, paired tier-for-tier at +8) are in-game-verified; the wiki lists five tiers
	// — bronze, iron, green, blue, red — so each band is taken as 20–24 / 28–32 with the
	// never-yet-observed bronze ids inferred. The floors are the wiki's tier minimums: a tier icon
	// is the game's own statement that the player risks at least that much, inventory included —
	// often more than their visible gear alone adds up to.
	private static final int BH_UNSKULLED_FIRST = 20;
	private static final int BH_SKULLED_FIRST = 28;
	private static final int BH_TIER_COUNT = 5;
	private static final String[] BH_TIER_NAMES = {"Bronze", "Iron", "Green", "Blue", "Red"};
	private static final long[] BH_TIER_FLOORS = {0, 200_001, 800_001, 2_000_001, 8_000_001};

	/** Ticks without a sighting before the focus is dropped (~5 minutes). */
	private static final int FORGET_TICKS = 500;

	private final Client client;
	private final HiscoreManager hiscoreManager;
	private final PvpProfitTrackerPlugin plugin;

	// All state is written on the client thread only. Readers off it use the snapshot.
	private String name; // sanitized display name of the focused opponent; null = no focus
	private final Map<KitType, Integer> equipped = new EnumMap<>(KitType.class);
	private final Set<Integer> seenItems = new LinkedHashSet<>();
	private int skullIcon = -1;
	private HeadIcon overhead;
	private HiscoreResult hiscore;
	private int lastSeenTick;
	private boolean visible;

	/** Immutable view of the current estimate, safe to read from the EDT (side panel). */
	private volatile Snapshot snapshot;

	OpponentTracker(Client client, HiscoreManager hiscoreManager, PvpProfitTrackerPlugin plugin)
	{
		this.client = client;
		this.hiscoreManager = hiscoreManager;
		this.plugin = plugin;
	}

	Snapshot snapshot()
	{
		return snapshot;
	}

	String focusedName()
	{
		return name;
	}

	/** Focus a new opponent. Re-selecting the current one just refreshes their gear. */
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
			log.debug("opponent focused: {}", pName);
		}
		refresh(p);
	}

	void clear()
	{
		name = null;
		equipped.clear();
		seenItems.clear();
		skullIcon = -1;
		overhead = null;
		hiscore = null;
		visible = false;
		snapshot = null;
	}

	boolean isFocused(Player p)
	{
		final String pName = sanitizedName(p);
		return name != null && name.equals(pName);
	}

	/** Re-read the focused opponent's visible state. Client thread only. */
	void refresh(Player p)
	{
		final PlayerComposition comp = p.getPlayerComposition();
		if (comp != null)
		{
			for (final KitType slot : VISIBLE_SLOTS)
			{
				final int id = comp.getEquipmentId(slot);
				if (id > 0)
				{
					equipped.put(slot, id);
					if (seenItems.add(id))
					{
						log.debug("opponent {} new item seen: {} ({} in pool)", name, id, seenItems.size());
					}
				}
				else
				{
					equipped.remove(slot);
				}
			}
		}
		if (p.getSkullIcon() != skullIcon)
		{
			log.debug("opponent {} skull icon {} -> {}", name, skullIcon, p.getSkullIcon());
		}
		skullIcon = p.getSkullIcon();
		overhead = p.getOverheadIcon();
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
	 * Per-tick upkeep: re-find the opponent in the scene (their gear can change while no event
	 * fires for us), poll the hiscore lookup until it answers, and drop a focus that has been out
	 * of sight for so long the fight is clearly over.
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
			// Returns the cached result, or null while the background fetch is still out.
			hiscore = hiscoreManager.lookupAsync(name, HiscoreEndpoint.NORMAL);
			if (hiscore != null)
			{
				log.debug("opponent {} hiscores: def {} pray {} mage {} hp {}", name,
					hiscoreLevel(HiscoreSkill.DEFENCE), hiscoreLevel(HiscoreSkill.PRAYER),
					hiscoreLevel(HiscoreSkill.MAGIC), hiscoreLevel(HiscoreSkill.HITPOINTS));
				recompute();
			}
		}
	}

	private static String sanitizedName(Player p)
	{
		final String n = p.getName();
		return n == null ? null : Text.toJagexName(Text.removeTags(n));
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

	/** Rebuild the published snapshot from current state. Client thread only. */
	private void recompute()
	{
		if (name == null)
		{
			snapshot = null;
			return;
		}

		// Value the whole seen-item pool with the same price chain the player's own risk uses:
		// user override → live feed → BH activation fee → reclaim cost → store value. Rank order
		// additionally respects the learned keep-priority floors, like the player's own ranking.
		final List<long[]> ranked = new ArrayList<>(); // [rankValue, lossValue, itemId]
		long total = 0;
		for (final int id : seenItems)
		{
			final long value = plugin.deathValue(id);
			final long rank = Math.max(value, plugin.keepFloor(id));
			if (rank <= 0)
			{
				continue;
			}
			ranked.add(new long[]{rank, value, id});
			total += value;
		}
		ranked.sort((a, b) -> Long.compare(b[0], a[0]));

		final boolean skulled = plugin.isSkullIconSkulled(skullIcon);
		// Assumptions per the plugin's rules: Protect Item is assumed active in both cases —
		// unskulled keeps 3 + 1 protected, skulled keeps only the protected one.
		final int kept = skulled ? 1 : 4;
		long protectedGp = 0;
		for (int i = 0; i < ranked.size() && i < kept; i++)
		{
			protectedGp += ranked.get(i)[1];
		}
		// Smite value: what losing Protect Item exposes — the one extra slot it was covering.
		final int protectSlot = skulled ? 0 : 3;
		final long smiteGp = ranked.size() > protectSlot ? ranked.get(protectSlot)[1] : 0;

		String tier = null;
		long tierFloor = 0;
		final int tierIndex = tierIndex(skullIcon);
		if (tierIndex >= 0)
		{
			tier = BH_TIER_NAMES[tierIndex];
			tierFloor = BH_TIER_FLOORS[tierIndex];
		}
		// The tier icon is the game's statement of their minimum total risk (inventory included);
		// visible gear is our floor from the other direction. Risk estimate = the larger.
		final long riskGp = Math.max(Math.max(0, total - protectedGp), tierFloor);

		final int[] equippedIds = new int[VISIBLE_SLOTS.length];
		for (int i = 0; i < VISIBLE_SLOTS.length; i++)
		{
			final Integer id = equipped.get(VISIBLE_SLOTS[i]);
			equippedIds[i] = id == null ? -1 : id;
		}
		final List<Integer> seenOnly = new ArrayList<>();
		for (final int id : seenItems)
		{
			if (!equipped.containsValue(id))
			{
				seenOnly.add(id);
			}
		}
		final int[] seenOnlyIds = new int[seenOnly.size()];
		for (int i = 0; i < seenOnlyIds.length; i++)
		{
			seenOnlyIds[i] = seenOnly.get(i);
		}

		snapshot = new Snapshot(name, visible, skulled, tier, tierFloor, total, riskGp, smiteGp,
			kept, equippedIds, seenOnlyIds, overhead,
			hiscoreLevel(HiscoreSkill.DEFENCE), hiscoreLevel(HiscoreSkill.PRAYER),
			hiscoreLevel(HiscoreSkill.MAGIC), hiscoreLevel(HiscoreSkill.HITPOINTS));
	}

	/** 0–4 tier index for a BH risk-tier icon (either band), or -1 when the icon is not one. */
	private static int tierIndex(int icon)
	{
		if (icon >= BH_UNSKULLED_FIRST && icon < BH_UNSKULLED_FIRST + BH_TIER_COUNT)
		{
			return icon - BH_UNSKULLED_FIRST;
		}
		if (icon >= BH_SKULLED_FIRST && icon < BH_SKULLED_FIRST + BH_TIER_COUNT)
		{
			return icon - BH_SKULLED_FIRST;
		}
		return -1;
	}

	/** One coherent read of the estimate; the fields are final so the EDT can't see a torn state. */
	static final class Snapshot
	{
		final String name;
		final boolean visible;
		final boolean skulled;
		final String tier;      // BH tier name, or null off BH worlds
		final long tierFloor;   // minimum risk the tier icon vouches for
		final long totalSeenGp; // everything seen equipped this fight
		final long riskGp;      // estimated loss on death, protections applied
		final long smiteGp;     // extra exposed if Protect Item is lost
		final int keptAssumed;  // protected slots assumed (4 unskulled, 1 skulled)
		final int[] equippedIds;  // VISIBLE_SLOTS order, -1 = empty
		final int[] seenOnlyIds;  // seen this fight, not currently worn
		final HeadIcon overhead;  // their overhead prayer, if any
		final int defenceLevel;   // -1 until the hiscore lookup answers
		final int prayerLevel;
		final int magicLevel;
		final int hitpointsLevel;

		Snapshot(String name, boolean visible, boolean skulled, String tier, long tierFloor,
			long totalSeenGp, long riskGp, long smiteGp, int keptAssumed, int[] equippedIds,
			int[] seenOnlyIds, HeadIcon overhead, int defenceLevel, int prayerLevel,
			int magicLevel, int hitpointsLevel)
		{
			this.name = name;
			this.visible = visible;
			this.skulled = skulled;
			this.tier = tier;
			this.tierFloor = tierFloor;
			this.totalSeenGp = totalSeenGp;
			this.riskGp = riskGp;
			this.smiteGp = smiteGp;
			this.keptAssumed = keptAssumed;
			this.equippedIds = equippedIds;
			this.seenOnlyIds = seenOnlyIds;
			this.overhead = overhead;
			this.defenceLevel = defenceLevel;
			this.prayerLevel = prayerLevel;
			this.magicLevel = magicLevel;
			this.hitpointsLevel = hitpointsLevel;
		}
	}
}
