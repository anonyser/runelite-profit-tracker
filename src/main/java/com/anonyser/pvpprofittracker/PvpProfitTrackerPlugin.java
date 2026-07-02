package com.anonyser.pvpprofittracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "PvP Profit Tracker",
	description = "Tracks real PvP profit — loot-key gains minus what you lose on death and consumables — "
		+ "with K/D, risk and net worth",
	tags = {"pvp", "profit", "loot", "kill", "death", "wilderness", "bounty", "risk"}
)
public class PvpProfitTrackerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PvpProfitTrackerPlugin.class);

	// PvP loot keys (held in the inventory) and the Deadman containers their contents live in.
	private static final int[] LOOT_KEYS = {
		ItemID.WILDY_LOOT_KEY0, ItemID.WILDY_LOOT_KEY1, ItemID.WILDY_LOOT_KEY2,
		ItemID.WILDY_LOOT_KEY3, ItemID.WILDY_LOOT_KEY4,
	};
	private static final int[] LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0, InventoryID.DEADMAN_LOOT_INV1, InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3, InventoryID.DEADMAN_LOOT_INV4,
	};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PvpProfitTrackerOverlay overlay;

	@Inject
	private PvpProfitTrackerConfig config;

	private final Stats session = new Stats();

	// Live, display-only derived values.
	private long riskGp;
	private long netWorthGp;
	private long bankValueGp;

	// Loot-key edge detection (so we count a kill on the transition to "holding a key", not on login).
	private boolean heldLootKey;
	private boolean lootKeySynced;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		lootKeySynced = false;
		heldLootKey = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			// Re-sync loot-key state on the next inventory tick rather than counting a phantom kill.
			lootKeySynced = false;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		final int id = e.getContainerId();

		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			recomputeLiveValues();
			if (id == InventoryID.INV)
			{
				detectLootKeyPickup();
			}
		}
		else if (id == InventoryID.BANK)
		{
			bankValueGp = value(e.getItemContainer());
			recomputeLiveValues();
		}

		if (config.debugLogging())
		{
			log.info("[capture] ItemContainerChanged id={}", id);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		final Player me = client.getLocalPlayer();
		if (me == null || e.getActor() != me)
		{
			return;
		}
		// v0.1: book the current at-risk value as the loss. The precise kept-on-death engine
		// (keep top 3 / 4 with Protect Item / 0 when skulled) lands after the in-game capture session.
		final long lost = riskGp;
		session.addDeath(lost);
		if (config.debugLogging())
		{
			log.info("[capture] local player death, booked loss {}", lost);
		}
	}

	private void detectLootKeyPickup()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INV);
		boolean has = false;
		if (inv != null)
		{
			for (final int key : LOOT_KEYS)
			{
				if (inv.contains(key))
				{
					has = true;
					break;
				}
			}
		}

		if (!lootKeySynced)
		{
			heldLootKey = has;
			lootKeySynced = true;
			return;
		}

		if (has && !heldLootKey)
		{
			final long gp = valueLootKeyContents();
			session.addKill(gp);
			if (config.debugLogging())
			{
				log.info("[capture] loot key received, contents valued {}", gp);
			}
		}
		heldLootKey = has;
	}

	private long valueLootKeyContents()
	{
		long total = 0;
		for (final int container : LOOT_KEY_CONTAINERS)
		{
			total += value(client.getItemContainer(container));
		}
		return total;
	}

	private void recomputeLiveValues()
	{
		final long inv = value(client.getItemContainer(InventoryID.INV));
		final long worn = value(client.getItemContainer(InventoryID.WORN));
		netWorthGp = bankValueGp + inv + worn;
		// v0.1: risk = everything currently carried. Kept-on-death rules refine this after capture.
		riskGp = inv + worn;
	}

	/**
	 * Sum the GE value of a container. {@link ItemManager#getItemPrice} is variation-aware, so ornamented
	 * and Bounty Hunter corrupted items are priced at their real base value, not the untradeable shell.
	 */
	private long value(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		long total = 0;
		for (final Item item : container.getItems())
		{
			final int itemId = item.getId();
			final int qty = item.getQuantity();
			if (itemId <= 0 || qty <= 0)
			{
				continue;
			}
			total += (long) itemManager.getItemPrice(itemId) * qty;
		}
		return total;
	}

	Stats getSession()
	{
		return session;
	}

	long getRiskGp()
	{
		return riskGp;
	}

	long getNetWorthGp()
	{
		return netWorthGp;
	}

	/** Compact gp formatting: 1.2M / 12.3K / 950. */
	static String gp(long v)
	{
		final long a = Math.abs(v);
		if (a >= 10_000_000)
		{
			return (v / 1_000_000) + "M";
		}
		if (a >= 1_000_000)
		{
			return String.format("%.1fM", v / 1_000_000.0);
		}
		if (a >= 100_000)
		{
			return (v / 1000) + "K";
		}
		if (a >= 1000)
		{
			return String.format("%.1fK", v / 1000.0);
		}
		return Long.toString(v);
	}

	@Provides
	PvpProfitTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvpProfitTrackerConfig.class);
	}
}
