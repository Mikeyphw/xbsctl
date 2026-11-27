package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Serialized and durable QS commands, shared with existing RootCommandRunner/Tasker history. */
final class XbsTileController {
    private static final Object LOCK=new Object();
    private static final String PREFS="gx04_tile_pending_v1";
    private static final long MAX_SNAPSHOT_AGE_MS=120_000L;
    private static final long MAX_BRIDGE_AGE_MS=120_000L;
    private static final long PENDING_TIMEOUT_MS=120_000L;
    private static final long FAILURE_VISIBLE_MS=60_000L;
    private XbsTileController(){}
    private static SharedPreferences prefs(Context c,XbsTileDecision.Kind kind){
        return c.getApplicationContext().getSharedPreferences(PREFS+"_"+kind.name(),Context.MODE_PRIVATE);
    }
    private static boolean fresh(long now,long then,long limit){
        return then>0L && now>=then && now-then<=limit;
    }
    static boolean trusted(XbsSnapshot snap,EventBridgeReport bridge,long now){
        return snap!=null && snap.available && fresh(now,snap.capturedAt,MAX_SNAPSHOT_AGE_MS)
            && bridge!=null && bridge.authenticated() && !bridge.securityFailure
            && bridge.sequence>0L && fresh(now,bridge.capturedAt,MAX_BRIDGE_AGE_MS);
    }
    private static boolean activePending(Context c){
        long now=System.currentTimeMillis();
        for(XbsTileDecision.Kind kind:XbsTileDecision.Kind.values()){
            SharedPreferences p=prefs(c,kind);
            long at=p.getLong("started",0L);
            if(at<=0L || p.getString("action","").isEmpty())continue;
            if(now<at || now-at>PENDING_TIMEOUT_MS){
                // Reconcile expired commands even if their tile is not currently listening.
                observe(c,kind);
                if(p.getLong("started",0L)<=0L || p.getString("action","").isEmpty())continue;
            }
            return true;
        }
        return false;
    }
    private static boolean confirmedTarget(XbsSnapshot snap,XbsTileDecision.Kind kind,
                                           String event,boolean target){
        if(kind==XbsTileDecision.Kind.PAUSE)return snap.paused==target;
        if(kind==XbsTileDecision.Kind.SAFE)return snap.safeMode==target;
        // Some daemon snapshots omit inactive events entirely. A verified terminal
        // command AND a newer authenticated snapshot are required by the caller.
        return !event.isEmpty() && (target?snap.active.contains(event):!snap.active.contains(event));
    }
    static XbsTileDecision observe(Context c,XbsTileDecision.Kind kind){
        synchronized(LOCK){
            long now=System.currentTimeMillis();
            XbsSnapshot snap=XbsStateStore.current(c);
            EventBridgeReport bridge=EventBridgeStore.cached(c);
            SharedPreferences p=prefs(c,kind);
            boolean attested=trusted(snap,bridge,now);
            long started=p.getLong("started",0L);
            String action=p.getString("action","");
            boolean pending=!action.isEmpty() && started>0L;
            boolean recovery=p.getBoolean("recovery",false);
            boolean failed=fresh(now,p.getLong("failed_at",0L),FAILURE_VISIBLE_MS);
            if(pending){
                String runId=p.getString("run_id","");
                RunRecord record=runId.isEmpty()?null:RunStore.find(c,runId);
                boolean target=p.getBoolean("target",false);
                String event=p.getString("event","");
                boolean advanced=snap.capturedAt>started && bridge.capturedAt>started
                    && bridge.sequence>p.getLong("bridge_sequence",0L);
                XbsTileCompletionPolicy.Outcome outcome=XbsTileCompletionPolicy.evaluate(
                    record==null?null:record.status,attested,advanced,
                    confirmedTarget(snap,kind,event,target));
                if(outcome==XbsTileCompletionPolicy.Outcome.FAILED ||
                   outcome==XbsTileCompletionPolicy.Outcome.RECOVERY_REQUIRED){
                    boolean needsReview=outcome==XbsTileCompletionPolicy.Outcome.RECOVERY_REQUIRED;
                    if(!p.edit().remove("action").remove("started").remove("run_id")
                        .remove("bridge_sequence").remove("event")
                        .putLong("failed_at",now).putBoolean("recovery",needsReview).commit())recovery=true;
                    pending=false;failed=true;recovery|=needsReview;
                } else if(outcome==XbsTileCompletionPolicy.Outcome.CONFIRMED){
                    if(!p.edit().remove("action").remove("started").remove("run_id")
                       .remove("bridge_sequence").remove("event").remove("recovery").commit())recovery=true;
                    else pending=false;
                }else if(now<started || now-started>PENDING_TIMEOUT_MS){
                    if(!p.edit().remove("action").remove("started").remove("run_id")
                        .remove("bridge_sequence").remove("event")
                        .putLong("failed_at",now).putBoolean("recovery",true).commit())recovery=true;
                    pending=false;recovery=true;
                }
            }
            if(kind==XbsTileDecision.Kind.SAVER||kind==XbsTileDecision.Kind.PROFILES){
                String selected=XbsTileConfiguration.selected(c,kind);
                boolean configured=!selected.isEmpty() && XbsTileConfiguration.candidates(snap).contains(selected);
                return XbsTileDecision.event(kind,attested,configured,snap.active.contains(selected),
                    snap.safeMode || snap.paused,pending,failed,recovery);
            }
            return XbsTileDecision.evaluate(kind,attested,snap.paused,snap.safeMode,pending,failed,recovery);
        }
    }
    static boolean configured(Context c,XbsTileDecision.Kind kind){
        if(kind!=XbsTileDecision.Kind.SAVER && kind!=XbsTileDecision.Kind.PROFILES)return true;
        return !XbsTileConfiguration.selected(c,kind).isEmpty();
    }
    static boolean requestToggle(Context context,XbsTileDecision.Kind kind){
        return requestToggle(context,kind,false);
    }
    /** Destructive event_stop requires a separate, explicit in-app confirmation. */
    static boolean requestToggle(Context context,XbsTileDecision.Kind kind,boolean confirmedStop){
        if(kind==XbsTileDecision.Kind.RESTORE)return false;
        synchronized(LOCK){
            XbsTileDecision decision=observe(context,kind);
            if(decision.phase!=XbsTileDecision.Phase.ACTIVE && decision.phase!=XbsTileDecision.Phase.INACTIVE
                || decision.action==null || activePending(context))return false;
            String event="";
            if(kind==XbsTileDecision.Kind.SAVER || kind==XbsTileDecision.Kind.PROFILES){
                event=XbsTileConfiguration.selected(context,kind);
                XbsSnapshot snap=XbsStateStore.current(context);
                if(!XbsTileConfiguration.candidates(snap).contains(event) || snap.safeMode || snap.paused)return false;
            }
            SuiteAction allowlisted=SuiteCatalog.find("xbs",decision.action);
            if(allowlisted==null || (allowlisted.destructive
                    && !(confirmedStop && "event_stop".equals(decision.action)
                         && (kind==XbsTileDecision.Kind.SAVER||kind==XbsTileDecision.Kind.PROFILES)))
                || allowlisted.needsArgument!=(kind==XbsTileDecision.Kind.SAVER||kind==XbsTileDecision.Kind.PROFILES))return false;
            List<RunRecord> active=RunStore.active(context);
            if(active==null || !active.isEmpty())return false;
            EventBridgeReport bridge=EventBridgeStore.cached(context);
            XbsSnapshot observed=XbsStateStore.current(context);
            long now=System.currentTimeMillis();
            if(!trusted(observed,bridge,now))return false;
            boolean target=kind==XbsTileDecision.Kind.PAUSE?"pause".equals(decision.action)
                :kind==XbsTileDecision.Kind.SAFE?"safe_on".equals(decision.action)
                :"event_run".equals(decision.action);
            SharedPreferences p=prefs(context,kind);
            if(!p.edit().putString("action",decision.action).putString("event",event)
                .putLong("started",now).putBoolean("target",target)
                .putLong("bridge_sequence",bridge.sequence).remove("failed_at").commit())return false;
            final CommandLaunchResult result;
            try{result=RootCommandRunner.run(context,allowlisted,event,null,ExecutionSource.TILE);}
            catch(RuntimeException error){markFailure(context,kind);return false;}
            if(result==null || !result.started || result.runId.isEmpty()){
                markFailure(context,kind);return false;
            }
            if(!p.edit().putString("run_id",result.runId).commit()){
                // Failed to durably tie the launch to its run: never report a successful tile transition.
                p.edit().putBoolean("recovery",true).commit();return false;
            }
            // A successfully started command remains PENDING until authenticated state advances.
            return true;
        }
    }
    /** Clear only a reviewed, already-stopped recovery marker. Never replay a root command. */
    static boolean acknowledgeRecovery(Context context,XbsTileDecision.Kind kind){
        synchronized(LOCK){
            SharedPreferences p=prefs(context,kind);
            if(!p.getBoolean("recovery",false)
                || p.getLong("started",0L)>0L || !p.getString("action","").isEmpty())return false;
            List<RunRecord> running=RunStore.active(context);
            if(running==null || !running.isEmpty())return false;
            return p.edit().remove("recovery").remove("failed_at").commit();
        }
    }
    static boolean recoveryNeedsReview(Context context,XbsTileDecision.Kind kind){
        try{return prefs(context,kind).getBoolean("recovery",false);}
        catch(RuntimeException corrupted){return false;}
    }
    private static void markFailure(Context c,XbsTileDecision.Kind kind){
        prefs(c,kind).edit().remove("action").remove("started").remove("run_id")
            .putLong("failed_at",System.currentTimeMillis()).commit();
    }
}
