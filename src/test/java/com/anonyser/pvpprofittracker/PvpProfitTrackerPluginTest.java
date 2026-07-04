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
		// Dev runs only: surface the plugin's log.debug listener lines. Which slf4j backend
		// answers depends on the test classpath — RuneLite's logback or the slf4j-simple test
		// dependency — so cover both; a hard logback cast here killed the dev client outright
		// when simple won the binding. Under simple, everything lands in ~/.runelite/pvp-dev.log
		// (properties must be set before the first logger is created). Under logback, the
		// properties are inert and the lines go to client.log as usual.
		System.setProperty("org.slf4j.simpleLogger.log.com.anonyser", "debug");
		System.setProperty("org.slf4j.simpleLogger.logFile",
			System.getProperty("user.home") + "/.runelite/pvp-dev.log");
		final org.slf4j.Logger logger = LoggerFactory.getLogger("com.anonyser");
		if (logger instanceof Logger)
		{
			((Logger) logger).setLevel(Level.DEBUG);
		}
		ExternalPluginManager.loadBuiltin(PvpProfitTrackerPlugin.class);
		RuneLite.main(args);
	}
}
