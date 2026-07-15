package com.anonyser.pvpprofittracker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatsTest
{
	@Test
	public void recoveredCountsTowardProfit()
	{
		final Stats s = new Stats();
		s.addGain(1000);
		s.addDeath(5000);
		assertEquals(-4000, s.profit());
		// Loot half of it back after a double death — profit should climb by exactly that much.
		s.addRecovered(3000);
		assertEquals(-1000, s.profit());
	}

	@Test
	public void resetProfitClearsRecovered()
	{
		final Stats s = new Stats();
		s.addRecovered(1234);
		s.resetProfit();
		assertEquals(0, s.recoveredGp);
		assertEquals(0, s.profit());
	}

	@Test
	public void copyFromCarriesRecovered()
	{
		final Stats a = new Stats();
		a.addRecovered(777);
		final Stats b = new Stats();
		b.copyFrom(a);
		assertEquals(777, b.recoveredGp);

		final Stats c = new Stats();
		c.addRecovered(50);
		c.copyFrom(null);
		assertEquals(0, c.recoveredGp);
	}
}
