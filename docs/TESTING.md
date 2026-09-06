# Testing

## Automated checks

```sh
# Requires Python 3 and JDK 17; no Starsector files required.
python3 build.py test

# Also compiles the mod and runs journey contract tests against the installed API.
python3 build.py build

# Runs the same checks, builds the jar, then creates an installable zip.
python3 build.py package
```

The decision suite runs a deterministic 4,000-day simulation. The journey suite tests war, hostile conquest, lost destinations, return trips, off-screen retirement, battle protection, cleanup, independent scheduling cadences, idle/cap scan suppression, pause handling, and reload deadline initialization. Assertions in these suites are not a substitute for running the game.

## First live campaign smoke test

Status for 0.1.1: **not yet performed**.

1. Build, enable Living Sector in the launcher, and start Starsector 0.98a-RC8. Use a separate save slot for the prototype.
2. Enable `debugLogging` in `data/config/living_sector.json` before starting the game. For a quicker touch test, temporarily set `planningIntervalDays` to `1.0`, `maintenanceIntervalDays` to `1.0`, `vip.dailySpawnChance` to `1.0` and `vip.originCooldownDays` to `0.0`. Restore normal values after testing.
3. Load a campaign with at least two inhabited, non-hostile planets. Advance several unpaused campaign days. Look for `Spawned vip` in `starsector-core/starsector.log`.
4. If Console Commands is enabled, run the following to list active routes and current locations:

   ```text
   runcode org.lazywizard.console.Console.showMessage(livingsector.campaign.TrafficManager.status());
   ```

   Console Commands is optional and is not a dependency of this mod. The status function also shows planning/maintenance counts, sector scans, and last/max update milliseconds since load. Market IDs in its output identify the selected colonies.

5. Find a listed **VIP Shuttle** and observe boarding, departure, system jumps where applicable, and docking/despawn at its destination. Verify that it can be encountered and attacked as a normal civilian fleet.
6. Save with an active trip, exit, reload, and check status again. There should still be one manager, the existing journey should continue, and new departures should remain bounded.
7. With Nexerelin, test a war or conquest during an active trip. On the next maintenance pass the shuttle should divert if the route is no longer safe. Also verify same-faction trips, neutral cross-faction trips, and peace restoring new departures.
8. Destroy a shuttle and advance at least `maintenanceIntervalDays`. It should leave the active list and free capacity. Test expiry with a temporarily low `maximumTripDays`, checking that the fleet does not disappear while visible or fighting.
9. Set global `enabled` to `false`, restart, and load. New departures should stop while existing fleets continue to finish and be cleaned up.

Log actual results and the tested game/mod versions before marking the smoke test complete. Headless interface tests cannot prove jump-point navigation or save/reload compatibility with a real campaign.

## Performance verification

With the default intervals, the headless manager test verifies six planning snapshots over 30 campaign days, compared with thirty in the original daily implementation. Disabled idle traffic and healthy maintenance perform zero sector scans. The tests also cover a full fleet cap, custom intervals, paused time, and a large time step producing one pass rather than a backlog. This is an operation-count check, not an FPS benchmark.

For an actual campaign, record the colony count, active fleet count, configured intervals, and console status timings over a few months, including a war and several diversions. Keep the default intervals for this test. Expect normal planning roughly every five days and safety checks every two; extra snapshots are possible when unsafe fleets require another port. Measure before deciding whether to stagger work across frames or add event listeners. Test save/reload with active fleets as part of this run.
