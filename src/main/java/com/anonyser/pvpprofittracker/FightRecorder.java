package com.anonyser.pvpprofittracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Records one fight's hit-by-hit log while it happens. Deliberately client-free: the plugin does
 * every game read (weapons, HP, spec energy, hitsplat filtering) on the client thread and feeds
 * plain values in; this class only keeps the state machine and the log, so the tricky parts stay
 * unit-testable.
 *
 * <p>Damage lands mid-tick but HP settles at the end of it (a Vengeance heal or a second splat can
 * move it again), so entries buffer as "pending" and get their defender-HP filled in when the
 * plugin calls {@link #settle} from GameTick — which the client fires after the tick's hitsplats
 * have all applied.</p>
 *
 * <p>Reflection labelling reuses the shapes proven in live PvP testing: a Ring of Recoil reflection
 * is ceil(10%) of the hit that caused it and Vengeance is floor(75%), the reflection lands the same
 * tick, and the client can display it BEFORE its trigger — so both orders are checked. A
 * recoil-shaped splat on the opponent would be MY ring, which is checkable, so it is only labelled
 * when a reflect ring is actually worn; a suspected reflection on me is their (invisible) ring and
 * always labels; Vengeance can't be checked either way and always labels.</p>
 */
final class FightRecorder
{
	/** ~30s without damage abandons the log (the fight fizzled; nothing is saved). */
	static final int DISENGAGE_TICKS = 50;

	/** A marathon fight stops logging past this many splats — the stored JSON must stay sane. */
	static final int MAX_HITS = 500;

	/** A spec-energy drop tags your damage landing within this many ticks as the spec hit. */
	static final int SPEC_WINDOW_TICKS = 3;

	private boolean active;
	private String name;
	private long startMs;
	private int startTick;
	private int lastDamageTick;
	private final List<FightLog.Entry> hits = new ArrayList<>();
	private final List<FightLog.Entry> pending = new ArrayList<>();
	// Running damage totals for the live overlay — counted for EVERY splat, even past MAX_HITS.
	private int totalMine;
	private int totalTheirs;

	boolean isActive()
	{
		return active;
	}

	String opponentName()
	{
		return name;
	}

	int totalMine()
	{
		return totalMine;
	}

	int totalTheirs()
	{
		return totalTheirs;
	}

	void start(String opponentName, long nowMs, int tick)
	{
		active = true;
		name = opponentName;
		startMs = nowMs;
		startTick = tick;
		lastDamageTick = tick;
		hits.clear();
		pending.clear();
		totalMine = 0;
		totalTheirs = 0;
	}

	/** One damage splat, already filtered and attributed by the plugin. */
	void onDamage(int tick, boolean byMe, int amount, int weaponId, String weaponName, boolean spec)
	{
		if (!active)
		{
			return;
		}
		lastDamageTick = tick; // even a beyond-cap hit keeps the fight alive for the outcome
		if (byMe)
		{
			totalMine += amount;
		}
		else
		{
			totalTheirs += amount;
		}
		if (hits.size() + pending.size() >= MAX_HITS)
		{
			return;
		}
		final FightLog.Entry e = new FightLog.Entry();
		e.t = tick - startTick;
		e.me = byMe;
		e.amount = amount;
		e.weapon = weaponId;
		e.wname = weaponName;
		e.spec = spec;
		pending.add(e);
	}

	/**
	 * Settle this tick's entries: label reflections, then stamp the defender's HP as it stands now
	 * that the tick's damage has fully applied. Call from GameTick (after the tick's hitsplats,
	 * before the next tick's). Either HP may be -1 (unknown) and is stored as-is.
	 */
	void settle(int myHp, int oppHp, boolean iWearReflectRing)
	{
		if (!active || pending.isEmpty())
		{
			return;
		}
		labelReflections(pending, iWearReflectRing);
		// The client can DISPLAY a reflection before the hit that caused it; store real hits
		// first so a tick always reads cause before effect.
		pending.sort((a, b) -> Boolean.compare(a.tag != null, b.tag != null));
		for (final FightLog.Entry e : pending)
		{
			// The defender is me for their hits, them for mine.
			e.hp = e.me ? oppHp : myHp;
			hits.add(e);
		}
		pending.clear();
	}

	/** True when the fight has gone quiet long enough to abandon. */
	boolean disengaged(int tick)
	{
		return active && tick - lastDamageTick > DISENGAGE_TICKS;
	}

	/** Ticks since the last damage either way; 0 when no fight is running. */
	int quietTicks(int tick)
	{
		return active ? Math.max(0, tick - lastDamageTick) : 0;
	}

	/**
	 * Close the log with its outcome. Unsettled entries keep hp = -1 rather than guessing.
	 * Returns null when nothing was ever recorded.
	 */
	FightLog finish(String outcome, long endTs, int endTick, boolean iWearReflectRing)
	{
		if (!active)
		{
			return null;
		}
		labelReflections(pending, iWearReflectRing);
		pending.sort((a, b) -> Boolean.compare(a.tag != null, b.tag != null));
		hits.addAll(pending);
		pending.clear();
		final FightLog log = new FightLog();
		log.name = name;
		log.ts = endTs;
		log.startTs = startMs;
		log.duration = Math.max(0, endTick - startTick);
		log.outcome = outcome;
		log.hits.addAll(hits);
		discard();
		return log.hits.isEmpty() ? null : log;
	}

	void discard()
	{
		active = false;
		name = null;
		hits.clear();
		pending.clear();
		totalMine = 0;
		totalTheirs = 0;
	}

	/**
	 * Within one tick's entries, find an opposite-side pair whose amounts shape as a reflection
	 * (checked in both display orders) and label the reflected splat instead of blaming a weapon.
	 */
	static void labelReflections(List<FightLog.Entry> tickEntries, boolean iWearReflectRing)
	{
		for (int i = 0; i < tickEntries.size(); i++)
		{
			for (int j = i + 1; j < tickEntries.size(); j++)
			{
				final FightLog.Entry a = tickEntries.get(i);
				final FightLog.Entry b = tickEntries.get(j);
				if (a.me == b.me || a.tag != null || b.tag != null)
				{
					continue;
				}
				char shape = reflectShape(a.amount, b.amount);
				FightLog.Entry reflected = b;
				if (shape == '-')
				{
					shape = reflectShape(b.amount, a.amount);
					reflected = a;
				}
				if (shape == '-')
				{
					continue;
				}
				// A recoil-shaped splat on the opponent would be my own ring — only credible
				// when a reflect ring is actually worn. Their ring / either Vengeance can't be
				// checked, so those always label.
				if (shape == 'R' && reflected.me && !iWearReflectRing)
				{
					continue;
				}
				reflected.tag = shape == 'R' ? "recoil" : "venge";
				reflected.weapon = -1;
				reflected.wname = null;
				reflected.spec = false;
			}
		}
	}

	/** 'R' if second is ceil(10%) of first (Ring of Recoil), 'V' if floor(75%) (Vengeance). */
	static char reflectShape(int firstAmt, int secondAmt)
	{
		if (firstAmt <= 0 || secondAmt <= 0)
		{
			return '-';
		}
		if (secondAmt == (firstAmt + 9) / 10)
		{
			return 'R';
		}
		if (secondAmt == (int) (firstAmt * 0.75))
		{
			return 'V';
		}
		return '-';
	}
}
