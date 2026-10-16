package io.github.mikeceead.xbscompanion;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Shared root-side process-tree ownership, cancellation, and verified termination. */
final class RootProcessTree {
    private static final String RUN_DIR = "/data/local/tmp/xbscompanion-runs";
    private RootProcessTree() {}

    static String pidFile(String token) {
        String safe = token == null ? "" : token.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.length() > 96) safe = safe.substring(0, 96);
        if (safe.isEmpty()) throw new IllegalArgumentException("Invalid root process token");
        return RUN_DIR + "/" + safe + ".pid";
    }

    static String wrap(String pidFile, String command) {
        String payload = "exec sh -c " + shellQuote(command);
        return "RUN_DIR=" + shellQuote(RUN_DIR) + "; mkdir -p \"$RUN_DIR\"; PIDFILE=" + shellQuote(pidFile) + "; rm -f \"$PIDFILE\"; "
            + "xbs_cleanup(){ rm -f \"$PIDFILE\"; }; trap 'exit 130' TERM INT HUP; trap xbs_cleanup EXIT; "
            + "if command -v setsid >/dev/null 2>&1; then setsid sh -c " + shellQuote(payload) + " & else sh -c " + shellQuote(payload) + " & fi; "
            + "CHILD=$!; echo \"$CHILD\" > \"$PIDFILE\"; wait \"$CHILD\"; exit $?";
    }


    static TerminationResult terminateToken(String token) {
        try { return terminate(pidFile(token)); }
        catch (IllegalArgumentException invalid) { return new TerminationResult(false, "verification_failed", "invalid process token"); }
    }
    static TerminationResult terminate(String pidFile) {
        String killer = buildTerminationShell(pidFile);
        try {
            Process process = new ProcessBuilder("su", "-c", killer).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) output.append(line).append('\n');
                } catch (Exception ignored) {}
            }, "xbs-root-tree-verifier");
            reader.start();
            if (!process.waitFor(7, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new TerminationResult(false, "verification_failed", "termination verifier timed out");
            }
            reader.join(750L);
            String result = output.toString().trim();
            if (result.contains("XBS_TERM=terminated")) return new TerminationResult(true, "terminated", "");
            if (result.contains("XBS_TERM=not_running")) return new TerminationResult(true, "not_running", "");
            if (result.contains("XBS_TERM=still_running")) return new TerminationResult(false, "still_running", "root descendants survived TERM/KILL");
            return new TerminationResult(false, "verification_failed", result.isEmpty() ? "no verifier result" : result);
        } catch (Exception error) {
            return new TerminationResult(false, "verification_failed", error.getClass().getSimpleName());
        }
    }

    static String buildTerminationShellForTest(String pidFile) { return buildTerminationShell(pidFile); }

    private static String buildTerminationShell(String pidFile) {
        // Capture descendants before signalling so they can still be verified after re-parenting.
        // A process-group scan also catches descendants when the leader has already exited.
        return "PIDFILE=" + shellQuote(pidFile) + "; "
            + "if [ ! -s \"$PIDFILE\" ]; then printf 'XBS_TERM=not_running\\n'; exit 0; fi; "
            + "P=$(cat \"$PIDFILE\" 2>/dev/null || true); case \"$P\" in *[!0-9]*|'') printf 'XBS_TERM=verification_failed\\n'; exit 2;; esac; "
            + "collect_desc(){ X=$1; [ -r /proc/$X/task/$X/children ] || return 0; for C in $(cat /proc/$X/task/$X/children 2>/dev/null); do collect_desc \"$C\"; printf '%s ' \"$C\"; done; }; "
            + "collect_group(){ for S in /proc/[0-9]*/stat; do [ -r \"$S\" ] || continue; Q=${S#/proc/}; Q=${Q%/stat}; V=$(cat \"$S\" 2>/dev/null || true); R=${V##*) }; set -- $R; [ \"${3:-}\" = \"$P\" ] && printf '%s ' \"$Q\"; done; }; "
            + "PIDS=\"$P $(collect_desc \"$P\") $(collect_group)\"; "
            + "alive_pid(){ X=$1; [ -r /proc/$X/stat ] || return 1; V=$(cat /proc/$X/stat 2>/dev/null || true); R=${V##*) }; set -- $R; [ \"${1:-Z}\" != Z ]; }; "
            + "alive_any(){ for X in $PIDS; do alive_pid \"$X\" && return 0; done; return 1; }; "
            + "signal_all(){ SIG=$1; kill -$SIG -\"$P\" 2>/dev/null || true; for X in $PIDS; do kill -$SIG \"$X\" 2>/dev/null || true; done; }; "
            + "if ! alive_any; then rm -f \"$PIDFILE\"; printf 'XBS_TERM=not_running\\n'; exit 0; fi; "
            + "signal_all TERM; I=0; while alive_any && [ $I -lt 15 ]; do sleep 0.1; I=$((I+1)); done; "
            + "if alive_any; then PIDS=\"$PIDS $(collect_desc \"$P\") $(collect_group)\"; signal_all KILL; I=0; while alive_any && [ $I -lt 15 ]; do sleep 0.1; I=$((I+1)); done; fi; "
            + "if alive_any; then printf 'XBS_TERM=still_running\\n'; exit 3; fi; rm -f \"$PIDFILE\"; printf 'XBS_TERM=terminated\\n'";
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    static final class TerminationResult {
        final boolean terminated;
        final String status;
        final String detail;
        TerminationResult(boolean terminated, String status, String detail) {
            this.terminated = terminated;
            this.status = status == null ? "verification_failed" : status;
            this.detail = detail == null ? "" : detail;
        }
    }
}
