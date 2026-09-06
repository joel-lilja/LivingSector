# Living Sector

A Starsector mod for making the sector feel inhabited through traffic driven by game state.

In this project's feature discussions, "planets" generally includes inhabited stations unless explicitly stated otherwise.

The first feature is a VIP shuttle traveling between two colonies that are not hostile to each other. The main deliverable is the framework underneath it: new traffic algorithms can use colony data, diplomacy, existing journeys, and departure history without rewriting scheduling or fleet management.

**Status:** 0.1.1 prototype for Starsector **0.98a-RC8**. Compiled against that API and covered by automated policy and journey tests. The live campaign smoke test is still pending; see [testing](docs/TESTING.md).

## Phase 1

- Occasional, attackable civilian fleets named **VIP Shuttle**, using the vanilla Mudskipper transport.
- Trips between distinct inhabited planets and stations, within a system or across systems. Same-faction and neutral-to-friendly cross-faction routes are allowed.
- A colony-count-based population target that varies over time. Crowding reduces departure probability; per-type and global hard limits bound fleet count.
- Origin cooldowns and duplicate-route prevention.
- Current route relations are checked every two campaign days by default. War, hostile conquest, or a disappearing destination causes diversion to a safe port.
- Persistent scheduling and journey state, with cleanup after arrival, destruction, or timeout.

This is ambient passenger traffic. Named VIP characters, passenger interactions, missions, escorts, custom ships, and economic effects are future features.

## Install and build

There are no required library mods. Living Sector reads current vanilla faction relations, including changes made by Nexerelin; it does not require Nexerelin. Compatibility with the complete installed mod list still needs an in-game test.

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

Alternatively set `STARSECTOR_HOME` to that installation. The build runs tests and writes `jars/LivingSector.jar`. Enable **Living Sector** in the game's mod launcher and restart the game after rebuilding.

To produce an installable archive with the jar and source:

```sh
python3 build.py package
```

Extract `dist/LivingSector-0.1.1.zip` into `Starsector/mods`. Game libraries and generated binaries are not committed to this repository. A GitHub source archive needs building before it can be played.

## Configuration

Edit [data/config/living_sector.json](data/config/living_sector.json), then restart Starsector.

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Allow new departures; existing journeys still finish when false. |
| `debugLogging` | `false` | Log spawns, diversions, and cleanup to `starsector-core/starsector.log`. |
| `globalFleetLimit` | `40` | Maximum tracked fleets across all Living Sector traffic policies. |
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

## Development

Run the decision tests without a game installation:

```sh
python3 build.py test
```

`build` and `package` additionally compile against the installed API and run headless journey contract tests. See [architecture and extension points](docs/ARCHITECTURE.md) and the [live test procedure](docs/TESTING.md).

MIT licensed. Starsector and its game assets belong to Fractal Softworks and are not distributed here.
