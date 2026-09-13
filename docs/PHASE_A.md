# Phase A: civilian traffic

The default automatic provider is one civilian group containing local passenger shuttles, interstellar liners and private charters. The group replaces automatic VIP spike departures. Existing VIP journeys finish using their saved executor and count toward the civilian limit.

## Decisions and fleet requests

The scheduler takes a lazy sector snapshot every five campaign days by default. Eligible ports are inhabited, visible markets of size 3 or above, including stations; the existing port reader excludes systems without jump points. Endpoints must be distinct and non-hostile in both directions. Port weights mildly favor larger markets and shorter distances.

The group has one soft target: `min(20, 2 + eligiblePorts / 12)`, with ±25% variation rerolled every 25 days. Its daily probability is `0.35 / (1 + (active / target)^4)`, converted to the planning interval. At most one departure is admitted per pass. The shared hard limit is 30 trips, within the global limit of 40; neither target forces a minimum population. All three types share a ten-day origin cooldown. A successful native admission consumes it even before a physical fleet appears.

Types compete with relative weights 5:3:2. A type lacking valid endpoints yields to the others. Same-direction routes conflict across the group; reverse routes also conflict if either trip returns. An empty suitable ship pool rejects the proposed admission without consuming a cooldown or mission ID. The scheduler retries on a later normal pass.

| Purpose | Geography | Desired passenger capacity | Max ships / hull | Boarding / unloading | Return chance | Lifetime |
| --- | --- | --- | --- | --- | --- | --- |
| Local passenger shuttle | Same system | 30 | 1 / destroyer | 0.25 / 0.25 days | 35% | 30 days |
| Interstellar passenger liner | Different systems | `150 × max(1, smaller endpoint size − 2)` | 3 / cruiser | 2 / 1.5 days | 50% | 180 days |
| Private charter | Either | 10 | 1 / destroyer | 0.5 / 0.25 days | 25% | 90 days |

Every trip unloads. A returning trip then travels back to its origin and docks; a one-way trip finishes at the destination. The return roll happens when the plan is proposed and is retained at admission. A fleet that survives several physical generations does not reroll its itinerary. Lifetime includes stops and return travel; safety diversion can supersede the planned route.

`CivilianFleetRequest` separates ship requirements from route purpose. Capacity is a composition goal estimated from spare crew berths, not passenger cargo, actual demand or a guarantee of filled seats. No commodities are created or removed from markets. Fleet size stops at the requested capacity or ship-count bound.

## Ship selection and persistence

At native admission, `CivilianShipSelector` uses the origin faction's and Independent faction's `personnelSmall/Medium/Large` and `linerSmall/Medium/Large` role pools. It uses the game's role weights and blocks unrelated fallback factions. For each ship, it tries the home pool first with probability 65%, otherwise Independents; an empty eligible pool falls back to the other. This is a preference, not a guaranteed fleet-wide percentage.

Eligible variants must have a civilian non-carrier hull, passenger transport/liner hints, spare crew capacity and an allowed hull size. Stations, modules, unboardable hulls, combat ships and non-passenger freighters are excluded. Modded factions work through their registered role pools; a modded passenger hull absent from both pools will not be discovered by an all-hull scan. Independent hulls do not change the fleet's allegiance.

Admission samples suitable ships to establish a fleet-point budget, then saves the request and itinerary. Materialization selects composition again within the remaining budget. Role list changes may produce different ships, and hull/CR, captains and cargo use normal generation defaults. Actual physical casualties and additional offscreen route damage reduce the allowance through the [aggregate damage model](OFFSCREEN_DAMAGE.md); repeated materialization does not refill or erode that allowance. A positive budget unable to fit a valid ship cancels explicitly. Old explicit VIP/test variant lists remain bounded generation templates. Removing a ship mod used by a live save is still unsupported.

## Native execution and extension points

All Phase A plans request native execution, irrespective of the legacy `useNativeRoutes` setting. Starsector/Nex retains normal movement, materialization and battle behavior. Living Sector does not change hostile target priorities, introduce a shadow faction or implement total-war modes in this phase. Civilian routes remain excluded from the checked Nex strategic-strength queries.

A fresh sector can initially have the vanilla route manager. Automatic civilian planning waits for Nex's manager without scanning or admitting trips; save/load activates Nex's replacement in the supported installation. Living Sector does not replace the global manager itself.

The pure policy owns route decisions over snapshots. The campaign adapter owns live validation, faction role selection and native bindings. Add new civilian purposes inside the shared provider; register a separate policy only for a group needing its own budget. Additional state such as demand, accessibility or danger can be added to snapshots and policy decisions later. A standalone public library is not part of Phase A.

## Settings, migration and debugging

Optional LunaLib exposes 28 fields, including group controls, type toggles/weights and all three return probabilities. Without Luna, the `civilian` JSON block supplies the same defaults. Legacy `vip` keys and `useNativeRoutes` remain available for the earlier spike/extension path but do not select automatic Phase A behavior. Old Luna VIP values do not transfer to the new civilian controls; review the new defaults when upgrading.

Existing plans keep their original fields, selected ships and stops. Newly added optional plan fields default to the legacy path when absent from serialized data. The civilian scheduler starts its own target and departure history; old VIPs count toward capacity, but their historic per-port cooldowns are not copied into the new group. Headless XStream tests check mission data; a full live upgrade/save/load remains a separate validation step.

`ls status` identifies subtype, operator faction, chosen variants and one-way/return itinerary. With debug history enabled, `CREATED` and `OBSERVED_EXISTING` records also contain the budget group, selected variants, return intent and requested passenger capacity. The recorder remains off by default and uses the existing rotating allowance. The [test guide](TESTING.md) describes the first live Phase A run.
