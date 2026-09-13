# Configuration reference

[Back to the README](../README.md) · [Player commands and optional recording](PLAYTEST.md)

With **LunaLib enabled**, open its settings menu and select **Living Sector**. General, Civilian Traffic, Advanced and Debug tabs expose the existing traffic controls. Save/apply the menu changes to update the mod without restarting. Basic debug logging and the traffic recorder default to off. Phase A always uses native routes.

For long-run debugging, enable **Record traffic history** or run `ls debug on`. Use `ls debug summary 180` for recent events and outcomes, `ls debug trip <tripId>` for one trip, and `ls debug off` to stop. The [recorder guide](DEBUG_RECORDER_DESIGN.md) covers all commands, save/load timelines and retention limits. Its JSONL archives live in `saves/common/living-sector-debug/`, with a shared rotating 32 MiB allowance by default. Console overrides end on campaign load or the next Luna settings apply; the Luna/JSON setting persists across loads.

**Without LunaLib**, edit [data/config/living_sector.json](../data/config/living_sector.json), then restart Starsector. LunaLib is not a required launcher dependency, and its adapter is never loaded when the library is disabled. No LunaLib objects are stored in campaign saves.

When LunaLib is enabled, its saved values (including menu defaults) override corresponding JSON fields. Missing Luna fields fall back to JSON. Legacy `vip` settings remain JSON-only for the preserved spike; they no longer control automatic traffic. An invalid update is rejected as a whole, retaining the previous valid configuration and reporting the problem in `starsector.log`. LunaLib owns its settings persistence; treat menu values as installation-wide rather than per-save overrides.

Traffic controls affect subsequent planning and maintenance; they do not rewrite existing itineraries, remove fleets when a limit is reduced, reset origin cooldowns, or undo `ls pause`. Changing check intervals reschedules them without catch-up work. Settings are read at startup, campaign load and menu-save callbacks, never polled per frame.

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Allow new departures; existing journeys still finish when false. |
| `debugLogging` | `false` | Log spawns, diversions, and cleanup to `starsector-core/starsector.log`. |
| `debugTrafficHistory` | `false` | Enable the separate rotating traffic event recorder. |
| `debugHistoryMiB` | `32` | Shared recorder archive allowance across all saves/runs; 8–128 MiB. |
| `useNativeRoutes` | `false` | Legacy extension executor flag; Phase A and explicit console tests always use native routes. |
| `globalFleetLimit` | `40` | Maximum tracked direct journeys plus native missions. A route with a physical fleet counts once. |
| `planningIntervalDays` | `5` | Campaign days between traffic planning passes and their sector snapshots. |
| `maintenanceIntervalDays` | `2` | Campaign days between active-fleet safety and cleanup checks. |
| `civilian.includeStations` | `true` | Include inhabited stations as normal civilian endpoints and in the population target. Set false for planet-only traffic. |
| `civilian.minimumMarketSize` | `3` | Minimum civilian endpoint size. |
| `civilian.baseTarget` | `2` | Base population target. |
| `civilian.marketsPerAdditionalFleet` | `12` | Eligible colonies per additional target fleet. |
| `civilian.maximumTarget` | `20` | Maximum base target before variation. |
| `civilian.targetVariation` | `0.25` | Random variation of up to ±25%. |
| `civilian.targetRerollDays` | `25` | Campaign days between target rerolls. |
| `civilian.dailySpawnChance` | `0.35` | Daily probability basis, converted to a chance over each planning interval. |
| `civilian.hardLimit` | `30` | Absolute limit on tracked civilian trips including legacy VIPs. |
| `civilian.originCooldownDays` | `10` | Minimum interval between successful civilian departures from one origin. |

With `N` eligible colonies, the base target is `min(20, 2 + N / 12)`, multiplied by a random factor between `0.75` and `1.25`. A sector with 60 eligible colonies has a base target of 7. A sector with fewer than two has no civilian departures.

The daily probability basis is `p = 0.35 / (1 + (active / target)^4)`. At each planning pass, the probability of one departure is `1 - (1 - p)^planningIntervalDays`. With no active civilian traffic, the default five-day pass has about an 88% chance of proposing a trip. A policy can spawn **at most one fleet per pass**: longer intervals intentionally reduce maximum traffic throughput. Missed intervals do not queue catch-up spawns. Set `planningIntervalDays` to `1` to restore daily planning.

The target controls crowding; it does not promise a particular number of shuttles. Travel duration, compatible routes, cooldowns, and planning frequency also determine observed traffic.

Port selection mildly favors larger colonies and shorter trips. Origin weight is `sqrt(max(1, size - 2))`; destination weight is `sqrt(max(1, size - 2) / (1 + distanceLY / 10))`. A size-6 port gets twice the origin weight of a size-3 port. For equally sized destinations, one 10 light-years away gets about 71% of the weight of one in the same system. These preferences apply equally to planets and stations; eligibility, cooldowns, and diplomacy still apply.

Routine maintenance reads only each journey's live endpoints and their faction relations. Full snapshots are created for planning or when an unsafe journey cannot return to its origin and needs another port. All searches within one update share a snapshot. Planning skips full scans when globally disabled, at the global fleet cap, or without policies ready to run. Maintenance can take up to its configured interval to notice a war or release a finished fleet; longer intervals trade responsiveness for less work. Native fleet AI continues moving between checks.

Hidden markets, uninhabited planets, and systems without jump points are excluded. Emergency diversions may use an inhabited station even when ordinary civilian traffic is limited to planets. If there is no safe port, or a trip times out, the fleet is retired when it is not visible to the player and is not in a battle.

The manager installs when a new or existing campaign loads. Once you save with the mod, that save contains its Java classes; removing the mod from that save is not supported. Set `enabled` to `false` to stop new traffic while retaining the mod.

The native executor preserves normal Starsector/Nex materialization rules. It stores a remaining fleet-point budget and regenerates suitable ships when a route materializes. Ship identities, hull/CR, captains and cargo may change. Physical casualties and additional abstract damage reduce the allowance through the [aggregate damage model](OFFSCREEN_DAMAGE.md); no additional battles are simulated. Legacy checkpoints convert using surviving ships. Full older-campaign migration still needs a live save/load check.
