package com.anonyser.pvpprofittracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvpProfitTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PvpProfitTrackerPlugin.class);
		RuneLite.main(args);
	}
}
