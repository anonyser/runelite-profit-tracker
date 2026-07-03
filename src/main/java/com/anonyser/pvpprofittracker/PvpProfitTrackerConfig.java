package com.anonyser.pvpprofittracker;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(PvpProfitTrackerConfig.GROUP)
public interface PvpProfitTrackerConfig extends Config
{
	String GROUP = "pvpprofittracker";

	// Shared explainer for the three tracking modes, shown on every tracker toggle.
	String MODES = " — Actual: your true in-game value. Session: this session only, resets on restart. "
		+ "Baseline: long-term tally that keeps saving across sessions until you reset it. "
		+ "Reset buttons live on the plugin's side panel (the green $ icon in the sidebar).";

	@ConfigSection(
		name = "Display",
		description = "Overlay appearance and number formatting",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Trackers",
		description = "Which values are shown. Live details and the baseline reset buttons are on the "
			+ "plugin's side panel — the green $ icon in the sidebar." + MODES,
		position = 1
	)
	String trackersSection = "trackers";

	@ConfigSection(
		name = "Advanced",
		description = "Tracking behaviour and debugging",
		position = 2
	)
	String advancedSection = "advanced";

	// --- Display ---

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show the profit overlay on screen.",
		position = 0,
		section = displaySection
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gpFormat",
		name = "Number format",
		description = "Full numbers (1,428,638) or compact (1.428M / 900M / 1.428B) for gp values.",
		position = 1,
		section = displaySection
	)
	default GpFormat gpFormat()
	{
		return GpFormat.FULL;
	}

	@ConfigItem(
		keyName = "profitColor",
		name = "Profit colour",
		description = "Colour used when you are in profit.",
		position = 2,
		section = displaySection
	)
	default Color profitColor()
	{
		return new Color(0, 200, 83);
	}

	@ConfigItem(
		keyName = "lossColor",
		name = "Loss colour",
		description = "Colour used when you are down overall.",
		position = 3,
		section = displaySection
	)
	default Color lossColor()
	{
		return new Color(216, 60, 62);
	}

	// --- Trackers (listed in display order: K/D, profit, risk, net worth, crates, points) ---

	@ConfigItem(
		keyName = "showActualKd",
		name = "Actual K/D",
		description = "Your true in-game K/D. Load it once by opening the Kill Death Ratio window at "
			+ "Edgeville; kills and deaths keep counting from there. Counts world PvP only — "
			+ "Bounty Hunter kills/deaths aren't included in the game's number." + MODES,
		position = 0,
		section = trackersSection
	)
	default boolean showActualKd()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBaselineKd",
		name = "Baseline K/D",
		description = "Kills and deaths since you last reset the baseline." + MODES,
		position = 1,
		section = trackersSection
	)
	default boolean showBaselineKd()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSessionKd",
		name = "Session K/D",
		description = "Kills and deaths this session." + MODES,
		position = 2,
		section = trackersSection
	)
	default boolean showSessionKd()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBaselineProfit",
		name = "Baseline profit",
		description = "Profit since you last reset the baseline. There is no \"actual\" profit — the plugin "
			+ "can't know your history from before it was tracking." + MODES,
		position = 3,
		section = trackersSection
	)
	default boolean showBaselineProfit()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSessionProfit",
		name = "Session profit",
		description = "Profit this session: loot keys + crate rewards − deaths − consumables." + MODES,
		position = 4,
		section = trackersSection
	)
	default boolean showSessionProfit()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRisk",
		name = "Risk",
		description = "What you would lose if you died right now. On death this is applied to profit "
			+ "as a loss.",
		position = 5,
		section = trackersSection
	)
	default boolean showRisk()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNetWorth",
		name = "Net worth",
		description = "Total account value (bank + worn + inventory). Informational only — never counts "
			+ "toward profit. Open your bank to record it.",
		position = 6,
		section = trackersSection
	)
	default boolean showNetWorth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSessionCrates",
		name = "Session crates",
		description = "Bounty Hunter crates received this session." + MODES,
		position = 7,
		section = trackersSection
	)
	default boolean showSessionCrates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBaselineCrates",
		name = "Baseline crates",
		description = "Bounty Hunter crates received since you last reset the baseline." + MODES,
		position = 8,
		section = trackersSection
	)
	default boolean showBaselineCrates()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showCurrentPoints",
		name = "Current points",
		description = "Your actual Bounty Hunter points balance, straight from the game — goes down "
			+ "when you spend. Session/baseline track points gained instead.",
		position = 9,
		section = trackersSection
	)
	default boolean showCurrentPoints()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBaselinePoints",
		name = "Baseline points",
		description = "Bounty Hunter points gained since you last reset the baseline." + MODES,
		position = 10,
		section = trackersSection
	)
	default boolean showBaselinePoints()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSessionPoints",
		name = "Session points",
		description = "Bounty Hunter points gained this session (read from the game's own points value)."
			+ MODES,
		position = 11,
		section = trackersSection
	)
	default boolean showSessionPoints()
	{
		return true;
	}

	// --- Advanced ---

	@ConfigItem(
		keyName = "pvpOnly",
		name = "Wilderness / PvP only",
		description = "Only count kills, deaths and consumables while in a PvP context (recommended).",
		position = 0,
		section = advancedSection
	)
	default boolean pvpOnly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "Log game ids (containers, animations, widgets, chat) to help capture PvP data. "
			+ "Leave off for normal play.",
		position = 1,
		section = advancedSection
	)
	default boolean debugLogging()
	{
		return false;
	}

	@ConfigItem(
		keyName = "priceOverrides",
		name = "Price overrides",
		description = "Prices for items the live price feed doesn't know yet (brand-new releases), "
			+ "as itemId=gp pairs separated by commas, e.g. 33631=11025000. Only used while the "
			+ "feed has no price for the item.",
		position = 2,
		section = advancedSection
	)
	default String priceOverrides()
	{
		return "";
	}
}
