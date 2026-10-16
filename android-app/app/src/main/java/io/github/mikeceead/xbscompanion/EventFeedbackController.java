package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

final class EventFeedbackController {
    private static long lastToastAt;
    private static String lastToast = "";
    private EventFeedbackController() {}

    static void onEvent(Context context, XbsEvent event) {
        int mode = AppSettings.toastMode(context);
        if (mode == AppSettings.TOAST_OFF || event == null || event.replayed) return;
        if (mode == AppSettings.TOAST_TRANSITIONS && !event.isTransition()) return;
        if (mode == AppSettings.TOAST_IMPORTANT && !event.isImportant()) return;

        long now = System.currentTimeMillis();
        String text = "XBS: " + event.message;
        if (text.equals(lastToast) && now - lastToastAt < 5000) return;
        if (!event.isImportant() && now - lastToastAt < 1200) return;
        lastToast = text;
        lastToastAt = now;
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }
}
