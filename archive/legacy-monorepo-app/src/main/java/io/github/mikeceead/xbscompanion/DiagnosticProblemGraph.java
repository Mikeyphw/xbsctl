package io.github.mikeceead.xbscompanion;

import android.content.Context;
import java.util.Locale;

/** Typed root-cause view over diagnostic evidence. Keeps dependent symptoms from becoming separate problems. */
final class DiagnosticProblemGraph {
    enum FailureType {
        NONE, CONFIG_MISSING, CONFIG_INACCESSIBLE, ROOT_DENIED, BINARIES_MISSING,
        PROCESS_NOT_SAMPLED, PROCESS_INTERNAL_ERROR, PROCESS_UNAVAILABLE, PROCESS_PARTIAL
    }

    static final class Problem {
        final String id;
        final FailureType type;
        final String severity;
        final String affects;
        final String action;
        final String detail;
        Problem(String id, FailureType type, String severity, String affects, String action, String detail) {
            this.id = id; this.type = type; this.severity = severity; this.affects = affects;
            this.action = action; this.detail = oneLine(detail);
        }
        String supportLine() {
            return "problem=" + id + " type=" + type.name().toLowerCase(Locale.US)
                + " severity=" + severity + " affects=" + affects + " action=" + action
                + (detail.isEmpty() ? "" : " detail=" + detail);
        }
    }

    private DiagnosticProblemGraph() {}

    static FailureType storageFailure(RootDiagnosticsReport d) {
        if (d == null || d.capturedAt <= 0L) return FailureType.NONE;
        if (!d.rootGranted) return FailureType.ROOT_DENIED;
        if (!d.binariesReady()) return FailureType.BINARIES_MISSING;
        if (!d.configExists) return FailureType.CONFIG_MISSING;
        if (!d.configReadable || !d.configWritable || !d.listExists || !d.listReadable
            || !d.listWritable || !d.backupExists || !d.backupReadable || !d.backupWritable) {
            return FailureType.CONFIG_INACCESSIBLE;
        }
        return FailureType.NONE;
    }

    static FailureType processFailure(ProcessSnapshot p) {
        if (p == null || p.capturedAt <= 0L) return FailureType.PROCESS_NOT_SAMPLED;
        if (p.rootAvailable) return p.diagnostics.partial() ? FailureType.PROCESS_PARTIAL : FailureType.NONE;
        String error = oneLine(p.error).toLowerCase(Locale.US);
        if (error.isEmpty() || error.contains("no process sample cached yet")) return FailureType.PROCESS_NOT_SAMPLED;
        if (error.contains("no closing quote") || error.contains("syntax error") || error.contains("parse error")
            || error.contains("internal") || error.contains("malformed shell")) return FailureType.PROCESS_INTERNAL_ERROR;
        return FailureType.PROCESS_UNAVAILABLE;
    }

    static String supportText(Context context) {
        RootDiagnosticsReport d = RootDiagnosticsStore.cached(context);
        ProcessSnapshot p = ProcessRepository.cached(context);
        StringBuilder out = new StringBuilder("format=1\n");
        FailureType storage = storageFailure(d);
        if (storage == FailureType.CONFIG_MISSING) {
            out.append(new Problem("config_missing", storage, "blocker", "managed_storage,events,runtime_mutation",
                "initialize_config", "Canonical XBS configuration is missing").supportLine()).append('\n');
        } else if (storage == FailureType.CONFIG_INACCESSIBLE) {
            out.append(new Problem("managed_storage_inaccessible", storage, "blocker", "managed_storage,configuration",
                "repair_storage", "A required managed path is unavailable").supportLine()).append('\n');
        }
        FailureType process = processFailure(p);
        if (process == FailureType.PROCESS_INTERNAL_ERROR) {
            out.append(new Problem("process_sampler_internal_error", process, "blocker", "process_monitor,process_evidence",
                "process_details", p.error).supportLine()).append('\n');
        } else if (process == FailureType.PROCESS_UNAVAILABLE) {
            out.append(new Problem("process_sampler_unavailable", process, "warning", "process_monitor,process_evidence",
                "retry_process", p.error).supportLine()).append('\n');
        } else if (process == FailureType.PROCESS_PARTIAL) {
            out.append(new Problem("process_sample_partial", process, "warning", "process_visibility",
                "process_details", p.diagnostics.summary()).supportLine()).append('\n');
        }
        if (out.toString().equals("format=1\n")) out.append("problem=none severity=pass\n");
        return out.toString();
    }

    static String storageDetail(FailureType type) {
        if (type == FailureType.CONFIG_MISSING) return "Canonical XBS configuration is missing; initialize XtremeBS.json";
        if (type == FailureType.CONFIG_INACCESSIBLE) return "A managed XBS path is missing, unreadable, or unwritable";
        return "Config, list, and backup paths are readable and writable";
    }

    static String processDetail(FailureType type, String raw) {
        if (type == FailureType.PROCESS_INTERNAL_ERROR) return "Internal process sampler failure: " + oneLine(raw);
        if (type == FailureType.PROCESS_UNAVAILABLE) return oneLine(raw).isEmpty() ? "Process sampling is unavailable" : oneLine(raw);
        if (type == FailureType.PROCESS_NOT_SAMPLED) return "No process sample has been cached yet";
        return oneLine(raw);
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        String s = value.replace('\r',' ').replace('\n',' ').replace('\t',' ').trim().replaceAll(" +", " ");
        return s.length() <= 320 ? s : s.substring(0, 319) + "…";
    }
}
