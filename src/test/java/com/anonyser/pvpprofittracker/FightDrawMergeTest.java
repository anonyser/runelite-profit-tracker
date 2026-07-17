package com.anonyser.pvpprofittracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FightDrawMergeTest
{
	private static FightHistory.Fight fight(String name, boolean win, long ts)
	{
		return new FightHistory.Fight(name, 100, false, false, win, ts);
	}

	@Test
	public void winAndLossCollapseIntoOneDrawKeepingTheEarlierTs()
	{
		final List<FightHistory.Fight> fights = new ArrayList<>();
		fights.add(fight("Other", true, 500));
		fights.add(fight("Foe", false, 1_000)); // my death landed first
		fights.add(fight("Foe", true, 3_000));  // theirs three seconds later
		final FightHistory.Fight draw = FightHistory.mergeDoubleDeath(fights, "Foe", 60_000);
		assertNotNull(draw);
		assertTrue(draw.draw);
		assertFalse(draw.win);
		assertEquals(1_000, draw.ts); // the first record's ts — the fight log's key
		assertEquals("Draw", draw.outcomeText());
		assertEquals(2, fights.size()); // the unrelated fight + the draw
		assertEquals("Other", fights.get(0).name);
	}

	@Test
	public void nameMatchIsCaseInsensitiveAndOldDrawsAreNeverReused()
	{
		final List<FightHistory.Fight> fights = new ArrayList<>();
		final FightHistory.Fight old = fight("foe", false, 100);
		old.draw = true;
		fights.add(old);
		fights.add(fight("FOE", true, 1_000));
		fights.add(fight("foe", false, 2_000));
		final FightHistory.Fight draw = FightHistory.mergeDoubleDeath(fights, "Foe", 60_000);
		assertNotNull(draw);
		assertEquals(2, fights.size());
		assertTrue(fights.get(0).draw); // the old draw was left alone
		assertEquals(1_000, draw.ts);
	}

	@Test
	public void noPairMeansNoChange()
	{
		final List<FightHistory.Fight> fights = new ArrayList<>();
		fights.add(fight("Foe", true, 1_000)); // a win with no matching loss
		assertNull(FightHistory.mergeDoubleDeath(fights, "Foe", 60_000));
		assertEquals(1, fights.size());
		assertFalse(fights.get(0).draw);
	}

	@Test
	public void aStalePairOutsideTheWindowIsNotMerged()
	{
		final List<FightHistory.Fight> fights = new ArrayList<>();
		fights.add(fight("Foe", false, 1_000));
		fights.add(fight("Foe", true, 500_000)); // an old loss + a much later win
		assertNull(FightHistory.mergeDoubleDeath(fights, "Foe", 60_000));
		assertEquals(2, fights.size());
	}

	@Test
	public void oldJsonWithoutTheDrawFieldReadsAsWinOrLoss()
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final List<FightHistory.Fight> back = FightHistory.parse(gson,
			"[{\"name\":\"Foe\",\"cmb\":100,\"target\":false,\"bh\":false,\"win\":true,\"ts\":5}]");
		assertEquals(1, back.size());
		assertFalse(back.get(0).draw);
		assertEquals("Win", back.get(0).outcomeText());
	}
}
