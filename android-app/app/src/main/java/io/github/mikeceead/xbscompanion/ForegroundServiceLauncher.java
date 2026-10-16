package io.github.mikeceead.xbscompanion;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

/** Starts foreground services while converting platform restrictions into durable evidence. */
final class ForegroundServiceLauncher {
    private ForegroundServiceLauncher() {}

    static ForegroundServiceLaunchResult promote(Service service, int notificationId, Notification notification, String purpose) {
        long now = System.currentTimeMillis();
        ForegroundServiceLaunchResult result;
        try {
            service.startForeground(notificationId, notification);
            result = ForegroundServiceLaunchResult.started(purpose, now);
        } catch (SecurityException error) {
            result = ForegroundServiceLaunchResult.failed(
                purpose, ForegroundServiceLaunchResult.SECURITY_ERROR, error, now);
        } catch (IllegalStateException error) {
            String simple = error.getClass().getSimpleName();
            String code = "ForegroundServiceStartNotAllowedException".equals(simple)
                ? ForegroundServiceLaunchResult.BACKGROUND_RESTRICTED
                : ForegroundServiceLaunchResult.ILLEGAL_STATE;
            result = ForegroundServiceLaunchResult.failed(purpose, code, error, now);
        } catch (RuntimeException error) {
            String simple = error.getClass().getSimpleName();
            String code = "ForegroundServiceStartNotAllowedException".equals(simple)
                ? ForegroundServiceLaunchResult.BACKGROUND_RESTRICTED
                : ForegroundServiceLaunchResult.RUNTIME_ERROR;
            result = ForegroundServiceLaunchResult.failed(purpose, code, error, now);
        }
        RuntimeNotificationStore.recordLaunch(service, result);
        return result;
    }

    static ForegroundServiceLaunchResult start(Context context, Intent intent, String purpose) {
        long now = System.currentTimeMillis();
        ForegroundServiceLaunchResult result;
        try {
            context.getApplicationContext().startForegroundService(new Intent(intent));
            result = ForegroundServiceLaunchResult.started(purpose, now);
        } catch (SecurityException error) {
            result = ForegroundServiceLaunchResult.failed(
                purpose, ForegroundServiceLaunchResult.SECURITY_ERROR, error, now);
        } catch (IllegalStateException error) {
            String simple = error.getClass().getSimpleName();
            String code = "ForegroundServiceStartNotAllowedException".equals(simple)
                ? ForegroundServiceLaunchResult.BACKGROUND_RESTRICTED
                : ForegroundServiceLaunchResult.ILLEGAL_STATE;
            result = ForegroundServiceLaunchResult.failed(purpose, code, error, now);
        } catch (RuntimeException error) {
            String simple = error.getClass().getSimpleName();
            String code = "ForegroundServiceStartNotAllowedException".equals(simple)
                ? ForegroundServiceLaunchResult.BACKGROUND_RESTRICTED
                : ForegroundServiceLaunchResult.RUNTIME_ERROR;
            result = ForegroundServiceLaunchResult.failed(purpose, code, error, now);
        }
        RuntimeNotificationStore.recordLaunch(context, result);
        return result;
    }
}
