package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import livingsector.model.SectorSnapshot;
import livingsector.model.SectorSnapshot.Port;
import livingsector.traffic.TrafficContext;
import livingsector.traffic.TrafficPlan;
import java.util.function.Supplier;

/** Saved journey state. Native fleet AI handles navigation, jumps, avoidance and docking. */
public final class TrafficJourney {
    final CampaignFleetAPI fleet;
    final String typeId, originId;
    String destinationId;
    final double expiresAt;
    boolean diverted, retiring;

    public TrafficJourney(CampaignFleetAPI fleet, TrafficPlan plan, double day) {
        this.fleet = fleet;
        typeId = plan.typeId;
        originId = plan.originId;
        destinationId = plan.destinationId;
        expiresAt = day + plan.maximumTripDays;
    }

    public TrafficContext.Route route() { return new TrafficContext.Route(typeId, originId, destinationId); }

    /** @return true when the manager may forget this journey. */
    public boolean advance(double day, Supplier<SectorSnapshot> fallbackSnapshot) {
        if (!fleet.isAlive()) return true;
        // Never change assignments or remove a fleet while it is participating in a battle.
        if (fleet.getBattle() != null) return false;
        if (fleet.isEmpty()) { fleet.despawn(FleetDespawnReason.NO_MEMBERS, null); return true; }
        if (day >= expiresAt) {
            retire();
            return !fleet.isAlive();
        }
        MarketAPI from = Global.getSector().getEconomy().getMarket(originId);
        MarketAPI to = Global.getSector().getEconomy().getMarket(destinationId);
        if (!SectorReader.isPort(from)) from = null;
        if (!SectorReader.isPort(to)) to = null;
        String faction = fleet.getFaction().getId();
        boolean safe = to != null && SectorReader.peaceful(faction, to.getFactionId());
        if (!diverted) {
            safe &= from != null && to != null && SectorReader.peaceful(from.getFactionId(), to.getFactionId())
                    && SectorReader.peaceful(faction, from.getFactionId());
        }
        if (safe && !retiring) return false;

        // Prefer returning to the origin; otherwise find the nearest safe inhabited port.
        MarketAPI fallback = from != null && SectorReader.peaceful(faction, from.getFactionId()) ? from : null;
        if (fallback == null) {
            // Only a journey with no safe origin requires a sector-wide search.
            SectorSnapshot sector = fallbackSnapshot.get();
            double best = Double.MAX_VALUE;
            float x = fleet.getLocationInHyperspace().x / Misc.getUnitsPerLightYear();
            float y = fleet.getLocationInHyperspace().y / Misc.getUnitsPerLightYear();
            for (Port port : sector.ports) {
                double distance = Math.hypot(port.xLY - x, port.yLY - y);
                if (sector.peaceful(faction, port.factionId) && distance < best) {
                    best = distance;
                    fallback = Global.getSector().getEconomy().getMarket(port.id);
                }
            }
        }
        if (fallback == null) {
            retire();
        } else {
            fleet.clearAssignments();
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, fallback.getPrimaryEntity(),
                    10000f, "diverting to " + fallback.getName());
            destinationId = fallback.getId();
            diverted = true;
            retiring = false;
            fleet.getMemoryWithoutUpdate().set("$livingSector_destination", destinationId);
            TrafficManager.debug("Diverted " + typeId + " from " + originId + " to " + destinationId);
        }
        return !fleet.isAlive();
    }

    private void retire() {
        if (!fleet.isVisibleToPlayerFleet()) {
            fleet.despawn(FleetDespawnReason.OTHER, null);
        } else if (!retiring) {
            // Timeout/no safe port: do not pop out of existence in front of the player.
            fleet.clearAssignments();
            fleet.addAssignment(FleetAssignment.HOLD, fleet, 10000f, "awaiting safe passage");
            retiring = true;
        }
    }
}
