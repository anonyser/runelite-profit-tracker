package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;

/**
 * The hit-by-hit breakdown of one recorded fight, keyed to its {@link FightHistory.Fight} by the
 * fight's end timestamp. Only fights that actually produced a Win/Loss/Draw entry keep a log, and
 * only the most recent {@link #MAX_LOGS} keep one at all — the one-line history holds far more
 * fights than the logs do, so old fights simply stop offering a breakdown. All lives in one global
 * config value as JSON. Pure data + helpers; persistence belongs to the plugin, rendering to the
 * breakdown window.
 */
final class FightLog
{
	/** Newest fights keep a full log; anything older loses its breakdown (the history line stays). */
	static final int MAX_LOGS = 25;

	/** One damage splat. The field names are the stored JSON schema — never rename them. */
	static final class Entry
	{
		int t;            // game ticks since the fight's first hit (~0.6s each)
		boolean me;       // true = your hit on them, false = their hit on you
		int weapon = -1;  // attacker's weapon item id when the hit landed, -1 unknown
		String wname;     // weapon name, resolved at record time on the client thread
		boolean spec;     // landed right after your spec energy dropped (your hits only)
		String tag;       // null, or "recoil"/"venge" when the amounts shape as a reflection
		int amount;       // damage on the splat (0 = blocked)
		int hp = -1;      // defender's HP once the tick settled: yours exact, theirs estimated

		Entry()
		{
			// Gson
		}
	}

	String name;      // opponent display name
	long ts;          // the matching Fight's end-timestamp (epoch ms) — the lookup key
	long startTs;     // epoch ms of the first hit, for the header
	int duration;     // ticks from first damage to the fight's end
	String outcome;   // "Win" / "Loss" / "Draw" at save time
	List<Entry> hits = new ArrayList<>();

	FightLog()
	{
		// Gson
	}

	/** Parse the stored JSON list; malformed or missing input is just an empty list. */
	static List<FightLog> parse(Gson gson, String json)
	{
		final List<FightLog> out = new ArrayList<>();
		if (json == null || json.isEmpty())
		{
			return out;
		}
		try
		{
			final FightLog[] arr = gson.fromJson(json, FightLog[].class);
			if (arr != null)
			{
				for (final FightLog l : arr)
				{
					if (l != null && l.name != null)
					{
						if (l.hits == null)
						{
							l.hits = new ArrayList<>();
						}
						out.add(l);
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			// Corrupt value — drop the breakdowns rather than take the plugin down.
		}
		return out;
	}

	static String serialize(Gson gson, List<FightLog> logs)
	{
		return gson.toJson(logs);
	}

	/** Append a log, dropping the oldest past the cap. */
	static void append(List<FightLog> logs, FightLog l)
	{
		logs.add(l);
		while (logs.size() > MAX_LOGS)
		{
			logs.remove(0);
		}
	}

	/** The log saved for the fight ending at this timestamp, or null. */
	static FightLog byTs(List<FightLog> logs, long ts)
	{
		for (int i = logs.size() - 1; i >= 0; i--)
		{
			if (logs.get(i).ts == ts)
			{
				return logs.get(i);
			}
		}
		return null;
	}

	/** "m:ss" from a tick count (~0.6s per tick). */
	static String clock(int ticks)
	{
		final int totalSeconds = (int) Math.round(ticks * 0.6);
		return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
	}
}
