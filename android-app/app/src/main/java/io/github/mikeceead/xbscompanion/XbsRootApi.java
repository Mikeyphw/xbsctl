package io.github.mikeceead.xbscompanion;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class XbsRootApi {
    private static final int MAX_OUTPUT = 128000;
    private static final int MAX_LARGE_OUTPUT = 2_000_000;
    private static final String ROOT_CACHE = "root_status_v2";
    private static final long ROOT_CACHE_AGE = 30_000L;
    private XbsRootApi() {}

    static XbsSnapshot readSnapshot(Context context) { return EventBridgeReader.read(context); }

    static XbsSnapshot readSnapshot() { return readLegacySnapshot(); }

    static XbsSnapshot readLegacySnapshot() {
        String status = XbsConfigApi.logPath("XtremeBS.status");
        String shell = "EVENT=/data/local/tmp/XtremeBS/event_changes.json; "
            + "STATUS=" + shellQuote(status) + "; "
            + XbsRuntimeResolver.availability("XBS_AVAILABLE", "xbs", "XBSctl")
            + "printf '{\"available\":'; "
            + "if [ \"$XBS_AVAILABLE\" = true ]; then printf true; else printf false; fi; "
            + "printf ',\"event\":'; if [ -s \"$EVENT\" ]; then cat \"$EVENT\"; else printf '{\"safe_mode\":false,\"last_change\":null,\"profiles\":{},\"active\":[],\"inactive\":[]}'; fi; "
            + "printf ',\"status\":'; if [ -s \"$STATUS\" ]; then cat \"$STATUS\"; else printf '{\"mode\":\"unknown\",\"active_profiles\":[]}'; fi; "
            + "printf '}';";
        CommandOutput output = runRoot(shell);
        if (output.exitCode != 0) return XbsSnapshot.unavailable(output.error.isEmpty() ? output.stdout : output.error);
        return XbsSnapshot.parse(output.stdout);
    }

    static RootStatus cachedRootStatus(Context context) {
        String raw = XbsCache.get(context, ROOT_CACHE, ROOT_CACHE_AGE);
        if (raw == null) raw = XbsCache.peek(context, ROOT_CACHE);
        if (raw == null || raw.isEmpty()) return RootStatus.unchecked();
        try { return RootStatus.fromJson(new JSONObject(raw)); } catch (Exception ignored) { return RootStatus.unchecked(); }
    }

    static RootStatus checkRoot(Context context, boolean force) {
        if (!force) {
            String cached = XbsCache.get(context, ROOT_CACHE, ROOT_CACHE_AGE);
            if (cached != null) try { return RootStatus.fromJson(new JSONObject(cached)); } catch (Exception ignored) {}
        }
        String shell = "UIDV=$(id -u 2>/dev/null || printf x); "
            + "MANAGER=Generic; "
            + "if [ -d /data/adb/ksu ] || [ -d /data/adb/modules/.ksu ]; then MANAGER='KernelSU / KernelSU Next'; "
            + "elif [ -d /data/adb/ap ] || [ -d /data/adb/apatch ]; then MANAGER=APatch; "
            + "elif [ -d /data/adb/magisk ] || [ -f /data/adb/magisk.db ]; then MANAGER=Magisk; fi; "
            + XbsRuntimeResolver.resolveXbs("XBS_PATH")
            + XbsRuntimeResolver.resolveXbsCtl("XBSCTL_PATH")
            + "XBS=false; XBSCTL=false; "
            + "[ -n \"$XBS_PATH\" ] && XBS=true; "
            + "[ -n \"$XBSCTL_PATH\" ] && XBSCTL=true; "
            + "printf '%s\\t%s\\t%s\\t%s' \"$UIDV\" \"$MANAGER\" \"$XBS\" \"$XBSCTL\"";
        CommandOutput output = runRoot(shell, 15, MAX_OUTPUT);
        RootStatus status;
        if (output.exitCode != 0) {
            String message = output.error.isEmpty() ? output.stdout : output.error;
            status = new RootStatus(true, false, false, false, "Unknown", "", message.isEmpty() ? "Root permission denied" : message, System.currentTimeMillis());
        } else {
            String[] fields = output.stdout.trim().split("\\t", 4);
            String uid = fields.length > 0 ? fields[0] : "";
            String manager = fields.length > 1 ? fields[1] : "Generic";
            boolean xbs = fields.length > 2 && "true".equals(fields[2]);
            boolean ctl = fields.length > 3 && "true".equals(fields[3]);
            boolean granted = "0".equals(uid);
            status = new RootStatus(true, granted, xbs, ctl, manager, uid,
                granted ? (xbs ? "Root and XBS are ready" : "Root granted, but XBS was not found") : "su did not return uid 0", System.currentTimeMillis());
        }
        XbsCache.put(context, ROOT_CACHE, status.toJson().toString());
        return status;
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    static CommandOutput runRoot(String shell) { return runRoot(shell, 30, MAX_OUTPUT); }
    static CommandOutput runRootLarge(String shell) { return runRoot(shell, 45, MAX_LARGE_OUTPUT); }
    static CommandOutput runRootLarge(String shell, int timeoutSeconds) { return runRoot(shell, timeoutSeconds, MAX_LARGE_OUTPUT); }

    private static CommandOutput runRoot(String shell, int timeoutSeconds, int maxOutput) {
        int exit = -1; StringBuilder out = new StringBuilder(); String error = ""; Process process = null;
        try {
            String token = "api-" + Long.toUnsignedString(System.nanoTime(), 36) + "-" + Long.toUnsignedString(Thread.currentThread().getId(), 36);
            String pidFile = RootProcessTree.pidFile(token);
            String wrapped = RootProcessTree.wrap(pidFile, shell);
            process = new ProcessBuilder("su", "-c", wrapped).redirectErrorStream(true).start();
            final Process running = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                            if (out.length() > maxOutput) out.delete(0, out.length() - maxOutput);
                        }
                    }
                } catch (Exception ignored) {}
            }, "xbs-root-reader");
            readerThread.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                RootProcessTree.TerminationResult termination = RootProcessTree.terminate(pidFile);
                process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                if (!termination.terminated) termination = RootProcessTree.terminate(pidFile);
                error = "Root command timed out after " + timeoutSeconds + " seconds; privileged process tree=" + termination.status;
                if (!termination.detail.isEmpty()) error += " (" + termination.detail + ")";
                exit = termination.terminated ? 124 : 125;
            } else exit = process.exitValue();
            readerThread.join(1500L);
        } catch (Exception exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (process != null) process.destroyForcibly();
        }
        String stdout; synchronized (out) { stdout = out.toString().trim(); }
        return new CommandOutput(exit, stdout, error);
    }

    static final class CommandOutput {
        final int exitCode; final String stdout; final String error;
        CommandOutput(int exitCode, String stdout, String error) {
            this.exitCode = exitCode; this.stdout = stdout == null ? "" : stdout; this.error = error == null ? "" : error;
        }
    }
}
