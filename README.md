# Living Sector

A Starsector mod for making the sector feel inhabited through traffic driven by game state.

In this project's feature discussions, "planets" generally includes inhabited stations unless explicitly stated otherwise.

The first feature is a VIP shuttle traveling between two colonies that are not hostile to each other. The main deliverable is the framework underneath it: new traffic algorithms can use colony data, diplomacy, existing journeys, and departure history without rewriting scheduling or fleet management.

**Status:** 0.2.0 development prototype for Starsector **0.98a-RC8** and Nexerelin **0.12.2c**. Adds persistent missions, a native-route executor, return itineraries, condition checkpoints, and short console test commands. Automated checks pass; the new executor still needs its [first live route test](docs/ROUTE_TEST.md). The earlier 0.1.1 direct-fleet path passed spawning, hyperspace travel, station arrival/despawn, and active save/reload in game.

**Quick test:** restart the game with the rebuilt jar, Nexerelin, and Console Commands enabled. In a freshly generated sector, save and load once so Nex's route-manager replacement is active. Run `ls test`, then `ls visit ls-1` using the mission ID it prints. Unpause briefly and run `ls status ls-1` and `ls verify ls-1`. Use `ls help` for more commands. These create explicit test trips; automatic VIP departures retain the old executor unless `useNativeRoutes` is enabled.

## Phase 1

- Occasional, attackable civilian fleets named **VIP Shuttle**, using the vanilla Mudskipper transport.
- Trips between distinct inhabited planets and stations, within a system or across systems. Same-faction and neutral-to-friendly cross-faction routes are allowed.
- A colony-count-based population target that varies over time. Crowding reduces departure probability; per-type and global hard limits bound fleet count.
- Origin cooldowns and duplicate-route prevention.
- Current route relations are checked every two campaign days by default. War, hostile conquest, or a disappearing destination causes diversion to a safe port.
- Persistent scheduling and journey state, with cleanup after arrival, destruction, or timeout.

This is ambient passenger traffic. Named VIP characters, passenger interactions, missions, escorts, custom ships, and economic effects are future features.

## Install and build

Nexerelin is required, along with its own dependencies (LazyLib and MagicLib). Console Commands is optional for ordinary play and required for the short `ls` test commands. LunaLib is optional and provides an in-game settings menu. Building this source tree requires all five mods installed; their libraries are used for compilation/testing and are never bundled into Living Sector. Compatibility with the complete installed mod list still needs an in-game test.

For a source checkout, install **Python 3** and **JDK 17** (`java` and `javac` on `PATH`), then clone into your game's `mods` directory:

```sh
git clone git@github.com:joel-lilja/LivingSector.git
cd LivingSector
python3 build.py build
```

On Windows, use `py build.py build` if Python is installed through the Windows launcher. The build finds the game automatically when this folder is `Starsector/mods/LivingSector`. Elsewhere, pass:

```sh
python3 build.py build --game-root "/path/to/Starsector"
```

Alternatively set `STARSECTOR_HOME` to that installation. The build runs unit and integration checks against a candidate jar, then installs it as `jars/LivingSector.jar` only after they pass. Fully quit the game before installing; a locked jar leaves the validated candidate available in `build/candidate`. Enable **Living Sector** in the game's mod launcher and restart the game after rebuilding.

To produce an installable archive with the jar and source:

```sh
python3 build.py package
```

`package` runs the checks and creates `dist/LivingSector-0.2.0.zip` without replacing the installed jar, so it can run while the game is open. Extract that archive into `Starsector/mods` with the game closed. Game libraries and generated binaries are not committed to this repository. A GitHub source archive needs building before it can be played.

## Configuration

With **LunaLib enabled**, open its settings menu and select **Living Sector**. General, VIP Traffic, Advanced and Debug tabs expose the existing traffic controls. Save/apply the menu changes to update the mod without restarting. Basic debug logging, the traffic recorder and experimental native routes default to off.

For long-run debugging, enable **Record traffic history** or run `ls debug on`. Use `ls debug summary 180` for recent events and outcomes, `ls debug trip <tripId>` for one trip, and `ls debug off` to stop. The [recorder guide](docs/DEBUG_RECORDER_DESIGN.md) covers all commands, save/load timelines and retention limits. Its JSONL archives live in `saves/common/living-sector-debug/`, with a shared rotating 32 MiB allowance by default. Console overrides end on campaign load or the next Luna settings apply; the Luna/JSON setting persists across loads.

**Without LunaLib**, edit [data/config/living_sector.json](data/config/living_sector.json), then restart Starsector. LunaLib is not a required launcher dependency, and its adapter is never loaded when the library is disabled. No LunaLib objects are stored in campaign saves.

When LunaLib is enabled, its saved values (including menu defaults) override corresponding JSON fields. Missing Luna fields fall back to JSON. The ship `variant` remains JSON-only. An invalid update is rejected as a whole, retaining the previous valid configuration and reporting the problem in `starsector.log`. LunaLib owns its settings persistence; treat menu values as installation-wide rather than per-save overrides.

Traffic controls affect subsequent planning and maintenance; they do not rewrite existing itineraries, remove fleets when a limit is reduced, reset origin cooldowns, or undo `ls pause`. Changing check intervals reschedules them without catch-up work. Settings are read at startup, campaign load and menu-save callbacks, never polled per frame.

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Allow new departures; existing journeys still finish when false. |
| `debugLogging` | `false` | Log spawns, diversions, and cleanup to `starsector-core/starsector.log`. |
| `debugTrafficHistory` | `false` | Enable the separate rotating traffic event recorder. |
| `debugHistoryMiB` | `32` | Shared recorder archive allowance across all saves/runs; 8–128 MiB. |
| `useNativeRoutes` | `false` | Use the experimental mission/native-route executor for new automatic departures. Existing direct fleets finish normally. Explicit `ls` tests use native routes regardless of this setting. |
| `globalFleetLimit` | `40` | Maximum tracked direct journeys plus native missions. A route with a physical fleet counts once. |
| `planningIntervalDays` | `5` | Campaign days between traffic planning passes and their sector snapshots. |
| `maintenanceIntervalDays` | `2` | Campaign days between active-fleet safety and cleanup checks. |
| `vip.includeStations` | `true` | Include inhabited stations as normal VIP endpoints and in the population target. Set false for planet-only traffic. |
| `vip.minimumMarketSize` | `3` | Minimum VIP endpoint size. |
| `vip.baseTarget` | `2` | Base population target. |
| `vip.marketsPerAdditionalFleet` | `12` | Eligible colonies per additional target fleet. |
| `vip.maximumTarget` | `20` | Maximum base target before variation. |
| `vip.targetVariation` | `0.25` | Random variation of up to ±25%. |
| `vip.targetRerollDays` | `25` | Campaign days between target rerolls. |
| `vip.dailySpawnChance` | `0.35` | Daily probability basis, converted to a chance over each planning interval. |
| `vip.hardLimit` | `30` | Absolute limit on tracked VIP fleets. |
| `vip.originCooldownDays` | `10` | Minimum interval between successful VIP departures from one origin. |
| `vip.maximumTripDays` | `180` | Total journey lifetime, including diversions. |

With `N` eligible colonies, the base target is `min(20, 2 + N / 12)`, multiplied by a random factor between `0.75` and `1.25`. A sector with 60 eligible colonies has a base target of 7. A sector with fewer than two has no VIP departures.

The daily probability basis is `p = 0.35 / (1 + (active / target)^4)`. At each planning pass, the probability of one departure is `1 - (1 - p)^planningIntervalDays`. With no active VIP traffic, the default five-day pass has about an 88% chance of proposing a trip. A policy can spawn **at most one fleet per pass**: longer intervals intentionally reduce maximum traffic throughput. Missed intervals do not queue catch-up spawns. Set `planningIntervalDays` to `1` to restore daily planning.

The target controls crowding; it does not promise a particular number of shuttles. Travel duration, compatible routes, cooldowns, and planning frequency also determine observed traffic.

Port selection mildly favors larger colonies and shorter trips. Origin weight is `sqrt(max(1, size - 2))`; destination weight is `sqrt(max(1, size - 2) / (1 + distanceLY / 10))`. A size-6 port gets twice the origin weight of a size-3 port. For equally sized destinations, one 10 light-years away gets about 71% of the weight of one in the same system. These preferences apply equally to planets and stations; eligibility, cooldowns, and diplomacy still apply.

Routine maintenance reads only each journey's live endpoints and their faction relations. Full snapshots are created for planning or when an unsafe journey cannot return to its origin and needs another port. All searches within one update share a snapshot. Planning skips full scans when globally disabled, at the global fleet cap, or without policies ready to run. Maintenance can take up to its configured interval to notice a war or release a finished fleet; longer intervals trade responsiveness for less work. Native fleet AI continues moving between checks.

Hidden markets, uninhabited planets, and systems without jump points are excluded. Emergency diversions may use an inhabited station even when ordinary VIP traffic is limited to planets. If there is no safe port, or a trip times out, the fleet is retired when it is not visible to the player and is not in a battle.

The manager installs when a new or existing campaign loads. Once you save with the mod, that save contains its Java classes; removing the mod from that save is not supported. Set `enabled` to `false` to stop new traffic while retaining the mod.

The native executor preserves normal Starsector/Nex materialization rules. It checkpoints surviving ship IDs, variants, captains, hull, base CR, mothball/flagship state, and cargo at distance despawn. It does not simulate extra offscreen repair or civilian battles. Unexpected additional abstract damage is diagnosed as an unsupported transition rather than regenerating a pristine fleet. Legacy persisted classes remain intact; migration of an actual 0.1.1 campaign to 0.2.0 still needs a live save/load check.

## Development

Run the decision tests without a game installation:

```sh
python3 build.py test
```

Run the full automated checks before a live test, without changing the installed jar:

```sh
python3 build.py integration
```

This includes journey checks and named scenarios against the installed Starsector, Nex and Console Commands APIs: startup/load hooks, console entry points, global and fleet events, route lifecycle, battle-loss callbacks, and mission-data serialization. Results are written to `build/reports/integration.xml`; successful runs also write `validated-build.json` with the candidate hash and dependency paths. `build` and `package` run the same checks automatically. See [how to extend the harness](tests/INTEGRATION.md), [architecture](docs/ARCHITECTURE.md), the [live route test](docs/ROUTE_TEST.md), and [testing details](docs/TESTING.md).

MIT licensed. Starsector and its game assets belong to Fractal Softworks and are not distributed here.
