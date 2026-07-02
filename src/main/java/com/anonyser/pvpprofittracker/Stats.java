package com.anonyser.pvpprofittracker;

/**
 * A mutable tally for one tracking mode (session / baseline; "actual" uses only kills and deaths).
 * All gp values are in coins. Profit is event-sourced: loot-key gains plus crate rewards, minus
 * what's lost on death, minus consumables used in PvP — never a net-worth diff.
 */
class Stats
{
	long kills;
	long deaths;
	long gainedGp;      // loot keys claimed
	long lostToDeathGp; // risk lost on our deaths
	long consumedGp;    // food / potions used in PvP
	long crates;        // Bounty Hunter crates received
	long crateGp;       // value looted from opened Bounty Hunter crates
	long points;        // Bounty Hunter points gained

	long profit()
	{
		return gainedGp + crateGp - lostToDeathGp - consumedGp;
	}

	double kd()
	{
		return deaths == 0 ? kills : (double) kills / deaths;
	}

	void addKill()
	{
		kills++;
	}

	void addGain(long gp)
	{
		gainedGp += gp;
	}

	void addDeath(long lostGp)
	{
		deaths++;
		lostToDeathGp += lostGp;
	}

	void addConsumed(long gp)
	{
		consumedGp += gp;
	}

	void addCrates(int n)
	{
		crates += n;
	}

	void addCrateValue(long gp)
	{
		crateGp += gp;
	}

	void addPoints(long n)
	{
		points += n;
	}

	// Each tracker section resets independently (baseline K/D vs profit vs crates vs points).

	void resetKd()
	{
		kills = 0;
		deaths = 0;
	}

	void resetProfit()
	{
		gainedGp = 0;
		lostToDeathGp = 0;
		consumedGp = 0;
		crateGp = 0;
	}

	void resetCrates()
	{
		crates = 0;
	}

	void resetPoints()
	{
		points = 0;
	}

	void reset()
	{
		resetKd();
		resetProfit();
		resetCrates();
		resetPoints();
	}

	/** Copy another tally's values in place (null clears), keeping this object's identity stable. */
	void copyFrom(Stats o)
	{
		if (o == null)
		{
			reset();
			return;
		}
		kills = o.kills;
		deaths = o.deaths;
		gainedGp = o.gainedGp;
		lostToDeathGp = o.lostToDeathGp;
		consumedGp = o.consumedGp;
		crates = o.crates;
		crateGp = o.crateGp;
		points = o.points;
	}
}
