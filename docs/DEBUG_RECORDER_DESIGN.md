# Traffic debug recorder design

**Status: implemented; live smoke test pending.** The existing `debugLogging` setting controls basic log messages. This recorder is a separate, optional tool for investigating long campaign runs.

## Recording

- Off by default, with a LunaLib toggle and console on/off commands. JSON configuration remains available without LunaLib.
- When disabled, perform no recorder collection, extra scanning, or file writes.
- Cover Living Sector's direct and native traffic. Record meaningful events: creation, departure/route transitions, materialization and despawn, diversions, battles, completion, and failure. Do not log positions per frame.
- Include campaign date, stable trip ID, fleet ID, faction, route, transition and reason where known. Label observations and unknown outcomes honestly.
- Identify recording runs and save/load boundaries so reloading an earlier save does not merge alternate timelines.
- Buffer events and write bounded batches to dedicated files. Keep full archives outside campaign saves; persist only the small amount of recorder state needed to identify the timeline. A hard crash may lose the latest unflushed batch.

## Rotation and storage budget

| Control | Agreed value |
| --- | --- |
| Default total disk allowance | **32 MiB** |
| Configurable allowance | **8–128 MiB** |
| Maximum individual file size | **512 KiB** |
| Files at the default allowance | **Up to 64**, with space reserved for recorder metadata |
| Memory buffer target | **64 KiB** |

Use one shared disk allowance for all Living Sector recorder files across campaigns, recording sessions and game restarts. Starting another campaign must not allocate another independent allowance.

Rotate files and remove the oldest managed files as the allowance is reached. Include active files and recorder metadata in budget accounting. Reducing the allowance brings existing archives within the new limit immediately when enabled. If disabled, defer trimming until recording next starts, preserving the no-writes-while-off rule. Only delete this recorder's own managed files; ordinary game logs and unrelated files are outside its ownership. If rotation or deletion fails, stop further recorder writes and report the problem rather than allowing unbounded growth.

These are byte-based retention limits, not a promise to retain a fixed number of campaign months. At an estimated 500 bytes per event, 32 MiB could hold roughly 67,000 events before metadata overhead. Measure actual output before choosing different defaults or promising six-month/year coverage.

## Analysis

Summaries should support a chosen campaign period and report departures, outcomes, journey durations, unresolved trips, and materialization counts. Individual trip histories provide the evidence behind unusual summary results.

Reports must identify the selected run/timeline, the oldest retained campaign date for it, and any recording gaps or missing history caused by rotation. Do not present a partial archive as a complete year. Recording cannot reconstruct events from before it was enabled.

Expected workflow: enable recording, play the desired period, generate a summary/export, inspect suspicious trips, then disable recording. The storage allowance continues to bound the retained recorder archives.

## Controls and files

In LunaLib → Living Sector → Debug, enable **Record traffic history** and choose **Recorder disk allowance (MiB)**. Without LunaLib, configure `debugTrafficHistory` and `debugHistoryMiB` in `data/config/living_sector.json` and restart. Both paths default to recording off and a 32 MiB allowance.

Console Commands provides:

```text
ls debug on
ls debug status
ls debug summary 180
ls debug summary 365
ls debug summary all
ls debug trip ls-1
ls debug runs
ls debug summary all <runId>
ls debug flush
ls debug export
ls debug off
```

Console on/off overrides apply to the current loaded session until the next campaign load or Luna settings apply. Use the Luna/JSON setting to keep recording enabled across loads. Repeated `on` while healthy does not create another recording branch. Status and ordinary play do no recorder filesystem work while off. Explicit summary/run/trip queries can read existing files while off but do not rotate or write them.

The files are `saves/common/living-sector-debug/slot-000.jsonl` through `slot-255.jsonl`. Only the configured number of slots is retained: up to 64 at the default, or 16 at 8 MiB. Each contains an ownership/order header followed by compact JSON events. The header counts toward the per-file and shared byte limits; there is no separately growing manifest. Sequence order, not slot number, determines chronological order after rotation. Logical UTF-8 content bytes are budgeted; filesystem allocation overhead is outside that accounting.

`ls debug export` flushes an enabled recorder and prints the archive location. These JSONL files are the structured export; the command does not generate additional unbounded copies. Copy them outside the game folder before further rotation if you want a permanent snapshot. Reports show at most 30 matching events, while the files contain all retained events. A direct trip's ID is `direct-<fleetId>`; native trips retain their `ls-<number>` IDs.

Writes occur at a 64 KiB batch threshold, at roughly 30-second wall-clock intervals while the campaign is advancing, on explicit flush/report/export, at save hooks, and when stopping recording. Explicitly stopping performs one final flush before the recorder is disabled. The writer uses Starsector's common-file API and rewrites at most one 512 KiB file per flush. It never runs background tasks or changes Nex's files.

## Timelines and interpretation

Every recording start/load gets a new run ID. The campaign save holds only a campaign ID and the last run/event/day cursor. A new run links to that saved cursor; summaries include ancestor events only up to those cutoffs, excluding discarded futures. Previous branches can be inspected explicitly with their run IDs. Start records include game/mod versions and JSON/effective settings; subsequent configuration changes record the effective settings again. Save request/success/failure markers distinguish attempted saves from confirmations.

Existing trips are recorded as `OBSERVED_EXISTING`, not newly created departures. Native lifecycle callbacks capture representation changes and outcomes; route segment changes are recorded when observed by the existing executor. Physical location/assignment changes are sampled only during existing maintenance passes, so brief intermediate changes can be missed. Timestamps describe when the mod observed an event. This is not an exact recording of every engine AI decision.

Summary windows filter by event date. Journey duration statistics require both a retained creation and a terminal outcome, and use those observation times. Trips without a recorded terminal outcome are unresolved observations, not proof that those fleets still exist. Reports disclose missing sequences/ancestors, damaged lines, the oldest retained calendar date, and known unrecorded intervals. They cannot reconstruct a deleted beginning or a period when logging was disabled.

Any read/write/delete failure stops recorder writes and surfaces an error in `ls debug status` and the game log; traffic continues. Explicit `ls debug on` can retry after resolving the problem. A damaged or unrecognized slot header is preserved and causes recording to stop, rather than guessing ownership and deleting it. Move that file aside before retrying. A crash can lose the in-memory tail or interrupt the current file rewrite.
