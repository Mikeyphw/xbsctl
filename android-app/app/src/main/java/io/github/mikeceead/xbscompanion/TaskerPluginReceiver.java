package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;


public final class TaskerPluginReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR=ExternalEntryExecutor.create("xbs-tasker-action");
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!TaskerProtocol.ACTION_FIRE_SETTING.equals(intent.getAction()))return;
        final boolean ordered=isOrderedBroadcast();final String sender=TaskerConfigCodec.sentFromPackage(this);final int senderUid=TaskerConfigCodec.sentFromUid(this);final PendingResult pending=goAsync();final Context app=context.getApplicationContext();final Intent request=new Intent(intent);
        try {
            EXECUTOR.execute(()->{
                try { handle(app,request,pending,ordered,sender,senderUid); }
                catch(RuntimeException malformed) {
                    fail(pending,ordered,"Tasker action rejected: malformed input");
                    ExternalEntryStore.record(app,"tasker_action","malformed_input_rejected","",false,false);
                } finally { pending.finish(); }
            });
        } catch (RuntimeException rejected) {
            fail(pending,ordered,"Tasker action queue is busy");
            ExternalEntryStore.record(app,"tasker_action","queue_rejected","",false,!sender.isEmpty());
            pending.finish();
        }
    }

    private void handle(Context context,Intent intent,PendingResult pending,boolean ordered,String sender,int senderUid){
        Bundle config=intent.getBundleExtra(TaskerProtocol.EXTRA_BUNDLE);
        TaskerRequestPolicy.Validation auth=TaskerAuthorization.verify(context,config,false);
        String host=config==null?"":config.getString(TaskerRequestPolicy.KEY_HOST_PACKAGE,"");
        boolean senderVerified=sender!=null&&!sender.isEmpty();
        if(!auth.valid){fail(pending,ordered,"Tasker configuration rejected: "+auth.code);ExternalEntryStore.record(context,"tasker_action",auth.code,host,false,senderVerified);return;}
        if(!TaskerConfigCodec.senderMatches(context,sender,senderUid,host)){fail(pending,ordered,"Tasker sender does not match the signed host.");ExternalEntryStore.record(context,"tasker_action","sender_mismatch",host,false,true);return;}
        String cliId=config.getString(TaskerProtocol.KEY_CLI_COMMAND,"");
        CliCommand command=XbsCliCatalog.find(cliId);
        if(command==null||!command.special.isEmpty()){fail(pending,ordered,"Unknown or non-runnable XBS CLI command");ExternalEntryStore.record(context,"tasker_action","unknown_command",host,false,senderVerified);return;}
        Set<String> fields=new LinkedHashSet<>();for(CliField field:command.fields)fields.add(field.name);
        TaskerRequestPolicy.Validation policy=TaskerRequestPolicy.validateAction(TaskerConfigCodec.strings(config),fields,command.destructive);
        if(!policy.valid){fail(pending,ordered,"Tasker configuration rejected: "+policy.code);ExternalEntryStore.record(context,"tasker_action",policy.code,host,false,senderVerified);return;}
        TaskerConfigCodec.ParseResult parsed=TaskerConfigCodec.parseCliValues(config.getString(TaskerProtocol.KEY_CLI_VALUES,""),command);
        if(!parsed.valid){fail(pending,ordered,"Tasker fields rejected: "+parsed.code);ExternalEntryStore.record(context,"tasker_action",parsed.code,host,false,senderVerified);return;}
        final String shell;try{shell=command.build(parsed.values);}catch(IllegalArgumentException error){fail(pending,ordered,error.getMessage());ExternalEntryStore.record(context,"tasker_action","field_validation_failed",host,false,senderVerified);return;}
        // A second delivery of the same signed action within 1.5 seconds is not
        // another intentional command; prevent it from reaching the root queue.
        String dedupKey=senderUid+":"+config.getString(TaskerRequestPolicy.KEY_SIGNATURE,"")+":"+command.id+":"+config.getString(TaskerProtocol.KEY_CLI_VALUES,"");
        if (!R05aActionDedup.claim(dedupKey,System.nanoTime())) {
            fail(pending,ordered,"Duplicate Tasker action suppressed");
            ExternalEntryStore.record(context,"tasker_action","duplicate_suppressed",host,false,senderVerified);
            return;
        }
        CommandLaunchResult result=RootCommandRunner.runCommand(context,"tasker_"+command.id,command.title,command.description,shell,command.destructive,intent);
        if (!result.started) R05aActionDedup.release(dedupKey);
        if(result.started){if(ordered)pending.setResultCode(TaskerProtocol.RESULT_PENDING);ExternalEntryStore.record(context,"tasker_action","accepted",host,true,senderVerified);}
        else{fail(pending,ordered,result.error);ExternalEntryStore.record(context,"tasker_action","launch_failed",host,false,senderVerified);}
    }

    private static void fail(PendingResult pending,boolean ordered,String message){if(!ordered)return;pending.setResultCode(TaskerProtocol.RESULT_FAILED);Bundle variables=new Bundle();variables.putString("%suite_ok","false");variables.putString("%suite_error",message==null?"Tasker action failed":message);Bundle extras=pending.getResultExtras(true);TaskerProtocol.addVariables(extras,variables);pending.setResultExtras(extras);}
}
