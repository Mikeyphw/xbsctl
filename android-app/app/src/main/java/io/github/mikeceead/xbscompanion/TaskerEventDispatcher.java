package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

final class TaskerEventDispatcher {
    private static final String[] TASKER_PACKAGES = {"net.dinglisch.android.taskerm", "net.dinglisch.android.tasker"};
    private TaskerEventDispatcher() {}

    static void eventChanged(Context context, XbsEvent event, XbsSnapshot snapshot) {
        if (!AppSettings.taskerEventsEnabled(context) || event == null) return;
        Bundle pass = TaskerConditionProtocol.passThrough(event, snapshot);
        if (pass != null) sendRequest(context, TaskerEventEditActivity.class.getName(), pass);
        sendRequest(context, TaskerStateEditActivity.class.getName(), null);
    }

    static void stateChanged(Context context) {
        if (!AppSettings.taskerEventsEnabled(context)) return;
        sendRequest(context, TaskerStateEditActivity.class.getName(), null);
    }

    private static void sendRequest(Context context, String activity, Bundle passThrough) {
        PackageManager pm = context.getPackageManager();
        for (String packageName : TASKER_PACKAGES) {
            try {
                pm.getPackageInfo(packageName, 0);
                if (!TaskerAuthorization.trustedHost(context, packageName)) continue;
                Intent intent = new Intent(TaskerConditionProtocol.ACTION_REQUEST_QUERY);
                intent.setPackage(packageName);
                intent.putExtra(TaskerConditionProtocol.EXTRA_ACTIVITY, activity);
                if (passThrough != null) intent.putExtra(TaskerConditionProtocol.EXTRA_PASS_THROUGH, passThrough);
                context.sendBroadcast(intent);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
    }
}
