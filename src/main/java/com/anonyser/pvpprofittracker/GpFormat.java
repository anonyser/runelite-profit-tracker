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

	/**
	 * Parse a user-typed gp amount, accepting the shorthand people actually read: plain digits
	 * ("4000000"), grouping commas or spaces ("4,000,000"), and k/m/b suffixes with an optional
	 * decimal ("4m", "4000k", "4.5m", "4 mil"). K = thousand, M = million, B = billion,
	 * case-insensitive; a trailing "gp" is ignored. Anything unparseable, empty or negative is 0.
	 */
	static long parseGp(String raw)
	{
		if (raw == null)
		{
			return 0;
		}
		// Drop grouping separators and spaces, lower-case the unit, and ignore a trailing "gp".
		String s = raw.trim().toLowerCase().replace(",", "").replace("_", "").replace(" ", "");
		if (s.endsWith("gp"))
		{
			s = s.substring(0, s.length() - 2);
		}
		if (s.isEmpty())
		{
			return 0;
		}
		// A trailing run of letters is the unit; its first letter picks the multiplier.
		long mult = 1;
		int end = s.length();
		while (end > 0 && Character.isLetter(s.charAt(end - 1)))
		{
			end--;
		}
		if (end < s.length())
		{
			switch (s.charAt(end))
			{
				case 'k':
					mult = 1_000L;
					break;
				case 'm':
					mult = 1_000_000L;
					break;
				case 'b':
					mult = 1_000_000_000L;
					break;
				default:
					return 0;
			}
			s = s.substring(0, end);
		}
		if (s.isEmpty())
		{
			return 0;
		}
		try
		{
			if (s.indexOf('.') >= 0)
			{
				final double val = Double.parseDouble(s);
				if (val < 0)
				{
					return 0;
				}
				final double gp = val * mult;
				return gp >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(gp);
			}
			final long val = Long.parseLong(s);
			if (val < 0)
			{
				return 0;
			}
			return val > Long.MAX_VALUE / mult ? Long.MAX_VALUE : val * mult;
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}
}
