package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.OptionalFleetData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteFleetSpawner;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.fleets.NexRouteManager;
import exerelin.campaign.fleets.utils.NexRouteManagerListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.model.TrafficMission.Stop;
import livingsector.traffic.TrafficContext;
import livingsector.traffic.TrafficPlan;

/** One saved executor using the existing route manager; never replaces native spawn rules. */
public final class NativeTraffic implements RouteFleetSpawner, NexRouteManagerListener, FleetEventListener {
    public static final String SOURCE = "living_sector_missions";
    public static final String MISSION_KEY = "$livingSector_mission";
    final Map<String, NativeMission> active = new LinkedHashMap<String, NativeMission>();
    final List<TrafficMission> recent = new ArrayList<TrafficMission>();
    private long nextId = 1;
    private transient TrafficRecorder recorder;
    void setRecorder(TrafficRecorder value) { recorder = value; }

    public void onLoad() {
        if (!Global.getSector().getListenerManager().hasListener(this)) {
            Global.getSector().getListenerManager().addListener(this, true);
        }
        for (NativeMission entry : active.values()) {
            CampaignFleetAPI fleet = fleet(entry);
            if (fleet != null && !fleet.getEventListeners().contains(this)) fleet.addEventListener(this);
        }
    }

    public int size() { return active.size(); }

    public void addRoutesTo(List<TrafficContext.Route> routes) {
        for (NativeMission entry : active.values()) {
            TrafficPlan plan = entry.mission.plan;
            routes.add(new TrafficContext.Route(plan.typeId, plan.originId, plan.destinationId));
        }
    }

    public TrafficMission start(TrafficPlan plan, double day, long seed, boolean roundTrip, boolean test) {
        RouteManager manager = RouteManager.getInstance();
        if (!(manager instanceof NexRouteManager)) {
            // Nex installs its replacement through XStream's RouteManager alias on load.
            // A freshly generated campaign can still have the vanilla instance.
            if (manager.getClass() == RouteManager.class) {
                throw new IllegalStateException("The vanilla route manager is still active. On a fresh sector, save and load"
                        + " that save so Nex can install its route manager, then retry. No mission was created.");
            }
            throw new IllegalStateException("Unsupported active route manager: " + manager.getClass().getName()
                    + "; expected NexRouteManager. No mission was created; report this class name for compatibility checks.");
        }
        MarketAPI from = market(plan.originId), to = market(plan.destinationId);
        if (!SectorReader.isPort(from) || !SectorReader.isPort(to)
                || !SectorReader.peaceful(from.getFactionId(), to.getFactionId())) {
            throw new IllegalArgumentException("The trip needs two distinct inhabited, non-hostile ports");
        }
        onLoad();
        TrafficMission mission = new TrafficMission("ls-" + nextId++, plan, from.getFactionId(), seed,
                day, roundTrip, test);
        NativeMission entry = new NativeMission(mission);
        if (recorder != null) recorder.attach(mission, true);
        active.put(mission.id, entry);
        try {
            OptionalFleetData extra = new OptionalFleetData();
            extra.factionId = mission.factionId;
            extra.fleetType = FleetTypes.TRADE_LINER;
            // Absence matters: a zero-valued strength still appears in one Nex report.
            extra.strength = null;
            extra.damage = 0f;
            RouteData route = manager.addRoute(SOURCE, from, seed, extra, this, entry);
            entry.route = route;
            compileItinerary(route, mission);
            mission.note("ROUTE_REGISTERED", "native route registered; waiting for normal materialization");
            log(mission, "registered");
            return mission;
        } catch (RuntimeException ex) {
            mission.finish(State.FAILED, "route registration failed: " + ex.getMessage());
            if (entry.route != null) manager.removeRoute(entry.route);
            archive(entry);
            throw ex;
        }
    }

    static void compileItinerary(RouteData route, TrafficMission mission) {
        SectorEntityToken previous = null;
        int segment = 0;
        for (Stop stop : mission.stops) {
            MarketAPI port = market(stop.marketId);
            if (!SectorReader.isPort(port)) throw new IllegalArgumentException("Missing port " + stop.marketId);
            SectorEntityToken target = port.getPrimaryEntity();
            if (previous != null) {
                RouteSegment travel = new RouteSegment(Integer.valueOf(segment++), previous, target);
                travel.daysMax = Math.max(.01f, travel.daysMax);
                route.addSegment(travel);
            }
            route.addSegment(new RouteSegment(Integer.valueOf(segment++), stop.dwellDays, target));
            previous = target;
        }
    }

    @Override
    public CampaignFleetAPI spawnFleet(RouteData route) {
        NativeMission entry = entry(route);
        if (entry == null || !entry.mission.active() || entry.cancelReason != null) return null;
        TrafficMission mission = entry.mission;
        CampaignFleetAPI fleet = null;
        try {
            if (mission.fleetId() != null || route.getActiveFleet() != null) {
                throw new IllegalStateException("A physical binding already exists");
            }
            if (!safe(entry)) {
                mission.finish(State.CANCELLED, "ports changed before materialization");
                return null;
            }
            MarketAPI origin = market(mission.plan.originId);
            if (entry.checkpoint == null) {
                fleet = CivilianFleetFactory.create(mission.plan, origin, mission.factionId);
                route.getExtra().fp = (float) fleet.getFleetPoints();
            } else {
                // Physical battle losses have already been captured as surviving members.
                // A new abstract loss needs a separate supported resolver; never silently heal it.
                if (damage(route) > entry.checkpoint.routeDamage + .001f) {
                    throw new IllegalStateException("Unreconciled abstract damage; refusing to regenerate ships");
                }
                if (entry.checkpoint.ships.isEmpty()) {
                    mission.finish(State.DESTROYED, "no surviving ships in checkpoint");
                    return null;
                }
                fleet = CivilianFleetFactory.empty(mission.plan, origin, mission.factionId);
                entry.checkpoint.restore(fleet);
            }
            fleet.setName(mission.plan.fleetName + (mission.test ? " [" + mission.id + "]" : ""));
            fleet.getMemoryWithoutUpdate().set(MISSION_KEY, mission.id);
            fleet.addEventListener(this);
            // This native subclass places the fleet at the route's actual progress.
            fleet.addScript(new NativeTrafficAssignmentAI(fleet, route));
            mission.bindFleet(fleet.getId());
            observeProgress(entry);
            log(mission, "spawned " + fleet.getId());
            return fleet;
        } catch (RuntimeException ex) {
            if (fleet != null && fleet.getContainingLocation() != null) {
                fleet.removeEventListener(this);
                fleet.despawn(FleetDespawnReason.OTHER, null);
            }
            mission.finish(State.FAILED, "fleet construction failed: " + ex.getMessage());
            Global.getLogger(NativeTraffic.class).error("Living Sector " + mission.id + " spawn failed", ex);
            return null; // Nex expires this route; maintenance archives the failed mission.
        }
    }

    @Override
    public void reportAboutToBeDespawnedByRouteManager(RouteData route) {
        NativeMission entry = entry(route);
        if (entry == null || route.getActiveFleet() == null) return;
        entry.checkpoint = FleetCheckpoint.capture(route.getActiveFleet(), damage(route));
        entry.mission.note("CHECKPOINT", "checkpoint: " + entry.checkpoint.ships.size() + " survivors; native damage=" + damage(route));
    }

    @Override
    public boolean shouldRepeat(RouteData route) {
        NativeMission entry = entry(route);
        if (entry != null && entry.mission.active()) {
            if (entry.cancelReason != null) entry.mission.finish(State.CANCELLED, entry.cancelReason);
            else if (!safe(entry)) entry.mission.finish(State.CANCELLED, "destination invalid at abstract arrival");
            else {
                observeProgress(entry);
                entry.mission.finish(State.COMPLETED, "native route reached final stop offscreen");
            }
        }
        return false;
    }

    @Override
    public boolean shouldCancelRouteAfterDelayCheck(RouteData route) {
        NativeMission entry = entry(route);
        return entry == null || !entry.mission.active() || entry.cancelReason != null;
    }

    @Override public void reportRouteAdded(RouteData route) { }
    @Override public void reportRouteRemoved(RouteData route) {
        NativeMission entry = entry(route);
        if (entry != null) entry.mission.note("ROUTE_REMOVED", "native route removed; awaiting lifecycle reconciliation");
        // Do not mutate Nex's route iterator or infer a terminal result inside this callback.
    }
    @Override public void reportRouteFleetSpawned(CampaignFleetAPI fleet, RouteData route) {
        NativeMission entry = entry(route);
        if (entry != null) entry.mission.bindFleet(fleet.getId());
    }
    @Override public void reportRouteFleetDespawned(CampaignFleetAPI fleet, RouteData route) {
        NativeMission entry = entry(route);
        if (entry != null) entry.mission.releaseFleet(fleet.getId());
    }

    @Override public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI winner, BattleAPI battle) {
        // FleetEventListener's global broadcast has no fleet. We are registered globally
        // for Nex route events; track battles only via our directly attached fleet listener.
        if (fleet == null) return;
        NativeMission entry = entry(fleet);
        if (entry != null && entry.mission.active()) {
            entry.battles++;
            entry.mission.note("BATTLE", "native battle callback " + entry.battles);
            // Nex may update extra.damage after this callback. Checkpoint at dematerialization.
        }
    }

    @Override public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        NativeMission entry = entry(fleet);
        if (entry == null || !fleet.getId().equals(entry.mission.fleetId())) return;
        TrafficMission mission = entry.mission;
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) {
            entry.distanceDespawns++;
            mission.releaseFleet(fleet.getId());
            if (entry.cancelReason != null) mission.finish(State.CANCELLED, entry.cancelReason);
        } else if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE || reason == FleetDespawnReason.NO_MEMBERS) {
            mission.finish(State.DESTROYED, reason.toString());
        } else if (entry.cancelReason != null) {
            mission.finish(State.CANCELLED, entry.cancelReason);
        } else if (reason == FleetDespawnReason.REACHED_DESTINATION && finalArrival(entry, fleet, param)) {
            observeProgress(entry);
            mission.finish(State.COMPLETED, "physical fleet docked at final stop");
        } else {
            mission.finish(State.FAILED, "unexpected despawn: " + reason);
        }
        log(mission, "despawn " + reason);
    }

    static boolean finalArrival(NativeMission entry, CampaignFleetAPI fleet, Object target) {
        if (entry.route == null || entry.route.getCurrentIndex() != entry.route.getSegments().size() - 1) return false;
        MarketAPI port = market(entry.mission.stops.get(entry.mission.stops.size() - 1).marketId);
        if (!SectorReader.isPort(port) || !safe(entry)) return false;
        SectorEntityToken entity = port.getPrimaryEntity();
        return target == entity || (fleet.getContainingLocation() == entity.getContainingLocation()
                && Misc.getDistance(fleet, entity) <= entity.getRadius() + fleet.getRadius() + 1000);
    }

    public void maintain(double day) {
        if (active.isEmpty()) return;
        RouteManager manager = RouteManager.getInstance();
        List<RouteData> registered = manager.getRoutesForSource(SOURCE);
        for (NativeMission entry : new ArrayList<NativeMission>(active.values())) {
            if (recorder != null) recorder.observe(entry);
            CampaignFleetAPI fleet = fleet(entry);
            boolean bound = entry.route != null && registered.contains(entry.route);
            if (fleet != null && fleet.getBattle() != null) continue;
            if (fleet != null && fleet.isEmpty()) {
                fleet.despawn(FleetDespawnReason.NO_MEMBERS, null);
                fleet = null;
            }
            if (entry.mission.active()) {
                observeProgress(entry);
                if (day - entry.mission.createdAt >= entry.mission.plan.maximumTripDays) requestCancel(entry, "trip expired");
                if (!safe(entry)) requestCancel(entry, "ports or diplomacy changed");
                if (!bound) {
                    if (fleet == null) entry.mission.finish(State.FAILED, "native route binding disappeared");
                    else requestCancel(entry, "native route removed while fleet still active");
                }
                if (entry.cancelReason != null) {
                    if (fleet == null) entry.mission.finish(State.CANCELLED, entry.cancelReason);
                    else returnFleet(entry, fleet);
                }
            }
            if (!entry.mission.active() && fleet == null) {
                if (bound) manager.removeRoute(entry.route);
                archive(entry);
            }
        }
    }

    private void returnFleet(NativeMission entry, CampaignFleetAPI fleet) {
        if (entry.returning) {
            MarketAPI current = market(entry.returnMarketId);
            if (SectorReader.isPort(current) && SectorReader.peaceful(entry.mission.factionId, current.getFactionId())) return;
            entry.returning = false;
        }
        MarketAPI fallback = market(entry.mission.plan.originId);
        if (!SectorReader.isPort(fallback) || !SectorReader.peaceful(entry.mission.factionId, fallback.getFactionId())) {
            fallback = null;
            float best = Float.MAX_VALUE;
            for (MarketAPI port : Global.getSector().getEconomy().getMarketsCopy()) {
                if (!SectorReader.isPort(port) || !SectorReader.peaceful(entry.mission.factionId, port.getFactionId())) continue;
                float distance = Misc.getDistance(fleet.getLocationInHyperspace(), port.getPrimaryEntity().getLocationInHyperspace());
                if (distance < best) { fallback = port; best = distance; }
            }
        }
        fleet.clearAssignments();
        if (fallback != null) {
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, fallback.getPrimaryEntity(), 10000f,
                    "returning passengers to " + fallback.getName());
            entry.returning = true;
            entry.returnMarketId = fallback.getId();
            if (entry.mission.hasEventSink()) entry.mission.note("RETURNING", "returning to " + entry.returnMarketId);
        } else if (!fleet.isVisibleToPlayerFleet()) {
            fleet.despawn(FleetDespawnReason.OTHER, null);
        } else {
            fleet.addAssignment(FleetAssignment.HOLD, fleet, 10000f, "awaiting safe passage");
        }
    }

    public void cancel(String id) {
        NativeMission entry = active.get(id);
        if (entry == null) throw new IllegalArgumentException("No active mission " + id);
        requestCancel(entry, "cancelled by test command");
    }

    static void requestCancel(NativeMission entry, String reason) {
        if (entry.cancelReason == null) {
            entry.cancelReason = reason;
            entry.mission.note("CANCEL_REQUESTED", "return/cancel requested: " + reason);
        }
    }

    static void observeProgress(NativeMission entry) {
        if (entry.route != null && entry.route.getCurrentIndex() >= 0) {
            entry.mission.observeLeg(Math.min(entry.mission.stops.size() - 1, (entry.route.getCurrentIndex() + 1) / 2));
            if (entry.mission.hasEventSink() && (entry.debugSegment == null || entry.debugSegment != entry.route.getCurrentIndex())) {
                entry.debugSegment = entry.route.getCurrentIndex();
                entry.mission.note("SEGMENT_CHANGED", "segment " + (entry.debugSegment + 1) + "/" + entry.route.getSegments().size()
                        + (entry.debugSegment % 2 == 1 ? ": travelling" : ": visiting port"));
            }
        }
    }

    static boolean safe(NativeMission entry) {
        for (Stop stop : entry.mission.stops) {
            MarketAPI port = market(stop.marketId);
            if (!SectorReader.isPort(port) || !SectorReader.peaceful(entry.mission.factionId, port.getFactionId())) return false;
        }
        MarketAPI from = market(entry.mission.plan.originId), to = market(entry.mission.plan.destinationId);
        return SectorReader.peaceful(from.getFactionId(), to.getFactionId());
    }

    static float damage(RouteData route) {
        Float damage = route.getExtra() == null ? null : route.getExtra().damage;
        if (damage == null) return 0;
        if (!Float.isFinite(damage)) throw new IllegalStateException("Non-finite native damage");
        return Math.max(0, Math.min(1, damage));
    }

    static MarketAPI market(String id) { return Global.getSector().getEconomy().getMarket(id); }
    static CampaignFleetAPI fleet(NativeMission entry) {
        CampaignFleetAPI fleet = entry.route == null ? null : entry.route.getActiveFleet();
        return fleet != null && fleet.isAlive() ? fleet : null;
    }
    static NativeMission entry(RouteData route) {
        return SOURCE.equals(route.getSource()) && route.getCustom() instanceof NativeMission ? (NativeMission) route.getCustom() : null;
    }
    private NativeMission entry(CampaignFleetAPI fleet) {
        return active.get(fleet.getMemoryWithoutUpdate().getString(MISSION_KEY));
    }
    private void archive(NativeMission entry) {
        active.remove(entry.mission.id);
        if (recent.size() == 16) recent.remove(0);
        recent.add(entry.mission);
        entry.checkpoint = null;
        entry.route = null;
    }
    private static void log(TrafficMission mission, String message) {
        Global.getLogger(NativeTraffic.class).info("Living Sector " + mission.id + ": " + message + "; state=" + mission.state());
    }
}
