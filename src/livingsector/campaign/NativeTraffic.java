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
import java.util.HashSet;
import java.util.Set;
import livingsector.model.FleetBudget;
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

    private void registerRouteListener() {
        if (!Global.getSector().getListenerManager().hasListener(this)) {
            Global.getSector().getListenerManager().addListener(this, true);
        }
    }

    public void onLoad() {
        registerRouteListener();
        for (NativeMission entry : active.values()) {
            ensureBudget(entry);
            CampaignFleetAPI fleet = fleet(entry);
            if (fleet != null && !fleet.getEventListeners().contains(this)) fleet.addEventListener(this);
        }
    }

    public int size() { return active.size(); }
    public static boolean available() { return RouteManager.getInstance() instanceof NexRouteManager; }

    public void addRoutesTo(List<TrafficContext.Route> routes) {
        for (NativeMission entry : active.values()) {
            TrafficPlan plan = entry.mission.plan;
            routes.add(new TrafficContext.Route(plan.typeId, plan.originId, plan.destinationId, entry.mission.stops.size() > 2));
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
        plan = CivilianShipSelector.resolve(plan, from.getFactionId(), seed);
        if (plan == null) return null; // No suitable faction/Independent passenger hull; do not admit a phantom trip.
        float initialPoints = CivilianShipSelector.points(plan);
        plan = plan.withoutSelectedVariants();
        registerRouteListener();
        TrafficMission mission = new TrafficMission("ls-" + nextId++, plan, from.getFactionId(), seed,
                day, roundTrip || plan.roundTrip, test);
        NativeMission entry = new NativeMission(mission);
        entry.budget = new FleetBudget(initialPoints);
        active.put(mission.id, entry);
        if (recorder != null) recorder.attach(mission, true);
        try {
            OptionalFleetData extra = new OptionalFleetData();
            extra.factionId = mission.factionId;
            extra.fleetType = FleetTypes.TRADE_LINER;
            // Absence matters: a zero-valued strength still appears in one Nex report.
            extra.strength = null;
            extra.damage = 0f;
            extra.fp = initialPoints;
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
            if (!reconcileAbstractDamage(entry)) return null;
            MarketAPI origin = market(mission.plan.originId);
            long generationSeed = mission.seed + 0x9E3779B97F4A7C15L * mission.generation();
            TrafficPlan generated = CivilianShipSelector.generate(mission.plan, mission.factionId,
                    generationSeed, entry.budget.remaining);
            if (generated == null) {
                mission.finish(State.CANCELLED, "no eligible civilian ship fits remaining budget " + entry.budget.remaining);
                return null;
            }
            fleet = CivilianFleetFactory.create(generated, origin, mission.factionId);
            float generatedFP = points(fleet);
            entry.budget.beginPhysical(generatedFP);
            // Nex adds lostFP / startingFP to cumulative route damage. Scale the denominator
            // to the original route allowance, including generation rounding, on OUR fleet only.
            fleet.getMemoryWithoutUpdate().set("$startingFP", generatedFP / Math.max(.000001f, 1 - entry.budget.routeDamage));
            fleet.setName(mission.plan.fleetName + (mission.test ? " [" + mission.id + "]" : ""));
            fleet.getMemoryWithoutUpdate().set(MISSION_KEY, mission.id);
            fleet.addEventListener(this);
            // This native subclass places the fleet at the route's actual progress.
            fleet.addScript(new NativeTrafficAssignmentAI(fleet, route));
            mission.bindFleet(fleet.getId());
            if (recorder != null) {
                recorder.materialized(entry, fleet);
                recorder.trackBattleFleet(mission, fleet);
            }
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
        ensureBudget(entry);
        float before = entry.budget.remaining;
        entry.budget.endPhysical(points(route.getActiveFleet()), damage(route));
        budgetEvent(entry, "BUDGET_CAPTURED", before, "physical survivors captured at native distance despawn");
        if (entry.budget.remaining <= 0) entry.mission.finish(State.DESTROYED, "no surviving fleet budget");
    }

    @Override
    public boolean shouldRepeat(RouteData route) {
        NativeMission entry = entry(route);
        if (entry != null && entry.mission.active()) {
            if (!reconcileAbstractDamage(entry)) return false;
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
        fleet.removeEventListener(this);
    }

    @Override public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI winner, BattleAPI battle) {
        // FleetEventListener's global broadcast has no fleet. We are registered globally
        // for Nex route events; track battles only via our directly attached fleet listener.
        if (fleet == null) return;
        if (recorder != null) recorder.battle(fleet, winner, battle);
        NativeMission entry = entry(fleet);
        if (entry != null && entry.mission.active()) {
            entry.battles++;
            entry.mission.note("BATTLE_CALLBACK", "native battle callback " + entry.battles);
            // Nex may update extra.damage after this callback. Compress at dematerialization.
        }
    }

    @Override public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
        NativeMission entry = entry(fleet);
        if (entry == null || !fleet.getId().equals(entry.mission.fleetId())) return;
        TrafficMission mission = entry.mission;
        if (reason != FleetDespawnReason.PLAYER_FAR_AWAY && reason != FleetDespawnReason.DESTROYED_BY_BATTLE
                && reason != FleetDespawnReason.NO_MEMBERS && entry.budget != null && entry.budget.physicalFP > 0) {
            float before = entry.budget.remaining;
            entry.budget.endPhysical(points(fleet), damage(entry.route));
            budgetEvent(entry, "BUDGET_CAPTURED", before, "physical survivors at terminal despawn");
            if (entry.budget.remaining <= 0) {
                mission.finish(State.DESTROYED, "no survivors at terminal despawn");
                return;
            }
        }
        if (reason == FleetDespawnReason.PLAYER_FAR_AWAY) {
            entry.distanceDespawns++;
            mission.releaseFleet(fleet.getId());
            if (entry.cancelReason != null) mission.finish(State.CANCELLED, entry.cancelReason);
        } else if (reason == FleetDespawnReason.DESTROYED_BY_BATTLE || reason == FleetDespawnReason.NO_MEMBERS) {
            if (entry.budget != null) {
                float before = entry.budget.remaining;
                entry.budget.endPhysical(0, damage(entry.route));
                entry.budget.remaining = 0;
                budgetEvent(entry, "BUDGET_CAPTURED", before, "physical fleet destroyed");
            }
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
        Set<RouteData> registered = new HashSet<RouteData>(manager.getRoutesForSource(SOURCE));
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
                if (fleet == null && bound) reconcileAbstractDamage(entry);
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

    /** Called only at native boundaries or scheduled maintenance; never apply physical losses twice. */
    private boolean reconcileAbstractDamage(NativeMission entry) {
        if (!entry.mission.active()) return false;
        if (entry.route.getActiveFleet() != null) return true;
        ensureBudget(entry);
        float before = entry.budget.remaining;
        if (entry.budget.applyAbstract(damage(entry.route)))
            budgetEvent(entry, "ABSTRACT_DAMAGE", before, "additional native route damage");
        if (entry.budget.remaining > 0) return true;
        entry.mission.finish(State.DESTROYED, "no remaining fleet budget after offscreen route damage");
        return false;
    }

    /** Convert old saved bindings once, never retaining per-ship API objects afterwards. */
    private void ensureBudget(NativeMission entry) {
        if (entry.budget != null) return;
        CampaignFleetAPI physical = fleet(entry);
        float accounted = entry.checkpoint == null ? 0 : entry.checkpoint.routeDamage;
        if (physical != null) {
            // Current physical ships supersede an older distance-despawn checkpoint.
            entry.budget = FleetBudget.migrate(points(physical), Math.max(accounted, damage(entry.route)));
            if (entry.budget.remaining > 0) {
                entry.budget.beginPhysical(entry.budget.remaining);
                physical.getMemoryWithoutUpdate().set("$startingFP",
                        entry.budget.physicalFP / Math.max(.000001f, 1 - entry.budget.routeDamage));
            }
        } else if (entry.checkpoint != null) {
            entry.budget = FleetBudget.migrate(entry.checkpoint.survivingPoints(), accounted);
        } else {
            entry.budget = new FleetBudget(CivilianShipSelector.points(entry.mission.plan));
        }
        entry.mission.plan = entry.mission.plan.withoutSelectedVariants();
        entry.checkpoint = null;
        budgetEvent(entry, "BUDGET_MIGRATED", entry.budget.remaining, "converted legacy fleet state to aggregate budget");
    }

    private void budgetEvent(NativeMission entry, String kind, float before, String reason) {
        entry.debugPreviousFP = before;
        try {
            entry.mission.note(kind, reason + "; budget FP=" + before + " -> " + entry.budget.remaining
                    + "; accounted route damage=" + entry.budget.routeDamage);
        } finally { entry.debugPreviousFP = null; }
    }

    static float points(CampaignFleetAPI fleet) {
        float points = 0;
        for (com.fs.starfarer.api.fleet.FleetMemberAPI member : fleet.getFleetData().getMembersListCopy())
            points += member.getFleetPointCost();
        return points;
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
        entry.budget = null;
        entry.route = null;
    }
    private static void log(TrafficMission mission, String message) {
        Global.getLogger(NativeTraffic.class).info("Living Sector " + mission.id + ": " + message + "; state=" + mission.state());
    }
}
