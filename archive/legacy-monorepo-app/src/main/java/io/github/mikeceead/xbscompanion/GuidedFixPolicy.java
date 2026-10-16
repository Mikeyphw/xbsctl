package io.github.mikeceead.xbscompanion;

/** Pure-Java mapping from stable Debug Health IDs to explicit, bounded repair flows. */
final class GuidedFixPolicy {
    static final int RISK_NAVIGATION = 0;
    static final int RISK_LOCAL_MUTATION = 1;
    static final int RISK_ROOT_READ = 2;
    static final int RISK_ROOT_MUTATION = 3;

    static final String OPEN_SOURCE = "open_source";
    static final String REFRESH_ROOT_EVIDENCE = "refresh_root_evidence";
    static final String REPAIR_STORAGE = "repair_storage";
    static final String INITIALIZE_HISTORY = "initialize_history";
    static final String RECOVER_TRANSACTION = "recover_transaction";
    static final String REPAIR_NOTIFICATIONS = "repair_notifications";
    static final String RESTART_MONITOR = "restart_monitor";

    private GuidedFixPolicy() {}

    static final class Spec {
        final String checkId;
        final String title;
        final String explanation;
        final String action;
        final String buttonLabel;
        final String sourceActionId;
        final int risk;
        final boolean confirmationRequired;

        Spec(String checkId, String title, String explanation, String action,
             String buttonLabel, String sourceActionId, int risk, boolean confirmationRequired) {
            this.checkId = clean(checkId);
            this.title = clean(title);
            this.explanation = clean(explanation);
            this.action = clean(action);
            this.buttonLabel = clean(buttonLabel);
            this.sourceActionId = clean(sourceActionId);
            this.risk = Math.max(RISK_NAVIGATION, Math.min(RISK_ROOT_MUTATION, risk));
            this.confirmationRequired = confirmationRequired;
        }

        boolean executable() { return !OPEN_SOURCE.equals(action); }
        boolean rootBacked() { return risk >= RISK_ROOT_READ; }
        boolean mutatesState() { return risk == RISK_LOCAL_MUTATION || risk == RISK_ROOT_MUTATION; }
        String riskLabel() {
            if (risk == RISK_ROOT_MUTATION) return "Root mutation";
            if (risk == RISK_ROOT_READ) return "Read-only root probe";
            if (risk == RISK_LOCAL_MUTATION) return "Local app repair";
            return "Navigation only";
        }
    }

    static Spec forItem(DebugHealthReport.Item item) {
        if (item == null) return open("", "Health evidence", "Open the source workbench to inspect this check.", DebugHealthPolicy.ACTION_ROOT);
        String id = item.id;
        if ("root_evidence".equals(id) || "root_access".equals(id)
            || "xbs_binaries".equals(id) || "foreground_services".equals(id)
            || "schema_compatibility".equals(id)) {
            return new Spec(id, "Refresh root and compatibility evidence",
                "Runs one bounded read-only root probe, refreshes the live capability contract, and updates the diagnostic caches. It does not change XBS configuration.",
                REFRESH_ROOT_EVIDENCE, "Run bounded diagnostic probe", DebugHealthPolicy.ACTION_ROOT,
                RISK_ROOT_READ, true);
        }
        if ("managed_storage".equals(id)) {
            return new Spec(id, "Initialize or repair XBS configuration",
                "Creates only canonical managed directories, initializes XtremeBS.json when absent, validates it, and requests a runtime reload. Existing configuration is preserved and broad chmod is never used.",
                REPAIR_STORAGE, "Initialize & verify configuration", DebugHealthPolicy.ACTION_ROOT,
                RISK_ROOT_MUTATION, true);
        }
        if ("history_store".equals(id)) {
            return new Spec(id, "Initialize local history evidence",
                "Opens the Room history store explicitly, completes any pending legacy migration, applies bounded retention, and writes a passive status snapshot for future read-only health checks.",
                INITIALIZE_HISTORY, "Initialize local history", DebugHealthPolicy.ACTION_ACTIVITY,
                RISK_LOCAL_MUTATION, true);
        }
        if ("config_transaction".equals(id)) {
            return new Spec(id, "Recover an interrupted configuration transaction",
                "Reads the authoritative root-side journal. Recovery is offered only for an interrupted recoverable transaction and restores the complete pre-transaction file set. A committed transaction is never rolled back automatically.",
                RECOVER_TRANSACTION, "Inspect and recover transaction", DebugHealthPolicy.ACTION_CONFIGURATION,
                RISK_ROOT_MUTATION, true);
        }
        if ("notifications".equals(id)) {
            return new Spec(id, "Repair notification setup",
                "Recreates missing XBS channels using their declared defaults, then opens Android notification settings when permission, app-level enablement, or a user-disabled channel still needs attention.",
                REPAIR_NOTIFICATIONS, "Repair notification setup", DebugHealthPolicy.ACTION_NOTIFICATIONS,
                RISK_LOCAL_MUTATION, true);
        }
        if ("event_monitor".equals(id)) {
            return new Spec(id, "Restart the event monitor",
                "Starts the configured foreground monitor service. The action is refused when monitoring is intentionally disabled.",
                RESTART_MONITOR, "Restart event monitor", DebugHealthPolicy.ACTION_RUNTIME,
                RISK_LOCAL_MUTATION, true);
        }
        return open(id, "Open the responsible workbench",
            "This check has no safe automatic repair. The guided flow opens the source screen without changing state.", item.actionId);
    }

    private static Spec open(String id, String title, String explanation, String actionId) {
        return new Spec(id, title, explanation, OPEN_SOURCE, "Open source workbench", actionId,
            RISK_NAVIGATION, false);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
