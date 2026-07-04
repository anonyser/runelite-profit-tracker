# PvP Profit Tracker

A RuneLite plugin that tracks your **real** PvP profit — not just what you loot, but what it actually
costs you. Profit is an event ledger:

```
profit = loot-key gains + crate rewards − death losses − consumables used in PvP
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
- **Risk** — live "what you'd lose if you died right now": kept-items aware (3 kept, 4 with Protect
  Item, 0 skulled, 1 skulled with Protect Item), with untradeables priced at their repair-on-death cost
  from a bundled table.
- **Net worth** — bank + carried + chugging-barrel contents, GE prices with high-alch for untradeables.
  Informational only; it never feeds profit.
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
- **Opponent risk** — right-click a player and choose **Risk** to estimate what they're carrying,
  your hit chance against them, and your max hit. See below.

## Tracking modes

Most values come in up to three flavours, each toggleable:

- **Actual** — your true in-game value (K/D and points balance).
- **Session** — this session only; resets when the client restarts.
- **Baseline** — a long-term tally that keeps saving across sessions until you reset it. Reset buttons
  live on the plugin's side panel (the green `$` icon in the sidebar).

## Opponent risk

Right-click another player and choose **Risk** to focus them. A separate overlay — movable and
toggleable on its own — and a side-panel section then track:

- **Their visible gear** — worn equipment only. Another player's inventory is never visible, and the
  plugin doesn't pretend otherwise. Items seen equipped during the fight stay in the pool after
  they're switched away: the dragon claws they flashed two minutes ago are still in their inventory,
  and still part of their risk.
- **Estimated risk** — the seen-item pool valued with the same price chain as your own risk (your
  price overrides win here too, and Bounty Hunter activation fees and repair costs apply), minus the
  items they'd keep. On Bounty Hunter worlds the risk-tier skull (bronze / iron / green / blue / red)
  sets a floor — it's the game's own statement of their minimum total risk, inventory included.
- **Smite value** — what losing Protect Item would additionally expose.
- **Hit chance and max hit** — your current setup (gear, boosts, prayers, selected combat style)
  against their hiscore levels and visible defence bonuses, using the standard OSRS formulas. Magic
  max hits are spell-dependent and shown as such rather than guessed.

The estimates deliberately assume a strong opponent: Protect Item on, a super combat potion, and the
best defensive prayer their Prayer level allows (Piety-tier from 70, Chivalry from 60, the Skin line
below that). Unskulled opponents are assumed to protect their top 4 items; skulled, their single
best. The overlay and panel say when a number rests on an assumption. Selecting another player moves
the focus; **Clear** (right-click the overlay, or the side-panel button) drops it, and five minutes
out of sight drops it automatically.

## Config

Everything is toggleable under **Trackers**, number formatting (full `1,428,638` or compact `1.428M`)
under **Display**, the opponent estimate under **Opponent risk**, and the Wilderness/PvP-only gate
under **Advanced**. The side panel shows the full
breakdown, the baseline resets, and the crate-value flash when you open a crate.

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
