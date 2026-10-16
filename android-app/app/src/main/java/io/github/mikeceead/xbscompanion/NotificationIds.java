package io.github.mikeceead.xbscompanion;

/** Namespaces dynamic notification and PendingIntent IDs away from foreground IDs. */
final class NotificationIds {
    private static final int RUN_PREFIX = 0x51000000;
    private static final int EVENT_PREFIX = 0x52000000;
    private static final int REQUEST_PREFIX = 0x53000000;
    private static final int MASK = 0x00ffffff;

    private NotificationIds() {}

    static int run(String runId) { return RUN_PREFIX | stableHash(runId); }
    static int event(String eventId) { return EVENT_PREFIX | stableHash(eventId); }
    static int request(String kind, String key) { return REQUEST_PREFIX | stableHash(safe(kind) + '\n' + safe(key)); }

    private static int stableHash(String value) {
        int hash = 0x811c9dc5;
        String safe = safe(value);
        for (int i = 0; i < safe.length(); i++) {
            hash ^= safe.charAt(i);
            hash *= 0x01000193;
        }
        return hash & MASK;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
