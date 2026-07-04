package com.anonyser.pvpprofittracker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CombatCalcTest
{
	@Test
	public void effectiveLevelFloorsThePrayerProductBeforeAddingStance()
	{
		// 118 boosted × 1.23 (Piety) = 145.14 → floors to 145, then +3 aggressive and +8
		assertEquals(156, CombatCalc.effectiveLevel(118, 1.23, 3));
		// no prayer, no stance: level + 8
		assertEquals(107, CombatCalc.effectiveLevel(99, 1.0, 0));
	}

	@Test
	public void maxHitAppliesTheHalfRoundingOfTheStandardFormula()
	{
		// floor(0.5 + 156 × (118 + 64) / 640) = floor(44.8625)
		assertEquals(44, CombatCalc.maxHit(156, 118));
		// floor(0.5 + 107 × 64 / 640) = floor(11.2)
		assertEquals(11, CombatCalc.maxHit(107, 0));
	}

	@Test
	public void hitChanceFollowsTheAccuracyFormula()
	{
		// equal rolls sit just under a coin flip: a / (2(d+1))
		assertEquals(10000d / 20002d, CombatCalc.hitChance(10000, 10000), 1e-12);
		// attacker ahead: 1 - (d+2) / (2(a+1))
		assertEquals(1d - 5002d / 20002d, CombatCalc.hitChance(10000, 5000), 1e-12);
		assertTrue(CombatCalc.hitChance(20000, 10000) > CombatCalc.hitChance(10000, 10000));
		assertEquals(0, CombatCalc.hitChance(0, 100), 0);
	}

	@Test
	public void superCombatBoostIsFivePlusFifteenPercent()
	{
		assertEquals(19, CombatCalc.superCombatBoost(99));
		assertEquals(15, CombatCalc.superCombatBoost(70));
		assertEquals(5, CombatCalc.superCombatBoost(1));
	}

	@Test
	public void bestDefensivePrayerFollowsThePrayerLevelBrackets()
	{
		assertEquals(1.25, CombatCalc.defensivePrayerMult(99), 0);
		assertEquals(1.25, CombatCalc.defensivePrayerMult(70), 0);
		assertEquals(1.20, CombatCalc.defensivePrayerMult(60), 0);
		assertEquals(1.15, CombatCalc.defensivePrayerMult(28), 0);
		assertEquals(1.10, CombatCalc.defensivePrayerMult(13), 0);
		assertEquals(1.05, CombatCalc.defensivePrayerMult(1), 0);
		assertEquals(1.0, CombatCalc.defensivePrayerMult(0), 0);
	}

	@Test
	public void overheadProtectionLeavesSixtyPercent()
	{
		assertEquals(26, CombatCalc.afterOverhead(44));
		assertEquals(0, CombatCalc.afterOverhead(0));
	}
}
