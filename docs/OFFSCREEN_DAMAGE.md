# Aggregate fleet budgets and damage

Native civilian missions preserve their trip and a fleet-point allowance. They regenerate ships when Nex materializes the route. Individual identities, variants, captains, hull/CR and cargo are not preserved between physical generations. Living Sector does not generate extra battles or choose offscreen winners.

## Generation

Admission samples the faction/Independent civilian role pools to establish the initial allowance, without creating a fleet. The saved request retains passenger capacity preferences and ship/count limits. Each physical generation selects eligible hulls again, with a mission/generation seed and a strict remaining-FP filter. Capacity is a preference, not permission to exceed a damaged fleet's budget. Existing explicit VIP/test variant lists are bounded templates.

For example, a 20-FP trip with 40% losses has a 12-FP allowance. Its next fleet can contain different ships totaling at most 12 FP. Ship count may change. If a positive allowance cannot fit any eligible hull, materialization cancels with a clear reason; no oversized minimum ship is supplied. Zero allowance means destruction.

## Abstract damage

`FleetBudget` saves initial and remaining FP, an accounted route-damage watermark, and the starting FP of the current physical generation. When native route damage increases from `D0` to `D1`:

```text
remainingFP = remainingFP * (1 - D1) / (1 - D0)
```

Damage is clamped to 0–1; full damage explicitly exhausts the allowance. Non-finite inputs are diagnosed. Repeated or decreased counters do not restore allowance or apply losses again. Going from 50% to 75% removes half the remaining budget.

This runs during scheduled abstract maintenance, before materialization and before abstract completion. No ships need constructing to calculate abstract loss. Full loss ends as `DESTROYED`, including before the first physical generation.

## Physical casualties

While a fleet exists, the engine owns its ships and combat. Living Sector compresses survivors at Nex's pre-distance-despawn callback, after normal battle dispatch:

```text
remainingFP *= min(1, survivingPhysicalFP / generatedPhysicalFP)
```

It then imports the current native damage watermark and clears the physical baseline. That makes repeated capture harmless. If a 12-FP allowance generates only 10 FP because of hull sizes, retaining all 10 leaves the allowance at 12; losing half reduces it to 6. Hull-only damage and CR are allowed to reset at the next generation. Cargo uses ordinary generation defaults.

Nex adds physical lost FP divided by its starting-FP denominator to route damage. For Living Sector's new fleets, `$startingFP` is `generatedPhysicalFP / (1 - accountedRouteDamage)`, so successive generations contribute to the same cumulative scalar even when hull-size rounding leaves budget unspent. This uses memory on our own fleets and the installed Nex code; no Nex files or global rules are changed. The budget capture imports that updated scalar without charging the physical loss again.

## Saved data

Old `FleetCheckpoint` classes/fields remain readable but new traffic never creates rich checkpoints. On campaign load or a native boundary, old abstract checkpoints convert surviving hull FP to a budget and retain their accounted damage. Older records missing ship FP derive it from the saved hull specification. Invalid/missing specifications are diagnosed instead of inventing capacity.

A current physical fleet supersedes a stale checkpoint and is preserved intact during migration. Its observed FP becomes the new baseline, and its own Nex starting-FP denominator is updated for subsequent battles. Converted `initial` FP is the surviving allowance at migration, not a reconstruction of the original intact fleet. Old rich state and selected Phase A variants are released after conversion. New aggregate data serializes the damage watermark and any current physical baseline.

## Debugging and coverage

Recording remains off by default, automatically flushed and rotated within its existing allowance. Native events include `initialBudgetFP`, `remainingBudgetFP` and `accountedRouteDamage`. Budget transitions also include `previousBudgetFP`:

- `BUDGET_MIGRATED`: legacy data converted (retained in bounded mission history even when load occurs before recorder attachment).
- `BUDGET_CAPTURED`: physical survivors compressed, or physical destruction recorded.
- `ABSTRACT_DAMAGE`: additional native scalar damage applied.
- `FLEET_COMPOSITION`: actual generated ship IDs, variant IDs and FP. Different IDs across generations are not evidence of casualties.

Actual `BATTLE` diagnostics still describe observed participants and losses. Abstract damage does not invent per-ship casualties or opponents. `ls status <id>` shows the allowance and current physical baseline; while physical, additional casualties are reflected in actual ship data until capture.

Automated checks cover fractional/complete loss, generation rounding, repeated/decreased counters, real Nex callback order and repeated battles across generations, migration, data serialization, composition changes and recorder on/off behavior. They do not reproduce real combat, sensor-driven materialization or Starsector's full campaign save graph. Live campaign upgrade and performance checks remain necessary.
