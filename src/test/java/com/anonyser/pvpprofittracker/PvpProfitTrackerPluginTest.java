package com.anonyser.pvpprofittracker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.slf4j.LoggerFactory;

public class PvpProfitTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Dev runs only: surface the plugin's log.debug listener lines in client.log.
		// The shipped Hub build never runs this class, so it stays at the default level there.
		((Logger) LoggerFactory.getLogger("com.anonyser")).setLevel(Level.DEBUG);
		ExternalPluginManager.loadBuiltin(PvpProfitTrackerPlugin.class);
		RuneLite.main(args);
	}
}
