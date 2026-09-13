package livingsector.campaign;

import com.fs.starfarer.api.*;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.listeners.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.*;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
import exerelin.campaign.battle.NexWarSimScript;
import exerelin.campaign.fleets.NexRouteManager;
import java.lang.reflect.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import livingsector.LivingSectorPlugin;
import livingsector.LivingSectorSettings;
import livingsector.model.TrafficMission;
import livingsector.model.TrafficMission.State;
import livingsector.traffic.TrafficPlan;
import org.lwjgl.util.vector.Vector2f;

/** Shared test world. Real Nex routes/event dispatch; fake campaign entities, never a live game. */
final class CampaignFixture {
    private CampaignFixture() { }

    static void defaults() throws Exception {
        TrafficRecorder.releasePrevious();
        Field settings = LivingSectorPlugin.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(null, new LivingSectorSettings());
        for (String name : new String[]{"civilianPolicy", "refreshOptionalSettings"}) {
            Field field = LivingSectorPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, null);
        }
        Field policies = livingsector.traffic.TrafficRegistry.class.getDeclaredField("POLICIES");
        policies.setAccessible(true);
        ((Map<?, ?>) policies.get(null)).clear();
        Global.setSector(null);
        Global.setSettings(null);
        Global.setFactory(null);
        Global.setCombatEngine(null);
    }

    static AssertionError unmodeled(String api, String method) {
        return new AssertionError("Fixture needs an explicit model for " + api + "." + method);
    }
    static final class TestRoutes extends NexRouteManager {
        boolean forbidMembershipScan;
        @Override public List<RouteData> getRoutesForSource(String source) {
            List<RouteData> routes = super.getRoutesForSource(source);
            if (!forbidMembershipScan) return routes;
            return new ArrayList<RouteData>(routes) {
                @Override public boolean contains(Object value) { throw new AssertionError("Repeated linear route membership scan"); }
            };
        }
        @Override public void spawnAndDespawn() { /* Sensors are outside this fixture; scenarios drive native hooks. */ }
    }
    interface Answer { Object get(String name, Object[] args) throws Exception; }
    static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return "Fake " + type.getSimpleName();
            Object value = answer.get(method.getName(), args == null ? new Object[0] : args);
            if (value != null) return value;
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) return false;
            if (returns == int.class) return 0;
            if (returns == float.class) return 0f;
            if (returns == double.class) return 0d;
            if (returns == long.class) return 0L;
            return null;
        }));
    }

    static MemoryAPI memory() {
        Map<String, Object> data = new HashMap<String, Object>();
        return proxy(MemoryAPI.class, (method, args) -> {
            switch (method) {
                case "set": data.put((String) args[0], args[1]); return null;
                case "unset": data.remove(args[0]); return null;
                case "contains": return data.containsKey(args[0]);
                case "getBoolean": return Boolean.TRUE.equals(data.get(args[0]));
                case "is": return Objects.equals(data.get(args[0]), args[1]);
                case "get": case "getString": return data.get(args[0]);
                case "getFloat": return data.containsKey(args[0]) ? ((Number) data.get(args[0])).floatValue() : 0f;
                default: return null;
            }
        });
    }

    static final class World {
        boolean war, failFactory, paused, lunaEnabled, showingDialog = true;
        int configReads;
        int recorderReads, recorderWrites, recorderDeletes;
        boolean failRecorderWrites, failRecorderDeletes;
        final Map<String, String> commonFiles = new LinkedHashMap<String, String>();
        double elapsedDays;
        InteractionDialogAPI interaction;
        int fleetSequence, shipSequence;
        final List<Object> listeners = new ArrayList<Object>();
        final Set<Object> transientListeners = new HashSet<Object>();
        final List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
        final List<FakeFleet> created = new ArrayList<FakeFleet>();
        final Map<FleetMemberAPI, FakeShip> ships = new IdentityHashMap<FleetMemberAPI, FakeShip>();
        final Map<CampaignFleetAPI, FakeFleet> fleetObjects = new IdentityHashMap<CampaignFleetAPI, FakeFleet>();
        final Map<String, MarketAPI> markets = new LinkedHashMap<String, MarketAPI>();
        final Map<String, FactionAPI> factionOverrides = new HashMap<String, FactionAPI>();
        final Map<String, ShipVariantAPI> variantSpecs = new HashMap<String, ShipVariantAPI>();
        final Map<String, Map<String, List<String>>> rolePools = new HashMap<String, Map<String, List<String>>>();
        int rolePicks;
        int listenerReads;
        final MemoryAPI memory = memory();
        final FactionAPI faction = faction("a"), enemy = faction("pirates");
        final StarSystemAPI system;
        final SectorEntityToken origin, destination;
        final TrafficManager manager = new TrafficManager();
        final NativeTraffic traffic = manager.nativeTraffic();
        final List<EveryFrameScript> scripts = new ArrayList<EveryFrameScript>(Collections.singletonList(manager));
        CampaignFleetAPI player;
        CampaignUIAPI ui;
        LocationAPI hyperspace;
        // The engine's active location is separate from the player's entity membership.
        LocationAPI currentLocation;
        final TestRoutes routes;
        RouteData lastRoute;

        World() {
            com.fs.starfarer.api.combat.ShipHullSpecAPI hull = proxy(com.fs.starfarer.api.combat.ShipHullSpecAPI.class,
                    (m, a) -> m.equals("getFleetPoints") ? 3 : null);
            variantSpecs.put("mudskipper_Standard", proxy(ShipVariantAPI.class, (m, a) ->
                    m.equals("getHullSpec") ? hull : m.equals("getHullVariantId") ? "mudskipper_Standard" : null));
            system = proxy(StarSystemAPI.class, (method, args) -> {
                if (method.equals("getFleets")) return fleets;
                if (method.equals("getJumpPoints")) return Collections.singletonList(proxy(JumpPointAPI.class, (m, a) -> null));
                if (method.equals("getLocation")) return new Vector2f();
                if (method.equals("getName") || method.equals("getId")) return "test-system";
                if (method.equals("addEntity")) {
                    CampaignFleetAPI fleet = (CampaignFleetAPI) args[0];
                    if (!fleets.contains(fleet)) fleets.add(fleet);
                    placeInSystem(fleet);
                }
                if (method.equals("removeEntity")) fleets.remove(args[0]);
                return null;
            });
            origin = token("origin", PlanetAPI.class, 3000);
            destination = token("station", SectorEntityToken.class, 6000);
            markets.put("origin", market("origin", origin));
            markets.put("station", market("station", destination));
            EconomyAPI economy = proxy(EconomyAPI.class, (m, a) -> {
                if (m.equals("getMarket")) return markets.get(a[0]);
                if (m.equals("getMarketsCopy")) return new ArrayList<MarketAPI>(markets.values());
                throw unmodeled("EconomyAPI", m);
            });
            ListenerManagerAPI listenerManager = proxy(ListenerManagerAPI.class, (m, a) -> {
                if (m.equals("addListener")) {
                    listeners.add(a[0]);
                    if (a.length == 2 && Boolean.TRUE.equals(a[1])) transientListeners.add(a[0]);
                    return null;
                }
                if (m.equals("removeListener")) { listeners.remove(a[0]); transientListeners.remove(a[0]); return null; }
                if (m.equals("hasListener")) return listeners.contains(a[0]);
                if (m.equals("getListeners")) {
                    List<Object> matches = new ArrayList<Object>();
                    for (Object listener : listeners) if (((Class<?>) a[0]).isInstance(listener)) matches.add(listener);
                    return matches;
                }
                throw unmodeled("ListenerManagerAPI", m);
            });
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (m, a) -> {
                if (m.equals("convertToDays")) return a[0];
                if (m.equals("getTimestamp")) return (long) (elapsedDays * 1000);
                if (m.equals("getElapsedDaysSince")) return (float) (elapsedDays - ((Long) a[0]) / 1000d);
                if (m.equals("getDateString")) return "fixture day " + elapsedDays;
                throw unmodeled("CampaignClockAPI", m);
            });
            Global.setSettings(proxy(SettingsAPI.class, (m, a) -> {
                if (m.equals("getFloat") && "unitsPerLightYear".equals(a[0])) return 1000f;
                // Misc's static initialization also reads combat/UI settings, outside these scenarios.
                if (m.equals("getFloat")) {
                    if ("fluxPerCapacitor".equals(a[0])) return 200f;
                    if ("dissipationPerVent".equals(a[0])) return 10f;
                    if ("gateTransitFuelCostMult".equals(a[0])) return 1f;
                    if ("officerMaxLevel".equals(a[0])) return 6f;
                    if (Arrays.asList("minTerrainEffectMult", "standardBurnPenaltyMult", "accessibilitySameFactionBonus",
                            "accessibilityPerUnitShipping", "sneakBurnMult", "impactSoundVolumeMult").contains(a[0])) return 1f;
                }
                if (m.equals("getBoolean") && "colorblindMode".equals(a[0])) return false;
                if (m.equals("getInt") && "maxColonySize".equals(a[0])) return 6;
                if (m.equals("getInt") && "overMaxIndustriesPenalty".equals(a[0])) return 1;
                if (m.equals("getInt") && "maxPermanentHullmods".equals(a[0])) return 2;
                if (m.equals("getColor") && ((String) a[0]).startsWith("mount")) return java.awt.Color.WHITE;
                if (m.equals("getSortedAbilityIds")) return Collections.emptyList();
                if (m.equals("getCurrentState")) return GameState.CAMPAIGN;
                if (m.equals("getVersionString")) return "fixture 0.98a";
                if (m.equals("fileExistsInCommon")) { recorderReads++; return commonFiles.containsKey(a[0]); }
                if (m.equals("readTextFileFromCommon")) {
                    recorderReads++;
                    if (!commonFiles.containsKey(a[0])) throw new java.io.IOException("Missing fixture file");
                    return commonFiles.get(a[0]);
                }
                if (m.equals("writeTextFileToCommon")) {
                    recorderWrites++;
                    if (failRecorderWrites) throw new java.io.IOException("Injected disk failure");
                    if (((String) a[1]).getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new java.io.IOException("Game API file limit");
                    commonFiles.put((String) a[0], (String) a[1]); return null;
                }
                if (m.equals("deleteTextFileFromCommon")) {
                    recorderDeletes++;
                    if (!failRecorderDeletes) commonFiles.remove(a[0]);
                    return null;
                }
                if (m.equals("getModManager")) return proxy(ModManagerAPI.class, (n, v) -> {
                    if (n.equals("getModSpec")) return proxy(ModSpecAPI.class, (key, values) -> {
                        if (key.equals("getId")) return v[0];
                        if (key.equals("getVersion")) return "fixture";
                        throw unmodeled("ModSpecAPI", key);
                    });
                    if (n.equals("isModEnabled")) return "nexerelin".equals(v[0]) || "lw_console".equals(v[0])
                            || (lunaEnabled && "lunalib".equals(v[0]));
                    if (n.equals("getEnabledModsCopy")) return Collections.singletonList(proxy(ModSpecAPI.class, (key, values) -> {
                        if (key.equals("getId")) return LivingSectorPlugin.ID;
                        throw unmodeled("ModSpecAPI", key);
                    }));
                    throw unmodeled("ModManagerAPI", n);
                });
                if (m.equals("getVariant")) return variantSpecs.containsKey(a[0]) ? variantSpecs.get(a[0]) : proxy(ShipVariantAPI.class, (n, v) -> null);
                if (m.equals("loadJSON") && "data/config/living_sector.json".equals(a[0])) {
                    configReads++;
                    try { return new JSONObject(new String(Files.readAllBytes(Paths.get((String) a[0])), StandardCharsets.UTF_8)); }
                    catch (Exception ex) { throw new AssertionError("Cannot load repository settings", ex); }
                }
                if (m.equals("loadCSV") && "data/config/LunaSettings.csv".equals(a[0])) {
                    try { return org.json.CDL.toJSONArray(new String(Files.readAllBytes(Paths.get((String) a[0])), StandardCharsets.UTF_8)); }
                    catch (Exception ex) { throw new AssertionError("Cannot load Luna settings CSV", ex); }
                }
                throw unmodeled("SettingsAPI", m + Arrays.toString(a));
            }));
            Global.setSector(proxy(SectorAPI.class, (m, a) -> {
                switch (m) {
                    case "getMemoryWithoutUpdate": return memory;
                    case "getEconomy": return economy;
                    case "getListenerManager": return listenerManager;
                    case "getClock": return clock;
                    case "getScripts": return new ArrayList<EveryFrameScript>(scripts);
                    case "hasScript": for (EveryFrameScript script : scripts) if (((Class<?>) a[0]).isInstance(script)) return true; return false;
                    case "addScript": scripts.add((EveryFrameScript) a[0]); return null;
                    case "removeScript": scripts.remove(a[0]); return null;
                    case "getPlayerFleet": return player;
                    case "getCampaignUI": return ui;
                    case "getHyperspace": return hyperspace;
                    case "getCurrentLocation": return currentLocation;
                    case "setCurrentLocation": currentLocation = (LocationAPI) a[0]; return null;
                    case "isPaused": return paused;
                    case "getFaction": return factionOverrides.containsKey(a[0]) ? factionOverrides.get(a[0]) : "a".equals(a[0]) ? faction : enemy;
                    default: throw unmodeled("SectorAPI", m);
                }
            }));
            Global.setFactory(proxy(FactoryAPI.class, (m, a) -> {
                if (m.equals("createEmptyFleet")) {
                    if (failFactory) throw new IllegalStateException("injected factory failure");
                    FakeFleet fleet = new FakeFleet(this, "native-" + ++fleetSequence);
                    created.add(fleet);
                    return fleet.api;
                }
                if (m.equals("createFleetMember")) return new FakeShip(this, "ship-" + ++shipSequence,
                        a[1] instanceof ShipVariantAPI ? (ShipVariantAPI) a[1] : variantSpecs.get(a[1])).api;
                return null;
            }));
            routes = new TestRoutes();
            memory.set(RouteManager.KEY, routes);
            hyperspace = proxy(LocationAPI.class, (m, a) -> {
                if (m.equals("addEntity") && fleetObjects.containsKey(a[0])) fleetObjects.get(a[0]).location = hyperspace;
                if (m.equals("getLocation")) return new Vector2f();
                if (m.equals("isHyperspace")) return true;
                if (m.equals("getName") || m.equals("getId")) return "hyperspace";
                if (m.equals("getFleets")) return Collections.emptyList();
                return null;
            });
            player = new FakeFleet(this, "player").api;
            currentLocation = system;
            ui = proxy(CampaignUIAPI.class, (m, a) -> {
                if (m.equals("isShowingDialog")) return showingDialog;
                if (m.equals("getCurrentInteractionDialog")) return interaction;
                if (m.equals("isShowingMenu")) return false;
                throw unmodeled("CampaignUIAPI", m);
            });
        }

        private void placeInSystem(CampaignFleetAPI fleet) {
            if (fleetObjects.containsKey(fleet)) fleetObjects.get(fleet).location = system;
        }

        void advance(float days) {
            if (!paused) { elapsedDays += days; routes.advance(days); }
            for (EveryFrameScript script : new ArrayList<EveryFrameScript>(scripts)) script.advance(days);
        }

        /** Emulates loss of transient listeners, then invokes the actual plugin hook; no serialization claim. */
        void reloadHooks() {
            listeners.removeAll(transientListeners);
            transientListeners.clear();
            new LivingSectorPlugin().onGameLoad(false);
        }

        void battle(FakeFleet fleet, CampaignFleetAPI winner, boolean globalFirst) {
            BattleAPI battle = proxy(BattleAPI.class, (m, a) -> null);
            if (globalFirst) ListenerUtil.reportBattleOccurred(null, winner, battle);
            for (FleetEventListener listener : new ArrayList<FleetEventListener>(fleet.listeners)) {
                listener.reportBattleOccurred(fleet.api, winner, battle);
            }
            if (!globalFirst) ListenerUtil.reportBattleOccurred(null, winner, battle);
        }

        NativeMission start(boolean roundTrip) {
            TrafficPlan plan = new TrafficPlan("vip", "origin", "station", "Test",
                    Arrays.asList("mudskipper_Standard", "mudskipper_Standard"), 1f, 180f);
            TrafficMission mission = traffic.start(plan, 0, 123, roundTrip, true);
            NativeMission result = traffic.active.get(mission.id);
            lastRoute = result.route;
            return result;
        }
        FactionAPI faction(String id) {
            return proxy(FactionAPI.class, (m, a) -> {
                if (m.equals("getId")) return id;
                if (m.equals("getFleetTypeName")) return "Test Fleet";
                if (m.equals("isHostileTo")) return war;
                if (m.equals("pickShip")) {
                    rolePicks++;
                    if (!((FactionAPI.ShipPickParams) a[1]).blockFallback) throw new AssertionError("Role selection must block foreign fallbacks");
                    Map<String, List<String>> roles = rolePools.get(id);
                    List<String> candidates = roles == null ? null : roles.get(a[0]);
                    List<ShipRolePick> picks = new ArrayList<ShipRolePick>();
                    if (candidates != null) for (String variant : candidates) {
                        if (((ShipFilter) a[2]).isAvailable(variant)) picks.add(new ShipRolePick(variant));
                    }
                    return picks.isEmpty() ? picks : Collections.singletonList(picks.get(((Random) a[3]).nextInt(picks.size())));
                }
                return null;
            });
        }
        <T extends SectorEntityToken> T token(String id, Class<T> type, float x) {
            return proxy(type, (m, a) -> {
                switch (m) {
                    case "isAlive": return true;
                    case "getId": case "getName": return id;
                    case "getMarket": return markets.get(id);
                    case "getContainingLocation": case "getStarSystem": return system;
                    case "getLocation": case "getLocationInHyperspace": return new Vector2f(x, 0);
                    case "getRadius": return 100f;
                    default: return null;
                }
            });
        }
        MarketAPI market(String id, SectorEntityToken entity) {
            return proxy(MarketAPI.class, (m, a) -> {
                switch (m) {
                    case "getId": case "getName": return id;
                    case "getFactionId": return "a";
                    case "getFaction": return faction;
                    case "isInEconomy": return true;
                    case "getLocationInHyperspace": return entity.getLocationInHyperspace();
                    case "getSize": return 5;
                    case "getPrimaryEntity": return entity;
                    case "getStarSystem": return system;
                    default: return null;
                }
            });
        }
    }

    static final class FakeShip {
        String id, name = "Survivor";
        float hull = 1, cr = .7f;
        boolean mothballed, flagship;
        final FleetMemberAPI api;
        FakeShip(World world, String id) {
            this(world, id, null);
        }
        FakeShip(World world, String id, ShipVariantAPI supplied) {
            this.id = id;
            ShipVariantAPI[] variant = {null};
            variant[0] = supplied != null ? supplied : proxy(ShipVariantAPI.class, (m, a) -> m.equals("clone") ? variant[0] : null);
            RepairTrackerAPI repair = proxy(RepairTrackerAPI.class, (m, a) -> {
                if (m.equals("getBaseCR") || m.equals("getCR")) return cr;
                if (m.equals("getMaxCR")) return .7f;
                if (m.equals("setCR")) cr = (Float) a[0];
                if (m.equals("setMothballed")) mothballed = (Boolean) a[0];
                return null;
            });
            FleetMemberStatusAPI status = proxy(FleetMemberStatusAPI.class, (m, a) -> {
                if (m.equals("getHullFraction")) return hull;
                if (m.equals("setHullFraction")) hull = (Float) a[0];
                return null;
            });
            api = proxy(FleetMemberAPI.class, (m, a) -> {
                switch (m) {
                    case "getId": return this.id;
                    case "setId": this.id = (String) a[0]; break;
                    case "getShipName": return name;
                    case "setShipName": name = (String) a[0]; break;
                    case "getVariant": return variant[0];
                    case "getStatus": return status;
                    case "getRepairTracker": return repair;
                    case "getMinCrew": return 1f;
                    case "getFleetPointCost": return supplied != null && supplied.getHullSpec() != null
                            ? (int) supplied.getHullSpec().getFleetPoints() : 3;
                    case "isMothballed": return mothballed;
                    case "isFlagship": return flagship;
                    default: break;
                }
                return null;
            });
            world.ships.put(api, this);
        }
    }

    static final class FakeCargo {
        final Map<String, Float> quantities = new LinkedHashMap<String, Float>();
        final CargoAPI api = proxy(CargoAPI.class, (m, a) -> {
            if (m.equals("createCopy")) { FakeCargo copy = new FakeCargo(); copy.quantities.putAll(quantities); return copy.api; }
            if (m.equals("getStacksCopy")) {
                List<CargoStackAPI> stacks = new ArrayList<CargoStackAPI>();
                for (Map.Entry<String, Float> item : quantities.entrySet()) {
                    String id = item.getKey(); float amount = item.getValue();
                    stacks.add(proxy(CargoStackAPI.class, (n, v) -> n.equals("getType") ? CargoAPI.CargoItemType.RESOURCES
                            : n.equals("getData") ? id : n.equals("getSize") ? amount : null));
                }
                return stacks;
            }
            if (m.equals("clear")) quantities.clear();
            if (m.equals("addAll")) for (CargoStackAPI stack : ((CargoAPI) a[0]).getStacksCopy()) {
                String id = (String) stack.getData(); quantities.put(id, quantities.getOrDefault(id, 0f) + stack.getSize());
            }
            if (m.equals("removeItems")) {
                String id = (String) a[1]; quantities.put(id, Math.max(0, quantities.getOrDefault(id, 0f) - ((Number) a[2]).floatValue()));
                return true;
            }
            if (m.equals("addCrew") || m.equals("addFuel") || m.equals("addSupplies")) {
                String id = m.equals("addCrew") ? "crew" : m.equals("addFuel") ? "fuel" : "supplies";
                quantities.put(id, quantities.getOrDefault(id, 0f) + ((Number) a[0]).floatValue());
            }
            if (m.equals("getCommodityQuantity")) return quantities.getOrDefault(a[0], 0f);
            return null;
        });
    }

    static final class FakeFleet {
        final String id;
        boolean alive = true, inBattle;
        final List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
        final List<FleetMemberAPI> snapshot = new ArrayList<FleetMemberAPI>();
        final List<FleetEventListener> listeners = new ArrayList<FleetEventListener>();
        final MemoryAPI memory = memory();
        final CampaignFleetAPI api;
        final FakeCargo cargo = new FakeCargo();
        FleetAssignment assigned;
        LocationAPI location;
        final Vector2f position = new Vector2f(3000, 0);
        com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI ai;
        FakeFleet(World world, String id) {
            this.id = id;
            location = world.system;
            FleetDataAPI data = proxy(FleetDataAPI.class, (m, a) -> {
                if (m.equals("getMembersListCopy")) return new ArrayList<FleetMemberAPI>(members);
                if (m.equals("getSnapshot")) return new ArrayList<FleetMemberAPI>(snapshot);
                if (m.equals("addFleetMember")) {
                    FleetMemberAPI member = a[0] instanceof String ? new FakeShip(world, "ship-" + ++world.shipSequence, world.variantSpecs.get(a[0])).api : (FleetMemberAPI) a[0];
                    members.add(member); return member;
                }
                if (m.equals("removeFleetMember")) members.remove(a[0]);
                if (m.equals("setFlagship")) for (FleetMemberAPI member : members) world.ships.get(member).flagship = member == a[0];
                return null;
            });
            api = proxy(CampaignFleetAPI.class, (m, a) -> {
                switch (m) {
                    case "getId": case "getName": case "getFullName": return id;
                    case "isAlive": return alive;
                    case "isEmpty": return members.isEmpty();
                    case "getBattle": return inBattle ? proxy(BattleAPI.class, (n, v) -> null) : null;
                    case "getFleetData": return data;
                    case "getCargo": return cargo.api;
                    case "getMemoryWithoutUpdate": return memory;
                    case "getFaction": return world.faction;
                    case "getAI": return ai;
                    case "getContainingLocation": return location;
                    case "getLocation": case "getLocationInHyperspace": return position;
                    case "setLocation": position.set((Float) a[0], (Float) a[1]); break;
                    case "getFleetPoints": { int fp = 0; for (FleetMemberAPI member : members) fp += member.getFleetPointCost(); return fp; }
                    case "getEffectiveStrength": return 100f;
                    case "addEventListener": listeners.add((FleetEventListener) a[0]); break;
                    case "removeEventListener": listeners.remove(a[0]); break;
                    case "getEventListeners": world.listenerReads++; return listeners;
                    case "despawn": despawn((FleetDespawnReason) a[0], a[1]); break;
                    case "clearAssignments": assigned = null; break;
                    case "addAssignment": assigned = (FleetAssignment) a[0]; break;
                    default: break;
                }
                return null;
            });
            world.fleetObjects.put(api, this);
        }
        void despawn(FleetDespawnReason reason, Object param) {
            for (FleetEventListener listener : new ArrayList<FleetEventListener>(listeners)) listener.reportFleetDespawnedToListener(api, reason, param);
            ListenerUtil.reportFleetDespawnedToListener(api, reason, param);
            alive = false;
        }
    }
}
