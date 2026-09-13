package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.battle.NexWarSimScript;
import exerelin.campaign.battle.NexWarSimScript.FactionStrengthReportEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import livingsector.LivingSectorPlugin;
import livingsector.model.TrafficMission;
import livingsector.traffic.TrafficPlan;
import org.lwjgl.util.vector.Vector2f;

/** Test actions are opt-in console calls. None run during ordinary traffic planning. */
public final class TrafficDebug {
    private TrafficDebug() { }

    public static String start(String originId, String destinationId, boolean roundTrip) {
        return start(originId, destinationId, roundTrip, false);
    }

    public static String start(String originId, String destinationId, boolean roundTrip, boolean checkpointTest) {
        TrafficManager manager = TrafficManager.get();
        if (manager.activeCount() >= LivingSectorPlugin.settings().globalFleetLimit) {
            throw new IllegalStateException("Living Sector capacity is full; let existing trips finish");
        }
        MarketAPI from, to;
        if (originId == null && destinationId == null) {
            List<MarketAPI> ports = new ArrayList<MarketAPI>();
            for (MarketAPI port : Global.getSector().getEconomy().getMarketsCopy()) if (SectorReader.isPort(port)) ports.add(port);
            CampaignFleetAPI player = Global.getSector().getPlayerFleet();
            ports.sort(Comparator.comparingDouble(port -> distance(player, port)));
            from = to = null;
            // Bound the search even in a sector containing many mutually hostile factions.
            int attempts = 0;
            outer: for (MarketAPI candidate : ports) {
                for (int pass = 0; pass < 2; pass++) {
                    for (MarketAPI destination : ports) {
                        if (++attempts > 4096) break outer;
                        if (candidate == destination || !SectorReader.peaceful(candidate.getFactionId(), destination.getFactionId())) continue;
                        boolean differentSystem = candidate.getPrimaryEntity().getContainingLocation() != destination.getPrimaryEntity().getContainingLocation();
                        if (pass == 0 && !differentSystem) continue;
                        from = candidate; to = destination; break outer;
                    }
                }
            }
            if (from == null) throw new IllegalArgumentException("No compatible ports found; provide explicit market IDs");
        } else {
            from = NativeTraffic.market(originId); to = NativeTraffic.market(destinationId);
            if (!SectorReader.isPort(from) || !SectorReader.isPort(to)) throw new IllegalArgumentException("Unknown/ineligible market ID");
        }
        String variant = LivingSectorPlugin.settings().vip.variant;
        // Two transports allow a partial-loss checkpoint test. Ordinary VIP profiles stay unchanged.
        TrafficPlan plan = new TrafficPlan("vip", from.getId(), to.getId(), "LS Route Test",
                Arrays.asList(variant, variant), checkpointTest ? 45f : 2f, 180f);
        TrafficMission mission = manager.nativeTraffic().start(plan, manager.day(), Double.doubleToLongBits(Math.random()), roundTrip, true);
        return "Created " + mission.id + ": " + from.getName() + " [" + from.getId() + "] -> "
                + to.getName() + " [" + to.getId() + "]" + (roundTrip ? " -> home" : "")
                + "\nManual test bypasses departure probability/cooldowns, but counts toward traffic capacity."
                + (checkpointTest ? "\nThis test boards for 45 days so the native seen-fleet retention period can elapse." : "")
                + "\nRun: ls visit " + mission.id + "\nUnpause briefly for native spawning, then: ls status " + mission.id;
    }

    public static String status(String id) {
        TrafficManager manager = TrafficManager.get();
        if (id == null) return TrafficManager.status();
        NativeTraffic nativeTraffic = manager.existingNativeTraffic();
        if (nativeTraffic == null) return "No native missions yet. Run: ls test";
        NativeMission entry = nativeTraffic.active.get(id);
        if (entry != null) return describe(entry, manager.day());
        for (TrafficMission mission : nativeTraffic.recent) if (mission.id.equals(id)) return describe(mission, manager.day());
        return "No mission " + id + " (only the 16 most recent finished missions are retained)";
    }

    static String describe(NativeTraffic traffic, double day) {
        StringBuilder out = new StringBuilder("\nNative missions=").append(traffic.size());
        for (NativeMission entry : traffic.active.values()) out.append('\n').append(describe(entry, day));
        for (TrafficMission mission : traffic.recent) out.append("\nRecent ").append(mission.id).append(' ')
                .append(mission.state()).append(": ").append(mission.outcome());
        return out.toString();
    }

    private static String describe(TrafficMission mission, double day) {
        return appendHistory(missionHeader(mission, day), mission).toString();
    }

    private static StringBuilder missionHeader(TrafficMission mission, double day) {
        return new StringBuilder(mission.id).append(" ").append(mission.state())
                .append("; age=").append(round(day - mission.createdAt)).append("d; leg=").append(mission.leg() + 1)
                .append('/').append(mission.stops.size()).append("; generation=").append(mission.generation())
                .append("; fleet ID=").append(mission.fleetId()).append("; last fleet=").append(mission.lastFleetId())
                .append("\n  type=").append(mission.plan.typeId).append("; faction=").append(mission.factionId)
                .append("; itinerary=").append(mission.plan.originId).append(" -> ").append(mission.plan.destinationId)
                .append(mission.stops.size() > 2 ? " -> " + mission.plan.originId : " (one way)")
                .append("; legacy template variants=").append(mission.plan.variants);
    }

    private static StringBuilder appendHistory(StringBuilder out, TrafficMission mission) {
        if (!mission.events().isEmpty()) out.append("\n  History (oldest first):");
        for (String event : mission.events()) out.append("\n    ").append(event);
        return out;
    }

    private static String describe(NativeMission entry, double day) {
        StringBuilder out = missionHeader(entry.mission, day);
        RouteData route = entry.route;
        out.append("\n  route=").append(route == null ? "missing" : route.getSource())
                .append("; distance despawns=").append(entry.distanceDespawns).append("; battle callbacks=").append(entry.battles);
        if (route != null) {
            out.append("; segment=").append(route.getCurrentIndex() + 1).append('/').append(route.getSegments().size())
                    .append("; route damage=").append(round(NativeTraffic.damage(route)))
                    .append("; strategic strength=").append(route.getExtra().strength);
            if (route.getCurrent() != null) out.append("; segment days=").append(round(route.getCurrent().elapsed))
                    .append('/').append(round(route.getCurrent().daysMax));
        }
        if (entry.budget != null) out.append("\n  Budget FP=").append(round(entry.budget.remaining))
                .append("; initial FP=").append(round(entry.budget.initial))
                .append("; accounted damage=").append(round(entry.budget.routeDamage))
                .append("; physical starting FP=").append(round(entry.budget.physicalFP));
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        if (fleet != null) {
            out.append("\n  Current: PHYSICAL at ").append(fleet.getContainingLocation().getName())
                    .append("; x=").append(Math.round(fleet.getLocation().x)).append(" y=").append(Math.round(fleet.getLocation().y))
                    .append("; assignment=").append(fleet.getCurrentAssignment() == null ? "none" : fleet.getCurrentAssignment().getAssignment());
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                out.append("\n  ship ").append(member.getId()).append(' ').append(member.getShipName())
                        .append("; hull=").append(round(member.getStatus().getHullFraction()))
                        .append("; CR=").append(round(member.getRepairTracker().getCR()));
            }
        } else {
            out.append("\n  Current: ABSTRACT (normal native route); ships regenerate within remaining budget");
            if (entry.budget == null) out.append("; legacy state awaits migration");
        }
        return appendHistory(out, entry.mission).toString();
    }

    /** Moves only the player, at explicit command invocation; never forces a route to spawn. */
    public static String visit(String id) {
        NativeMission entry = active(id);
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        // Console Commands opens a message dialog to capture input; it is not an interaction.
        if (player.getBattle() != null || Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null) {
            throw new IllegalStateException("Close the interaction/battle before visiting a test mission");
        }
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        LocationAPI location;
        Vector2f point;
        if (fleet != null) { location = fleet.getContainingLocation(); point = fleet.getLocation(); }
        else {
            RouteData route = entry.route;
            if (route == null || route.getCurrent() == null) throw new IllegalStateException("Route binding is missing");
            // First visit uses the origin port; later visits approach the native route through hyperspace.
            if (route.getCurrent().isInSystem()) {
                location = route.getCurrent().getFrom().getContainingLocation();
                point = route.getCurrent().getFrom().getLocation();
            } else {
                location = Global.getSector().getHyperspace();
                point = route.getInterpolatedHyperLocation();
            }
        }
        movePlayer(player, location, point.x + 500, point.y + 500);
        if (fleet != null) {
            return "Player moved near " + id + " in " + location.getName()
                    + ". Fleet is already PHYSICAL; fleet ID=" + fleet.getId() + ".";
        }
        return "Player moved near the ABSTRACT route for " + id + " in " + location.getName()
                + ". Unpause for native materialization; repeat ls visit " + id + " once it is physical if needed.";
    }

    public static String damage(String id, boolean removeShip) {
        NativeMission entry = active(id);
        if (!entry.mission.test) throw new IllegalArgumentException("Damage injection is restricted to explicit test missions");
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        if (fleet == null || fleet.getBattle() != null) throw new IllegalStateException("Test fleet must be physical and outside battle");
        List<FleetMemberAPI> ships = fleet.getFleetData().getMembersListCopy();
        if (ships.isEmpty() || (removeShip && ships.size() < 2)) throw new IllegalStateException("No spare test ship to remove");
        FleetMemberAPI ship = ships.get(ships.size() - 1);
        if (removeShip) {
            fleet.getFleetData().removeFleetMember(ship);
            fleet.getFleetData().syncIfNeeded();
            entry.mission.note("SYNTHETIC_LOSS", "synthetic loss: " + ship.getId());
        } else {
            ship.getStatus().setHullFraction(.5f);
            ship.getRepairTracker().setCR(.4f);
            entry.mission.note("SYNTHETIC_DAMAGE", "synthetic hull/CR damage: " + ship.getId());
        }
        return "Injected test " + (removeShip ? "ship removal" : "damage") + " into " + id
                + ". Ship removal reduces the next generation budget; hull/CR may reset on regeneration. This is a synthetic test.";
    }

    public static String away(String id) {
        NativeMission entry = active(id);
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player.getBattle() != null || Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null) {
            throw new IllegalStateException("Close the interaction/battle before moving the player");
        }
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        Vector2f point = fleet == null ? entry.route.getInterpolatedHyperLocation() : fleet.getLocationInHyperspace();
        LocationAPI hyperspace = Global.getSector().getHyperspace();
        movePlayer(player, hyperspace, point.x + 12 * Misc.getUnitsPerLightYear(), point.y);
        return "Player moved 12 LY away. Unpause and check ls status " + id
                + ". Native retention may keep a previously seen fleet physical for about 30 days; Nex operations may also retain fleets.";
    }

    private static void movePlayer(CampaignFleetAPI player, LocationAPI location, float x, float y) {
        if (player.getContainingLocation() != location) {
            player.getContainingLocation().removeEntity(player);
            location.addEntity(player);
        }
        // Entity membership and the engine's active location are separate, as in Console's Jump.
        // Check independently so repeating a command also repairs an earlier incomplete teleport.
        if (Global.getSector().getCurrentLocation() != location) Global.getSector().setCurrentLocation(location);
        player.setLocation(x, y);
        player.clearAssignments();
        player.setMoveDestination(x, y);
    }

    public static String verify(String id) {
        NativeMission entry = active(id);
        RouteData route = entry.route;
        if (route == null || !entry.mission.active()) return "FAIL: mission has no active native route";
        int bindings = 0;
        for (RouteData candidate : RouteManager.getInstance().getRoutesForSource(NativeTraffic.SOURCE)) {
            NativeMission data = NativeTraffic.entry(candidate);
            if (data != null && data.mission.id.equals(id)) bindings++;
        }
        CampaignFleetAPI fleet = NativeTraffic.fleet(entry);
        boolean flags = fleet == null || fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET);
        boolean identity = fleet == null ? entry.mission.fleetId() == null : fleet.getId().equals(entry.mission.fleetId());
        boolean civilian = route.getExtra().strength == null && route.getExtra().getStrengthModifiedByDamage() == 0;
        LocationAPI location = fleet != null ? fleet.getContainingLocation() : route.getCurrent().getCurrentContainingLocation();
        FactionAPI faction = Global.getSector().getFaction(entry.mission.factionId);
        FactionAPI enemy = Global.getSector().getFaction("pirates".equals(faction.getId()) ? "hegemony" : "pirates");
        boolean excluded = true;
        for (FactionStrengthReportEntry report : NexWarSimScript.getFactionStrengthReport(faction, enemy, location, false).entries) {
            if (report.route == route || (fleet != null && report.fleet == fleet)) excluded = false;
        }
        String allied = "n/a in hyperspace";
        if (location instanceof StarSystemAPI) {
            allied = round(NexWarSimScript.getFactionAndAlliedStrength(faction, enemy, (StarSystemAPI) location))
                    + " total in system (our route contributes " + route.getExtra().getStrengthModifiedByDamage() + ")";
        }
        return (bindings == 1 && flags && identity && civilian && excluded ? "PASS" : "FAIL")
                + ": bindings=" + bindings + "; fleet identity=" + identity + "; trade flag=" + flags
                + "; null strategic strength=" + civilian + "; excluded from Nex report=" + excluded
                + "\nNex allied-strength query: " + allied
                + "\nThis is a current-state check; repeat after distance despawn, respawn, and save/load.";
    }

    public static String probe(String action, double days) {
        TrafficManager manager = TrafficManager.get();
        if ("start".equals(action)) {
            manager.setProbe(new DistractionProbe(Global.getSector().getPlayerFleet().getContainingLocation(), manager.day(), days));
            manager.probe().advance(manager.day());
        } else if ("stop".equals(action) && manager.probe() != null) manager.probe().stop(manager.day());
        else if (!"status".equals(action)) throw new IllegalArgumentException("Use ls probe start [days], status, or stop");
        return manager.probe() == null ? "No probe. Run ls probe start in the location to observe." : manager.probe().summary();
    }

    public static String cancel(String id) {
        TrafficManager manager = TrafficManager.get();
        active(id);
        manager.existingNativeTraffic().cancel(id);
        manager.existingNativeTraffic().maintain(manager.day());
        return "Return/cancellation requested for " + id + ". Battles finish first; existing physical fleets return normally.";
    }

    private static NativeMission active(String id) {
        NativeTraffic traffic = TrafficManager.get().existingNativeTraffic();
        NativeMission entry = traffic == null ? null : traffic.active.get(id);
        if (entry == null) throw new IllegalArgumentException("No active native mission " + id);
        return entry;
    }
    private static double distance(CampaignFleetAPI player, MarketAPI port) {
        SectorEntityToken entity = port.getPrimaryEntity();
        return player.getContainingLocation() == entity.getContainingLocation() ? Misc.getDistance(player, entity)
                : 1000000 + Misc.getDistance(player.getLocationInHyperspace(), entity.getLocationInHyperspace());
    }
    private static String round(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
