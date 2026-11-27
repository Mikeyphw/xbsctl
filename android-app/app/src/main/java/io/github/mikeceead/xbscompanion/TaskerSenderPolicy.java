package io.github.mikeceead.xbscompanion;

/** Actual sender identity must be complete on API 34+, never inferred from one field. */
final class TaskerSenderPolicy {
    private TaskerSenderPolicy() {}
    static boolean matches(int api, String sender, int uid, String expected, int installedUid, boolean pinned) {
        if (expected == null || expected.isEmpty()) return false;
        boolean absent = (sender == null || sender.isEmpty()) && uid == -1;
        if (absent) return api >= 26 && api < 34 && pinned;
        return api >= 34 && expected.equals(sender) && uid >= 0 && installedUid >= 0 && uid == installedUid;
    }
}
