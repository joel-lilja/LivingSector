package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import java.util.ArrayList;
import java.util.List;

/** Adapter-owned save data, without retaining the despawned fleet and its scripts. */
final class FleetCheckpoint {
    static final class Ship {
        String id, name;
        ShipVariantAPI variant;
        PersonAPI captain;
        float hull, cr;
        boolean mothballed, flagship;
    }
    final List<Ship> ships = new ArrayList<Ship>();
    CargoAPI cargo;
    float routeDamage;

    static FleetCheckpoint capture(CampaignFleetAPI fleet, float damage) {
        FleetCheckpoint saved = new FleetCheckpoint();
        saved.routeDamage = damage;
        saved.cargo = fleet.getCargo().createCopy();
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            Ship ship = new Ship();
            ship.id = member.getId();
            ship.name = member.getShipName();
            ship.variant = member.getVariant().clone();
            ship.captain = member.getCaptain();
            ship.hull = member.getStatus().getHullFraction();
            ship.cr = member.getRepairTracker().getBaseCR();
            ship.mothballed = member.isMothballed();
            ship.flagship = member.isFlagship();
            saved.ships.add(ship);
        }
        return saved;
    }

    void restore(CampaignFleetAPI fleet) {
        for (Ship ship : ships) {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, ship.variant.clone());
            member.setId(ship.id);
            member.setShipName(ship.name);
            member.setCaptain(ship.captain);
            fleet.getFleetData().addFleetMember(member);
            member.getRepairTracker().setMothballed(ship.mothballed);
            member.getRepairTracker().setCR(ship.cr);
            member.getStatus().setHullFraction(ship.hull);
            if (ship.flagship) fleet.getFleetData().setFlagship(member);
        }
        fleet.getFleetData().syncIfNeeded();
        fleet.getCargo().clear();
        fleet.getCargo().addAll(cargo);
    }
}
