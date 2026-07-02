package com.anonyser.pvpprofittracker;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How large gp values are displayed: full numbers (1,428,638) or compact (1.428M / 900M / 1.428B).
 */
public enum GpFormat
{
	FULL("Full numbers"),
	COMPACT("Compact (1.428M)");

	private final String label;

	GpFormat(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	String format(long v)
	{
		if (this == FULL)
		{
			return String.format("%,d", v);
		}
		final long a = Math.abs(v);
		if (a >= 1_000_000_000L)
		{
			return scaled(v, 1_000_000_000L, 3) + "B";
		}
		if (a >= 1_000_000L)
		{
			return scaled(v, 1_000_000L, 3) + "M";
		}
		if (a >= 100_000L)
		{
			return scaled(v, 1_000L, 1) + "K";
		}
		return String.format("%,d", v);
	}

	/** value/divisor with up to maxDecimals decimals, truncated, trailing zeros trimmed: 1.428, 900. */
	private static String scaled(long v, long divisor, int maxDecimals)
	{
		return BigDecimal.valueOf(v)
			.divide(BigDecimal.valueOf(divisor), maxDecimals, RoundingMode.DOWN)
			.stripTrailingZeros()
			.toPlainString();
	}
}
