# Testing

## Current development build: 0.2.0

Start with the [native-route test instructions](ROUTE_TEST.md). The basic 0.2.0 native one-way trip has now passed live: user screenshots at elapsed days 26.1 and 33.6 show `ls-1 COMPLETED: physical fleet docked at final stop`, with no active native missions. Automatic departures continue using the original direct executor (5 then 6 total departures, 3 then 2 active). Return trips, distance despawn/restoration and battle-loss persistence remain live-test gaps. The separate 0.1.1 smoke-test record below applies to the original executor.

The first 0.2.0 live attempt, in a freshly generated sector, stopped at the route-manager prerequisite without creating a mission. Saving/loading then allowed `ls-1` to register and spawn, confirmed in the game log. The test guide includes this initial save/load for Nex's replacement to take effect. Regression tests cover repeated vanilla-manager rejection without side effects, identifying an unsupported replacement, and retrying after a Nex manager becomes available. They simulate that availability change, not the save loader itself.

The next live check found `ls visit` incorrectly treating Console Commands' hidden message dialog as a campaign interaction. Both `visit` and `away` now check for an actual interaction dialog or battle. Regression tests exercise player movement with the console overlay open and verify that actual interactions/battles still block both commands without movement. A subsequent game log confirms `ls visit` succeeded and `ls status` reported the saved physical fleet still boarding with its original fleet/ship IDs.

That session later crashed during an NPC autoresolve: `ListenerUtil.reportBattleOccurred` delivered the documented null fleet for a global notification, which `NativeTraffic` dereferenced. A new regression reproduced the exact exception through the installed `ListenerUtil` before the fix. The listener now ignores global battle broadcasts and records only callbacks naming an owned fleet. Tests cover abstract and physical missions, load-time registration, unrelated fleets, and recording an owned fleet's battle once despite its accompanying global notification. The fix changes no saved fields. Live testing subsequently resumed and reached mission completion; these screenshots do not independently establish a real battle-loss cycle.

Another live issue exposed player teleports failing to update the sector's active location. Both commands now synchronize entity membership and active location and clear old player assignments. Three new command scenarios reproduced the omission before the fix, including recovery from stale location state. The user reported that hyperspace lag stopped after installing it. At elapsed day 33.6, status reports 6 sector scans and a maximum manager update of 2.9926 ms; this measures the manager, not total frame time.

The new pure mission suite checks return intent, stable identity across fleet generations, duplicate/stale callback handling, terminal-state stability, and bounded history. The new adapter suite runs the installed Nex route manager's timing and spawn/despawn callbacks against fake campaign entities. It covers abstract completion, intermediate versus final docking, survivor/condition restoration, late native damage import, null-spawn expiry, cancellation/battle protection, listener registration, and both actual Nex military-strength queries in physical and abstract states. Probe tests check that long time jumps are reported as missing coverage instead of projected pursuit time.

The injected factory failure in the adapter suite is deliberate: the assertion checks that the failed mission releases capacity without leaving a fleet. The expanded harness also runs Nex's battle-loss callback with controlled before/after fleet members, plus installed XStream serialization of mission data. It does not run actual combat/autoresolve, campaign navigation, sensors, or the complete campaign save serializer.

## Automated checks

```sh
# Requires Python 3 and JDK 17; no Starsector files required.
python3 build.py test

# Full unit + integration checks; leaves the installed jar untouched.
python3 build.py integration

# Same checks, then installs the validated jar. Close the game first.
python3 build.py build

# Same checks, then creates an installable zip without changing the installed jar.
python3 build.py package
```

The integration runner has 36 scenarios without LunaLib (`build/reports/integration.xml`) and 6 with the installed library (`build/reports/luna-integration.xml`). It continues through independent scenario failures, then exits unsuccessfully if any failed. Each scenario resets the campaign globals, mod settings and provider registry. The candidate jar is on the test classpath; production loose class files are excluded. Jar checks enforce Java 8 bytecode, configured entry-point presence, and exclusion of dependency/test classes. A successful run writes `build/reports/validated-build.json`, identifying the candidate SHA-256 and installed library paths; stale reports are cleared before a new full run.

LunaLib remains optional at runtime. Headless tests exercise its real CSV parser and settings callback, validate all 21 menu field mappings, retain valid settings after a rejected update, and preserve active traffic. A separate JVM removes its jars entirely and loads the plugin/campaign anyway. The live menu still needs a smoke test: restart with the new build, open Luna settings → Living Sector, disable automatic traffic and apply, then re-enable it. Existing trips should continue in either case. Leave basic debug logging off after testing. On a disposable test installation, disabling LunaLib should restore JSON settings without affecting saved missions; do not disable unrelated mods that require it.

Recorder scenarios cover zero recorder file I/O while disabled, native lifecycle events, direct arrivals/destruction/diversion, duplicate callbacks, recording an existing trip, write-error recovery, save rollback ancestry, period queries, and Luna's live recorder toggle. Storage scenarios fill multiple runs with UTF-8 events and check every write against the shared disk allowance, individual file size, and slot limit. They also cover reducing the allowance, read-only reports, deletion failures, and unrecognized files. File operations use an in-memory implementation of the common-file API; actual game file writing remains a live check.

The reusable fixture and extension rules are documented in [tests/INTEGRATION.md](../tests/INTEGRATION.md). Core campaign/settings/listener stubs reject unmodeled calls. Fleet and ship doubles intentionally model only the state used by these scenarios; this is not a full fake Starsector engine.

Four Python tests check the Python build workflow: failed checks preserve the previous installation/package, `integration` does not install, file-lock failure preserves the old jar and candidate, and packaging uses the candidate rather than a stale installed jar. Campaign integration scenarios themselves are Java. No test framework or extra runtime library is added to the mod.

The decision suite runs a deterministic 4,000-day simulation. The journey suite tests war, hostile conquest, lost destinations, return trips, off-screen retirement, battle protection, cleanup, independent scheduling cadences, idle/cap scan suppression, pause handling, and reload deadline initialization. Assertions in these suites are not a substitute for running the game.

`TrafficBehaviorTests` adds six scenarios through the public policy and scheduler interfaces:

- Size-3 origins remain competitive with size-6 origins after flattening selection weights.
- Distant destinations still receive substantial traffic while nearby destinations remain favored.
- Equally sized, equally distant planets and stations have equal selection opportunities as origins and destinations.
- A station-only sector can generate station-to-station trips and contributes to the population target.
- Two traffic types keep independent hard limits, duplicate-route checks, and origin cooldowns.
- A positive target never forces departures when the configured chance is zero.

Selection tests sample 12,000 proposals per comparison using fixed random seeds and broad acceptance bands. They check actual selected routes rather than calling the private weight calculation, so they catch accidental bias changes without depending on an exact random sequence. These are simulated proposals; they create no game fleets. All six scenarios run with `python3 build.py test`, and therefore also with `build` and `package`.

## Recorder live smoke test (pending)

1. Restart with the new jar and load a test save. `ls debug status` should show OFF with default settings.
2. Enable **Record traffic history** in Luna settings → Living Sector → Debug and apply. Without LunaLib, use `ls debug on` for this session or enable `debugTrafficHistory` in the JSON settings before starting the game.
3. Let automatic traffic run or create a native test with `ls test`. Run `ls debug flush` and `ls debug summary all`. An existing trip should be recorded as an observation, while a newly created trip should count as a departure. `ls debug trip ls-1` shows retained events for that native trip; use the actual trip ID from status.
4. Run `ls debug export` to find the JSONL directory and confirm files exist. The default shared allowance is 32 MiB; rotation is already tested automatically, so there is no need to fill it manually.
5. With recording enabled through LunaLib or JSON, save, advance, then reload the earlier save. `ls debug runs` should list separate recording segments. `ls debug summary all` should follow the loaded save's timeline and exclude the abandoned future. Console-only `on` resets on load, so re-enable it if testing that way.
6. Run `ls debug off`, allow its final flush, then advance and save again. Recorder file timestamps should stay unchanged. Leave recording disabled after the test.

These checks exercise the actual game filesystem, menu and save hooks. Headless serialization covers the recorder cursor and mission data, not the complete campaign save graph. See [the recorder design](DEBUG_RECORDER_DESIGN.md) for retention and gap interpretation.

## First live campaign smoke test

Status for 0.1.1: **core smoke test passed**. In the first live test, two VIP shuttles spawned and reached hyperspace. One was observed completing its trip to an inhabited station and despawning normally on arrival, confirming station selection, travel, docking, and destination cleanup. A campaign was saved and reloaded with active shuttle traffic, and the traffic continued successfully after reload. The observed status showed two planning passes, five maintenance passes, two sector scans, and last/max manager update times of 0.0326/2.4106 ms. No Living Sector errors were found in the inspected log. War diversion, destruction cleanup, timeout behavior, and longer performance observation remain optional follow-up tests.

1. Build, enable Living Sector in the launcher, and start Starsector 0.98a-RC8. Use a separate save slot for the prototype.
2. Enable `debugLogging` in `data/config/living_sector.json` before starting the game. For a quicker touch test, temporarily set `planningIntervalDays` to `1.0`, `maintenanceIntervalDays` to `1.0`, `vip.dailySpawnChance` to `1.0` and `vip.originCooldownDays` to `0.0`. Restore normal values after testing.
3. Load a campaign with at least two inhabited, non-hostile planets or stations. Advance several unpaused campaign days. Look for `Spawned vip` in `starsector-core/starsector.log`.
4. If Console Commands is enabled, run the following to list active routes and current locations:

   ```text
   runcode org.lazywizard.console.Console.showMessage(livingsector.campaign.TrafficManager.status());
   ```

   Console Commands is optional and is not a dependency of this mod. Status shows the current campaign date, elapsed campaign days since this campaign's Living Sector manager was installed, and time until the next planning/maintenance passes. Elapsed days persist across saves; performance counters and timings reset on load. Market IDs identify the selected colonies; each shuttle also lists its unique fleet ID and local coordinates, making movement within hyperspace visible between status snapshots.

   Each active fleet prints two console commands. Run them separately, while paused:

   ```text
   jump <location ID printed by status, or hyperspace>
   goto <fleet ID printed by status>
   ```

   Replace the placeholders with the printed values, without angle brackets. If already in the fleet's location, just use `goto`. `goto` resolves fleet IDs only in your current location, so use `jump` first when needed. Refresh status if the shuttle has moved to another system or despawned. These are the installed Console Commands mod's own teleport commands; requesting status itself never moves the player or the shuttle.

5. Find a listed **VIP Shuttle** and observe boarding, departure, system jumps where applicable, and docking/despawn at its destination. Verify that it can be encountered and attacked as a normal civilian fleet.
6. Save with an active trip, exit, reload, and check status again. There should still be one manager, the existing journey should continue, and new departures should remain bounded.
7. With Nexerelin, test a war or conquest during an active trip. On the next maintenance pass the shuttle should divert if the route is no longer safe. Also verify same-faction trips, neutral cross-faction trips, and peace restoring new departures.
8. Destroy a shuttle and advance at least `maintenanceIntervalDays`. It should leave the active list and free capacity. Test expiry with a temporarily low `maximumTripDays`, checking that the fleet does not disappear while visible or fighting.
9. Set global `enabled` to `false`, restart, and load. New departures should stop while existing fleets continue to finish and be cleaned up.

Log actual results and the tested game/mod versions before marking the smoke test complete. Headless interface tests cannot prove jump-point navigation or save/reload compatibility with a real campaign.

## Performance verification

With the default intervals, the headless manager test verifies six planning snapshots over 30 campaign days, compared with thirty in the original daily implementation. Disabled idle traffic and healthy maintenance perform zero sector scans. The tests also cover a full fleet cap, custom intervals, paused time, and a large time step producing one pass rather than a backlog. This is an operation-count check, not an FPS benchmark.

For an actual campaign, record the colony count, active fleet count, configured intervals, and console status timings over a few months, including a war and several diversions. Keep the default intervals for this test. Expect normal planning roughly every five days and safety checks every two; extra snapshots are possible when unsafe fleets require another port. Measure before deciding whether to stagger work across frames or add event listeners. Test save/reload with active fleets as part of this run.
