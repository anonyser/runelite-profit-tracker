# PvP Profit Tracker

A RuneLite plugin that tracks your **real** PvP profit — not just what you loot, but what it actually costs
you. Profit is an event ledger:

```
profit = loot-key value gained  −  what you lose on death  −  consumables used in PvP
```

Bank/net-worth changes (buying supplies at the GE, etc.) never touch profit — only PvP events do. It's
purely PvP: PvM/skilling never affect the number.

Also shows **K/D**, a live **risk** meter (what you'd lose if you died right now) and total **net worth**
(bank + gear + inventory, refreshed when you open the bank).

Display-only — it reads game state and shows numbers. No automation.

## Status
v0.1 — in development. See the tracker repo's `runelite-plugin-library/06-profit-tracker-groundwork.md`
for the design and the in-game data still to be captured (skull/death signals, Bounty Hunter varbits,
Edgeville K/D sign import, chugging-barrel handling).

## Dev
```bash
./gradlew build           # compile + test
./gradlew runClient       # launch a dev RuneLite client with the plugin (Java 11)
```
