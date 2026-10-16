package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class SuiteCatalog {
    private static final List<SuiteAction> ACTIONS = Collections.unmodifiableList(Arrays.asList(
        new SuiteAction("xbs", "status", "Runtime status", "Read XBS runtime and profile state.", "xbs --json status", "root", false, false),
        new SuiteAction("xbs", "reload", "Reload runtime", "Reload XBS configuration.", "xbs --json runtime reload", "root", false, false),
        new SuiteAction("xbs", "pause", "Pause runtime", "Pause XBS processing.", "xbs --json runtime pause", "root", false, false),
        new SuiteAction("xbs", "resume", "Resume runtime", "Resume XBS processing.", "xbs --json runtime resume", "root", false, false),
        new SuiteAction("xbs", "safe_on", "Safe mode on", "Enable XBS safe mode.", "xbs --json runtime safe on", "root", false, false),
        new SuiteAction("xbs", "safe_off", "Safe mode off", "Disable XBS safe mode.", "xbs --json runtime safe off", "root", false, false),
        new SuiteAction("xbs", "event_list", "List events", "List valid XBS event keys.", "xbs --json event list", "root", false, false),
        new SuiteAction("xbs", "event_run", "Run event", "Start an XBS event runner.", "xbs --json event run {arg}", "root", true, false),
        new SuiteAction("xbs", "event_stop", "Stop event", "Stop an XBS event runner.", "xbs --json event stop {arg}", "root", true, true),
        new SuiteAction("xbs", "validate", "Validate config", "Validate the current XBS configuration.", "xbs --json validate", "root", false, false)
    ));

    private SuiteCatalog() {}

    static List<SuiteAction> all() { return ACTIONS; }

    static List<SuiteAction> forModule(String module) {
        List<SuiteAction> out = new ArrayList<>();
        for (SuiteAction action : ACTIONS) if (action.module.equals(module)) out.add(action);
        return out;
    }

    static SuiteAction find(String module, String id) {
        for (SuiteAction action : ACTIONS) {
            if (action.module.equals(module) && action.id.equals(id)) return action;
        }
        return null;
    }

    static String moduleTitle(String module) { return "XBS"; }
}
