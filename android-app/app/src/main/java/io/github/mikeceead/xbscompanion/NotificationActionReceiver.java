package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NotificationActionReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.mikeceead.xbscompanion.NOTIFICATION_ACTION";
    static final String EXTRA_ACTION = "action";
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_RUN_ID = "run_id";
    static final String EXTRA_TOKEN = "notification_token";
    static final String EXTRA_ISSUED_AT = "notification_issued_at";
    static final String EXTRA_NONCE = "notification_nonce";

    private static final Map<String, Long> RECENT_ACTIONS = new ConcurrentHashMap<>();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() == null
            || !context.getPackageName().equals(intent.getComponent().getPackageName())) return;
        String actionId = safe(intent.getStringExtra(EXTRA_ACTION));
        String event = safe(intent.getStringExtra(EXTRA_EVENT));
        String runId = safe(intent.getStringExtra(EXTRA_RUN_ID));
        String key = NotificationUxPolicy.actionKey(actionId, event, runId);
        if (!accept(key, System.currentTimeMillis())) {
            RuntimeNotificationStore.recordAction(context, "duplicate_tap", actionId);
            return;
        }

        String nonce = safe(intent.getStringExtra(EXTRA_NONCE));
        long issuedAt = intent.getLongExtra(EXTRA_ISSUED_AT, 0L);
        if ("cancel_run".equals(actionId)) {
            if (!RuntimeNotificationStore.consumeCancelAction(context, runId, nonce, issuedAt, System.currentTimeMillis())) {
                RuntimeNotificationStore.recordAction(context, "stale_or_replayed_action", "cancel_run");
                return;
            }
            RunRecord record = RunStore.find(context, runId);
            if (record == null || !NotificationUxPolicy.isActive(record.status)) {
                RuntimeNotificationStore.recordAction(context, "stale_run", runId);
                return;
            }
            ForegroundServiceLaunchResult launch = RootCommandService.requestCancel(context, runId);
            RuntimeNotificationStore.recordAction(context, launch.started ? "allowed" : launch.code, "cancel_run");
            return;
        }


        if (!RuntimeNotificationStore.consumeAction(context, nonce, key)) {
            RuntimeNotificationStore.recordAction(context, "stale_or_replayed_action", actionId);
            return;
        }

        XbsSnapshot snapshot = XbsStateStore.current(context);
        EventBridgeReport bridge = EventBridgeStore.cached(context);
        RuntimeNotificationReport report = RuntimeNotificationStore.cached(context);
        RuntimeNotificationPolicy.Decision decision = RuntimeNotificationPolicy.evaluateAction(
            actionId, event, intent.getStringExtra(EXTRA_TOKEN), intent.getLongExtra(EXTRA_ISSUED_AT, 0L),
            System.currentTimeMillis(), RuntimeNotificationPolicy.token(snapshot, bridge), bridge,
            snapshot.paused, snapshot.safeMode, snapshot.active);
        if (!decision.allowed) {
            RuntimeNotificationStore.recordAction(context, decision.code, actionId);
            new NotificationController(context).postStatus(snapshot);
            return;
        }
        if ("monitor_stop".equals(actionId)) {
            EventMonitorService.stop(context);
            RuntimeNotificationStore.recordAction(context, "allowed", "monitor_stop");
            return;
        }

        SuiteAction action = SuiteCatalog.find("xbs", actionId);
        if (action == null) {
            RuntimeNotificationStore.recordAction(context, "unknown_action", actionId);
            return;
        }
        CommandLaunchResult result = RootCommandRunner.run(context, action, event, null, ExecutionSource.NOTIFICATION);
        RuntimeNotificationStore.recordAction(context, result.started ? "allowed" : "launch_failed",
            result.started ? actionId : result.error);
        if (result.started) EventMonitorService.refresh(context);
    }

    static boolean accept(String key, long now) {
        Long previous = RECENT_ACTIONS.put(key, now);
        if (previous == null) { prune(now); return true; }
        if (now - previous >= NotificationUxPolicy.ACTION_DEDUPE_WINDOW_MS) { prune(now); return true; }
        RECENT_ACTIONS.put(key, previous);
        return false;
    }

    private static void prune(long now) {
        if (RECENT_ACTIONS.size() < 32) return;
        RECENT_ACTIONS.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
