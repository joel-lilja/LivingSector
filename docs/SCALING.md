# Scaling design: regenerate civilian fleets

**Status: aggregate regeneration and the initial lookup fixes are implemented. Headless measurements are available; live 5,000-ship capacity is unverified.** Civilian missions use a fleet budget and regenerate ships when a native route materializes. This supersedes the earlier proposal for permanent ship slots, survivor bitsets and shared loadout tables. Exact ship persistence is no longer a requirement for ordinary civilian traffic.

The capacity target remains **5,000 concurrently represented Living Sector ships across the sector**. Abstract missions represent an approximate population rather than storing individual ships. This is a capacity target, not a desired population or an instruction to raise departure rates and limits. It does not promise performance with 5,000 physical ships in one location.

## What we keep

Store a small record per mission:

- Mission ID, traffic type, operator faction, itinerary and lifetime.
- Generation profile: civilian roles, size/count limits and faction/Independent preferences.
- Initial and remaining fleet-point budget, plus route damage already accounted for.
- Generation seed/counter and starting fleet points of the current physical generation.
- Existing bounded history and native route binding.

The trip remains the same trip. Ships, names, variants, captains, cargo, hull condition and CR may be regenerated. Generations may contain different numbers of ships within the profile's limits. Fleet points are a coarse size budget, not exact combat effectiveness or passenger capacity.

There is no persistent per-ship roster, loadout table, captain serializer or exceptional-state checkpoint system in this implementation. Named persistent actors can be a separate feature if a future traffic type needs them.

## Materialization

The native route manager continues to decide when a fleet appears or disappears. Living Sector generates from the saved profile and remaining budget using the faction's available civilian ships plus Independents. Changed role pools may produce different composition; that is acceptable.

Selection respects the remaining budget. Passenger capacity is a preference and must not force damaged traffic back to its original size. Unspent points caused by discrete hull sizes remain in the aggregate budget; repeated spawning must not progressively consume them.

If no valid ship fits a positive budget, retire with an explicit reason. Do not add an unconditional minimum ship exceeding the budget. Zero budget means destruction. Use bounded selection attempts and do not construct throwaway fleets during ordinary maintenance.

Hull/CR and ordinary cargo use normal generation defaults. A reappearing fleet may look repaired or differently equipped; that approximation is accepted. Aggregate casualties still reduce its size budget. Never rebuild a fleet while physically present or in battle.

## Loss accounting

Use one remaining budget and an accounted-damage watermark. For example, a 20-point mission with 40% aggregate losses has a 12-point budget. It may return as different ships totaling at most 12 points; its allowance does not reset to 20.

For additional abstract route damage:

```text
R = remaining fleet-point budget
D0 = route damage already accounted for
D1 = current route damage

if D1 > D0:
    R = R * (1 - D1) / (1 - D0)
```

Clamp valid damage to 0–1, handle full destruction explicitly, and diagnose non-finite input. Repeated or decreased damage counters do not restore budget or charge losses again. A move from 50% to 75% damage removes half the remaining budget.

While physical, the actual fleet is authoritative. At a battle/despawn boundary, compress surviving fleet points into the budget. Normalize against points actually generated, so rounding alone is not a casualty: a 12-point budget generating 10 physical points stays 12 if all survive; losing half those physical points reduces the budget to 6. Ordinary hull damage need not persist after dematerialization.

Physical casualties and Nex's scalar update can describe the same battle. Charge that loss once and advance the watermark with the physical result. Capture at the pre-distance-despawn hook after battle dispatch; the integration suite exercises real Nex callbacks in both attached orders and both global broadcast orders. No new battle simulation or Nex modifications are required by this design.

## Migration and diagnostics

Keep existing serialized classes readable. Add a versioned aggregate field and convert old checkpoints once at a safe boundary. Derive remaining budget from surviving saved ships, preserve accounted route damage, then release the rich checkpoint. Do not use the pristine original roster to set a damaged fleet's allowance. An already physical fleet remains intact until its normal lifecycle boundary.

Migration and current loss accounting are documented in [offscreen damage](OFFSCREEN_DAMAGE.md). Exact survivor IDs, hull, CR and cargo cease to be persistence guarantees once converted. Old serialized checkpoint classes remain readable but are not created by new traffic.

Debug recording stays optional, off by default and within existing disk rotation limits. Record budget before/after, loss cause, damage watermark and actual composition at materialization. Distinguish regenerated composition from observed casualties: changed ship IDs alone are not evidence of destruction. Abstract damage has no invented ship-level battle detail.

## Scaling work that still matters

Removing cloned variants, captain references and cargo copies should reduce retained state, but gains need measurement. Fewer saved objects do not eliminate native route or physical AI costs.

Count routes separately: 5,000 solo shuttles create more route work than 1,000 five-ship fleets. Current profiles contain one to three ships, making small-fleet cases especially relevant.

Implemented lookup changes:

1. Maintenance builds a route-membership set once per pass.
2. Admission registers the route listener locally; full fleet listener reconciliation runs on campaign load.
3. Each planning snapshot indexes active type counts and directed endpoint conflicts, including both return-trip directions. Rebuilding each pass avoids saved caches or lifecycle invalidation hooks.
Maintenance batching remains a measurement-driven option; current cadence and gameplay defaults are unchanged.

Compare current and replacement implementations at 100, 1,000 and 5,000 represented ships: solo fleets, pairs and larger groups; abstract/mixed populations; materialization bursts; long turnover; save/load; and recording off/on. Report Living Sector bookkeeping, native routes and physical fleets separately. Measure retained memory, allocation/GC, save size/time and update/materialization stalls. Manager timings alone do not establish capacity.

## Validation and remaining measurements

Run `python3 build.py benchmark` after a validated integration/package build. It runs the same headless admission/maintenance workload at 100, 1,000 and 5,000 represented ships, with 1/2/5 ships per route, and writes `build/reports/scaling.json`. A baseline was collected from the prior rich-checkpoint candidate before rebuilding. Fake entities isolate adapter overhead; these numbers do not establish live FPS, physical AI costs or heap capacity.

Functional checks cover generation, damage accounting, old checkpoint conversion, physical/abstract data serialization, rounding, exhausted budgets, actual Nex battle callback order, structured recording and disabled I/O. Operation-based regressions prohibit route-list membership searches and admission-wide fleet listener reads. A 100-mission/300-generation churn check verifies route/budget/listener cleanup and bounded history.

Live profiling of sensor-driven materialization, physical AI, engine memory/GC and full campaign save/load remains outstanding. Add scheduling complexity only if those measurements justify it. Current population defaults remain unchanged; the 5,000-ship target does not force extra traffic.

## Recorded headless comparison — 2026-09-13

Same local JVM workload; 5 warm-up maintenance passes followed by 30 measured passes per case. Times are milliseconds per maintenance pass. Small-case results vary with JIT, allocation and scheduling noise; this is an initial comparison, not a performance guarantee.

| Represented ships | Ships per route | Routes | Previous p50 | Aggregate p50 | Previous p95 | Aggregate p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 100 | 1 | 100 | 0.37 | 0.36 | 0.81 | 0.81 |
| 100 | 2 | 50 | 0.13 | 0.14 | 0.50 | 0.27 |
| 100 | 5 | 20 | 0.05 | 0.04 | 0.18 | 0.11 |
| 1,000 | 1 | 1,000 | 1.21 | 1.54 | 2.00 | 2.43 |
| 1,000 | 2 | 500 | 0.49 | 0.41 | 0.73 | 0.75 |
| 1,000 | 5 | 200 | 0.29 | 0.17 | 0.46 | 0.28 |
| 5,000 | 1 | 5,000 | 7.05 | 4.28 | 13.89 | 9.81 |
| 5,000 | 2 | 2,500 | 2.87 | 2.19 | 4.20 | 2.82 |
| 5,000 | 5 | 1,000 | 0.77 | 0.90 | 1.20 | 1.70 |

The 5,000 solo-route case fell from about 7.05 ms to 4.28 ms at the median. Smaller cases do not all improve; retain the full table when comparing future changes. Admission of those 5,000 abstract routes took about 265 ms previously and 20 ms in this run, measured as one batch; this is unrelated to physical materialization time.

The workload uses installed native route objects with fake campaign entities, debug off, and explicitly bypasses normal population limits for benchmark setup. It times Living Sector admission/maintenance, not native per-frame advancement, combat, physical ship creation, rendering, heap retention or a full save. Those live measurements remain outstanding.

Measured aggregate candidate SHA-256: `7b3cee6d52d7a461157cf37901e8bb3d1dfed12dcd89d23fc630b82858b14626`. The previous candidate was `b1ec6c5d3e103e75178f5cadd01260f3fd4fb2f60744522d393e80e82fd794f5`. Local raw reports are `build/reports/scaling-baseline.json` and `build/reports/scaling.json`; the latter is replaced by the next benchmark run.
