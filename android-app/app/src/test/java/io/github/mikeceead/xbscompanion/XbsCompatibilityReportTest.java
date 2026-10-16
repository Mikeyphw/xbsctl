package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class XbsCompatibilityReportTest {
    @Test public void parsesCurrentContractAndExplicitCapabilities() {
        String json = "{\"data\":{\"compatibility\":{\"contract_version\":\"1.0.0\","
            + "\"module\":{\"id\":\"XtremeBS\",\"version\":\"v2.1.5\",\"version_code\":215},"
            + "\"cli\":{\"version\":\"1.2.0\",\"command_schema_version\":\"1.1.0\"},"
            + "\"android_contract\":{\"minimum\":1,\"maximum\":1,\"minimum_app_version_code\":1},"
            + "\"config\":{\"active_version\":2,\"maximum_supported_version\":2,"
            + "\"schema_digest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"event_fields\":[\"allowlist\",\"handle_apps\"]},"
            + "\"commands\":[{\"id\":\"status\"},{\"id\":\"runtime_reload\"}],"
            + "\"runtime\":{\"kind\":\"go-native\",\"python_required\":false,\"yq_required\":false,\"bash_required\":false,\"bc_required\":false},"
            + "\"digest\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertTrue(report.isLive());
        assertTrue(report.compatible());
        assertTrue(report.supportsCommand("status"));
        assertFalse(report.supportsCommand("event_stop"));
        assertTrue(report.supportsEventField("handle_apps"));
        assertTrue(report.runtimeIsGoNative());
        assertTrue(report.runtimeDependencyFree());
    }

    @Test public void olderStringCommandFixtureDegradesGracefully() {
        String json = "{\"data\":{\"compatibility\":{\"contract_version\":\"1.0.0\","
            + "\"android_contract\":{\"minimum\":1,\"maximum\":1},"
            + "\"config\":{\"active_version\":1,\"maximum_supported_version\":2,\"event_fields\":[\"allowlist\"]},"
            + "\"commands\":[\"status\",\"validate\"]}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertTrue(report.isLive());
        assertTrue(report.supportsCommand("validate"));
        assertFalse(report.supportsEventField("kill_wifi"));
    }

    @Test public void newerRequiredAndroidContractIsBlocked() {
        String json = "{\"data\":{\"compatibility\":{\"contract_version\":\"2.0.0\","
            + "\"android_contract\":{\"minimum\":2,\"maximum\":3},"
            + "\"config\":{\"active_version\":2,\"maximum_supported_version\":3,\"event_fields\":[]},"
            + "\"commands\":[{\"id\":\"status\"}],\"future_field\":true}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertTrue(report.isLive());
        assertFalse(report.contractSupported());
        assertFalse(report.compatible());
    }

    @Test public void unsafeOrCoercedContractIdsAreIgnored() {
        String json = "{\"data\":{\"compatibility\":{"
            + "\"android_contract\":{\"minimum\":1,\"maximum\":1},"
            + "\"config\":{\"active_version\":2,\"maximum_supported_version\":2,"
            + "\"event_fields\":[\"allowlist\",\"bad-field\",9]},"
            + "\"commands\":[{\"id\":\"status\"},{\"id\":\"bad;id\"},42]}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertTrue(report.supportsCommand("status"));
        assertFalse(report.supportsCommand("bad;id"));
        assertTrue(report.supportsEventField("allowlist"));
        assertFalse(report.supportsEventField("bad-field"));
        assertFalse(report.supportsEventField("9"));
    }

    @Test public void oversizedCompatibilityPayloadIsUnavailable() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i <= XbsCompatibilityReport.MAX_RAW_CHARS; i++) raw.append('x');
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(raw.toString(), 100L);
        assertFalse(report.isLive());
        assertTrue(report.error.contains("bounded parser limit"));
    }

    @Test public void oversizedLiveIdentifierArraysFailClosedBeforeIteration() {
        StringBuilder commands = new StringBuilder();
        for (int i = 0; i <= XbsCompatibilityReport.MAX_COMMAND_IDS; i++) {
            if (i > 0) commands.append(',');
            commands.append("{\"id\":\"cmd_").append(i).append("\"}");
        }
        String json = "{\"data\":{\"compatibility\":{"
            + "\"android_contract\":{\"minimum\":1,\"maximum\":1},"
            + "\"config\":{\"active_version\":2,\"maximum_supported_version\":2,\"event_fields\":[\"allowlist\"]},"
            + "\"commands\":[" + commands + "]}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertFalse(report.isLive());
        assertTrue(report.error.contains("command array"));
    }

    @Test public void oversizedCachedIdentifierArraysFailClosed() throws Exception {
        JSONObject cached = XbsCompatibilityReport.fallback(100L, "test").toJson();
        JSONArray ids = new JSONArray();
        for (int i = 0; i <= XbsCompatibilityReport.MAX_COMMAND_IDS; i++) ids.put("cmd_" + i);
        cached.put("commandIds", ids);
        XbsCompatibilityReport report = XbsCompatibilityReport.fromJson(cached);
        assertFalse(report.isLive());
        assertTrue(report.error.contains("identifier array"));
    }

    @Test public void liveRuntimeDependenciesAreRejected() {
        String json = "{\"data\":{\"compatibility\":{\"contract_version\":\"1.1.0\","
            + "\"android_contract\":{\"minimum\":1,\"maximum\":1,\"minimum_app_version_code\":1},"
            + "\"config\":{\"active_version\":2,\"maximum_supported_version\":2,\"event_fields\":[\"allowlist\"]},"
            + "\"commands\":[{\"id\":\"status\"}],"
            + "\"runtime\":{\"kind\":\"go-native\",\"python_required\":false,\"yq_required\":true}}}}";
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(json, 100L);
        assertTrue(report.isLive());
        assertFalse(report.runtimeDependencyFree());
        assertFalse(report.compatible());
    }

}
