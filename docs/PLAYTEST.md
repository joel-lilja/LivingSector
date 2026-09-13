# Living Sector: player guide

**Just play normally—leave debug recording off.** Enable it whenever you want to explore traffic history, investigate a problem, or collect examples for a suggestion. Feedback is welcome with or without recordings.

## Optional: record traffic history

Recording captures traffic events from the moment you enable it; it cannot recover events from when it was off. For a problem you can reproduce, enable recording before trying it again.

**With LunaLib:** open Luna settings → Living Sector → Debug, enable **Record traffic history**, and apply. This keeps recording enabled across saves and loads.

**With Console Commands:** open the console with **Ctrl+Backspace** (default) and run `ls debug on`. Run it again after loading a save; applying Luna settings also resets this console override.

Recording is **off by default**. Once enabled, it writes automatically and rotates old files within a **32 MiB** allowance by default. You do not need to keep exporting. To stop, disable the Luna setting or use `ls debug off` for the current session.

## Useful console commands

You can inspect current traffic with `ls status` without enabling recording. History commands show only retained recordings.

Replace `<id>` with a mission ID from `ls status`, such as `ls-3`.

| Command | What it does |
| --- | --- |
| `ls help` | Show all available commands. |
| `ls status` | Show active traffic, mission IDs and update timings. |
| `ls status <id>` | Inspect one trip, its ships, itinerary and recent events. |
| `ls verify <id>` | Check an active trip's current route/fleet bindings. |
| `ls debug on` / `ls debug off` | Enable or disable recording for this session. |
| `ls debug status` | Check whether recording is working or has an error. |
| `ls debug summary all` | Summarize retained traffic history; use `180` instead of `all` for the last 180 game days. |
| `ls debug trip <id>` | Show recorded events for one trip. |
| `ls debug export` | Flush pending records and show where the files are saved. |
| `ls visit <id>` | **Teleport your fleet** near an active trip to inspect it. |
| `ls pause` / `ls resume` | Stop/resume new departures. Existing trips continue; this choice persists in the save. |

If status says it is waiting for Nex's route manager in a fresh sector, save and load once.

## Report a problem or share a suggestion

Tell us what happened or what you would like to see changed. For bugs, include your mod/game/Nex versions and mod list, plus a mission ID or screenshot of `ls status` if relevant.

If you recorded relevant traffic, run `ls debug export`, then ZIP the **`saves/common/living-sector-debug/`** folder in your Starsector directory. You can attach that ZIP to your report; it contains the rotating `slot-*.jsonl.data` recordings.

For a crash, copy **`starsector-core/starsector.log` before restarting**, and include any error popup. You can report a problem even if recording was off or the crash prevented export.

Reply where you received the mod, or [open an issue](https://github.com/joel-lilja/LivingSector/issues/new).
