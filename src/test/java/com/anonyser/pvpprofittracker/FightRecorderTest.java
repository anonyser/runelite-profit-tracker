package com.anonyser.pvpprofittracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FightRecorderTest
{
	private static FightLog.Entry entry(boolean me, int amount)
	{
		final FightLog.Entry e = new FightLog.Entry();
		e.me = me;
		e.amount = amount;
		e.weapon = 1215;
		e.wname = "Dragon dagger";
		return e;
	}

	@Test
	public void settleStampsTheDefendersHp()
	{
		final FightRecorder r = new FightRecorder();
		r.start("Foe", 1_000L, 100);
		r.onDamage(100, true, 30, 1215, "Dragon dagger", false);
		r.onDamage(100, false, 12, 4151, "Abyssal whip", false);
		r.settle(87, 60, false);
		final FightLog log = r.finish("Win", 2_000L, 110, false);
		assertNotNull(log);
		assertEquals(2, log.hits.size());
		// My hit's defender is them; their hit's defender is me.
		assertEquals(60, log.hits.get(0).hp);
		assertEquals(87, log.hits.get(1).hp);
		assertEquals(10, log.duration);
		assertEquals("Win", log.outcome);
	}

	@Test
	public void unsettledHitsKeepUnknownHp()
	{
		final FightRecorder r = new FightRecorder();
		r.start("Foe", 1_000L, 100);
		r.onDamage(100, true, 30, 1215, "Dragon dagger", true);
		final FightLog log = r.finish("Loss", 2_000L, 105, false);
		assertNotNull(log);
		assertEquals(-1, log.hits.get(0).hp);
		assertTrue(log.hits.get(0).spec);
	}

	@Test
	public void nothingRecordedMeansNoLog()
	{
		final FightRecorder r = new FightRecorder();
		r.start("Foe", 1_000L, 100);
		assertNull(r.finish("Win", 2_000L, 110, false));
		assertFalse(r.isActive());
	}

	@Test
	public void disengageTrips50TicksAfterTheLastHit()
	{
		final FightRecorder r = new FightRecorder();
		r.start("Foe", 1_000L, 100);
		r.onDamage(120, true, 5, -1, null, false);
		assertFalse(r.disengaged(120 + FightRecorder.DISENGAGE_TICKS));
		assertTrue(r.disengaged(121 + FightRecorder.DISENGAGE_TICKS));
	}

	@Test
	public void theirRecoilOnMeAlwaysLabels()
	{
		// I hit 40; their (invisible) ring returns 4 = ceil(10%). Same tick, forward order.
		final List<FightLog.Entry> tick = new ArrayList<>();
		tick.add(entry(true, 40));
		tick.add(entry(false, 4));
		FightRecorder.labelReflections(tick, false);
		assertNull(tick.get(0).tag);
		assertEquals("recoil", tick.get(1).tag);
		assertEquals(-1, tick.get(1).weapon);
	}

	@Test
	public void myRecoilOnThemNeedsTheRingWorn()
	{
		// They hit 47; my ring's 5 displays FIRST (seen live). Reversed-order shape.
		final List<FightLog.Entry> withoutRing = new ArrayList<>();
		withoutRing.add(entry(true, 5));
		withoutRing.add(entry(false, 47));
		FightRecorder.labelReflections(withoutRing, false);
		assertNull(withoutRing.get(0).tag);

		final List<FightLog.Entry> withRing = new ArrayList<>();
		withRing.add(entry(true, 5));
		withRing.add(entry(false, 47));
		FightRecorder.labelReflections(withRing, true);
		assertEquals("recoil", withRing.get(0).tag);
	}

	@Test
	public void vengeanceLabelsWithoutAnyRing()
	{
		// They hit me 20; my Vengeance returns floor(15) on them.
		final List<FightLog.Entry> tick = new ArrayList<>();
		tick.add(entry(false, 20));
		tick.add(entry(true, 15));
		FightRecorder.labelReflections(tick, false);
		assertEquals("venge", tick.get(1).tag);
		assertFalse(tick.get(1).spec);
	}

	@Test
	public void ordinaryExchangesAreNotLabelled()
	{
		// 7 after a 25 shapes as neither recoil (3) nor venge (18), in either order.
		final List<FightLog.Entry> tick = new ArrayList<>();
		tick.add(entry(true, 25));
		tick.add(entry(false, 7));
		FightRecorder.labelReflections(tick, false);
		assertNull(tick.get(0).tag);
		assertNull(tick.get(1).tag);
	}

	@Test
	public void reflectShapesMatchTheLiveTestedMaths()
	{
		assertEquals('R', FightRecorder.reflectShape(40, 4));
		assertEquals('R', FightRecorder.reflectShape(47, 5));
		assertEquals('R', FightRecorder.reflectShape(42, 5));
		assertEquals('V', FightRecorder.reflectShape(20, 15));
		assertEquals('-', FightRecorder.reflectShape(3, 25));
		assertEquals('-', FightRecorder.reflectShape(0, 4));
		assertEquals('-', FightRecorder.reflectShape(40, 0));
	}
}
