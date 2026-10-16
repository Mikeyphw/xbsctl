package io.github.mikeceead.xbscompanion;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/** Process-level Material configuration for the native, root-only companion. */
public final class XbsApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (AppSettings.useMonet(this)) {
            DynamicColors.applyToActivitiesIfAvailable(this);
        }
        XbsHistoryStore.warmUp(this);
        XbsCompatibilityStore.cached(this);
    }
}
