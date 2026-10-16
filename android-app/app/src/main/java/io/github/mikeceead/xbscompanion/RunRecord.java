package io.github.mikeceead.xbscompanion;

import org.json.JSONException;
import org.json.JSONObject;

final class RunRecord {
    static final int MAX_ID_CHARS = 96;
    static final int MAX_TITLE_CHARS = 240;
    static final int MAX_COMMAND_CHARS = 4000;
    static final int MAX_SOURCE_CHARS = 64;
    static final int MAX_MESSAGE_CHARS = 1000;
    static final int MAX_OUTPUT_CHARS = RunRetentionPolicy.MAX_OUTPUT_CHARS;
    String runId="",module="",actionId="",title="Command",status="running",command="",backend="",workdir="~/",source="",mutationClass="",stdout="",stderr="",error="",message="";
    int exitCode=-1,timeoutSeconds=0; long startedAt=System.currentTimeMillis(),finishedAt=0; boolean pinned=false;
    JSONObject toJson() throws JSONException {JSONObject o=new JSONObject();o.put("run_id",limit(runId,MAX_ID_CHARS));o.put("module",line(module,MAX_SOURCE_CHARS));o.put("action_id",line(actionId,MAX_SOURCE_CHARS));o.put("title",line(title,MAX_TITLE_CHARS));o.put("status",line(status,MAX_SOURCE_CHARS));o.put("command",limit(command,MAX_COMMAND_CHARS));o.put("backend",line(backend,MAX_SOURCE_CHARS));o.put("workdir",line(workdir,MAX_COMMAND_CHARS));o.put("source",line(source,MAX_SOURCE_CHARS));o.put("mutation_class",line(mutationClass,MAX_SOURCE_CHARS));o.put("timeout_seconds",Math.max(0,Math.min(86_400,timeoutSeconds)));o.put("stdout",tail(stdout,MAX_OUTPUT_CHARS));o.put("stderr",tail(stderr,MAX_OUTPUT_CHARS));o.put("error",tail(error,MAX_OUTPUT_CHARS));o.put("message",ResultFormatter.notificationText(line(message,MAX_MESSAGE_CHARS)));o.put("exit_code",exitCode);o.put("started_at",Math.max(0L,startedAt));o.put("finished_at",Math.max(0L,finishedAt));o.put("pinned",pinned);return o;}
    static RunRecord fromJson(JSONObject o){RunRecord r=new RunRecord();if(o==null)return r;r.runId=limit(o.optString("run_id"),MAX_ID_CHARS);r.module=line(o.optString("module"),MAX_SOURCE_CHARS);r.actionId=line(o.optString("action_id"),MAX_SOURCE_CHARS);r.title=line(o.optString("title","Command"),MAX_TITLE_CHARS);r.status=line(o.optString("status","running"),MAX_SOURCE_CHARS);r.command=limit(o.optString("command"),MAX_COMMAND_CHARS);r.backend=line(o.optString("backend"),MAX_SOURCE_CHARS);r.workdir=line(o.optString("workdir","~/"),MAX_COMMAND_CHARS);r.source=line(o.optString("source"),MAX_SOURCE_CHARS);r.mutationClass=line(o.optString("mutation_class"),MAX_SOURCE_CHARS);r.timeoutSeconds=Math.max(0,Math.min(86_400,o.optInt("timeout_seconds",0)));r.stdout=tail(o.optString("stdout"),MAX_OUTPUT_CHARS);r.stderr=tail(o.optString("stderr"),MAX_OUTPUT_CHARS);r.error=tail(o.optString("error"),MAX_OUTPUT_CHARS);r.message=ResultFormatter.notificationText(line(o.optString("message"),MAX_MESSAGE_CHARS));r.exitCode=o.optInt("exit_code",-1);r.startedAt=Math.max(0L,o.optLong("started_at",System.currentTimeMillis()));r.finishedAt=Math.max(0L,o.optLong("finished_at",0));r.pinned=o.optBoolean("pinned",false);return r;}
    static boolean validRunId(String value){String id=value==null?"":value.trim();if(id.isEmpty()||id.length()>MAX_ID_CHARS)return false;for(int i=0;i<id.length();i++){char c=id.charAt(i);if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='-'||c=='.'||c==':'))return false;}return true;}
    static String line(String value,int max){String clean=value==null?"":value.replace('\r',' ').replace('\n',' ').replace('\t',' ').trim();return limit(clean.replaceAll(" +"," "),max);}
    static String limit(String value,int max){String safe=value==null?"":value;int bound=Math.max(0,max);return safe.length()<=bound?safe:safe.substring(0,bound);}
    static String tail(String value,int max){String safe=value==null?"":value;int bound=Math.max(0,max);return safe.length()<=bound?safe:safe.substring(safe.length()-bound);}
}
