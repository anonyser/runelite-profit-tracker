package com.anonyser.pvpprofittracker;

/** How the opponent HP overlay shows their health. Public: RuneLite's config proxy needs it. */
public enum HpDisplay
{
	HITPOINTS("Hitpoints"),
	PERCENT("Percent"),
	BOTH("Both");

	private final String label;

	HpDisplay(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
