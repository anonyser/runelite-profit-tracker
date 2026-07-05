package com.anonyser.pvpprofittracker;

import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
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
 * The player's OWN max hit, live, using the standard OSRS formulas: boosted levels (potions
 * included), active prayers, worn gear, and the selected combat style resolved from the game's
 * own weapon-style data. With the special-attack bar lit, the number switches to the spec's
 * ceiling for the known PvP spec weapons.
 *
 * Own-state only: per the Plugin Hub review of the 1.1.0 submission, everything opponent-facing
 * (hit chance, defensive assumptions, risk estimation) was removed.
 */
class CombatCalc
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CombatCalc.class);

	/** Damage scales with Strength and melee strength bonus — unique among ranged weapons. */
	private static final int ECLIPSE_ATLATL = 29000;
	private static final int DARK_BOW = 11235;
	private static final int DARK_BOW_BH = 27853;

	/**
	 * Special-attack MAX-HIT modifiers for the common PvP spec weapons, from the wiki's Special
	 * attacks page. Encoding: {damage multiplier, hits, show-as-total}. show-as-total = 1 means
	 * the number is the whole combo's ceiling (claws) rather than a per-hit value. Weapons not
	 * listed show their normal max hit while the spec bar is lit.
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
		SPEC_MAX.put(20368, new double[]{1.375, 1, 0}); // Armadyl godsword (or)
		SPEC_MAX.put(11804, new double[]{1.21, 1, 0});  // Bandos godsword
		SPEC_MAX.put(20370, new double[]{1.21, 1, 0});  // Bandos godsword (or)
		SPEC_MAX.put(27690, new double[]{1.5, 1, 0});   // Voidwaker (guaranteed, up to 150%)
		SPEC_MAX.put(DARK_BOW, new double[]{1.3, 2, 0});    // dark bow (1.5 with dragon arrows)
		SPEC_MAX.put(DARK_BOW_BH, new double[]{1.3, 2, 0}); // dark bow (bh)
		SPEC_MAX.put(19481, new double[]{1.25, 1, 0});  // heavy ballista
		SPEC_MAX.put(26712, new double[]{1.25, 1, 0});  // heavy ballista (or)
		SPEC_MAX.put(13576, new double[]{1.5, 1, 0});   // dragon warhammer
		SPEC_MAX.put(26710, new double[]{1.5, 1, 0});   // dragon warhammer (or)
		SPEC_MAX.put(28035, new double[]{1.5, 1, 0});   // corrupted dragon warhammer (bh)
		SPEC_MAX.put(21003, new double[]{1.0, 1, 0});   // elder maul (accuracy-only spec)
		SPEC_MAX.put(27100, new double[]{1.0, 1, 0});   // elder maul (or)
		SPEC_MAX.put(27857, new double[]{1.5, 1, 0});   // dragon mace (bh)
		SPEC_MAX.put(1305, new double[]{1.25, 1, 0});   // dragon longsword
		SPEC_MAX.put(27859, new double[]{1.25, 1, 0});  // dragon longsword (bh)
		SPEC_MAX.put(10887, new double[]{1.1, 1, 0});   // barrelchest anchor
		SPEC_MAX.put(27855, new double[]{1.1, 1, 0});   // barrelchest anchor (bh)
		final double[] abyDagger = {0.85, 2, 0};
		SPEC_MAX.put(13265, abyDagger); // abyssal dagger
		SPEC_MAX.put(13267, abyDagger); // abyssal dagger(p)
		SPEC_MAX.put(13269, abyDagger); // abyssal dagger(p+)
		SPEC_MAX.put(13271, abyDagger); // abyssal dagger(p++)
		SPEC_MAX.put(27861, abyDagger); // abyssal dagger (bh)
		SPEC_MAX.put(27863, abyDagger); // abyssal dagger (bh)(p)
		SPEC_MAX.put(27865, abyDagger); // abyssal dagger (bh)(p+)
		SPEC_MAX.put(27867, abyDagger); // abyssal dagger (bh)(p++)
		SPEC_MAX.put(20849, new double[]{1.0, 1, 0});   // dragon thrownaxe (accuracy-only)
		SPEC_MAX.put(3204, new double[]{1.1, 1, 0});    // dragon halberd
		SPEC_MAX.put(23987, new double[]{1.1, 1, 0});   // crystal halberd
	}

	/** Reads all game state on the client thread; the returned value is immutable. */
	private final Client client;
	private final ItemManager itemManager;
	// Change-detection for the spec debug line (client thread only).
	private boolean lastSpecActive;
	private int lastSpecWeapon = -1;

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

	/** One coherent read of the player's own numbers; fields final for EDT safety. */
	static final class MaxHitInfo
	{
		final Style style;
		final String styleName;   // display text, e.g. "Melee" or "Ranged (atlatl)"
		final int maxHit;         // -1 = spell-based (magic), unknown
		final boolean specActive; // the special-attack bar is lit
		final int specMaxHit;     // spec ceiling (-1 = weapon not in the table)
		final int specHits;       // hits per spec (2 for dds, etc.)
		final boolean specTotal;  // specMaxHit is the whole combo's ceiling (claws)

		MaxHitInfo(Style style, String styleName, int maxHit, boolean specActive, int specMaxHit,
			int specHits, boolean specTotal)
		{
			this.style = style;
			this.styleName = styleName;
			this.maxHit = maxHit;
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

		/** Display text for the max-hit line: "34", "2× 27", "up to 68", "spell-based". */
		String maxHitText()
		{
			if (maxHit < 0)
			{
				return "spell-based";
			}
			final int shown = specShown() ? specMaxHit : maxHit;
			return specShown() && specTotal ? "up to " + shown
				: specShown() && specHits > 1 ? specHits + "× " + shown
				: Integer.toString(shown);
		}
	}

	/** Compute the player's current max hit. Client thread only. */
	MaxHitInfo ownMaxHit()
	{
		final Stance stance = currentStance();
		final Bonuses mine = worn();
		final int weaponId = wornWeaponId();

		final Style style = stance.style;
		int maxHit = -1;
		String styleName;
		switch (style)
		{
			case MELEE:
			{
				styleName = "Melee";
				final int effStr = effectiveLevel(client.getBoostedSkillLevel(Skill.STRENGTH),
					strengthPrayerMult(), stance.strengthBonus);
				maxHit = maxHit(effStr, mine.str);
				break;
			}
			case RANGED:
			{
				if (weaponId == ECLIPSE_ATLATL)
				{
					// The atlatl's damage rides the STRENGTH level (visible boosts included) and
					// the gear's MELEE strength bonus, while ranged prayers boost the damage and
					// melee prayers do nothing — the wiki-documented one-off among ranged weapons.
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
				break;
			}
			case MAGIC:
				styleName = "Magic";
				maxHit = -1; // spell-dependent — shown as such rather than guessed
				break;
			default:
				styleName = "—";
				break;
		}

		// With the special-attack bar lit, the max-hit line switches to the spec's ceiling for
		// the known PvP spec weapons.
		final boolean specActive = client.getVarpValue(VarPlayerID.SA_ATTACK) == 1;
		int specMaxHit = -1;
		int specHits = 1;
		boolean specTotal = false;
		if (specActive && maxHit >= 0)
		{
			final double[] spec = SPEC_MAX.get(weaponId);
			if (spec != null)
			{
				double mult = spec[0];
				if ((weaponId == DARK_BOW || weaponId == DARK_BOW_BH) && wornAmmoIsDragonArrow())
				{
					mult = 1.5; // the dark bow spec jumps from 30% to 50% with dragon arrows
				}
				specMaxHit = (int) (maxHit * mult);
				specHits = (int) spec[1];
				specTotal = spec[2] == 1;
			}
		}
		if (specActive != lastSpecActive || weaponId != lastSpecWeapon)
		{
			// Change-only, so a missed table entry shows up in the dev log instead of silently
			// displaying the normal max (how the BH dragon mace gap was found).
			lastSpecActive = specActive;
			lastSpecWeapon = weaponId;
			log.debug("spec state: active={} weapon={} specMax={}", specActive, weaponId, specMaxHit);
		}

		return new MaxHitInfo(style, styleName, maxHit, specActive, specMaxHit, specHits, specTotal);
	}

	// --- The player's selected combat style, from the game's own weapon-style data ---

	private static final class Stance
	{
		final Style style;
		final int strengthBonus; // melee aggressive +3 / controlled +1
		final int rangedBonus;   // ranged accurate +3 (indistinguishable from rapid — kept at 0)

		Stance(Style style, int strengthBonus, int rangedBonus)
		{
			this.style = style;
			this.strengthBonus = strengthBonus;
			this.rangedBonus = rangedBonus;
		}
	}

	/**
	 * Resolve the selected attack style exactly like the core Attack Styles plugin: weapon
	 * category varbit → WEAPON_STYLES enum → style structs, with the defensive-autocast offset.
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
				return new Stance(Style.MELEE, 0, 0);
			case "Aggressive":
				return new Stance(Style.MELEE, 3, 0);
			case "Controlled":
				return new Stance(Style.MELEE, 1, 0);
			case "Defensive":
				return new Stance(Style.MELEE, 0, 0);
			case "Ranging":
				// Covers both the accurate (+3) and rapid (+0) styles — the game data doesn't
				// distinguish them here. Rapid is the PvP norm, so no bonus is applied.
				return new Stance(Style.RANGED, 0, 0);
			case "Longrange":
				return new Stance(Style.RANGED, 0, 0);
			case "Casting":
			case "Defensive casting":
				return new Stance(Style.MAGIC, 0, 0);
			default:
				return new Stance(Style.OTHER, 0, 0);
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

	// --- Equipment bonuses (own worn gear) ---

	private static final class Bonuses
	{
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
					final ItemStats stats = itemManager.getItemStats(item.getId());
					final ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
					if (e != null)
					{
						b.str += e.getStr();
						b.rstr += e.getRstr();
					}
				}
			}
		}
		return b;
	}

	// --- The player's active prayer multipliers (varbit reads, client thread) ---

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

	/** The best defensive prayer multiplier a given Prayer level allows. */
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
