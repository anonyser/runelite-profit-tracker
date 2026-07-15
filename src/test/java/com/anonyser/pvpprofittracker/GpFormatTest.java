package com.anonyser.pvpprofittracker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GpFormatTest
{
	@Test
	public void fourMillionEveryWayPeopleType()
	{
		assertEquals(4_000_000L, GpFormat.parseGp("4M"));
		assertEquals(4_000_000L, GpFormat.parseGp("4m"));
		assertEquals(4_000_000L, GpFormat.parseGp("4000K"));
		assertEquals(4_000_000L, GpFormat.parseGp("4000k"));
		assertEquals(4_000_000L, GpFormat.parseGp("4,000,000"));
		assertEquals(4_000_000L, GpFormat.parseGp("4000000"));
		assertEquals(4_000_000L, GpFormat.parseGp("4 mil"));
		assertEquals(4_000_000L, GpFormat.parseGp(" 4m "));
		assertEquals(4_000_000L, GpFormat.parseGp("4,000,000gp"));
	}

	@Test
	public void decimalsAndOtherUnits()
	{
		assertEquals(4_500_000L, GpFormat.parseGp("4.5m"));
		assertEquals(1_200L, GpFormat.parseGp("1.2k"));
		assertEquals(2_500_000_000L, GpFormat.parseGp("2.5b"));
		assertEquals(500L, GpFormat.parseGp("500"));
		assertEquals(1_000_000L, GpFormat.parseGp("1.0m"));
	}

	@Test
	public void garbageAndEdgesAreZero()
	{
		assertEquals(0L, GpFormat.parseGp(null));
		assertEquals(0L, GpFormat.parseGp(""));
		assertEquals(0L, GpFormat.parseGp("   "));
		assertEquals(0L, GpFormat.parseGp("abc"));
		assertEquals(0L, GpFormat.parseGp("m"));
		assertEquals(0L, GpFormat.parseGp("4x"));
		assertEquals(0L, GpFormat.parseGp("-5"));
	}

	@Test
	public void hugeInputSaturatesInsteadOfOverflowing()
	{
		assertEquals(Long.MAX_VALUE, GpFormat.parseGp("999999999999b"));
	}
}
