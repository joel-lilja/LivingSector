# Native-route test: 0.2.0

**Status: basic native one-way trip passed live.** Startup, physical spawning, travel, final docking and removal from the active mission list have been observed. Return trips, distance despawn/restoration and battle-loss persistence still need live checks. Headless tests exercise the installed Nex route manager and mission-data serialization, but do not run campaign navigation, combat, sensors, or the complete campaign save serializer.

Before testing changed code, run `python3 build.py integration`. Then, with the game closed, `python3 build.py build` repeats the checks and installs the validated jar. `package` creates an archive without updating the installed jar. See [automated coverage and its limits](TESTING.md) for what a passing preflight establishes. Loading an active mission and `ls visit` have also been observed.

The latest user screenshots at elapsed days 26.1 and 33.6 both show `Recent ls-1 COMPLETED: physical fleet docked at final stop`, with `Native missions=0`. Automatic traffic continues through the default direct executor: total departures rise from 5 to 6 while active traffic changes from 3 to 2. At day 33.6 the manager reports 6 planning passes, 14 maintenance passes, 6 sector scans and a maximum update time of 2.9926 ms. These are manager timings for this session, not a whole-game FPS benchmark or a high-population stress test.

Restart Starsector after rebuilding. Enable Living Sector, Nexerelin and its dependencies, and Console Commands. Use a separate save slot for the experiment. Leave `useNativeRoutes` at its default `false` for now; the commands below explicitly select the new executor.

**Freshly generated sector:** save the campaign, then load that save before running `ls test`. Nex installs its route-manager replacement through its save-loading alias; a fresh campaign can still have the vanilla manager. The first live attempt encountered this prerequisite before creating any mission. If the console says the active manager is not `NexRouteManager` (older diagnostic) or asks you to save/load, do that and retry. Generating another sector is unnecessary. If it still refuses after loading, report the full message and game log.

## Test 1: one trip

### Hyperspace teleport regression

`ls visit` and `ls away` previously moved the player without updating the sector's active location. Both now synchronize that state and clear old player assignments. The user reported that the hyperspace lag stopped after installing this fix. This validates the reported scenario, not performance across all traffic loads.

After installing the fix and restarting, load the existing test save. Run `ls status` to find an active mission ID (or `ls test` if none remain), then `ls away <id>` to enter hyperspace. Close the console and check movement/frame rate while unpaused, then while paused with Space. Run `ls visit <id>` to return to the mission and repeat the comparison. If the shuttle is in hyperspace, this also exercises visiting it there. Repeat `ls status <id>` and `ls verify <id>`; the command must not create another fleet generation or advance the route by itself. A finished mission requires a new test ID.

If slowdown persists, report whether pausing helps, whether it also happens when entering through a normal jump point, and the latest `ls status` output. These observations distinguish a teleport problem from simulation/rendering load; passing headless tests cannot establish FPS.

### Start or resume the route test

Run each command separately:

```text
ls test
ls visit ls-1
```

Use the actual mission ID printed by `ls test`; it may be `ls-2` or higher in a campaign that already has tests. The test selects inhabited, non-hostile ports near the player, preferring an intersystem destination. It creates a route served by two transports so later tests can remove one ship without destroying the whole mission. Planets and stations are both eligible.

`ls visit` teleports only the player. Unpause briefly so the native route manager can materialize the fleet, then run:

```text
ls status ls-1
ls verify ls-1
```

Expected: `ACTIVE`, `PHYSICAL`, generation `1`, two ship IDs, and a `PASS` current-state verification. Observe boarding, travel, and final docking/despawn. Status should eventually report `COMPLETED`, with the same mission ID. Finished missions remain in a bounded recent history; capacity is released at the next maintenance pass.

Status separates `Current: PHYSICAL`/`Current: ABSTRACT` from `History (oldest first)`. The historical registration event saying "waiting for normal materialization" describes the initial abstract state, not the fleet's present condition. `ls visit` reports when a fleet is already physical instead of always asking the player to wait for spawning.

Save/reload during travel and repeat status/verify. Existing 0.1.1 shuttles should also remain present and finish through their original executor. Report any `FAILED` outcome with `ls status <id>` and the corresponding log lines.

For exact endpoints:

```text
ls test <originMarketId> <destinationMarketId>
```

These are market IDs, not display names. Manual tests bypass departure probability, origin cooldowns, and duplicate-route suppression so repeated traffic can be created for a controlled comparison. They still consume the shared global mission limit and contribute to VIP crowding for automatic planning. Automatic departures continue using the normal policy rules.

## Return trip

```text
ls roundtrip
```

Visit the printed ID. Expected itinerary: origin → destination visit → origin → completion. Arriving at the intermediate port must not despawn the fleet as a completed trip. The route uses native movement and passive civilian visits.

## Distance despawn and survivors

Use a dedicated checkpoint test:

```text
ls checkpoint
ls visit <printedMissionId>
```

This trip boards for 45 days to give the native retention period time to elapse. Once physical:

```text
ls lose <id>
ls damage <id>
ls status <id>
ls away <id>
```

`lose` removes one test ship. `damage` sets the remaining test ship to 50% hull and 40% base CR. They work only on explicit test missions outside battle. These are synthetic persistence tests; they do not prove Nex's real battle callback.

`away` teleports the player 12 LY from the trip. Unpause and check status until `ABSTRACT` appears. A fleet recently observed by the player can remain physical for roughly 30 days under the installed native rules; Nex operations can also retain it. No thresholds or force-spawn flags are changed. A short ordinary trip can complete before dematerializing, which is why this test has a long boarding period.

Record the saved survivor ID and condition shown by abstract status, then:

```text
ls verify <id>
ls visit <id>
```

Unpause, then repeat status/verify. Expected: generation increased, a new fleet ID, the same surviving ship ID, and no restored lost ship. Compare condition with the recorded checkpoint. Native repairs can occur while the fleet is physical, including during its retention period; condition need not remain equal to the original injected damage indefinitely.

Save/reload while abstract as well as physical. A real battle/distance-despawn cycle is an additional test for actual battle-loss propagation.

## Measuring military distraction

Use comparable saves/scenarios and the same observation location. The probe does not create enemies, change diplomacy, or alter anyone's AI.

```text
ls pause
ls probe start 30
ls probe status
```

`pause` stops automatic Living Sector departures while existing trips finish. It persists in the save until `ls resume`. Run a baseline after existing traffic has cleared, then reload the same starting scenario and add a few explicit `ls test <origin> <destination>` trips. Repeat with a higher count within the global cap. Restart the probe for each comparison.

The probe samples the location where it was started every 0.25 campaign days, for the requested duration (default 30 days). It reports NPC fleet-days observed, fleet-days with a Living Sector fleet as the current tactical target, percentage, and up to six current pursuers with IDs, assignment types, and native pursuit durations. It excludes the player, stations, Living Sector fleets, and trade-flagged observers. Fleeing from a shuttle is not counted as targeting it for pursuit.

These are sampling estimates, not proof of delayed invasions. Also compare operation completion and whether patrols leave useful positions. Samples inspect at most 500 fleets in the location; truncation and time gaps are reported, so incomplete coverage is visible. No global fleet index or permanent monitor runs unless the probe is explicitly started.

```text
ls probe stop
ls resume
```

## Commands and cleanup

`ls status` lists direct fleets, native missions, recent outcomes, and probe results. `ls verify <id>` checks current native binding/identity and civilian exclusion from Nex's strength report; it also evaluates the allied-strength query in a star system. It does not claim to prove all future transitions or military targeting behavior.

`ls cancel <id>` requests a normal safe return for a physical mission, or cancels an abstract trip. A fleet in battle finishes that battle first. This is cancellation, not a successful delivery. `ls help` lists all commands.

The default executor for automatic traffic remains the tested direct-fleet path. After the native experiment succeeds, `useNativeRoutes: true` enables the same new executor for ordinary policy-generated trips; it does not convert already-running direct journeys.
