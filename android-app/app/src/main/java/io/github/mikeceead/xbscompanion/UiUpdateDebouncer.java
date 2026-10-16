package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Coalesces bursts of state broadcasts into one main-thread rebuild. */
final class UiUpdateDebouncer {
    private static final long DELAY_MS=96L;
    private final Context context; private final Handler handler=new Handler(Looper.getMainLooper()); private final Runnable action;
    private boolean pending;
    UiUpdateDebouncer(Context context,Runnable action){this.context=context.getApplicationContext();this.action=action;}
    void request(){if(pending){PerformanceStore.recordCoalesced(context);return;}pending=true;handler.postDelayed(()->{pending=false;action.run();},DELAY_MS);}
    void cancel(){pending=false;handler.removeCallbacksAndMessages(null);}
}
