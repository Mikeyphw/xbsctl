package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    static final int TOAST_OFF = 0;
    static final int TOAST_TRANSITIONS = 1;
    static final int TOAST_IMPORTANT = 2;
    static final int TOAST_ALL = 3;

    private static final String PREFS = "xbs_companion_settings";
    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean useMonet(Context context) { return prefs(context).getBoolean("monet", true); }
    static void setUseMonet(Context context, boolean value) { prefs(context).edit().putBoolean("monet", value).apply(); }
    static int page(Context context) { return prefs(context).getInt("page", 0); }
    static void setPage(Context context, int value) { prefs(context).edit().putInt("page", value).apply(); }

    static boolean monitorEnabled(Context context) { return prefs(context).getBoolean("monitor_enabled", false); }
    static void setMonitorEnabled(Context context, boolean value) { prefs(context).edit().putBoolean("monitor_enabled", value).apply(); }

    static boolean detailedStatusNotification(Context context) { return prefs(context).getBoolean("detailed_notification", true); }
    static void setDetailedStatusNotification(Context context, boolean value) { prefs(context).edit().putBoolean("detailed_notification", value).apply(); }

    static int commandResultNotificationMode(Context context) {
        return NotificationUxPolicy.normalizeResultMode(prefs(context).getInt("command_result_notifications", NotificationUxPolicy.RESULTS_FAILURES_ONLY));
    }
    static void setCommandResultNotificationMode(Context context, int value) {
        prefs(context).edit().putInt("command_result_notifications", NotificationUxPolicy.normalizeResultMode(value)).apply();
    }
    static String commandResultNotificationModeLabel(Context context) {
        return NotificationUxPolicy.resultModeLabel(commandResultNotificationMode(context));
    }

    static int toastMode(Context context) { return prefs(context).getInt("toast_mode", TOAST_TRANSITIONS); }
    static void setToastMode(Context context, int value) { prefs(context).edit().putInt("toast_mode", value).apply(); }

    static boolean taskerEventsEnabled(Context context) { return prefs(context).getBoolean("tasker_events", true); }
    static void setTaskerEventsEnabled(Context context, boolean value) { prefs(context).edit().putBoolean("tasker_events", value).apply(); }

    static int pollSeconds(Context context) { return Math.max(2, Math.min(30, prefs(context).getInt("poll_seconds", 3))); }
    static void setPollSeconds(Context context, int value) { prefs(context).edit().putInt("poll_seconds", Math.max(2, Math.min(30, value))).apply(); }

    static boolean startOnBoot(Context context) { return prefs(context).getBoolean("start_on_boot", true); }
    static void setStartOnBoot(Context context, boolean value) { prefs(context).edit().putBoolean("start_on_boot", value).apply(); }


    static int historyRetention(Context context) {
        int value = prefs(context).getInt("history_retention", 120);
        if (value <= 30) return 30;
        if (value <= 60) return 60;
        if (value <= 120) return 120;
        return 240;
    }
    static void setHistoryRetention(Context context, int value) {
        int normalized = value <= 30 ? 30 : value <= 60 ? 60 : value <= 120 ? 120 : 240;
        prefs(context).edit().putInt("history_retention", normalized).apply();
    }
    static int nextHistoryRetention(Context context) {
        switch (historyRetention(context)) {
            case 30: return 60;
            case 60: return 120;
            case 120: return 240;
            default: return 30;
        }
    }

    static String toastModeLabel(Context context) {
        switch (toastMode(context)) {
            case TOAST_OFF: return "Off";
            case TOAST_IMPORTANT: return "Safe mode and failures";
            case TOAST_ALL: return "All changes";
            default: return "Event start and stop";
        }
    }
}
