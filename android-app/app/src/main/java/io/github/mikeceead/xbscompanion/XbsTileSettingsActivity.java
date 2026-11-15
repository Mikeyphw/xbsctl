package io.github.mikeceead.xbscompanion;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Explicit in-app configuration. No privileged command or profile is inferred from a tile label. */
public final class XbsTileSettingsActivity extends AppCompatActivity {
    static final String EXTRA_KIND="gx04_kind";
    private final XbsTileDecision.Kind[] kinds={XbsTileDecision.Kind.SAVER,
        XbsTileDecision.Kind.PROFILES,XbsTileDecision.Kind.PAUSE,XbsTileDecision.Kind.SAFE,
        XbsTileDecision.Kind.RESTORE};
    private LinearLayout content;
    // Never run RootCommandRunner or Room-backed command-history checks on the UI thread.
    private final ExecutorService commandWorker=Executors.newSingleThreadExecutor();
    private void dispatchCommand(Runnable task){
        try{commandWorker.execute(task);}
        catch(RejectedExecutionException ignored){/* Finishing activity: fail closed, no replay. */}
    }
    @Override protected void onDestroy(){
        commandWorker.shutdown();
        super.onDestroy();
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*getResources().getDisplayMetrics().density);
        content.setPadding(pad,pad,pad,pad);scroll.addView(content);setContentView(scroll);
        render();
    }
    private void label(String s) {TextView text=new TextView(this);text.setText(s);text.setTextSize(16);
        text.setPadding(0,12,0,12);content.addView(text);}
    private void button(String s,View.OnClickListener click) {Button b=new Button(this);
        b.setText(s);b.setOnClickListener(click);content.addView(b);}
    private XbsTileDecision.Kind requestedKind() {
        String raw=getIntent()==null?"":getIntent().getStringExtra(EXTRA_KIND);
        if(raw!=null)for(XbsTileDecision.Kind kind:kinds)if(kind.name().equals(raw))return kind;
        return XbsTileDecision.Kind.PROFILES;
    }
    private void render() {
        content.removeAllViews();label("XBS Quick Settings · verified state only");
        XbsSnapshot snapshot=XbsStateStore.current(this);
        EventBridgeReport bridge=EventBridgeStore.cached(this);
        boolean attested=XbsTileController.trusted(snapshot,bridge,System.currentTimeMillis());
        label(attested ? "Authenticated runtime state available" : "Runtime state is unverified. Event selection is locked until refreshed.");
        for(XbsTileDecision.Kind kind:new XbsTileDecision.Kind[]{XbsTileDecision.Kind.SAVER,XbsTileDecision.Kind.PROFILES}){
            final String chosen=XbsTileConfiguration.selected(this,kind);
            label(kind==XbsTileDecision.Kind.SAVER?"Saver tile · "+(chosen.isEmpty()?"not configured":chosen)
                :"Profiles tile · "+(chosen.isEmpty()?"not configured":chosen));
            button("Choose "+kind.name().toLowerCase(java.util.Locale.US)+" event",view->choose(kind));
            if(!chosen.isEmpty()) {
                if(attested && snapshot.active.contains(chosen))
                    button("Stop "+chosen+" (confirmation required)",view->confirmStop(kind,chosen));
                button("Clear "+kind.name().toLowerCase(java.util.Locale.US)+" selection",
                    view->{XbsTileConfiguration.clear(this,kind);render();});
            }
        }
        label("Pause and Safe Mode tiles use the existing XBS allowlisted commands. They never toggle optimistically.");
        label("Add XBS tiles to Quick Settings (one request at a time)");
        for(XbsTileDecision.Kind kind:kinds){
            button("Add "+kind.name().toLowerCase(java.util.Locale.US)+" tile",v->requestTile(kind));
        }
        button("Open XBS dashboard",v->startActivity(new Intent(this,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        for(XbsTileDecision.Kind kind:kinds){
            if(XbsTileController.recoveryNeedsReview(this,kind))
                button("Review recovered "+kind.name()+" tile",v->confirmRecovery(kind));
        }
        label("Restore Defaults: XBS currently has no allowlisted runtime restore command. No daemon configuration will be changed from Quick Settings.");
        button("Review local tile shortcut reset",v->confirmLocalReset());
    }
    private void choose(XbsTileDecision.Kind kind) {
        XbsSnapshot snapshot=XbsStateStore.current(this);
        EventBridgeReport bridge=EventBridgeStore.cached(this);
        long now=System.currentTimeMillis();
        if(!XbsTileController.trusted(snapshot,bridge,now)){
            message("Event selection blocked","A fresh authenticated runtime snapshot is required.");return;
        }
        List<String> events=XbsTileConfiguration.candidates(snapshot);
        if(events.isEmpty()){message("No events available","Create an XBS event in the app, then refresh runtime state.");return;}
        String[] names=events.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Assign event to "+kind.name())
            .setItems(names,(dialog,index)->{
                XbsSnapshot latest=XbsStateStore.current(this);
                EventBridgeReport current=EventBridgeStore.cached(this);
                boolean saved=XbsTileConfiguration.select(this,kind,names[index],latest,current,System.currentTimeMillis());
                message(saved?"Shortcut configured":"Selection rejected",saved?"Only verified "+names[index]+" can be toggled.":"The authenticated state changed; refresh and retry.");
                render();
            }).setNegativeButton("Cancel",null).show();
    }
    private void confirmStop(XbsTileDecision.Kind kind,String event) {
        new AlertDialog.Builder(this).setTitle("Confirm stopping XBS event")
            .setMessage("Stop the configured event '"+event+"'? This changes active runtime state. A verified update is required before Quick Settings reports success.")
            .setNegativeButton("Cancel",null).setPositiveButton("Stop event",(d,w)->{
                if(!event.equals(XbsTileConfiguration.selected(this,kind))) {
                    message("Event changed","The selected event changed. No command was sent.");return;
                }
                // Confirmation is collected on the main thread; the actual allowlisted
                // root command is dispatched asynchronously with state revalidation.
                dispatchCommand(()->{
                    boolean same=event.equals(XbsTileConfiguration.selected(getApplicationContext(),kind));
                    boolean accepted=same && XbsTileController.requestToggle(getApplicationContext(),kind,true);
                    runOnUiThread(()->{
                        if(isFinishing() || isDestroyed())return;
                        message(accepted?"Stop requested":"Stop rejected",accepted?
                            "The command is pending. Check verified runtime state for completion.":
                            "The selected event, runtime evidence, or another command prevented this request.");
                        render();
                    });
                });
            }).show();
    }
    private void requestTile(XbsTileDecision.Kind kind) {
        if(Build.VERSION.SDK_INT<33){message("Add tile manually","Open Quick Settings, choose Edit, then add the XBS tile.");return;}
        Class<?> tile;
        switch(kind){
            case PAUSE:tile=XbsPauseTileService.class;break;
            case SAFE:tile=XbsSafeModeTileService.class;break;
            case SAVER:tile=XbsSaverTileService.class;break;
            case RESTORE:tile=XbsRestoreDefaultsTileService.class;break;
            default:tile=XbsProfilesTileService.class;break;
        }
        try {
            StatusBarManager manager=getSystemService(StatusBarManager.class);
            if(manager==null){message("Add tile manually","SystemUI tile requests unavailable; use Quick Settings Edit.");return;}
            manager.requestAddTileService(new ComponentName(this,tile),"XBS "+kind.name(),
                Icon.createWithResource(this,android.R.drawable.ic_menu_manage),getMainExecutor(),
                result->message("Quick Settings","SystemUI returned tile request result: "+result));
        }catch(RuntimeException denied){message("Add tile manually","SystemUI denied the request; add the XBS tile through Quick Settings Edit.");}
    }
    private void confirmRecovery(XbsTileDecision.Kind kind) {
        new AlertDialog.Builder(this).setTitle("Acknowledge unknown command outcome")
            .setMessage("This only clears the local recovery warning for "+kind.name()
                +". It does not re-run a command, change daemon state, or assert that the command succeeded. Review XBS activity first.")
            .setNegativeButton("Cancel",null).setPositiveButton("Acknowledge",(d,w)->{
                dispatchCommand(()->{
                    boolean cleared=XbsTileController.acknowledgeRecovery(getApplicationContext(),kind);
                    runOnUiThread(()->{
                        if(isFinishing() || isDestroyed())return;
                        message(cleared?"Acknowledged":"Still blocked",cleared?
                            "Recovery warning cleared; current state must still be verified.":
                            "A pending or active command still requires investigation.");
                        render();
                    });
                });
            }).show();
    }
    private void confirmLocalReset() {
        new AlertDialog.Builder(this).setTitle("Unlock LOCAL tile shortcut reset")
            .setMessage("This can erase only Saver/Profiles tile selections, not XBS daemon settings or system defaults. Continue?")
            .setNegativeButton("Cancel",null).setPositiveButton("Continue",(dialog,which)->
                new AlertDialog.Builder(this).setTitle("Confirm local tile reset")
                    .setMessage("Remove both tile event shortcuts? Existing XBS runtime profiles stay unchanged.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Reset shortcuts",(d,w)->{
                        if(XbsTileConfiguration.clearAll(this))message("Reset complete","Only local tile shortcuts were cleared.");
                        else message("Reset failed","Settings could not be committed.");render();
                    }).show()).show();
    }
    private void message(String title,String text) {new AlertDialog.Builder(this).setTitle(title).setMessage(text)
        .setPositiveButton("OK",null).show();}
}
