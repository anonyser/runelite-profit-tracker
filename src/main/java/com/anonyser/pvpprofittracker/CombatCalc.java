package com.anonyser.pvpprofittracker;

import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.HeadIcon;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ParamID;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Hit-chance and max-hit estimates against the focused opponent, using the standard OSRS combat
 * formulas. The player's side is exact where the client can see it: boosted levels (potions
 * included), active prayers, worn gear, and the selected combat style resolved from the game's
 * own weapon-style data. The opponent's side is an estimate built from their hiscore levels and
 * visible gear, deliberately assuming the best defensive prayer their Prayer level allows and a
 * super combat potion — a strong opponent, so the estimate errs honest. Every assumption is
 * labelled in the overlay.
 */
class CombatCalc
{
	/** Damage scales with Strength and melee strength bonus — unique among ranged weapons. */
	private static final int ECLIPSE_ATLATL = 29000;
	private static final int DARK_BOW = 11235;

	/**
	 * Special-attack MAX-HIT modifiers for the common PvP spec weapons, from the wiki's Special
	 * attacks page (accuracy modifiers are not modelled — this feeds the max-hit line only).
	 * Encoding: {damage multiplier, hits, show-as-total}. show-as-total = 1 means the number is
	 * the whole combo's ceiling (claws) rather than a per-hit value. Weapons not listed show
	 * their normal max hit while the spec bar is lit.
	 */
	private static final java.util.Map<Integer, double[]> SPEC_MAX = new java.util.HashMap<>();
	static
	{
		final double[] dds = {1.15, 2, 0};
		SPEC_MAX.put(1215, dds);  // dragon dagger
		SPEC_MAX.put(1231, dds);  // dragon dagger(p)
		SPEC_MAX.put(5680, dds);  // dragon dagger(p+)
		SPEC_MAX.put(5698, dds);  // dragon dagger(p++)
		SPEC_MAX.put(13652, new double[]{2.0, 4, 1});   // dragon claws — combo ceiling
		SPEC_MAX.put(1434, new double[]{1.5, 1, 0});    // dragon mace
		SPEC_MAX.put(21009, new double[]{1.25, 1, 0});  // dragon sword
		final double[] dKnife = {1.0, 2, 0};
		SPEC_MAX.put(22804, dKnife); // dragon knife
		SPEC_MAX.put(22806, dKnife); // dragon knife(p)
		SPEC_MAX.put(22808, dKnife); // dragon knife(p+)
		final double[] gmaul = {1.0, 1, 0};
		SPEC_MAX.put(4153, gmaul);   // granite maul (instant, no modifier)
		SPEC_MAX.put(12848, gmaul);  // granite maul (or)
		SPEC_MAX.put(24225, gmaul);  // ornate maul
		SPEC_MAX.put(11802, new double[]{1.375, 1, 0}); // Armadyl godsword
		SPEC_MAX.put(11804, new double[]{1.21, 1, 0});  // Bandos godsword
		SPEC_MAX.put(27690, new double[]{1.5, 1, 0});   // Voidwaker (guaranteed, up to 150%)
		SPEC_MAX.put(DARK_BOW, new double[]{1.3, 2, 0}); // dark bow (1.5 with dragon arrows)
		SPEC_MAX.put(19481, new double[]{1.25, 1, 0});  // heavy ballista
		SPEC_MAX.put(13576, new double[]{1.5, 1, 0});   // dragon warhammer
		SPEC_MAX.put(21003, new double[]{1.0, 1, 0});   // elder maul (accuracy-only spec)
		final double[] abyDagger = {0.85, 2, 0};
		SPEC_MAX.put(13265, abyDagger); // abyssal dagger
		SPEC_MAX.put(13267, abyDagger); // abyssal dagger(p)
		SPEC_MAX.put(13269, abyDagger); // abyssal dagger(p+)
		SPEC_MAX.put(13271, abyDagger); // abyssal dagger(p++)
		SPEC_MAX.put(27908, new double[]{1.25, 1, 0});  // Statius's warhammer (bh)
		SPEC_MAX.put(22622, new double[]{1.25, 1, 0});  // Statius's warhammer
		SPEC_MAX.put(27904, new double[]{1.2, 1, 0});   // Vesta's longsword (bh)
		SPEC_MAX.put(22613, new double[]{1.2, 1, 0});   // Vesta's longsword
		SPEC_MAX.put(20849, new double[]{1.0, 1, 0});   // dragon thrownaxe (accuracy-only)
		SPEC_MAX.put(3204, new double[]{1.1, 1, 0});    // dragon halberd
		SPEC_MAX.put(23987, new double[]{1.1, 1, 0});   // crystal halberd
	}

	/** Reads all game state on the client thread; the returned estimate is immutable. */
	private final Client client;
	private final ItemManager itemManager;

	CombatCalc(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	// The combat class the player's selected style attacks with.
	enum Style
	{
		MELEE, RANGED, MAGIC, OTHER
	}

	/** One coherent estimate; fields final so the EDT can't see a torn state. */
	static final class Estimate
	{
		final Style style;
		final String styleName;   // display text, e.g. "Melee (slash)"
		final double hitChance;   // 0..1
		final int maxHit;         // -1 = spell-based (magic), unknown
		final boolean overheadCounters; // their overhead prayer blocks this style (40% off in PvP)
		final boolean defenceAssumed;   // hiscores didn't answer (yet) — level 99 assumed
		final int oppDefence;     // defence level used (post-assumption, pre-potion)
		final int oppPrayer;      // prayer level used for the best-prayer assumption
		final boolean specActive; // the special-attack bar is lit
		final int specMaxHit;     // spec ceiling (-1 = weapon not in the table)
		final int specHits;       // hits per spec (2 for dds, etc.)
		final boolean specTotal;  // specMaxHit is the whole combo's ceiling (claws)

		Estimate(Style style, String styleName, double hitChance, int maxHit,
			boolean overheadCounters, boolean defenceAssumed, int oppDefence, int oppPrayer,
			boolean specActive, int specMaxHit, int specHits, boolean specTotal)
		{
			this.style = style;
			this.styleName = styleName;
			this.hitChance = hitChance;
			this.maxHit = maxHit;
			this.overheadCounters = overheadCounters;
			this.defenceAssumed = defenceAssumed;
			this.oppDefence = oppDefence;
			this.oppPrayer = oppPrayer;
			this.specActive = specActive;
			this.specMaxHit = specMaxHit;
			this.specHits = specHits;
			this.specTotal = specTotal;
		}

		/** True when the max-hit line should show the special-attack number. */
		boolean specShown()
		{
			return specActive && specMaxHit >= 0;
		}

		/**
		 * Display text for the max-hit line, spec- and overhead-aware, shared by the overlay and
		 * the side panel: "34", "34 (20 prayed)", "2× 27", "up to 68 (40 prayed)", "spell-based".
		 */
		String maxHitText()
		{
			if (maxHit < 0)
			{
				return "spell-based";
			}
			final int shown = specShown() ? specMaxHit : maxHit;
			String text = specShown() && specTotal ? "up to " + shown
				: specShown() && specHits > 1 ? specHits + "× " + shown
				: Integer.toString(shown);
			if (overheadCounters)
			{
				text += " (" + afterOverhead(shown) + " prayed)";
			}
			return text;
		}
	}

	/** Compute the current estimate against the opponent snapshot. Client thread only. */
	Estimate estimate(OpponentTracker.Snapshot opp)
	{
		if (opp == null)
		{
			return null;
		}

		final Stance stance = currentStance();
		final Bonuses mine = worn();

		// Opponent levels: hiscores when they've answered, otherwise a maxed main — the
		// assumption that keeps the estimate conservative until real numbers arrive.
		final boolean defenceAssumed = opp.defenceLevel <= 0;
		final int oppDefence = defenceAssumed ? 99 : opp.defenceLevel;
		final int oppPrayer = opp.prayerLevel <= 0 ? 99 : opp.prayerLevel;
		final int oppMagic = opp.magicLevel <= 0 ? 99 : opp.magicLevel;

		// Assume a super combat potion and the best defensive prayer their Prayer level allows.
		final int boostedDef = oppDefence + superCombatBoost(oppDefence);
		final int effDef = effectiveLevel(boostedDef, defensivePrayerMult(oppPrayer), 0);

		// Opponent's style-specific defence bonus, from the gear we can currently see.
		final Bonuses theirs = fromIds(opp.equippedIds);

		final Style style = stance.style;
		double chance = 0;
		int maxHit = -1;
		String styleName;
		boolean overheadCounters = false;

		switch (style)
		{
			case MELEE:
			{
				// The weapon's best accuracy type stands in for the selected one — close enough
				// for an estimate, since players fight with their weapon's strong side.
				final int atkBonus = Math.max(mine.astab, Math.max(mine.aslash, mine.acrush));
				final int defBonus = mine.astab >= mine.aslash && mine.astab >= mine.acrush ? theirs.dstab
					: mine.aslash >= mine.acrush ? theirs.dslash : theirs.dcrush;
				final String type = mine.astab >= mine.aslash && mine.astab >= mine.acrush ? "stab"
					: mine.aslash >= mine.acrush ? "slash" : "crush";
				styleName = "Melee (" + type + ")";
				final int effAtk = effectiveLevel(client.getBoostedSkillLevel(Skill.ATTACK),
					attackPrayerMult(), stance.attackBonus);
				chance = hitChance(attackRoll(effAtk, atkBonus), attackRoll(effDef, defBonus));
				final int effStr = effectiveLevel(client.getBoostedSkillLevel(Skill.STRENGTH),
					strengthPrayerMult(), stance.strengthBonus);
				maxHit = maxHit(effStr, mine.str);
				overheadCounters = opp.overhead == HeadIcon.MELEE;
				break;
			}
			case RANGED:
			{
				final int effAcc = effectiveLevel(client.getBoostedSkillLevel(Skill.RANGED),
					rangedAccuracyPrayerMult(), stance.rangedBonus);
				chance = hitChance(attackRoll(effAcc, mine.arange), attackRoll(effDef, theirs.drange));
				if (wornWeaponId() == ECLIPSE_ATLATL)
				{
					// The atlatl's damage rides the STRENGTH level (visible boosts included) and
					// the gear's MELEE strength bonus, while ranged prayers boost both accuracy
					// and damage and melee prayers do nothing — the wiki-documented one-off
					// among ranged weapons. Its accuracy side stays normal ranged.
					styleName = "Ranged (atlatl)";
					final int effStr = effectiveLevel(client.getBoostedSkillLevel(Skill.STRENGTH),
						rangedStrengthPrayerMult(), stance.rangedBonus);
					maxHit = maxHit(effStr, mine.str);
				}
				else
				{
					styleName = "Ranged";
					final int effStr = effectiveLevel(client.getBoostedSkillLevel(Skill.RANGED),
						rangedStrengthPrayerMult(), stance.rangedBonus);
					maxHit = maxHit(effStr, mine.rstr);
				}
				overheadCounters = opp.overhead == HeadIcon.RANGED
					|| opp.overhead == HeadIcon.RANGE_MAGE
					|| opp.overhead == HeadIcon.RANGE_MELEE;
				break;
			}
			case MAGIC:
			{
				styleName = "Magic";
				final int effAcc = effectiveLevel(client.getBoostedSkillLevel(Skill.MAGIC),
					magicPrayerMult(), 0);
				// Magic defence is 70% the defender's Magic level, 30% their Defence.
				final int effMagicDef = (int) (0.7 * (oppMagic + 8) + 0.3 * effDef);
				chance = hitChance(attackRoll(effAcc, mine.amagic), attackRoll(effMagicDef, theirs.dmagic));
				maxHit = -1; // spell-dependent — shown as such rather than guessed
				overheadCounters = opp.overhead == HeadIcon.MAGIC
					|| opp.overhead == HeadIcon.RANGE_MAGE;
				break;
			}
			default:
				styleName = "—";
				break;
		}

		// With the special-attack bar lit, the max-hit line switches to the spec's ceiling for
		// the known PvP spec weapons (in-game feedback: "press spec, see the spec number").
		final boolean specActive = client.getVarpValue(VarPlayerID.SA_ATTACK) == 1;
		int specMaxHit = -1;
		int specHits = 1;
		boolean specTotal = false;
		if (specActive && maxHit >= 0)
		{
			final int weaponId = wornWeaponId();
			final double[] spec = SPEC_MAX.get(weaponId);
			if (spec != null)
			{
				double mult = spec[0];
				if (weaponId == DARK_BOW && wornAmmoIsDragonArrow())
				{
					mult = 1.5; // the dark bow spec jumps from 30% to 50% with dragon arrows
				}
				specMaxHit = (int) (maxHit * mult);
				specHits = (int) spec[1];
				specTotal = spec[2] == 1;
			}
		}

		return new Estimate(style, styleName, chance, maxHit, overheadCounters,
			defenceAssumed, oppDefence, oppPrayer, specActive, specMaxHit, specHits, specTotal);
	}

	// --- The player's selected combat style, from the game's own weapon-style data ---

	private static final class Stance
	{
		final Style style;
		final int attackBonus;   // melee accurate +3 / controlled +1
		final int strengthBonus; // melee aggressive +3 / controlled +1
		final int rangedBonus;   // ranged accurate +3 (indistinguishable from rapid — kept at 0)

		Stance(Style style, int attackBonus, int strengthBonus, int rangedBonus)
		{
			this.style = style;
			this.attackBonus = attackBonus;
			this.strengthBonus = strengthBonus;
			this.rangedBonus = rangedBonus;
		}
	}

	/**
	 * Resolve the selected attack style exactly like the core Attack Styles plugin: weapon
	 * category varbit → WEAPON_STYLES enum → style structs, with the defensive-autocast offset.
	 * The style name then gives the combat class and the invisible stance bonus.
	 */
	private Stance currentStance()
	{
		final int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		int idx = client.getVarpValue(VarPlayerID.COM_MODE);
		final String[] styles = weaponStyleNames(weaponType);
		if (idx == 4)
		{
			idx += client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE);
		}
		final String name = idx >= 0 && idx < styles.length && styles[idx] != null ? styles[idx] : "";
		switch (name)
		{
			case "Accurate":
				return new Stance(Style.MELEE, 3, 0, 0);
			case "Aggressive":
				return new Stance(Style.MELEE, 0, 3, 0);
			case "Controlled":
				return new Stance(Style.MELEE, 1, 1, 0);
			case "Defensive":
				return new Stance(Style.MELEE, 0, 0, 0);
			case "Ranging":
				// Covers both the accurate (+3) and rapid (+0) styles — the game data doesn't
				// distinguish them here. Rapid is the PvP norm, so no bonus is applied.
				return new Stance(Style.RANGED, 0, 0, 0);
			case "Longrange":
				return new Stance(Style.RANGED, 0, 0, 0);
			case "Casting":
			case "Defensive casting":
				return new Stance(Style.MAGIC, 0, 0, 0);
			default:
				return new Stance(Style.OTHER, 0, 0, 0);
		}
	}

	private String[] weaponStyleNames(int weaponType)
	{
		// from script4525, as read by the core Attack Styles plugin
		final int weaponStyleEnum = client.getEnum(EnumID.WEAPON_STYLES).getIntValue(weaponType);
		if (weaponStyleEnum == -1)
		{
			if (weaponType == 22) // blue moon spear
			{
				return new String[]{"Accurate", "Aggressive", null, "Defensive", "Casting", "Defensive casting"};
			}
			if (weaponType == 30) // partisan
			{
				return new String[]{"Accurate", "Aggressive", "Aggressive", "Defensive"};
			}
			return new String[0];
		}
		final EnumComposition styleEnum = client.getEnum(weaponStyleEnum);
		final int[] structs = styleEnum.getIntVals();
		final String[] names = new String[structs.length];
		for (int i = 0; i < structs.length; i++)
		{
			final StructComposition struct = client.getStructComposition(structs[i]);
			String name = struct.getStringValue(ParamID.ATTACK_STYLE_NAME);
			if (i == 5 && "Defensive".equals(name))
			{
				name = "Defensive casting";
			}
			names[i] = name;
		}
		return names;
	}

	// --- Equipment bonuses ---

	private static final class Bonuses
	{
		int astab, aslash, acrush, amagic, arange;
		int dstab, dslash, dcrush, dmagic, drange;
		int str, rstr;
	}

	private int wornWeaponId()
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final Item weapon = worn == null ? null
			: worn.getItem(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
	}

	private boolean wornAmmoIsDragonArrow()
	{
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		final Item ammo = worn == null ? null
			: worn.getItem(net.runelite.api.EquipmentInventorySlot.AMMO.getSlotIdx());
		if (ammo == null || ammo.getId() <= 0)
		{
			return false;
		}
		try
		{
			return itemManager.getItemComposition(ammo.getId()).getName()
				.toLowerCase().contains("dragon arrow");
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private Bonuses worn()
	{
		final Bonuses b = new Bonuses();
		final ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			for (final Item item : worn.getItems())
			{
				if (item.getId() > 0)
				{
					add(b, item.getId());
				}
			}
		}
		return b;
	}

	private Bonuses fromIds(int[] ids)
	{
		final Bonuses b = new Bonuses();
		for (final int id : ids)
		{
			if (id > 0)
			{
				add(b, id);
			}
		}
		return b;
	}

	private void add(Bonuses b, int itemId)
	{
		final ItemStats stats = itemManager.getItemStats(itemId);
		final ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
		if (e == null)
		{
			return;
		}
		b.astab += e.getAstab();
		b.aslash += e.getAslash();
		b.acrush += e.getAcrush();
		b.amagic += e.getAmagic();
		b.arange += e.getArange();
		b.dstab += e.getDstab();
		b.dslash += e.getDslash();
		b.dcrush += e.getDcrush();
		b.dmagic += e.getDmagic();
		b.drange += e.getDrange();
		b.str += e.getStr();
		b.rstr += e.getRstr();
	}

	// --- The player's active prayer multipliers (varbit reads, client thread) ---

	private double attackPrayerMult()
	{
		if (on(VarbitID.PRAYER_PIETY))
		{
			return 1.20;
		}
		if (on(VarbitID.PRAYER_CHIVALRY))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_INCREDIBLEREFLEXES))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_IMPROVEDREFLEXES))
		{
			return 1.10;
		}
		if (on(VarbitID.PRAYER_CLARITYOFTHOUGHT))
		{
			return 1.05;
		}
		return 1;
	}

	private double strengthPrayerMult()
	{
		if (on(VarbitID.PRAYER_PIETY))
		{
			return 1.23;
		}
		if (on(VarbitID.PRAYER_CHIVALRY))
		{
			return 1.18;
		}
		if (on(VarbitID.PRAYER_ULTIMATESTRENGTH))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_SUPERHUMANSTRENGTH))
		{
			return 1.10;
		}
		if (on(VarbitID.PRAYER_BURSTOFSTRENGTH))
		{
			return 1.05;
		}
		return 1;
	}

	private double rangedAccuracyPrayerMult()
	{
		if (on(VarbitID.PRAYER_RIGOUR))
		{
			return 1.20;
		}
		if (on(VarbitID.PRAYER_DEADEYE))
		{
			return 1.18;
		}
		if (on(VarbitID.PRAYER_EAGLEEYE))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_HAWKEYE))
		{
			return 1.10;
		}
		if (on(VarbitID.PRAYER_SHARPEYE))
		{
			return 1.05;
		}
		return 1;
	}

	private double rangedStrengthPrayerMult()
	{
		if (on(VarbitID.PRAYER_RIGOUR))
		{
			return 1.23;
		}
		if (on(VarbitID.PRAYER_DEADEYE))
		{
			return 1.18;
		}
		if (on(VarbitID.PRAYER_EAGLEEYE))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_HAWKEYE))
		{
			return 1.10;
		}
		if (on(VarbitID.PRAYER_SHARPEYE))
		{
			return 1.05;
		}
		return 1;
	}

	private double magicPrayerMult()
	{
		if (on(VarbitID.PRAYER_AUGURY))
		{
			return 1.25;
		}
		if (on(VarbitID.PRAYER_MYSTICVIGOUR))
		{
			return 1.18;
		}
		if (on(VarbitID.PRAYER_MYSTICMIGHT))
		{
			return 1.15;
		}
		if (on(VarbitID.PRAYER_MYSTICLORE))
		{
			return 1.10;
		}
		if (on(VarbitID.PRAYER_MYSTICWILL))
		{
			return 1.05;
		}
		return 1;
	}

	private boolean on(int varbit)
	{
		return client.getVarbitValue(varbit) == 1;
	}

	// --- Pure formula pieces (unit-tested) ---

	/** effective level = floor(boosted × prayer) + stance + 8 */
	static int effectiveLevel(int boostedLevel, double prayerMult, int stanceBonus)
	{
		return (int) (boostedLevel * prayerMult) + stanceBonus + 8;
	}

	/** roll = effective × (equipment bonus + 64) */
	static int attackRoll(int effectiveLevel, int equipmentBonus)
	{
		return effectiveLevel * (equipmentBonus + 64);
	}

	/** The standard OSRS accuracy formula. */
	static double hitChance(int attackRoll, int defenceRoll)
	{
		if (attackRoll < 0 || defenceRoll < 0)
		{
			return 0;
		}
		if (attackRoll > defenceRoll)
		{
			return 1d - (defenceRoll + 2d) / (2d * (attackRoll + 1d));
		}
		return attackRoll / (2d * (defenceRoll + 1d));
	}

	/** max hit = floor(0.5 + effective × (strength bonus + 64) / 640) */
	static int maxHit(int effectiveLevel, int strengthBonus)
	{
		return (effectiveLevel * (strengthBonus + 64) + 320) / 640;
	}

	/** Super combat / super defence boost: 5 + 15% of the level. */
	static int superCombatBoost(int level)
	{
		return 5 + (int) (level * 0.15);
	}

	/**
	 * The best defensive prayer multiplier a given Prayer level allows — the assumption the spec
	 * asks for: Piety-tier from 70 (Rigour/Augury match its 25%), Chivalry from 60, then the
	 * Skin line below that.
	 */
	static double defensivePrayerMult(int prayerLevel)
	{
		if (prayerLevel >= 70)
		{
			return 1.25;
		}
		if (prayerLevel >= 60)
		{
			return 1.20;
		}
		if (prayerLevel >= 28)
		{
			return 1.15;
		}
		if (prayerLevel >= 13)
		{
			return 1.10;
		}
		if (prayerLevel >= 1)
		{
			return 1.05;
		}
		return 1;
	}

	/** PvP protection prayers block 40% — the shown hit lands at 60%. */
	static int afterOverhead(int maxHit)
	{
		return maxHit * 6 / 10;
	}
}
