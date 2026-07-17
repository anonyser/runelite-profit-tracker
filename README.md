<p align="center">
  <img src="docs/banner.png" alt="my osrs character, made out of text" width="400">
</p>

# PvP Profit Tracker

A RuneLite plugin that tracks your **real** PvP profit — not just what you loot, but what it actually
costs you. Profit is an event ledger:

```
profit = loot-key gains + crate rewards + recovered loot − death losses − consumables used in PvP
```

Buying supplies at the GE never touches profit — a loss only happens when you die or when you use
something up in PvP. It's purely PvP: PvM and skilling never move the number.

Display-only. It reads game state and shows numbers; it never sends input or acts for the player.

## What it tracks

- **Kills / deaths** — a kill is a loot key landing in your inventory; loot value books when you claim
  the chest, so banking the key first doesn't matter.
- **Profit** — loot keys, Bounty Hunter crate rewards (booked once, when the crate is opened), death
  losses (the risk you were carrying), and consumables: food, potions, and chugging-barrel sips, each
  priced at what you actually used (a brew sip costs one dose, not a whole potion).
- **Double death recovery** — when you and your focused opponent die within a few seconds of each
  other, a flashing **DOUBLE DEATH** prompt shows up with their name on it and stays until you deal
  with it. A **Double death** card appears at the top of the side panel: type what you looted back
  and hit **Add**, or **Dismiss** if you got nothing. It takes `4m`, `4000k`, `4,000,000` or plain
  numbers. What you enter goes onto both session and baseline profit, so a double death you partly
  recover from doesn't read as a straight loss. Drag the prompt wherever you want it. Toggleable
  under **Display**.
- **Draws** — a double death also counts as one **draw** in your fight record instead of a win and
  a loss, and records show wins, losses and draws.
- **Fight breakdowns** — every fight records a breakdown. Open one from Past fights by clicking
  the **Win / Loss / Draw** label: its own window with each hit as it happened, damage and timing,
  totals up top. Only the most recent fights keep one; the one-line history keeps going
  regardless. Toggleable under **Display**.
- **Live fight overlays** — a damage counter for the current fight (yours in green, theirs in
  red) and the opponent's current HP. Both movable and resizable — hold Alt to place them outside
  a fight.
- **Risk** — live "what you'd lose if you died right now": kept-items aware (3 kept, 4 with Protect
  Item, 0 skulled, 1 skulled with Protect Item), with untradeables priced at their repair-on-death cost
  from a bundled table.
- **Net worth** — bank + carried + chugging-barrel contents, GE prices with high-alch for untradeables.
  Open your bank to record it. Informational only; it never feeds profit.
- **Bounty Hunter** — crates received and their reward values, plus points: your current balance and
  points gained, read from the game's own data so kills, streaks and emblem turn-ins all count exactly.
- **Actual K/D** — read straight from the game's own stats: the Kill Death Ratio window at Edgeville,
  or the HUD on Bounty Hunter worlds, where it refreshes as you get kills. The game keeps separate
  tallies for world PvP and Bounty Hunter — the plugin shows whichever it read last.
- **Activated Bounty Hunter gear** — ancient warriors' equipment (Vesta's, Statius's, Morrigan's,
  Zuriel's, corrupted included) is valued at its coin activation fee automatically: that's what the
  killer receives if you die with it, so that's what you're risking. No setup needed.
- **Protect Item status** — `(On)` or `(Off)` beside the Risk value. Neutral colour outside the
  Wilderness; inside, `(Off)` glows red and `(On)` shows green, so walking in unprotected gets
  caught at a glance. Toggleable under **Trackers**.
- **Your max hit** — on the overlay, from your gear, boosts, prayers and selected combat style.
  With the special-attack bar lit it shows the spec's ceiling for the common PvP spec weapons,
  and the Eclipse atlatl's Strength-based damage is handled properly. Your own numbers only.
- **Gear inspect** — right-click a player and choose **Inspect** to see their worn equipment with
  GE prices, or receive a Bounty Hunter target for an automatic hiscore stat lookup. See below.

## Tracking modes

Most values come in up to three flavours, each toggleable:

- **Actual** — your true in-game value (K/D and points balance).
- **Session** — this session only; resets when the client restarts.
- **Baseline** — a long-term tally that keeps saving across sessions until you reset it. Reset buttons
  live on the plugin's side panel (the skulls icon in the sidebar).

Tallies are saved per account, so each one keeps its own numbers.

## Gear inspect

Right-click another player and choose **Inspect** to see their worn equipment on the side panel,
laid out like the game's equipment tab, with the GE price of each item shown on hover. This is
the same information Equipment Inspector has shown for years. Ammo and ring never render on
another player, so those two slots are crossed out. There is no total and nothing is calculated for you:
any adding up is yours to do.

Receiving a Bounty Hunter target runs an automatic stat lookup, the same behaviour as the core
Hiscore plugin's "Bounty lookup" option: combat levels from the public hiscores, plus Bounty
Hunter kills, Colosseum glory, TzKal-Zuk, Sol Heredit, Vardorvis and Jad kill counts where ranked.
The target's gear is not shown unless you right-click and Inspect them yourself. With
**Auto-inspect new target** on (under **Opponent gear**) the side panel opens on the new target's
gear by itself. If you also run PvP Performance Tracker, turn its auto-open off so the two don't
fight over the sidebar.

## Side panel

The skulls icon in the sidebar. It holds the full breakdown, the crate-value flash when you open a
crate, and the resets: **Reset session**, **Reset baseline K/D**, **Reset baseline profit**,
**Reset baseline crates**, **Reset baseline points**.

The focused player sits at the top. **Clear** drops them, and five minutes out of sight drops them
automatically. While someone is focused you also get:

- **Notes** — free-form, and they stick to that player between sessions.
- **W/L** — your record against them (draws counted separately), green when you're ahead, red
  when you're behind.
- **Past fights** — every fight you've had with them, newest first.

**Lookup** takes any player name: type it, press Enter, and you get their hiscore stats plus your
notes and W/L for that name, without having seen them. Names you already know pop up under the
field as you type, arrow keys to pick one, and the ✕ on a row forgets that name.

With nobody focused, **Past fights** shows one row per opponent — your most recent fight with
them. Click a name for your full history with that player. Fights that end with nobody dead are
kept too, marked **No result**; they never touch your record. Any fight's outcome label opens its
breakdown when one is saved.

## Config

Everything is toggleable. **Display** holds the overlay on/off switch, number formatting (full
`1,428,638` or compact `1.428M`), the profit and loss colours, the double death prompt, the fight
breakdowns, and the two fight overlays.
**Trackers** holds the individual trackers. **Opponent gear** holds the gear inspect and
auto-inspect. **Advanced** holds the Wilderness/PvP-only gate and price overrides.

### Price overrides

Brand-new items can take a few days to appear in the live price feed, and until then they count as
worthless — a just-released 11m weapon can price at 0 and vanish from your risk and net worth.
**Price overrides** (under **Advanced**) lets you set your own prices as `itemId=gp` pairs,
separated by commas:

```
33631=11025000, 4151=1500000
```

That prices item 33631 (Crimson kisten) at 11,025,000 gp and item 4151 (Abyssal whip) at
1,500,000 gp. Your price wins over the live feed, so it also works when a price just looks wrong
to you — for example, valuing something at what you actually paid. To find an item's id, open the
item's page on the [OSRS Wiki](https://oldschool.runescape.wiki) and check the **Item ID** row of
the infobox on the right. Changes apply as soon as you type them — no relog needed.

## Dev

```bash
./gradlew build           # compile + test (Java 11)
```
