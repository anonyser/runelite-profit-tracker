package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Completed-fight history behind the side panel's Past Fights lists. A fight is recorded only
 * when a kill or death actually lands in the W/L record — your death with the focused opponent
 * in sight, or their death while focused — so ordinary attacks and abandoned encounters never
 * appear. The whole history lives in one global config value as JSON, oldest first, capped so
 * it can't grow without bound. Pure helpers only: persistence belongs to the plugin, rendering
 * to the panel.
 */
final class FightHistory
{
	/** Newest fights push the oldest out past this. */
	static final int MAX_FIGHTS = 500;

	private static final DateTimeFormatter EXACT =
		DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu 'at' h:mm a", Locale.ENGLISH);

	private FightHistory()
	{
	}

	/** One completed fight. The field names are the stored JSON schema — never rename them. */
	static final class Fight
	{
		String name;      // opponent's display name as rendered in-game
		int cmb = -1;     // their combat level in the scene when the fight ended, -1 unknown
		boolean target;   // they were your Bounty Hunter target
		boolean bh;       // fought on a Bounty Hunter world (a non-target there is a rogue)
		boolean win;      // true = their death, false = yours
		boolean draw;     // double death: you both died within seconds (win is meaningless then).
		                  // Absent in pre-draw JSON, so old fights read as plain wins/losses.
		boolean noResult; // the fight went quiet with nobody dead — kept for review, never
		                  // counted in the W/L record (win is meaningless here too)
		long ts;          // epoch millis when the fight ended

		Fight()
		{
			// Gson
		}

		Fight(String name, int cmb, boolean target, boolean bh, boolean win, long ts)
		{
			this.name = name;
			this.cmb = cmb;
			this.target = target;
			this.bh = bh;
			this.win = win;
			this.ts = ts;
		}

		/** "Win" / "Loss" / "Draw" / "No result" for chips and rows. */
		String outcomeText()
		{
			return draw ? "Draw" : noResult ? "No result" : win ? "Win" : "Loss";
		}
	}

	/**
	 * Collapse a double death into one draw: remove the most recent win and loss vs this name
	 * within the pair window and append a single draw entry carrying the EARLIER entry's
	 * timestamp (the fight log, if one was saved, is keyed by that ts). Returns the draw, or
	 * null when the expected pair isn't there (nothing is changed then).
	 */
	static Fight mergeDoubleDeath(List<Fight> fights, String name, long windowMs)
	{
		int winIdx = -1;
		int lossIdx = -1;
		for (int i = fights.size() - 1; i >= 0 && (winIdx < 0 || lossIdx < 0); i--)
		{
			final Fight f = fights.get(i);
			// No-result entries carry win=false but were never a death — they can't be the pair.
			if (f.draw || f.noResult || !f.name.equalsIgnoreCase(name))
			{
				continue;
			}
			if (f.win && winIdx < 0)
			{
				winIdx = i;
			}
			else if (!f.win && lossIdx < 0)
			{
				lossIdx = i;
			}
		}
		if (winIdx < 0 || lossIdx < 0
			|| Math.abs(fights.get(winIdx).ts - fights.get(lossIdx).ts) > windowMs)
		{
			return null;
		}
		final Fight a = fights.get(winIdx);
		final Fight b = fights.get(lossIdx);
		final Fight earlier = a.ts <= b.ts ? a : b;
		final Fight drawFight = new Fight(earlier.name, Math.max(a.cmb, b.cmb),
			a.target || b.target, a.bh || b.bh, false, earlier.ts);
		drawFight.draw = true;
		fights.remove(Math.max(winIdx, lossIdx));
		fights.remove(Math.min(winIdx, lossIdx));
		fights.add(drawFight);
		return drawFight;
	}

	/** Parse the stored JSON list; malformed or missing input is just an empty history. */
	static List<Fight> parse(Gson gson, String json)
	{
		final List<Fight> out = new ArrayList<>();
		if (json == null || json.isEmpty())
		{
			return out;
		}
		try
		{
			final Fight[] arr = gson.fromJson(json, Fight[].class);
			if (arr != null)
			{
				for (final Fight f : arr)
				{
					if (f != null && f.name != null)
					{
						out.add(f);
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			// Corrupt value — start fresh rather than take the panel down with it.
		}
		return out;
	}

	static String serialize(Gson gson, List<Fight> fights)
	{
		return gson.toJson(fights);
	}

	/** Append a fight, dropping the oldest past the cap. */
	static void append(List<Fight> fights, Fight f)
	{
		fights.add(f);
		while (fights.size() > MAX_FIGHTS)
		{
			fights.remove(0);
		}
	}

	/** "Sunday, 12 July 2026 at 3:45 PM" in the machine's own timezone. */
	static String exact(long ts)
	{
		return exact(ts, ZoneId.systemDefault());
	}

	static String exact(long ts, ZoneId zone)
	{
		return EXACT.format(Instant.ofEpochMilli(ts).atZone(zone));
	}

	/** "1 hour 30 minutes ago" — the two largest non-zero units; under a minute is "just now". */
	static String relative(long nowMs, long ts)
	{
		final long seconds = Math.max(0, (nowMs - ts) / 1000);
		if (seconds < 60)
		{
			return "just now";
		}
		final long minutes = seconds / 60;
		final long hours = minutes / 60;
		final long days = hours / 24;
		if (days > 0)
		{
			final long restHours = hours % 24;
			return unit(days, "day") + (restHours > 0 ? " " + unit(restHours, "hour") : "") + " ago";
		}
		if (hours > 0)
		{
			final long restMinutes = minutes % 60;
			return unit(hours, "hour") + (restMinutes > 0 ? " " + unit(restMinutes, "minute") : "")
				+ " ago";
		}
		return unit(minutes, "minute") + " ago";
	}

	private static String unit(long n, String word)
	{
		return n + " " + word + (n == 1 ? "" : "s");
	}

	/**
	 * Exact combat level (the game floors it for display): 0.25 × (def + hp + ⌊prayer/2⌋) plus
	 * the best of melee 0.325 × (att + str), ranged 0.325 × ⌊3·ranged/2⌋ and magic
	 * 0.325 × ⌊3·magic/2⌋. -1 unless all seven stats are known.
	 */
	static double combatLevel(int attack, int strength, int defence, int ranged, int magic,
		int hitpoints, int prayer)
	{
		if (attack <= 0 || strength <= 0 || defence <= 0 || ranged <= 0
			|| magic <= 0 || hitpoints <= 0 || prayer <= 0)
		{
			return -1;
		}
		final double base = 0.25 * (defence + hitpoints + prayer / 2);
		final double melee = 0.325 * (attack + strength);
		final double range = 0.325 * (ranged + ranged / 2);
		final double mage = 0.325 * (magic + magic / 2);
		return base + Math.max(melee, Math.max(range, mage));
	}
}
