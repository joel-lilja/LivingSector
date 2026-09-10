package livingsector.model;

/** The only persistent recorder data: save cursor, not a copy of the log. */
public final class RecorderState {
    public String campaignId, runId;
    public long sequence;
    public double day;
}
