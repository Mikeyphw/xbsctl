package io.github.mikeceead.xbscompanion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

final class TaskerProtocol {
    static final String ACTION_FIRE_SETTING="com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    static final String EXTRA_BUNDLE="com.twofortyfouram.locale.intent.extra.BUNDLE";
    static final String EXTRA_BLURB="com.twofortyfouram.locale.intent.extra.BLURB";
    static final String KEY_MODULE="module",KEY_ACTION="action",KEY_ARGUMENT="argument",KEY_BACKEND="backend",KEY_WORKDIR="workdir",KEY_CLI_COMMAND="cli_command",KEY_CLI_VALUES="cli_values",KEY_REPLACE="net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS";
    static final int RESULT_OK=Activity.RESULT_OK,RESULT_FAILED=Activity.RESULT_FIRST_USER+1,RESULT_PENDING=Activity.RESULT_FIRST_USER+2;
    private static final String BASE="net.dinglisch.android.tasker",EXTRAS=BASE+".extras.",VARS=EXTRAS+"VARIABLES",RESULT=EXTRAS+"RESULT_CODE",COMPLETE=EXTRAS+"COMPLETION_INTENT",TIMEOUT=EXTRAS+"REQUESTED_TIMEOUT",RELEVANT=BASE+".RELEVANT_VARIABLES";
    private TaskerProtocol(){}

    static void decorate(Intent result,Bundle c){result.putExtra(EXTRA_BUNDLE,c);result.putExtra(EXTRA_BLURB,blurb(c));result.putExtra(TIMEOUT,3599000);result.putExtra(RELEVANT,new String[]{"%suite_ok\nSuccess\nMachine result success.","%suite_message\nMessage\nHuman-readable result.","%suite_data\nData JSON\nMachine result data.","%suite_exit_code\nExit code\nBackend exit code.","%suite_stdout\nStandard output\nCaptured output.","%suite_error\nError\nExecution error."});}
    static String blurb(Bundle c){String commandId=c.getString(KEY_CLI_COMMAND,"");if(!commandId.isEmpty()){CliCommand cmd=XbsCliCatalog.find(commandId);return cmd==null?"XBS CLI action":"XBS: "+cmd.title;}SuiteAction a=SuiteCatalog.find(c.getString(KEY_MODULE,""),c.getString(KEY_ACTION,""));return (a==null?"XBS action":SuiteCatalog.moduleTitle(a.module)+": "+a.title)+" via "+c.getString(KEY_BACKEND,"root");}
    static Bundle variables(int id,int exit,String stdout,String stderr,String error,String module,String action){Bundle v=new Bundle();v.putString("%suite_execution_id",String.valueOf(id));v.putString("%suite_exit_code",String.valueOf(exit));v.putString("%suite_stdout",safe(stdout));v.putString("%suite_stderr",safe(stderr));v.putString("%suite_error",safe(error));v.putString("%suite_module",safe(module));v.putString("%suite_requested_action",safe(action));MachineResult machine=MachineResult.parse(stdout);machine.addVariables(v,module==null?"suite":module);String human=ResultFormatter.summary(exit,stdout,stderr,error,exit==0?"Completed":"Failed");v.putString("%suite_message",human);if(!machine.parsed)v.putString("%suite_ok",String.valueOf(exit==0&&(error==null||error.trim().isEmpty())));if(exit!=0||error!=null&&!error.trim().isEmpty())v.putString("%errmsg",human);return v;}
    static void addVariables(Bundle result,Bundle vars){if(result!=null)result.putBundle(VARS,vars);}

    static boolean signalFinish(Context context,Intent original,int code,Bundle vars){
        Bundle config=original==null?null:original.getBundleExtra(EXTRA_BUNDLE);
        String host=config==null?"":config.getString(TaskerRequestPolicy.KEY_HOST_PACKAGE,"");
        String encoded=original==null?null:original.getStringExtra(COMPLETE);
        TaskerCompletionPolicy.Result parsed=TaskerCompletionPolicy.parse(encoded,host);
        if(!parsed.valid){ExternalEntryStore.record(context,"completion",parsed.code,host,false,false);return false;}
        Bundle safeVariables=TaskerCompletionPolicy.sanitizeVariables(vars);
        if(safeVariables==null){ExternalEntryStore.record(context,"completion","unsafe_result_variables",host,false,false);return false;}
        Intent done=parsed.intent;done.putExtra(RESULT,code);done.putExtra(VARS,safeVariables);
        boolean foreground=done.getBooleanExtra("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_FOREGROUND",false);
        try{
            String servicePackage=done.getStringExtra("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE");
            if(servicePackage!=null&&!servicePackage.isEmpty()){
                if(foreground){ForegroundServiceLaunchResult launch=ForegroundServiceLauncher.start(context,done,"tasker_completion");if(!launch.started){ExternalEntryStore.record(context,"completion",launch.code,host,false,false);return false;}}
                else context.startService(done);
            } else context.sendBroadcast(done);
            ExternalEntryStore.record(context,"completion","sent",host,true,false);return true;
        }catch(RuntimeException error){ExternalEntryStore.record(context,"completion","delivery_failed",host,false,false);return false;}
    }
    private static String safe(String s){return s==null?"":s;}
}
