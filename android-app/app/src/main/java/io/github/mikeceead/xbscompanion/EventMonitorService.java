package io.github.mikeceead.xbscompanion;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EventMonitorService extends Service {
    static final String ACTION_START = "io.github.mikeceead.xbscompanion.MONITOR_START";
    static final String ACTION_STOP = "io.github.mikeceead.xbscompanion.MONITOR_STOP";
    static final String ACTION_REFRESH = "io.github.mikeceead.xbscompanion.MONITOR_REFRESH";

    private static final AtomicBoolean SERVICE_ACTIVE = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean forceRefresh;
    private NotificationController notifications;

    static boolean isServiceActive() { return SERVICE_ACTIVE.get(); }

    static ForegroundServiceLaunchResult start(Context context) {
        Intent intent = new Intent(context, EventMonitorService.class).setAction(ACTION_START);
        return ForegroundServiceLauncher.start(context, intent, "event_monitor_start");
    }

    static void stop(Context context) {
        AppSettings.setMonitorEnabled(context, false);
        context.stopService(new Intent(context, EventMonitorService.class));
        new NotificationController(context).cancelStatus();
    }

    static ForegroundServiceLaunchResult refresh(Context context) {
        Intent intent = new Intent(context, EventMonitorService.class).setAction(ACTION_REFRESH);
        if (AppSettings.monitorEnabled(context)) {
            return ForegroundServiceLauncher.start(context, intent, "event_monitor_refresh");
        }
        return new ForegroundServiceLaunchResult(false, "event_monitor_refresh", "monitor_disabled",
            "Persistent monitor is disabled", System.currentTimeMillis());
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = new NotificationController(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AppSettings.setMonitorEnabled(this, false);
            running.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifications.cancelStatus();
            stopSelf();
            return START_NOT_STICKY;
        }
        forceRefresh = true;
        XbsSnapshot cached = XbsStateStore.current(this);
        ForegroundServiceLaunchResult promotion = ForegroundServiceLauncher.promote(this,
            NotificationController.STATUS_ID, notifications.buildStatus(cached), "event_monitor_promote");
        if (!promotion.started) {
            SERVICE_ACTIVE.set(false);
            running.set(false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        SERVICE_ACTIVE.set(true);
        RuntimeNotificationStore.recordService(this, true, "Event monitor service active");
        if (running.compareAndSet(false, true)) executor.execute(this::loop);
        return START_STICKY;
    }

    private void loop() {
        while (running.get() && AppSettings.monitorEnabled(this)) {
            XbsSnapshot current = XbsRootApi.readSnapshot(this);
            if (forceRefresh || !current.equivalentState(XbsStateStore.current(this))) {
                XbsStateProcessor.process(this, current, true);
                forceRefresh = false;
            } else {
                // The state can be equivalent while the latest transition ID changed.
                XbsSnapshot stored = XbsStateStore.current(this);
                String currentId = current.lastChange == null ? "" : current.lastChange.id;
                String storedId = stored.lastChange == null ? "" : stored.lastChange.id;
                if (!currentId.equals(storedId)) XbsStateProcessor.process(this, current, true);
            }
            try { Thread.sleep(AppSettings.pollSeconds(this) * 1000L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        running.set(false);
        SERVICE_ACTIVE.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        RuntimeNotificationStore.recordTimeout(this, "Event monitor foreground service timed out");
        running.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        SERVICE_ACTIVE.set(false);
        RuntimeNotificationStore.recordService(this, false, "Event monitor service stopped");
        running.set(false);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
