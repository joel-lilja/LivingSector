package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import java.util.Random;
import livingsector.traffic.TrafficPlan;

/** Executes a plan using vanilla assets and normal, attackable civilian fleets. */
public final class CivilianFleetFactory {
    public static final String TYPE_KEY = "$livingSector_type";
    private CivilianFleetFactory() { }

    public static CampaignFleetAPI spawn(TrafficPlan plan, Random random) {
        MarketAPI from = Global.getSector().getEconomy().getMarket(plan.originId);
        MarketAPI to = Global.getSector().getEconomy().getMarket(plan.destinationId);
        if (!SectorReader.isPort(from) || !SectorReader.isPort(to)
                || from.getFaction().isHostileTo(to.getFaction())
                || to.getFaction().isHostileTo(from.getFaction())) return null;

        CampaignFleetAPI fleet = create(plan, from, from.getFactionId());
        SectorEntityToken origin = from.getPrimaryEntity();
        // Assemble and assign before adding the fleet to the world, so failures leave no orphan fleet.
        if (plan.boardingDays > 0) {
            fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, origin, plan.boardingDays,
                    "boarding passengers at " + from.getName());
        }
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, to.getPrimaryEntity(), 10000f,
                "transporting passengers to " + to.getName());
        double angle = random.nextDouble() * Math.PI * 2;
        float radius = origin.getRadius() + 100;
        fleet.setLocation(origin.getLocation().x + (float) Math.cos(angle) * radius,
                origin.getLocation().y + (float) Math.sin(angle) * radius);
        fleet.setFacing(random.nextFloat() * 360);
        origin.getContainingLocation().addEntity(fleet);
        return fleet;
    }

    /** Construct off-world. Each executor owns placement and assignments. */
    static CampaignFleetAPI create(TrafficPlan plan, MarketAPI from, String factionId) {
        CampaignFleetAPI fleet = empty(plan, from, factionId);
        float crew = 0;
        for (String variant : plan.variants) {
            FleetMemberAPI member = fleet.getFleetData().addFleetMember(variant);
            member.getRepairTracker().setCR(member.getRepairTracker().getMaxCR());
            crew += member.getMinCrew();
        }
        fleet.getFleetData().setFlagship(fleet.getFleetData().getMembersListCopy().get(0));
        fleet.getFleetData().syncIfNeeded();
        fleet.getCargo().addCrew((int) Math.ceil(crew));
        fleet.getCargo().addFuel(fleet.getCargo().getMaxFuel());
        fleet.getCargo().addSupplies(Math.min(20, fleet.getCargo().getMaxCapacity()));
        return fleet;
    }

    static CampaignFleetAPI empty(TrafficPlan plan, MarketAPI from, String factionId) {
        CampaignFleetAPI fleet = FleetFactoryV3.createEmptyFleet(factionId, FleetTypes.TRADE_LINER, from);
        fleet.setName(plan.fleetName);
        fleet.setNoFactionInName(true);
        fleet.setTransponderOn(true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
        fleet.getMemoryWithoutUpdate().set(TYPE_KEY, plan.typeId);
        fleet.getMemoryWithoutUpdate().set("$livingSector_origin", plan.originId);
        fleet.getMemoryWithoutUpdate().set("$livingSector_destination", plan.destinationId);
        return fleet;
    }
}
