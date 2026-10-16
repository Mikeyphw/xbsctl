package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProcessRepository {
    private static final String CACHE_KEY = "process_snapshot_v4";
    private static final long CACHE_AGE = 1500L;
    private static final int MAX_CACHE_CHARS = 512 * 1024;
    private ProcessRepository() {}

    static ProcessSnapshot load(Context context, boolean force) {
        RootStatus root = XbsRootApi.checkRoot(context, false);
        if (!root.granted) return ProcessSnapshot.error(root.summary());

        ProcessSnapshot previous = parse(XbsCache.peek(context, CACHE_KEY));
        if (!force) {
            ProcessSnapshot current = parse(XbsCache.get(context, CACHE_KEY, CACHE_AGE));
            if (current != null) return current;
        }

        XbsRootApi.CommandOutput output = XbsRootApi.runRootLarge(buildScanShell(), 45);
        if (output.exitCode != 0) {
            String detail = output.error.isEmpty() ? output.stdout : output.error;
            if (detail == null || detail.trim().isEmpty()) detail = "Root process scan exited with code " + output.exitCode;
            return ProcessSnapshot.error(ResultFormatter.notificationText(detail));
        }

        ProcessSampleParser.Sample parsed = ProcessSampleParser.parse(output.stdout);
        ProcessScanDiagnostics diagnostics = new ProcessScanDiagnostics(
            parsed.cpuCount,
            parsed.scanned,
            parsed.readable,
            parsed.restricted,
            parsed.malformed,
            parsed.cmdlineUnavailable,
            parsed.executableUnavailable,
            parsed.contextUnavailable);
        if (!parsed.successful()) return ProcessSnapshot.error(parsed.error, diagnostics);

        long capturedAt = System.currentTimeMillis();
        Map<String, InstalledApp> packages = AppCatalog.byPackage(context);
        Map<Integer, List<InstalledApp>> byUid = AppCatalog.byUid(context);
        Map<String, List<String>> rules = XbsRuleIndex.load(context, false);
        Map<String, XbsProcess> previousByIdentity = new LinkedHashMap<>();
        if (previous != null) for (XbsProcess process : previous.processes) {
            if (process.startTicks > 0L) previousByIdentity.put(process.identityKey(), process);
        }
        long deltaTotal = previous == null ? 0L : Math.max(0L, parsed.totalTicks - previous.totalTicks);

        List<XbsProcess> processes = new ArrayList<>();
        for (ProcessSampleParser.Row row : parsed.rows) {
            InstalledApp owner = findOwner(row, packages, byUid);
            XbsProcess old = previousByIdentity.get(row.identityKey());
            double cpu = old == null ? 0.0 : ProcessSampleParser.cpuPercent(
                row.ticks,
                row.startTicks,
                old.ticks,
                old.startTicks,
                deltaTotal,
                parsed.cpuCount);
            String packageName = owner == null ? "" : owner.packageName;
            String label = owner == null
                ? (row.uid < 10000 ? "Android system" : "Unmapped app process")
                : owner.label;
            List<String> matched = matchRules(row.processName, rules);
            processes.add(new XbsProcess(
                row.pid,
                row.parentPid,
                row.uid,
                row.processName,
                row.command,
                row.executable,
                row.selinuxContext,
                row.rssKb,
                row.ticks,
                row.startTicks,
                capturedAt,
                row.niceValue,
                cpu,
                packageName,
                label,
                row.commandLineObserved,
                row.executableObserved,
                row.contextObserved,
                matched));
        }
        Collections.sort(processes, Comparator.comparingDouble((XbsProcess p) -> p.cpuPercent).reversed()
            .thenComparing(Comparator.comparingLong((XbsProcess p) -> p.rssKb).reversed())
            .thenComparing((XbsProcess p) -> p.processName));

        ProcessSnapshot snapshot = new ProcessSnapshot(
            true,
            parsed.totalTicks,
            capturedAt,
            processes,
            "",
            diagnostics);
        XbsCache.put(context, CACHE_KEY, snapshot.toJson().toString());
        return snapshot;
    }

    static ProcessSnapshot cached(Context context) {
        ProcessSnapshot snapshot = parse(XbsCache.peek(context, CACHE_KEY));
        return snapshot == null ? ProcessSnapshot.error("No process sample cached yet") : snapshot;
    }

    static void invalidate(Context context) { XbsCache.invalidate(context, CACHE_KEY); }

    static String buildScanShell() {
        return "export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/data/adb/ksu/bin:$PATH; "
            + "clean_field() { printf '%s' \"$1\" | tr '\\011\\015\\012' '   '; }; "
            + "is_uint() { case \"$1\" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }; "
            + "is_int() { case \"$1\" in -*) V=${1#-};; *) V=$1;; esac; is_uint \"$V\"; }; "
            + "if [ ! -r /proc/stat ]; then printf 'ERROR\\t/proc/stat is not readable\\n'; exit 13; fi; "
            + "IFS= read -r CPU_LINE < /proc/stat || { printf 'ERROR\\tCould not read /proc/stat\\n'; exit 13; }; "
            + "printf 'CPU\\t%s\\n' \"$CPU_LINE\"; "
            + "CPU_COUNT=0; while read -r LABEL REST; do case \"$LABEL\" in cpu[0-9]*) CPU_COUNT=$((CPU_COUNT+1));; esac; done < /proc/stat; "
            + "[ \"$CPU_COUNT\" -gt 0 ] || CPU_COUNT=1; "
            + "SCANNED=0; READABLE=0; RESTRICTED=0; MALFORMED=0; CMDMISS=0; EXEMISS=0; CTXMISS=0; "
            + "for D in /proc/[0-9]*; do "
            + "PID=${D##*/}; SCANNED=$((SCANNED+1)); "
            + "[ -r \"$D/stat\" ] && [ -r \"$D/status\" ] || { RESTRICTED=$((RESTRICTED+1)); continue; }; "
            + "IFS= read -r STAT < \"$D/stat\" || { RESTRICTED=$((RESTRICTED+1)); continue; }; "
            + "case \"$STAT\" in *') '*) ;; *) MALFORMED=$((MALFORMED+1)); continue;; esac; "
            + "RESTAT=${STAT##*) }; set -- $RESTAT; "
            + "PARENT=${2:-}; UT=${12:-}; ST=${13:-}; NICE=${17:-}; START=${20:-}; "
            + "is_uint \"$PARENT\" && is_uint \"$UT\" && is_uint \"$ST\" && is_int \"$NICE\" && is_uint \"$START\" && [ \"$START\" -gt 0 ] || { MALFORMED=$((MALFORMED+1)); continue; }; "
            + "UIDV=-1; RSS=0; while IFS= read -r LINE; do case \"$LINE\" in Uid:*) set -- $LINE; UIDV=${2:--1};; VmRSS:*) set -- $LINE; RSS=${2:-0};; esac; done < \"$D/status\"; "
            + "is_int \"$UIDV\" && is_uint \"$RSS\" || { MALFORMED=$((MALFORMED+1)); continue; }; "
            + "TICKS=$((UT+ST)); "
            + "COMM=''; if [ -r \"$D/comm\" ]; then IFS= read -r COMM < \"$D/comm\" 2>/dev/null || COMM=''; fi; "
            + "if [ -z \"$COMM\" ]; then COMM_PART=${STAT#*(}; COMM=${COMM_PART%)*}; fi; "
            + "CMD=''; CMD_OK=1; if [ -r \"$D/cmdline\" ]; then CMD=$(tr '\\000\\011\\015\\012' '    ' < \"$D/cmdline\" 2>/dev/null) || CMD_OK=0; else CMD_OK=0; fi; "
            + "if [ \"$CMD_OK\" -eq 0 ]; then CMDMISS=$((CMDMISS+1)); fi; [ -n \"$CMD\" ] || CMD=$COMM; "
            + "EXE=''; EXE_OK=1; EXE=$(readlink \"$D/exe\" 2>/dev/null) || { EXE_OK=0; EXE=''; EXEMISS=$((EXEMISS+1)); }; "
            + "CONTEXT=''; CTX_OK=1; if [ -r \"$D/attr/current\" ]; then IFS= read -r CONTEXT < \"$D/attr/current\" 2>/dev/null || CTX_OK=0; else CTX_OK=0; fi; "
            + "if [ \"$CTX_OK\" -eq 0 ]; then CTXMISS=$((CTXMISS+1)); fi; "
            + "COMM=$(clean_field \"$COMM\"); CMD=$(clean_field \"$CMD\"); EXE=$(clean_field \"$EXE\"); CONTEXT=$(clean_field \"$CONTEXT\"); "
            + "printf 'PROC\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "
            + "\"$PID\" \"$UIDV\" \"$RSS\" \"$TICKS\" \"$NICE\" \"$START\" \"$PARENT\" \"$COMM\" \"$CMD\" \"$EXE\" \"$CONTEXT\" \"$CMD_OK\" \"$EXE_OK\" \"$CTX_OK\"; "
            + "READABLE=$((READABLE+1)); done; "
            + "printf 'META\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "
            + "\"$SCANNED\" \"$READABLE\" \"$RESTRICTED\" \"$MALFORMED\" \"$CMDMISS\" \"$EXEMISS\" \"$CTXMISS\" \"$CPU_COUNT\"; exit 0";
    }

    private static InstalledApp findOwner(
            ProcessSampleParser.Row row,
            Map<String, InstalledApp> packages,
            Map<Integer, List<InstalledApp>> byUid) {
        String candidate = row.processName;
        int colon = candidate.indexOf(':');
        if (colon > 0) candidate = candidate.substring(0, colon);
        InstalledApp exact = packages.get(candidate);
        if (exact != null) return exact;
        List<InstalledApp> uidApps = byUid.get(row.uid);
        if (uidApps != null && uidApps.size() == 1) return uidApps.get(0);
        if (uidApps != null) for (InstalledApp app : uidApps) {
            if (row.processName.startsWith(app.packageName) || row.command.contains(app.packageName)) return app;
        }
        return null;
    }

    private static List<String> matchRules(String process, Map<String, List<String>> rules) {
        List<String> direct = rules.get(process);
        if (direct != null) return direct;
        for (Map.Entry<String,List<String>> entry : rules.entrySet()) {
            if (process.equals(entry.getKey()) || process.startsWith(entry.getKey() + ":")) return entry.getValue();
        }
        return Collections.emptyList();
    }

    private static ProcessSnapshot parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_CACHE_CHARS) return null;
        try { return ProcessSnapshot.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return null; }
    }
}
