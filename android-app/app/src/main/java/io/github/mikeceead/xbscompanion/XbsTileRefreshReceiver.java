package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;

/** Only app-private status/run changes may request a SystemUI re-read. No root calls. */
public final class XbsTileRefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        boolean scoped=context.getPackageName().equals(intent.getPackage())
            || intent.getComponent()!=null && context.getPackageName().equals(intent.getComponent().getPackageName());
        if(!scoped)return;
        String action=intent.getAction();
        if(!XbsStateStore.ACTION_STATE_UPDATED.equals(action) && !RunStore.ACTION_UPDATED.equals(action))return;
        Class<?>[] tiles={XbsPauseTileService.class,XbsSafeModeTileService.class,
            XbsSaverTileService.class,XbsProfilesTileService.class,XbsRestoreDefaultsTileService.class};
        for(Class<?> type:tiles){
            try{TileService.requestListeningState(context,new ComponentName(context,type));}
            catch(RuntimeException ignored){/* SystemUI/OEM may decline listening requests. */}
        }
    }
}
