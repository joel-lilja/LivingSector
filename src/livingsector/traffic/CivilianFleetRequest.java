package livingsector.traffic;

/** Saved composition requirements, independent of game hull IDs and live API objects. */
public final class CivilianFleetRequest {
    public final int passengerCapacity, maximumShips, maximumHullSize;
    public final double homeFactionPreference;
    public CivilianFleetRequest(int passengerCapacity, int maximumShips, int maximumHullSize, double homeFactionPreference) {
        if (passengerCapacity < 1 || maximumShips < 1 || maximumShips > 3 || maximumHullSize < 1 || maximumHullSize > 3
                || !Double.isFinite(homeFactionPreference) || homeFactionPreference < 0 || homeFactionPreference > 1) {
            throw new IllegalArgumentException("Invalid civilian fleet request");
        }
        this.passengerCapacity = passengerCapacity;
        this.maximumShips = maximumShips;
        this.maximumHullSize = maximumHullSize; // 1 frigate, 2 destroyer, 3 cruiser; no capital passenger fleets yet.
        this.homeFactionPreference = homeFactionPreference;
    }
}
