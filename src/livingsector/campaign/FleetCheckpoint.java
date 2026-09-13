package livingsector.campaign;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import java.util.ArrayList;
import java.util.List;

/** Adapter-owned save data, without retaining the despawned fleet and its scripts. */
final class FleetCheckpoint {
    static final class Ship {
        String id, name;
        ShipVariantAPI variant;
        PersonAPI captain;
        float hull, cr;
        float fleetPoints; // Absent in old saves; derived from the retained variant when needed.
        boolean mothballed, flagship;
    }
    final List<Ship> ships = new ArrayList<Ship>();
    CargoAPI cargo;
    float routeDamage;

    /** Only retained to read old saves. New generations never create a rich checkpoint. */
    float survivingPoints() {
        float result = 0;
        for (Ship ship : ships) {
            if (ship.hull <= 0) continue;
            float points = ship.fleetPoints;
            if (!(points > 0) && ship.variant != null && ship.variant.getHullSpec() != null)
                points = ship.variant.getHullSpec().getFleetPoints();
            if (!Float.isFinite(points) || points <= 0) throw new IllegalStateException("Invalid legacy ship fleet points");
            result += points;
        }
        return result;
    }
}
