package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict confirmation of the exact request completed by XBSctl --wait.
 * A receipt is only accepted when its request ID, action, and daemon observation agree.
 * No replays or automatic retries are attempted on timeout/uncertainty.
 */
final class R05aDaemonReceipt {
    enum State { APPLIED, PENDING, FAILED, CANCELLED, RECOVERY_REQUIRED }
    final State state;
    final String requestId;
    final String code;
    private R05aDaemonReceipt(State s,String id,String reason){state=s;requestId=id;code=reason;}
    boolean applied(){return state==State.APPLIED;}
    static R05aDaemonReceipt inspect(String command,int exit,String stdout,String error){
        String action=R05aCommandPolicy.expectedAction(command);
        if(action.isEmpty())return new R05aDaemonReceipt(exit==0 && (error==null || error.trim().isEmpty())?State.APPLIED:State.FAILED,"","not_controller");
        if(exit==130)return new R05aDaemonReceipt(State.CANCELLED,"","cancelled");
        if(exit==124 || exit==125)return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,"","process_outcome_unknown");
        if(stdout==null||stdout.length()>65536)return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,"","missing_or_oversized_receipt");
        try {
            // Control's --json protocol is one single JSON object. Ignore any shell chatter:
            // a mixed/noisy response is not an authenticated daemon acknowledgement.
            JSONObject obj=new JSONObject(stdout.trim());
            String id=obj.optString("request_id", "");
            if (!id.matches("[A-Za-z0-9_-]{12,128}"))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,"","missing_or_invalid_request_id");
            String completion=obj.optString("completion", "");
            String responseAction=obj.optString("requested_action", "");
            if (!action.equals(responseAction))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"wrong_action");
            if ("unknown".equals(completion)||"timeout".equals(completion))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,completion);
            if ("queued".equals(completion)||"pending".equals(completion))return new R05aDaemonReceipt(State.PENDING,id,completion);
            if ("cancelled".equals(completion))return new R05aDaemonReceipt(State.CANCELLED,id,"cancelled");
            if ("failed".equals(completion))return new R05aDaemonReceipt(State.FAILED,id,"daemon_failed");
            if (!"applied".equals(completion)||exit!=0||(error!=null&&!error.trim().isEmpty())||!"ok".equals(obj.optString("status", "")))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"unverified_completion");
            JSONObject observed=obj.optJSONObject("observed");
            if (observed==null || !observed.optBoolean("available",false))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"observation_unavailable");
            String event=obj.optString("event", "");
            if("start".equals(action)||"stop".equals(action)) {
                if(event.isEmpty())return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"missing_event");
                JSONArray active=observed.optJSONArray("active_events");
                if(active==null)return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"missing_active_events");
                boolean present=false;
                for(int i=0;i<active.length();i++)if(event.equals(active.optString(i,"")))present=true;
                if(present!="start".equals(action))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"observation_mismatch");
            } else if("pause".equals(action)||"resume".equals(action)||"safe".equals(action)) {
                String mode=observed.optString("mode","");
                String required="pause".equals(action)?"paused":"safe".equals(action)?"safe":"normal";
                if(!required.equals(mode))return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"mode_mismatch");
            } else if("reload".equals(action)) {
                if(observed.optString("last_reload_time","").isEmpty())return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,id,"reload_unobserved");
            }
            return new R05aDaemonReceipt(State.APPLIED,id,"daemon_confirmed");
        } catch(Exception e){return new R05aDaemonReceipt(State.RECOVERY_REQUIRED,"","invalid_receipt");}
    }

    String historyStatus(){
        switch(state){case APPLIED:return "ok";case CANCELLED:return "cancelled";case PENDING:return "pending";case RECOVERY_REQUIRED:return "recovery_required";default:return "failed";}
    }
    void taskerVariables(android.os.Bundle variables){
        if(variables==null)return;
        variables.putString("%suite_ok",String.valueOf(applied()));
        variables.putString("%suite_status",historyStatus());
        variables.putString("%suite_request_id",requestId);
        if(!applied()) variables.putString("%suite_error", "XBS daemon acknowledgement: "+code);
    }
}
