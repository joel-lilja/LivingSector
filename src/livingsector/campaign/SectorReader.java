package livingsector.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import livingsector.model.SectorSnapshot;
import org.lwjgl.util.vector.Vector2f;

/** The single place where live sector data becomes input for traffic algorithms. */
public final class SectorReader {
    private SectorReader() { }

    public static boolean peaceful(String first, String second) {
        FactionAPI a = Global.getSector().getFaction(first);
        FactionAPI b = Global.getSector().getFaction(second);
        return a != null && b != null && !a.isHostileTo(second) && !b.isHostileTo(first);
    }

    public static boolean isPort(MarketAPI market) {
        if (market == null || !market.isInEconomy() || market.isHidden()
                || market.isPlanetConditionMarketOnly() || market.getSize() < 3) return false;
        SectorEntityToken entity = market.getPrimaryEntity();
        return entity != null && entity.isAlive() && market.getStarSystem() != null
                && !market.getStarSystem().getJumpPoints().isEmpty();
    }

    public static SectorSnapshot capture(Set<String> extraFactions) {
        List<SectorSnapshot.Port> ports = new ArrayList<SectorSnapshot.Port>();
        Set<String> factions = new LinkedHashSet<String>(extraFactions);
        float units = Misc.getUnitsPerLightYear();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!isPort(market)) continue;
            Vector2f location = market.getLocationInHyperspace();
            ports.add(new SectorSnapshot.Port(market.getId(), market.getName(), market.getFactionId(),
                    market.getStarSystem().getId(), market.getSize(), market.getStabilityValue(),
                    location.x / units, location.y / units,
                    market.getPrimaryEntity() instanceof PlanetAPI, market.isPlayerOwned()));
            factions.add(market.getFactionId());
        }
        Map<String, Set<String>> hostilities = new LinkedHashMap<String, Set<String>>();
        for (String id : factions) {
            FactionAPI faction = Global.getSector().getFaction(id);
            Set<String> enemies = new LinkedHashSet<String>();
            for (String other : factions) {
                if (faction == null || faction.isHostileTo(other)) enemies.add(other);
            }
            hostilities.put(id, enemies);
        }
        return new SectorSnapshot(ports, hostilities);
    }
}
