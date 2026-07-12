package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FightHistoryTest
{
	@Test
	public void relativeShowsTheTwoLargestUnits()
	{
		final long now = 1_000_000_000_000L;
		assertEquals("just now", FightHistory.relative(now, now));
		assertEquals("just now", FightHistory.relative(now, now - 59_000));
		assertEquals("1 minute ago", FightHistory.relative(now, now - 60_000));
		assertEquals("12 minutes ago", FightHistory.relative(now, now - 12 * 60_000));
		assertEquals("1 hour 30 minutes ago", FightHistory.relative(now, now - 90 * 60_000));
		assertEquals("2 hours ago", FightHistory.relative(now, now - 2 * 3_600_000));
		assertEquals("1 day ago", FightHistory.relative(now, now - 24 * 3_600_000L));
		assertEquals("3 days 2 hours ago",
			FightHistory.relative(now, now - (3 * 24 + 2) * 3_600_000L));
		// A clock that went backwards must never render a negative age.
		assertEquals("just now", FightHistory.relative(now, now + 5_000));
	}

	@Test
	public void exactUsesTheFullWeekdayDateAndTime()
	{
		// 2026-07-12 15:45 UTC is a Sunday.
		final long ts = ZonedDateTime.of(2026, 7, 12, 15, 45, 0, 0, ZoneOffset.UTC)
			.toInstant().toEpochMilli();
		assertEquals("Sunday, 12 July 2026 at 3:45 PM", FightHistory.exact(ts, ZoneOffset.UTC));
	}

	@Test
	public void combatLevelMatchesTheGameFormula()
	{
		// Maxed combat stats: 0.25 × (99 + 99 + 49) + 0.325 × (99 + 99) = 126.1
		assertEquals(126.1, FightHistory.combatLevel(99, 99, 99, 99, 99, 99, 99), 1e-9);
		// A fresh level-3: 0.25 × (1 + 10 + 0) + 0.325 × (1 + 1) = 3.4
		assertEquals(3.4, FightHistory.combatLevel(1, 1, 1, 1, 1, 10, 1), 1e-9);
		// Unknown until every stat has answered.
		assertEquals(-1, FightHistory.combatLevel(99, 99, 99, 99, 99, 99, 0), 1e-9);
	}

	@Test
	public void historyRoundTripsThroughJson()
	{
		final Gson gson = new Gson();
		final List<FightHistory.Fight> fights = new ArrayList<>();
		FightHistory.append(fights, new FightHistory.Fight("Some Guy", 126, true, true, true, 123L));
		FightHistory.append(fights, new FightHistory.Fight("Other", 90, false, false, false, 456L));
		final List<FightHistory.Fight> back =
			FightHistory.parse(gson, FightHistory.serialize(gson, fights));
		assertEquals(2, back.size());
		assertEquals("Some Guy", back.get(0).name);
		assertEquals(126, back.get(0).cmb);
		assertTrue(back.get(0).target);
		assertTrue(back.get(0).bh);
		assertTrue(back.get(0).win);
		assertEquals(123L, back.get(0).ts);
		assertEquals("Other", back.get(1).name);
		assertFalse(back.get(1).win);
	}

	@Test
	public void appendDropsTheOldestPastTheCap()
	{
		final List<FightHistory.Fight> fights = new ArrayList<>();
		for (int i = 0; i < FightHistory.MAX_FIGHTS + 25; i++)
		{
			FightHistory.append(fights, new FightHistory.Fight("N" + i, 3, false, false, true, i));
		}
		assertEquals(FightHistory.MAX_FIGHTS, fights.size());
		// The 25 oldest were dropped, the newest survived.
		assertEquals("N25", fights.get(0).name);
		assertEquals("N" + (FightHistory.MAX_FIGHTS + 24), fights.get(fights.size() - 1).name);
	}

	@Test
	public void malformedStorageParsesToAnEmptyHistory()
	{
		final Gson gson = new Gson();
		assertTrue(FightHistory.parse(gson, null).isEmpty());
		assertTrue(FightHistory.parse(gson, "").isEmpty());
		assertTrue(FightHistory.parse(gson, "not json").isEmpty());
		assertTrue(FightHistory.parse(gson, "{\"a\":1}").isEmpty());
		// A null element or a record missing its name is skipped, not fatal.
		assertEquals(1, FightHistory.parse(gson, "[null, {\"ts\":5}, {\"name\":\"X\"}]").size());
	}
}
