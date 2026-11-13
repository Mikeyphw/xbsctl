package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.ExecutorService;


public final class TaskerConditionReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR=ExternalEntryExecutor.create("xbs-tasker-condition");
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!TaskerConditionProtocol.ACTION_QUERY_CONDITION.equals(intent.getAction()))return;
        String sender=TaskerConfigCodec.sentFromPackage(this);int senderUid=TaskerConfigCodec.sentFromUid(this);PendingResult pending=goAsync();Intent request=new Intent(intent);Context app=context.getApplicationContext();
        try {
            EXECUTOR.execute(()->{
                try { query(app,request,pending,sender,senderUid); }
                catch(RuntimeException malformed) {
                    pending.setResultCode(TaskerConditionProtocol.UNKNOWN);
                    ExternalEntryStore.record(app,"tasker_condition","malformed_input_rejected","",false,false);
                } finally { pending.finish(); }
            });
        } catch (RuntimeException rejected) {
            pending.setResultCode(TaskerConditionProtocol.UNKNOWN);
            ExternalEntryStore.record(app,"tasker_condition","queue_rejected","",false,!sender.isEmpty());
            pending.finish();
        }
    }
    private void query(Context context,Intent intent,PendingResult pending,String sender,int senderUid){
        Bundle config=intent.getBundleExtra(TaskerConditionProtocol.EXTRA_BUNDLE);String host=config==null?"":config.getString(TaskerRequestPolicy.KEY_HOST_PACKAGE,"");
        TaskerRequestPolicy.Validation auth=TaskerAuthorization.verify(context,config,true);boolean senderVerified=sender!=null&&!sender.isEmpty();
        if(!auth.valid||!TaskerConfigCodec.senderMatches(context,sender,senderUid,host)){pending.setResultCode(TaskerConditionProtocol.UNKNOWN);ExternalEntryStore.record(context,"tasker_condition",auth.valid?"sender_mismatch":auth.code,host,false,senderVerified);return;}
        String kind=config.getString(TaskerConditionProtocol.KEY_KIND,TaskerConditionProtocol.KIND_STATE);
        boolean accepted = TaskerConditionProtocol.KIND_EVENT.equals(kind)
            ? queryEvent(intent,config,pending) : queryState(context,config,pending);
        ExternalEntryStore.record(context,"tasker_condition",accepted?"accepted":"invalid_pass_through",host,accepted,senderVerified);
    }
    private static boolean queryEvent(Intent intent,Bundle config,PendingResult pending){Bundle pass=TaskerConditionProtocol.sanitizePassThrough(intent.getBundleExtra(TaskerConditionProtocol.EXTRA_PASS_THROUGH));if(pass==null||!pass.containsKey(TaskerConditionProtocol.MESSAGE_ID)){pending.setResultCode(TaskerConditionProtocol.UNKNOWN);return false;}String configured=config.getString(TaskerConditionProtocol.KEY_STATE,"any");String filter=bounded(config.getString(TaskerConditionProtocol.KEY_FILTER,""),160);String type=bounded(pass.getString("%xbs_event",""),160);String name=bounded(pass.getString("%xbs_event_name",""),160);boolean match="any".equals(configured)||configured.equals(type);if(!filter.isEmpty()&&!filter.equalsIgnoreCase(name))match=false;if(match){Bundle result=pending.getResultExtras(true);result.putBundle(TaskerConditionProtocol.EXTRA_VARIABLES,pass);pending.setResultExtras(result);pending.setResultCode(TaskerConditionProtocol.SATISFIED);}else pending.setResultCode(TaskerConditionProtocol.UNSATISFIED);return true;}
    private static boolean queryState(Context context,Bundle config,PendingResult pending){XbsSnapshot snapshot=XbsStateStore.current(context);String state=config.getString(TaskerConditionProtocol.KEY_STATE,"available");String filter=bounded(config.getString(TaskerConditionProtocol.KEY_FILTER,""),160);boolean satisfied;switch(state){case "paused":satisfied=snapshot.paused;break;case "safe_mode":satisfied=snapshot.safeMode;break;case "any_active":satisfied=!snapshot.active.isEmpty();break;case "event_active":satisfied=!filter.isEmpty()&&snapshot.active.contains(filter);break;default:satisfied=snapshot.available;}Bundle vars=new Bundle();snapshot.addTaskerVariables(vars);Bundle result=pending.getResultExtras(true);result.putBundle(TaskerConditionProtocol.EXTRA_VARIABLES,vars);pending.setResultExtras(result);pending.setResultCode(satisfied?TaskerConditionProtocol.SATISFIED:TaskerConditionProtocol.UNSATISFIED);return true;}
    private static String bounded(String s,int max){String v=s==null?"":s.trim();return v.length()<=max?v:v.substring(0,max);}
}
