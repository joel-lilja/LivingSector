package livingsector.console;

import livingsector.campaign.TrafficDebug;
import livingsector.campaign.TrafficManager;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** Optional Console Commands integration; campaign code never loads this class directly. */
public final class LivingSectorCommand implements BaseCommand {
    @Override public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) return CommandResult.WRONG_CONTEXT;
        String[] words = args.trim().isEmpty() ? new String[]{"help"} : args.trim().split("\\s+");
        try {
            String result;
            switch (words[0].toLowerCase(java.util.Locale.ROOT)) {
                case "test":
                case "roundtrip":
                case "checkpoint":
                    if (words.length != 1 && words.length != 3) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.start(words.length == 3 ? words[1] : null, words.length == 3 ? words[2] : null,
                            "roundtrip".equalsIgnoreCase(words[0]), "checkpoint".equalsIgnoreCase(words[0])); break;
                case "status":
                    if (words.length > 2) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.status(words.length == 2 ? words[1] : null); break;
                case "debug":
                    String action = words.length > 1 ? words[1].toLowerCase(java.util.Locale.ROOT) : "status";
                    if ("summary".equals(action)) {
                        if (words.length > 4) return CommandResult.BAD_SYNTAX;
                        result = TrafficManager.get().debugCommand(action, words.length > 2 ? words[2] : "all", words.length > 3 ? words[3] : null);
                    } else if ("trip".equals(action)) {
                        if (words.length != 3) return CommandResult.BAD_SYNTAX;
                        result = TrafficManager.get().debugCommand(action, "all", words[2]);
                    } else {
                        if (words.length > 2 || !("on".equals(action) || "off".equals(action) || "status".equals(action)
                                || "flush".equals(action) || "export".equals(action) || "runs".equals(action))) return CommandResult.BAD_SYNTAX;
                        result = TrafficManager.get().debugCommand(action, "all", null);
                    }
                    break;
                case "visit":
                    if (words.length != 2) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.visit(words[1]); break;
                case "verify":
                    if (words.length != 2) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.verify(words[1]); break;
                case "away":
                    if (words.length != 2) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.away(words[1]); break;
                case "damage":
                case "lose":
                    if (words.length != 2) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.damage(words[1], "lose".equalsIgnoreCase(words[0])); break;
                case "cancel":
                    if (words.length != 2) return CommandResult.BAD_SYNTAX;
                    result = cancel(words[1]); break;
                case "pause":
                case "resume":
                    if (words.length != 1) return CommandResult.BAD_SYNTAX;
                    boolean pause = "pause".equalsIgnoreCase(words[0]);
                    TrafficManager.get().pauseDepartures(pause);
                    result = "Automatic departures " + (pause ? "paused" : "resumed")
                            + ". Existing missions continue; this override persists in the save."; break;
                case "probe":
                    if (words.length < 2 || words.length > 3 || (words.length == 3 && !"start".equalsIgnoreCase(words[1]))) return CommandResult.BAD_SYNTAX;
                    result = TrafficDebug.probe(words[1].toLowerCase(java.util.Locale.ROOT), words.length == 3 ? Double.parseDouble(words[2]) : 30); break;
                case "help":
                    result = "ls test [originMarketId destinationMarketId] | ls roundtrip [originMarketId destinationMarketId]"
                            + "\nls checkpoint [originMarketId destinationMarketId] (45-day boarding for distance-despawn testing)"
                            + "\nls status [missionId] | ls visit <missionId> | ls verify <missionId>"
                            + "\nls away <missionId> | ls damage <missionId> | ls lose <missionId> | ls cancel <missionId>"
                            + "\nls pause | ls resume | ls probe start [days] | ls probe status | ls probe stop"
                            + "\nls debug on|off|status|flush|export|runs | ls debug summary [days|all] [runId] | ls debug trip <tripId>"
                            + "\nTest/roundtrip create two transports; visit teleports only the player. No native AI or visibility rules are replaced."; break;
                default: return CommandResult.BAD_SYNTAX;
            }
            Console.showMessage(result);
            return CommandResult.SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Console.showMessage("Living Sector: " + ex.getMessage());
            return CommandResult.ERROR;
        }
    }

    private static String cancel(String id) { return TrafficDebug.cancel(id); }
}
