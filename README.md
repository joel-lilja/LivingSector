# Living Sector

More civilian life in Starsector: passenger shuttles, interstellar liners and private charters travelling between inhabited planets and stations.

Fleets use passenger ships from their faction and Independents, including modded faction rosters. Some return home after unloading; others finish at their destination. Traffic varies with sector size and conditions.

**Current build: 0.3.0, early development.** Adds ambient traffic; passenger missions and economic effects are future work.

## Requirements

- **Starsector 0.98a-RC8** and **Nexerelin 0.12.2c**, with Nex's dependencies (**LazyLib** and **MagicLib**).
- Optional: **LunaLib** for in-game settings and **Console Commands** for inspecting traffic and troubleshooting.

## Install

1. Close Starsector and extract the supplied **LivingSector-0.3.0.zip** into your `Starsector/mods/` folder.
2. Check that `mods/LivingSector/mod_info.json` and `mods/LivingSector/jars/LivingSector.jar` exist.
3. Enable **Living Sector** and its dependencies in the launcher, then start the game.

Use the packaged mod ZIP, which includes the compiled jar. If you downloaded the source code, follow the [developer setup guide](docs/DEVELOPMENT.md) to build it.

Keep a backup or separate save while trying this development build. Once saved with Living Sector, removing the mod from that save is not supported; you can disable new traffic in settings instead.

**Fresh sector:** save and load once so Nex's route manager becomes available. Civilian departures currently wait until then.

## Settings

With LunaLib, open its settings menu → **Living Sector**. Adjust traffic frequency, enable individual traffic types, or change their chances of returning home. Apply changes to use them in game; existing trips continue.

Without LunaLib, edit [living_sector.json](data/config/living_sector.json) and restart. See the [configuration reference](docs/CONFIGURATION.md) for details.

## Playing and feedback

**Just play normally. Debug recording is off by default and can stay off.** If you want to explore traffic history or investigate a bug or suggestion, the [player guide](docs/PLAYTEST.md) explains recording, useful `ls` commands and which files to send.

[Report a bug or suggest a change](https://github.com/joel-lilja/LivingSector/issues/new). Feedback is welcome with or without recordings.

## Development

[Environment setup and build commands](docs/DEVELOPMENT.md) · [Architecture](docs/ARCHITECTURE.md) · [Phase A design](docs/PHASE_A.md)

[MIT licensed](LICENSE). Starsector and its game assets belong to Fractal Softworks and are not distributed here.
