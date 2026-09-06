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
    public boolean advance(SectorSnapshot sector, double day) {
        if (!fleet.isAlive()) return true;
        // Never change assignments or remove a fleet while it is participating in a battle.
        if (fleet.getBattle() != null) return false;
        if (fleet.isEmpty()) { fleet.despawn(FleetDespawnReason.NO_MEMBERS, null); return true; }
        if (day >= expiresAt) {
            retire();
            return !fleet.isAlive();
        }
        Port from = sector.port(originId);
        Port to = sector.port(destinationId);
        String faction = fleet.getFaction().getId();
        boolean safe = to != null && sector.peaceful(faction, to.factionId);
        if (!diverted) {
            safe &= from != null && to != null && sector.peaceful(from.factionId, to.factionId)
                    && sector.peaceful(faction, from.factionId);
        }
        if (safe && !retiring) return false;

        // Prefer returning to the origin; otherwise find the nearest safe inhabited port.
        Port fallback = from != null && sector.peaceful(faction, from.factionId) ? from : null;
        if (fallback == null) {
            double best = Double.MAX_VALUE;
            float x = fleet.getLocationInHyperspace().x / Misc.getUnitsPerLightYear();
            float y = fleet.getLocationInHyperspace().y / Misc.getUnitsPerLightYear();
            for (Port port : sector.ports) {
                double distance = Math.hypot(port.xLY - x, port.yLY - y);
                if (sector.peaceful(faction, port.factionId) && distance < best) {
                    best = distance;
                    fallback = port;
                }
            }
        }
        if (fallback == null) {
            retire();
        } else {
            MarketAPI market = Global.getSector().getEconomy().getMarket(fallback.id);
            fleet.clearAssignments();
            fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, market.getPrimaryEntity(),
                    10000f, "diverting to " + fallback.name);
            destinationId = fallback.id;
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
