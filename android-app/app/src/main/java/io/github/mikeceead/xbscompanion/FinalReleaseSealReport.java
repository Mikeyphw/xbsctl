package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable 14-gate Phase 31 release report. Missing evidence is always a blocker. */
final class FinalReleaseSealReport {
    static final int FORMAT_VERSION = 1;
    static final int EXPECTED_GATE_COUNT = 14;
    static final int PASS = 0;
    static final int BLOCKER = 2;

    static final class Gate {
        final String id;
        final String title;
        final String detail;
        final int severity;
        final long capturedAt;

        Gate(String id, String title, String detail, int severity, long capturedAt) {
            this.id = clean(id);
            this.title = clean(title);
            this.detail = clean(detail);
            this.severity = severity == PASS ? PASS : BLOCKER;
            this.capturedAt = Math.max(0L, capturedAt);
        }

        boolean passed() { return severity == PASS; }
    }

    final long evaluatedAt;
    final String versionName;
    final long versionCode;
    final String sourceSealSha256;
    final List<Gate> gates;

    FinalReleaseSealReport(long evaluatedAt, String versionName, long versionCode,
                           String sourceSealSha256, List<Gate> gates) {
        this.evaluatedAt = Math.max(0L, evaluatedAt);
        this.versionName = clean(versionName);
        this.versionCode = Math.max(0L, versionCode);
        this.sourceSealSha256 = clean(sourceSealSha256);
        this.gates = Collections.unmodifiableList(new ArrayList<>(gates == null
            ? Collections.emptyList() : gates));
    }

    int passCount() {
        int count = 0;
        for (Gate gate : gates) if (gate.passed()) count++;
        return count;
    }

    int blockerCount() { return gates.size() - passCount(); }

    boolean ready() {
        return gates.size() == EXPECTED_GATE_COUNT && passCount() == EXPECTED_GATE_COUNT;
    }

    String headline() {
        return ready() ? "Final release seal ready"
            : "Final release seal blocked · " + blockerCount() + " gate(s)";
    }

    String compactSummary() {
        return passCount() + "/" + EXPECTED_GATE_COUNT + " gates passed · version "
            + versionName + " (" + versionCode + ")";
    }

    String supportText() {
        StringBuilder out = new StringBuilder();
        out.append("format=").append(FORMAT_VERSION).append('\n');
        out.append("ready=").append(ready()).append('\n');
        out.append("evaluated_at=").append(evaluatedAt).append('\n');
        out.append("app_version=").append(oneLine(versionName)).append('\n');
        out.append("version_code=").append(versionCode).append('\n');
        out.append("source_seal_sha256=").append(oneLine(sourceSealSha256)).append('\n');
        out.append("passed=").append(passCount()).append('/').append(EXPECTED_GATE_COUNT).append('\n');
        for (Gate gate : gates) {
            out.append("gate.").append(oneLine(gate.id)).append('=')
                .append(gate.passed() ? "PASS" : "BLOCKER")
                .append(" captured_at=").append(gate.capturedAt)
                .append(" detail=").append(oneLine(gate.detail)).append('\n');
        }
        return out.toString();
    }

    private static String oneLine(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
            .replaceAll(" +", " ");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
