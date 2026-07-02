# Publishing to the RuneLite Plugin Hub

Plugins get listed by opening a pull request against `runelite/plugin-hub` with a small marker file;
a maintainer reviews and merges it. Same process that shipped the Digital Compass.

## Pre-submission checklist

- [ ] `build=standard` in `runelite-plugin.properties` (the one that cost the compass a review round).
- [ ] `displayName`, `author`, `description`, `tags` filled in.
- [ ] **Strip the dev-only `runClient` Gradle task** from `build.gradle` in the submitted commit —
      the Hub wants the clean standard build. The test-class harness (`PvpProfitTrackerPluginTest`)
      can stay; that's the standard example-plugin pattern.
- [ ] Bump `version` to `1.0.0` in `build.gradle`.
- [ ] `./gradlew build` green on Java 11.
- [ ] Plugin cleanly enables/disables — `shutDown()` removes the overlay, the nav button and resets
      all state.
- [ ] Display-only, no automation — hard gate.
- [ ] Verified in-game in the dev client, not just compiled.
- [ ] Commits, PR text and review replies in plain human voice.

## Steps

1. Create the public GitHub repo (`anonyser/runelite-profit-tracker`) and push, if not done yet.
2. Make the submission commit (strip `runClient`, bump version), push, and copy the **full
   40-character commit hash** (`git rev-parse HEAD`).
3. In the existing `anonyser/plugin-hub` fork, add `plugins/pvp-profit-tracker` (no extension):
   ```
   repository=https://github.com/anonyser/runelite-profit-tracker.git
   commit=<full 40-char hash>
   ```
4. Open a pull request from the fork to `runelite/plugin-hub`.
5. Their CI builds that exact commit. If the reviewer asks for changes: fix here, push, copy the new
   hash, and update `commit=` in the **same** PR — never open a new one.

## Rules this plugin keeps to

- Display only: it reads containers, varps and widgets, and draws overlays/panels. It never sends
  input or acts for the player.
- No gameplay automation of any kind.
- Minimal dependencies, `build=standard`.

## Tracking after submission

The Project Tracker app watches the repo and the Hub PR (commit, CI, review status) once the public
repo exists — add the GitHub URL to the project entry.
