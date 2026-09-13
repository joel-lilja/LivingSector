# Integration harness

Run `python3 build.py integration` from the repository before asking for a live test. It requires JDK 17, the installed Starsector API, Nexerelin, LazyLib, MagicLib, Console Commands and LunaLib. The last two are optional at runtime. Pass `--game-root` for a checkout outside the mod directory. Nothing connects to a running game or changes its save files.

`test` runs the pure Java suites and the Python build-workflow tests. `integration` additionally compiles the mod, validates the candidate jar, and runs the Java campaign suites. `build` adds installation after all checks succeed. `package` adds creation of the distributable archive and does not install it.

## Structure

| File | Responsibility |
| --- | --- |
| `campaign/livingsector/campaign/IntegrationSuite.java` | Named scenarios, campaign-global reset, failure reporting and JUnit XML |
| `campaign/livingsector/campaign/CampaignFixture.java` | Shared planets/stations, player/UI, economy, scripts, fleets, snapshots and listeners |
| `campaign/livingsector/campaign/NativeTrafficTests.java` | Existing route regressions: admission, lifecycle, aggregate regeneration, errors, civilian strength and probe sampling |
| `campaign/livingsector/campaign/EntryPointIntegrationTests.java` | Configured plugin/console classes, load hooks, actual event dispatch, both battle notification orders, Nex damage import, manager cadence |
| `campaign/livingsector/campaign/SaveDataIntegrationTests.java` | Installed XStream round trips of active, abstract and terminal mission data |
| `campaign/livingsector/campaign/OptionalDependencyTests.java` | Startup, campaign load and traffic with every LunaLib jar removed from the test JVM |
| `campaign/livingsector/campaign/LunaIntegrationTests.java` | Installed Luna CSV parser, getters and change dispatcher; defaults, all field mappings, disabled library, invalid/missing values and active-trip preservation |
| `campaign/livingsector/campaign/RecorderIntegrationTests.java` | Disabled I/O, lifecycle recording, save rollback cutoffs, old-save observations, failures and period reports |
| `campaign/livingsector/campaign/RotatingLogTests.java` | Shared byte/slot limits, UTF-8 batches, budget reductions, read-only queries and storage failures |
| `campaign/livingsector/campaign/BattleHistoryTests.java` | Casualties in global snapshots, callback deduplication, opponents/losses, missing evidence and disabled collection |
| `campaign/livingsector/campaign/ScriptRestrictionLoader.java` | Candidate mod loader that blocks reflection classes for optional-Luna startup regression checks |
| `campaign/livingsector/campaign/PhaseAIntegrationTests.java` | Civilian role pools/filtering, failed admission, regenerated variants within remaining budget, native scheduling readiness, saved profiles and legacy defaults |
| `campaign/livingsector/campaign/AttritionIntegrationTests.java` | Injected offscreen route damage through native spawn/completion/maintenance, aggregate budgets, real callback orders, migration/serialization, rounding, recorder data and binding cleanup |
| `build_workflow_test.py` | Build/install/package gate behavior; no game algorithms |

The tests stay in `livingsector.campaign` to inspect package-private bindings without making debug internals part of the mod's public API. They are compiled separately and never packaged into the runtime jar.

## Real code and modeled behavior

The harness uses the installed `ListenerUtil`, Nex route manager, Nex battle-loss callback and military-strength queries. It loads the plugin from `mod_info.json` and the console command from `commands.csv`, then calls their actual entry points. Console output uses the installed Console Commands class without constructing its renderer.

The world supplies fake API entities. Route timing uses the real manager, while `TestRoutes` disables its sensor pass so scenarios explicitly drive the native spawn/despawn hooks. Its test clock maps advancement units to days. It does not simulate piloting, rendering, sensors, combat winners, or arbitrary interactions among installed mods.

Phase A models faction role pools and hull metadata behind `FactionAPI.pickShip`, including its filter and blocked fallback contract. The selector, admission, native callbacks and aggregate budget implementation are real. These scenarios do not assert the composition of every installed faction or reproduce the game's weighted role picker internals.

The player's containing location and the sector's active location are modeled separately. Command regressions cover visits between systems and hyperspace, travel to an abstract route, stale-location recovery, clearing old assignments, and rejection without moving anything during interactions/battles. The three location scenarios failed before the teleport fix. They verify location consistency, not campaign frame rate.

`World.battle` invokes both global and attached fleet listeners, with selectable ordering. Global battle events intentionally carry a null fleet, following the API contract. Fleet snapshots provide Nex with real before/after member lists for loss accounting. Despawn helpers notify attached listeners and the global dispatcher. `World.reloadHooks` drops transient listeners and calls the real plugin's load hook; it does not serialize the world.

Settings, economy, sector, clock, campaign UI and listener stubs reject API calls they have not explicitly modeled. The lower-level ship/fleet proxies provide defaults for unsupported mechanics. Do not infer full engine fidelity from those defaults. `Misc` initialization receives placeholder combat/UI values that are outside the traffic scenarios.

The ordinary integration JVM omits LunaLib completely and asserts that its classes cannot load. A second JVM adds the installed LunaLib jar and writes `build/reports/luna-integration.xml`. It parses the real settings CSV, then supplies an in-memory Luna settings store and invokes the actual change dispatcher. It does not render the settings menu or read/write the user's saved Luna preferences. The candidate must pass both processes before installation or packaging.

Serialization tests use the installed XStream with its DOM driver on Living Sector's mission-data graph, including final fields, immutable lists, identity, history and terminal state. The runner opens the JDK packages needed by that older library only in the test JVM. It does not change game launch flags or reproduce the game's complete aliases, converters and save graph. Full campaign save/load remains a live check.

Recorder tests use an in-memory common-file store, including injected write/delete failures. They check bounded storage across recording runs, byte limits before each write, and no recorder I/O during disabled advancement and save hooks. Save rollback tests serialize the small recorder cursor with XStream and restore it before invoking load hooks; summaries must exclude events beyond that save's cutoff. These scenarios do not prove physical disk behavior or whole-campaign serialization. The Luna JVM additionally toggles recording through the real settings callback.

The recorder settings regression loads the candidate recorder in a child class loader that rejects `java.lang.reflect.*`, reproducing the live failure resolving `Field`. The harness invokes its settings snapshot and verifies all values with test-side reflection, so newly added settings cannot silently disappear from diagnostics. This is a targeted script-loader restriction check, not a recreation of all Starsector security rules.

Luna startup additionally runs in a restricted loader with all candidate mod classes isolated, checking actual override values and live callbacks. The absent-dependency JVM exercises that boundary with no Luna jars, including a stale enabled flag. Battle tests drive the installed global event dispatcher with modeled participant/member snapshots, including an eliminated fleet absent from current sides and receiving no attached battle callback. They validate correlation and evidence handling; they do not run combat or establish engine callback timing beyond the modeled cases.

## Adding a scenario

1. Add a named scenario to the appropriate class's `register(IntegrationSuite)` method. For a new class, register it in `IntegrationSuite.main`.
2. Create a fresh `CampaignFixture.World`. Arrange the smallest world state that triggers the behavior; use fixed inputs/seeds where the outcome depends on randomness.
3. Enter through the real boundary being tested: a command, plugin hook, global dispatcher, native route callback or manager advance. Do not replace that boundary with a direct call to an internal helper.
4. Assert observable outcomes: correct mission state, retained ship IDs/losses, no duplicate route or capacity use, no mutation after rejection, and any useful diagnostic. Avoid asserting private implementation details or merely echoing the fixture configuration.
5. Cover documented nulls and optional values, unrelated events, duplicate/late callbacks, and failure ordering where applicable. Extend a strict stub using the installed API contract; do not silence a failure by returning a convenient default.
6. For a reported crash, first demonstrate that the scenario fails with the old behavior, then apply the fix. Record what still needs a live check in `docs/TESTING.md`.

`build/reports/integration.xml` contains scenario names, timing and failure traces. A nonzero exit prevents installation and packaging. `validated-build.json` is created only after all checks pass and includes the candidate jar hash and dependency paths. Core compilation or JVM startup failures may occur before a scenario report exists; inspect the command output in that case. Do not use a previously generated report as evidence for newer code.

Public CI can run the pure tests without the game. Full integration needs a runner with a legitimate local game/mod installation; the build never downloads or redistributes those jars. The XML format can be consumed by CI without adding a test framework to the runtime mod.
