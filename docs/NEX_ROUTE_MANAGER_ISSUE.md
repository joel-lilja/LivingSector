# Fresh campaigns use vanilla RouteManager until the first save/load

On Starsector **0.98a-RC8** with Nexerelin **0.12.2c**, a freshly generated campaign uses the vanilla `RouteManager`. Saving and loading switches it to `NexRouteManager`.

This was observed in a modded installation while developing Living Sector. I have not yet reproduced it with only Nex and its dependencies enabled.

## Reproduction

1. Generate a new campaign with Nexerelin enabled.
2. Before loading any save, run this through Console Commands:

   ```java
   runcode org.lazywizard.console.Console.showMessage(com.fs.starfarer.api.impl.campaign.fleets.RouteManager.getInstance().getClass().getName());
   ```

3. The manager is `com.fs.starfarer.api.impl.campaign.fleets.RouteManager`.
4. Save, load that save, and repeat the command.
5. The manager is now `exerelin.campaign.fleets.NexRouteManager`.

## Expected behavior

Nex's route manager should be available during a fresh campaign without requiring a save/load first.

## Impact

Living Sector uses `NexRouteManagerListener` callbacks to track civilian route lifecycles. We currently defer traffic until the Nex manager is available. Otherwise, behavior would differ between a freshly generated campaign and the same campaign after loading.

## Apparent cause

`ExerelinModPlugin.addScripts()` calls `NexRouteManager.getInstance()`, but this resolves to the inherited static vanilla factory, which creates a vanilla `RouteManager` when none exists. [Plugin source](https://github.com/Histidine91/Nexerelin/blob/master/jars/sources/ExerelinCore/exerelin/plugins/ExerelinModPlugin.java)

The XStream alias maps `RouteManager` to `NexRouteManager` during loading, explaining why save/load resolves it. [Serialization configuration](https://github.com/Histidine91/Nexerelin/blob/master/jars/sources/ExerelinCore/exerelin/plugins/XStreamConfig.java)

## Suggested fix — untested

Explicitly initialize `NexRouteManager` at an early new-game hook, before route-producing scripts and initial campaign advancement. For example, the missing-manager case could use:

```java
// During early new-game initialization, before addScriptsAndEventsIfNeeded().
MemoryAPI memory = Global.getSector().getMemoryWithoutUpdate();
if (memory.get(RouteManager.KEY) == null) {
    memory.set(RouteManager.KEY, new NexRouteManager());
}
```

This handles only the case where no manager exists yet. Initialization order needs checking: if vanilla or another mod has already created one, preserve it unless a safe replacement is established. In particular, replacing a populated manager must preserve existing route references, fleet listeners and progress.

The deprecated `replaceExistingRouteManager()` copies route objects, so simply restoring that call may need additional compatibility review. [Replacement helper](https://github.com/Histidine91/Nexerelin/blob/master/jars/sources/ExerelinCore/exerelin/campaign/fleets/NexRouteManager.kt)

Suggested validation: fresh campaign before saving, repeated initialization, subsequent save/load, and a campaign where routes already exist.
