package com.anonyser.pvpprofittracker;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FightLogTest
{
	private static FightLog log(String name, long ts)
	{
		final FightLog l = new FightLog();
		l.name = name;
		l.ts = ts;
		l.startTs = ts - 30_000;
		l.duration = 50;
		l.outcome = "Win";
		final FightLog.Entry e = new FightLog.Entry();
		e.t = 3;
		e.me = true;
		e.weapon = 1215;
		e.wname = "Dragon dagger";
		e.amount = 22;
		e.hp = 41;
		l.hits.add(e);
		return l;
	}

	@Test
	public void roundTripsThroughJson()
	{
		final Gson gson = new Gson();
		final List<FightLog> logs = new ArrayList<>();
		logs.add(log("Foe", 1000L));
		final List<FightLog> back = FightLog.parse(gson, FightLog.serialize(gson, logs));
		assertEquals(1, back.size());
		final FightLog l = back.get(0);
		assertEquals("Foe", l.name);
		assertEquals(1000L, l.ts);
		assertEquals("Win", l.outcome);
		assertEquals(1, l.hits.size());
		assertEquals(22, l.hits.get(0).amount);
		assertEquals("Dragon dagger", l.hits.get(0).wname);
		assertEquals(41, l.hits.get(0).hp);
		assertTrue(l.hits.get(0).me);
	}

	@Test
	public void garbageAndEmptyParseToNothing()
	{
		final Gson gson = new Gson();
		assertTrue(FightLog.parse(gson, null).isEmpty());
		assertTrue(FightLog.parse(gson, "").isEmpty());
		assertTrue(FightLog.parse(gson, "not json").isEmpty());
	}

	@Test
	public void appendDropsTheOldestPastTheCap()
	{
		final List<FightLog> logs = new ArrayList<>();
		for (int i = 0; i < FightLog.MAX_LOGS + 5; i++)
		{
			FightLog.append(logs, log("F" + i, i));
		}
		assertEquals(FightLog.MAX_LOGS, logs.size());
		assertEquals(5, logs.get(0).ts);
		assertNull(FightLog.byTs(logs, 4));
		assertNotNull(FightLog.byTs(logs, FightLog.MAX_LOGS + 4));
	}

	@Test
	public void clockFormatsTicksAsMinutesAndSeconds()
	{
		assertEquals("0:00", FightLog.clock(0));
		assertEquals("0:10", FightLog.clock(17));
		assertEquals("1:00", FightLog.clock(100));
		assertEquals("2:06", FightLog.clock(210));
	}
}
