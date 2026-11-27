package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Enforces an explicit bounded daemon wait on every recognized mutating controller request.
 * A successful enqueue is NOT an applied result.  No shell is executed here.
 */
final class R05aCommandPolicy {
    private R05aCommandPolicy() {}

    static boolean requiresAck(String command) { return !expectedAction(command).isEmpty(); }

    static String expectedAction(String command) {
        List<String> words = words(command);
        if (words.isEmpty()) return "";
        String exe = words.get(0);
        List<String> tokens = new ArrayList<>();
        for (int i=1;i<words.size();i++) if (!"--json".equals(words.get(i)) && !"--quiet".equals(words.get(i)) && !"--no-interactive".equals(words.get(i))) tokens.add(words.get(i));
        if (tokens.isEmpty()) return "";
        if ("XBSctl".equals(exe)) return action(tokens.get(0));
        if (!"xbs".equals(exe) || tokens.size()<2) return "";
        if ("runtime".equals(tokens.get(0))) {
            String key=tokens.get(1);
            if ("safe".equals(key) && tokens.size()>2 && "off".equals(tokens.get(2))) return "resume";
            return action(key);
        }
        if ("event".equals(tokens.get(0))) {
            if ("run".equals(tokens.get(1))) return "start";
            if ("stop".equals(tokens.get(1))) return "stop";
        }
        return "";
    }

    private static String action(String value) {
        switch(value){case "start":case "stop":case "reload":case "pause":case "resume":case "safe":return value;default:return "";}
    }

    static String forceWait(String original) {
        if (original==null || !requiresAck(original)) return original;
        String command=original.trim();
        List<String> tokens=words(command);
        if (!tokens.contains("--json")) command += " --json";
        if (!tokens.contains("--wait")) command += " --wait";
        if (!tokens.contains("--timeout")) command += " --timeout 12";
        return command;
    }

    /** Strict bounded shell-word extraction: only recognized executable and fixed command path
     * are inspected. Event arguments are ignored; the daemon validates them and owns the ID.
     * A quoted shell character cannot make a privileged operation look like a controller command.
     */
    private static List<String> words(String command) {
        List<String> out=new ArrayList<>();
        if (command==null || command.length()>8192) return out;
        String trimmed=command.trim();
        if (trimmed.indexOf('\n')>=0 || trimmed.indexOf('\r')>=0 || trimmed.indexOf(';')>=0 || trimmed.indexOf('|')>=0 || trimmed.indexOf('&')>=0 || trimmed.indexOf('`')>=0 || trimmed.contains("$(")) return out;
        // Extract up to eight leading non-quoted, whitespace-separated command/control tokens.
        for (String word : trimmed.split("\\s+",12)) {
            if (word.startsWith("'") || word.startsWith("\"")) {out.add("<argument>");continue;}
            if (!word.matches("[A-Za-z0-9_./=:-]{1,128}")) return new ArrayList<>();
            out.add(word);
        }
        return out;
    }
}
