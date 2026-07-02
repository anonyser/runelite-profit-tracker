package com.anonyser.pvpprofittracker;

/**
 * A mutable tally for one tracking scope (session / since-enabled / tracked). All gp values are in coins.
 * Profit is event-sourced: gains from kills, minus what's lost on death, minus consumables used in PvP.
 */
class Stats
{
	long kills;
	long deaths;
	long gainedGp;      // loot keys claimed
	long lostToDeathGp; // risk lost on our deaths
	long consumedGp;    // food / potions used in PvP

	long profit()
	{
		return gainedGp - lostToDeathGp - consumedGp;
	}

	double kd()
	{
		return deaths == 0 ? kills : (double) kills / deaths;
	}

	void addKill(long gp)
	{
		kills++;
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

	void reset()
	{
		kills = 0;
		deaths = 0;
		gainedGp = 0;
		lostToDeathGp = 0;
		consumedGp = 0;
	}
}
