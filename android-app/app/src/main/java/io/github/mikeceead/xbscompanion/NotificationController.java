package io.github.mikeceead.xbscompanion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.provider.Settings;

import java.util.List;

final class NotificationController {
    static final int STATUS_ID = 0x584253;
    static final int COMMAND_SERVICE_ID = 0x584254;

    private static final String OLD_CHANNEL_RUNS = "xbs_runs";
    private static final String OLD_CHANNEL_STATUS = "xbs_status";
    static final String CHANNEL_COMMAND_PROGRESS = "xbs_command_progress_v2";
    static final String CHANNEL_COMMAND_RESULTS = "xbs_command_results_v2";
    static final String CHANNEL_STATUS = "xbs_runtime_status_v2";
    static final String CHANNEL_ALERTS = "xbs_alerts";

    private static final String GROUP_COMMAND_RESULTS = "xbs_command_results";
    private static final String GROUP_RUNTIME_STATUS = "xbs_runtime_status";

    private final Context context;

    NotificationController(Context context) {
        this.context = context.getApplicationContext();
        ensureChannels(this.context);
    }

    static void ensureChannels(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel progress = new NotificationChannel(
            CHANNEL_COMMAND_PROGRESS, "XBS active commands", NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("Foreground progress for active XBS root commands");
        quiet(progress, true);

        NotificationChannel results = new NotificationChannel(
            CHANNEL_COMMAND_RESULTS, app.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        results.setDescription(app.getString(R.string.notification_channel_description));
        results.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationChannel status = new NotificationChannel(
            CHANNEL_STATUS, app.getString(R.string.notification_runtime_channel_name), NotificationManager.IMPORTANCE_LOW);
        status.setDescription(app.getString(R.string.notification_runtime_channel_description));
        quiet(status, true);

        NotificationChannel alerts = new NotificationChannel(
            CHANNEL_ALERTS, app.getString(R.string.notification_alerts_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription(app.getString(R.string.notification_alerts_channel_description));
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        manager.createNotificationChannel(progress);
        manager.createNotificationChannel(results);
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(alerts);
        if (manager.getNotificationChannel(OLD_CHANNEL_RUNS) != null) manager.deleteNotificationChannel(OLD_CHANNEL_RUNS);
        if (manager.getNotificationChannel(OLD_CHANNEL_STATUS) != null) manager.deleteNotificationChannel(OLD_CHANNEL_STATUS);
    }

    boolean canPost() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return (Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            && manager.areNotificationsEnabled();
    }

    static Readiness readiness(Context context) {
        Context app = context.getApplicationContext();
        ensureChannels(app);
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        boolean permission = Build.VERSION.SDK_INT < 33
            || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean enabled = manager.areNotificationsEnabled();
        boolean progress = channelReady(manager, CHANNEL_COMMAND_PROGRESS);
        boolean results = channelReady(manager, CHANNEL_COMMAND_RESULTS);
        return new Readiness(permission, enabled, progress, results,
            channelReady(manager, CHANNEL_STATUS), channelReady(manager, CHANNEL_ALERTS),
            RuntimeNotificationStore.cached(app));
    }

    static Intent channelSettingsIntent(Context context, String channelId) {
        ensureChannels(context);
        return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
    }

    private static boolean channelReady(NotificationManager manager, String id) {
        NotificationChannel channel = manager.getNotificationChannel(id);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static final class Readiness {
        final boolean permissionGranted;
        final boolean appEnabled;
        final boolean commandProgressChannelReady;
        final boolean commandResultsChannelReady;
        final boolean commandChannelReady;
        final boolean runtimeChannelReady;
        final boolean alertsChannelReady;
        final RuntimeNotificationReport runtimeReport;

        Readiness(boolean permissionGranted, boolean appEnabled, boolean commandProgressChannelReady,
                  boolean commandResultsChannelReady, boolean runtimeChannelReady, boolean alertsChannelReady,
                  RuntimeNotificationReport runtimeReport) {
            this.permissionGranted = permissionGranted;
            this.appEnabled = appEnabled;
            this.commandProgressChannelReady = commandProgressChannelReady;
            this.commandResultsChannelReady = commandResultsChannelReady;
            this.commandChannelReady = commandProgressChannelReady && commandResultsChannelReady;
            this.runtimeChannelReady = runtimeChannelReady;
            this.alertsChannelReady = alertsChannelReady;
            this.runtimeReport = runtimeReport == null ? RuntimeNotificationReport.empty() : runtimeReport;
        }

        String summary() {
            if (!permissionGranted) return "Notification permission is denied";
            if (!appEnabled) return "App notifications are disabled";
            if (!commandProgressChannelReady || !commandResultsChannelReady || !runtimeChannelReady || !alertsChannelReady) {
                return "One or more notification channels are disabled";
            }
            return runtimeReport.summary();
        }
    }

    Notification buildStatus(XbsSnapshot snapshot) {
        EventBridgeReport bridge = EventBridgeStore.cached(context);
        long issuedAt = System.currentTimeMillis();
        String token = RuntimeNotificationStore.publish(context, snapshot, bridge);
        String actionNonce = RuntimeNotificationStore.actionNonce(context);
        boolean actionsAllowed = RuntimeNotificationPolicy.mutationActionsAllowed(snapshot, bridge, !token.isEmpty());
        boolean securityBlocked = bridge.securityFailure;

        Intent open = new Intent(context, MainActivity.class).putExtra("page", 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = activityStack("runtime-open", "status", open);
        String transport = RuntimeNotificationPolicy.transportLabel(bridge);
        String title = securityBlocked ? "XBS · runtime verification blocked" : "XBS · " + snapshot.headline();
        String text = securityBlocked ? bridge.compactSummary() : snapshot.summary() + " · " + transport;
        Notification.Builder builder = builder(CHANNEL_STATUS)
            .setSmallIcon(securityBlocked ? android.R.drawable.stat_notify_error : R.drawable.ic_devtool_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(securityBlocked ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setLocalOnly(true)
            .setColorized(false)
            .setGroup(GROUP_RUNTIME_STATUS);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (AppSettings.detailedStatusNotification(context)) {
            String detail = text;
            if (snapshot.active.size() > 1) detail += "\nActive events (" + snapshot.active.size() + "): " + XbsSnapshot.join(snapshot.active);
            if (snapshot.lastChange != null) detail += "\nLast change: " + snapshot.lastChange.message;
            detail += "\nTransport: " + bridge.compactSummary();
            builder.setStyle(new Notification.BigTextStyle().bigText(detail));
        }
        if (securityBlocked) {
            builder.addAction(openDiagnosticsAction("bridge-security"));
            return builder.build();
        }
        if (actionsAllowed) {
            if (snapshot.paused) builder.addAction(action("resume", "Resume", "", R.drawable.ic_xbs_resume, token, actionNonce, issuedAt));
            else builder.addAction(action("pause", "Pause", "", R.drawable.ic_xbs_pause, token, actionNonce, issuedAt));
            if (snapshot.safeMode) builder.addAction(action("safe_off", "Exit safe", "", R.drawable.ic_xbs_shield_off, token, actionNonce, issuedAt));
            else builder.addAction(action("safe_on", "Safe mode", "", R.drawable.ic_xbs_shield, token, actionNonce, issuedAt));
            if (snapshot.active.size() == 1) {
                String event = snapshot.active.get(0);
                builder.addAction(action("event_stop", "Stop " + shortName(event), event, R.drawable.ic_xbs_stop, token, actionNonce, issuedAt));
            } else builder.addAction(openEventsAction());
        } else builder.addAction(openDiagnosticsAction("runtime-unavailable"));
        builder.addAction(action("monitor_stop", "Stop monitor", "", R.drawable.ic_xbs_stop, token, actionNonce, issuedAt));
        return builder.build();
    }

    void postStatus(XbsSnapshot snapshot) {
        if (canPost()) context.getSystemService(NotificationManager.class).notify(STATUS_ID, buildStatus(snapshot));
    }

    void cancelStatus() {
        context.getSystemService(NotificationManager.class).cancel(STATUS_ID);
    }

    /** Posts only terminal per-run results. Active work is represented by one consolidated FGS notification. */
    void post(RunRecord record) {
        if (record == null || record.runId == null || record.runId.trim().isEmpty()) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int notificationId = NotificationIds.run(record.runId);
        if (NotificationUxPolicy.isActive(record.status)) {
            manager.cancel(notificationId);
            return;
        }
        if (!NotificationUxPolicy.shouldPostTerminal(
            AppSettings.commandResultNotificationMode(context), record.status, record.exitCode) || !canPost()) {
            manager.cancel(notificationId);
            return;
        }

        Intent open = runDetailIntent(record.runId);
        PendingIntent pending = activityStack("run-open", record.runId, open);
        boolean failure = NotificationUxPolicy.isFailureLike(record.status, record.exitCode);
        String state = NotificationUxPolicy.terminalLabel(record.status, record.exitCode);
        String message = ResultFormatter.notificationText(record.message);
        Notification.Builder builder = builder(CHANNEL_COMMAND_RESULTS)
            .setSmallIcon(failure ? android.R.drawable.stat_notify_error : R.drawable.ic_devtool_notification)
            .setContentTitle("XBS · " + state)
            .setContentText(record.title + (message.isEmpty() ? "" : " · " + message))
            .setStyle(new Notification.BigTextStyle().bigText(record.title + (message.isEmpty() ? "" : "\n" + message)))
            .setSubText(sourceLabel(record))
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setCategory(failure ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setGroup(GROUP_COMMAND_RESULTS)
            .setWhen(record.finishedAt > 0 ? record.finishedAt : System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending);
        manager.notify(notificationId, builder.build());
    }

    Notification buildCommandServiceStatus(List<RunRecord> active, String fallbackDetail) {
        int count = active == null ? 0 : active.size();
        RunRecord single = count == 1 ? active.get(0) : null;
        Intent open = single == null ? new Intent(context, ActivityWorkbenchActivity.class) : runDetailIntent(single.runId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = activityStack("command-service", single == null ? "all" : single.runId, open);

        String title;
        String text;
        if (single != null) {
            title = "XBS · " + ("cancelling".equals(single.status) ? "cancelling command" : "root command running");
            text = single.title + " · " + ResultFormatter.notificationText(single.message);
        } else if (count > 1) {
            title = "XBS · " + count + " root commands running";
            text = commandList(active);
        } else {
            title = "XBS · preparing root command";
            text = ResultFormatter.notificationText(fallbackDetail);
        }

        Notification.Builder builder = builder(CHANNEL_COMMAND_PROGRESS)
            .setSmallIcon(R.drawable.ic_devtool_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setContentIntent(pending);
        if (single != null && "running".equals(single.status)) { Notification.Action cancel=cancelRun(single.runId); if(cancel!=null)builder.addAction(cancel); }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    void postCommandServiceStatus(String detail) {
        context.getSystemService(NotificationManager.class).notify(
            COMMAND_SERVICE_ID, buildCommandServiceStatus(RunStore.active(context), detail));
    }

    void postImportant(XbsEvent event) {
        if (!canPost() || event == null) return;
        boolean diagnosticDestination = "runtime".equals(event.type)
            && ("unavailable".equals(event.state) || "error".equals(event.severity));
        Intent open = diagnosticDestination
            ? new Intent(context, RootDiagnosticsActivity.class)
            : new Intent(context, MainActivity.class).putExtra("page", 0);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = activityStack("event-open", event.id, open);
        boolean error = "error".equals(event.severity) || "unavailable".equals(event.state);
        Notification.Builder builder = builder(CHANNEL_ALERTS)
            .setSmallIcon(error ? android.R.drawable.stat_notify_error : R.drawable.ic_devtool_notification)
            .setContentTitle(eventTitle(event))
            .setContentText(event.message)
            .setStyle(new Notification.BigTextStyle().bigText(event.message))
            .setCategory(error ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setWhen(event.timestamp > 0 ? event.timestamp * 1000L : System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending);
        if (diagnosticDestination) builder.addAction(openDiagnosticsAction(event.id));
        context.getSystemService(NotificationManager.class).notify(NotificationIds.event(event.id), builder.build());
    }

    private Notification.Action cancelRun(String runId) {
        RuntimeNotificationStore.CancelAction token=RuntimeNotificationStore.publishCancelAction(context,runId);
        if(!token.ready())return null;
        String identity=runId+"/"+token.issuedAt+"/"+token.nonce;
        Intent intent = new Intent(context, NotificationActionReceiver.class)
            .setAction(NotificationActionReceiver.ACTION)
            .setData(Uri.parse("xbs-notification://cancel/"+identity))
            .putExtra(NotificationActionReceiver.EXTRA_ACTION, "cancel_run")
            .putExtra(NotificationActionReceiver.EXTRA_RUN_ID, runId)
            .putExtra(NotificationActionReceiver.EXTRA_NONCE, token.nonce)
            .putExtra(NotificationActionReceiver.EXTRA_ISSUED_AT, token.issuedAt);
        PendingIntent pending = PendingIntent.getBroadcast(context, NotificationIds.request("cancel-run", identity), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(R.drawable.ic_xbs_stop, "Cancel", pending).build();
    }

    private Notification.Action action(String actionId, String label, String event, int icon, String token, String nonce, long issuedAt) {
        String identity = actionId + "/" + Integer.toHexString(event == null ? 0 : event.hashCode())
            + "/" + token + "/" + issuedAt;
        Intent intent = new Intent(context, NotificationActionReceiver.class)
            .setAction(NotificationActionReceiver.ACTION)
            .setData(Uri.parse("xbs-notification://runtime/" + identity))
            .putExtra(NotificationActionReceiver.EXTRA_ACTION, actionId)
            .putExtra(NotificationActionReceiver.EXTRA_EVENT, event)
            .putExtra(NotificationActionReceiver.EXTRA_TOKEN, token)
            .putExtra(NotificationActionReceiver.EXTRA_NONCE, nonce)
            .putExtra(NotificationActionReceiver.EXTRA_ISSUED_AT, issuedAt);
        PendingIntent pending = PendingIntent.getBroadcast(context, NotificationIds.request("runtime-action", identity), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(icon, label, pending).build();
    }

    private Notification.Action openEventsAction() {
        Intent intent = new Intent(context, EventControlActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = activityStack("open", "events", intent);
        return new Notification.Action.Builder(R.drawable.ic_xbs_events, "Events", pending).build();
    }

    private Notification.Action openDiagnosticsAction(String eventId) {
        Intent intent = new Intent(context, RootDiagnosticsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = activityStack("diagnostics", eventId, intent);
        return new Notification.Action.Builder(R.drawable.ic_xbs_shield, "Diagnostics", pending).build();
    }

    private PendingIntent activityStack(String kind, String key, Intent target) {
        TaskStackBuilder stack = TaskStackBuilder.create(context);
        if (!(target.getComponent() != null && MainActivity.class.getName().equals(target.getComponent().getClassName()))) {
            stack.addNextIntent(new Intent(context, MainActivity.class).putExtra("page", 0));
        }
        stack.addNextIntent(target);
        return stack.getPendingIntent(NotificationIds.request(kind, key),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent runDetailIntent(String runId) {
        return new Intent(context, ActivityWorkbenchActivity.class)
            .putExtra("run_id", runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private Notification.Builder builder(String channel) { return new Notification.Builder(context, channel); }

    private static void quiet(NotificationChannel channel, boolean noBadge) {
        channel.setShowBadge(!noBadge);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
    }

    private static String sourceLabel(RunRecord record) {
        String source = record.source == null ? "" : record.source.trim();
        return source.isEmpty() ? "XBS command" : "XBS · " + source;
    }

    private static String commandList(List<RunRecord> active) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(4, active == null ? 0 : active.size());
        for (int i = 0; i < limit; i++) {
            if (out.length() > 0) out.append('\n');
            RunRecord run = active.get(i);
            out.append("• ").append(run.title);
            if ("cancelling".equals(run.status)) out.append(" · cancelling");
        }
        if (active != null && active.size() > limit) out.append("\n+").append(active.size() - limit).append(" more");
        return out.length() == 0 ? "Preparing root command…" : out.toString();
    }

    private static String eventTitle(XbsEvent event) {
        if ("runtime".equals(event.type) && "unavailable".equals(event.state)) return "XBS runtime unavailable";
        if ("safe_mode".equals(event.type) && "active".equals(event.state)) return "XBS safe mode enabled";
        if ("runtime".equals(event.type) && "paused".equals(event.state)) return "XBS runtime paused";
        return "XBS important event";
    }

    private static String shortName(String name) {
        String pretty = XbsSnapshot.pretty(name);
        return pretty.length() > 12 ? pretty.substring(0, 11) + "…" : pretty;
    }
}
