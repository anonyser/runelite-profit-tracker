package com.anonyser.pvpprofittracker;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(PvpProfitTrackerConfig.GROUP)
public interface PvpProfitTrackerConfig extends Config
{
	String GROUP = "pvpprofittracker";

	@ConfigSection(
		name = "Display",
		description = "Overlay appearance",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Tracking",
		description = "What counts, and where",
		position = 1
	)
	String trackingSection = "tracking";

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
		keyName = "showRisk",
		name = "Show risk",
		description = "Show what you would lose if you died right now.",
		position = 1,
		section = displaySection
	)
	default boolean showRisk()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNetWorth",
		name = "Show net worth",
		description = "Show total account value (bank + worn + inventory).",
		position = 2,
		section = displaySection
	)
	default boolean showNetWorth()
	{
		return true;
	}

	@Range(min = 6, max = 40)
	@ConfigItem(
		keyName = "fontSize",
		name = "Font size",
		description = "Overlay text size.",
		position = 3,
		section = displaySection
	)
	default int fontSize()
	{
		return 14;
	}

	@ConfigItem(
		keyName = "profitColor",
		name = "Profit colour",
		description = "Colour used when you are in profit.",
		position = 4,
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
		position = 5,
		section = displaySection
	)
	default Color lossColor()
	{
		return new Color(216, 60, 62);
	}

	@ConfigItem(
		keyName = "pvpOnly",
		name = "Wilderness / PvP only",
		description = "Only count kills, deaths and consumables while in a PvP context (recommended).",
		position = 0,
		section = trackingSection
	)
	default boolean pvpOnly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "Log game ids (containers, animations, varbits, widgets) to help capture PvP data. "
			+ "Leave off for normal play.",
		position = 9,
		section = trackingSection
	)
	default boolean debugLogging()
	{
		return false;
	}
}
