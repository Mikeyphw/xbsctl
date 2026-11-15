package io.github.mikeceead.xbscompanion;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** SystemUI-facing lifecycle only. No blocking command, Room read or daemon access on main. */
public abstract class XbsQuickTileService extends TileService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final XbsTileDecision.Kind kind;
    private volatile boolean destroyed=false;
    protected XbsQuickTileService(XbsTileDecision.Kind kind){this.kind=kind;}

    @Override public void onTileAdded(){super.onTileAdded();XbsTileLifecycleStore.record(this,kind,"added");refresh();}
    @Override public void onTileRemoved(){XbsTileLifecycleStore.record(this,kind,"removed");super.onTileRemoved();}
    @Override public void onStartListening(){super.onStartListening();XbsTileLifecycleStore.record(this,kind,"listening");refresh();}
    @Override public void onStopListening(){XbsTileLifecycleStore.record(this,kind,"stopped");super.onStopListening();}
    @Override public void onClick(){
        super.onClick();
        XbsTileLifecycleStore.record(this,kind,"clicked");
        if(isLocked() || destroyed){refresh();return;}
        if(kind==XbsTileDecision.Kind.RESTORE){openConfiguration();return;}
        if(kind==XbsTileDecision.Kind.SAVER || kind==XbsTileDecision.Kind.PROFILES){
            dispatch(()->{
                try{
                    if(!XbsTileController.configured(getApplicationContext(),kind)
                        || XbsTileController.observe(getApplicationContext(),kind).phase==XbsTileDecision.Phase.ACTIVE)
                        main.post(this::openConfiguration);
                    else XbsTileController.requestToggle(getApplicationContext(),kind);
                }catch(RuntimeException rejected){/* Corrupt state fails closed. */}
                refresh();
            });return;
        }
        dispatch(()->{
            try{XbsTileController.requestToggle(getApplicationContext(),kind);}
            catch(RuntimeException rejected){/* No optimistic state change. */}
            refresh();
        });
    }
    private void openConfiguration(){
        if(destroyed || isLocked())return;
        Intent intent=new Intent(this,XbsTileSettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(XbsTileSettingsActivity.EXTRA_KIND,kind.name());
        try{
            if(Build.VERSION.SDK_INT>=34){
                PendingIntent pending=PendingIntent.getActivity(this,3000+kind.ordinal(),intent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pending);
            }else startActivityAndCollapse(intent);
        }catch(RuntimeException denied){/* SystemUI may refuse activity launch on OEM/locked display. */}
    }
    private void dispatch(Runnable task){
        if(destroyed || worker.isShutdown())return;
        try{worker.execute(task);}catch(RejectedExecutionException ignored){}
    }
    private void refresh(){
        dispatch(()->{
            XbsTileDecision state;
            try{state=XbsTileController.observe(getApplicationContext(),kind);}
            catch(RuntimeException corrupt){state=XbsTileDecision.evaluate(kind,false,false,false,false,false,true);}
            final XbsTileDecision decision=state;
            main.post(()->{
                if(destroyed)return;
                Tile tile=getQsTile();if(tile==null)return;
                tile.setIcon(Icon.createWithResource(this,android.R.drawable.ic_menu_manage));
                tile.setLabel(decision.title);
                if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(decision.detail);
                tile.setState(decision.phase==XbsTileDecision.Phase.ACTIVE?Tile.STATE_ACTIVE:
                    decision.phase==XbsTileDecision.Phase.INACTIVE?Tile.STATE_INACTIVE:Tile.STATE_UNAVAILABLE);
                tile.updateTile();
            });
        });
    }
    @Override public void onDestroy(){
        destroyed=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
