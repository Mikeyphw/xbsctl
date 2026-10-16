package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import org.json.JSONObject;

/** Durable bounded hand-off for foreground root commands across process recreation. */
final class PendingCommandStore {
    private static final String PREFS = "root_pending_v1";
    private static final int MAX = 32;
    private PendingCommandStore() {}

    static void save(Context c, String runId, int executionId, XbsCommandSpec spec, boolean tasker) {
        if (c == null || spec == null || !safeId(runId)) throw new IllegalArgumentException("Invalid pending command");
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getAll().size() >= MAX && !p.contains(runId)) throw new IllegalStateException("Too many pending root commands");
        try {
            JSONObject o = new JSONObject();
            o.put("v", 2); o.put("run_id", runId); o.put("execution_id", executionId); o.put("tasker", tasker);
            o.put("id", spec.action.id); o.put("title", spec.action.title); o.put("description", spec.action.description);
            o.put("command", spec.command); o.put("destructive", spec.action.destructive); o.put("source", spec.source.name());
            o.put("recovery_kind", spec.recoveryKind); o.put("recovery_data", spec.recoveryData);
            o.put("owned", false); o.put("created_at", System.currentTimeMillis());
            if (!p.edit().putString(runId, o.toString()).commit()) throw new IllegalStateException("Pending command could not be persisted");
        } catch (Exception e) { throw new IllegalStateException("Pending command could not be persisted", e); }
    }

    static Recovered load(Context c, String runId) {
        if (c == null || !safeId(runId)) return null;
        String raw = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(runId, null);
        if (raw == null || raw.length() > 32768) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("v") != 2 || !runId.equals(o.optString("run_id"))) return Recovered.invalid("invalid_envelope");
            String command=o.optString("command", "").trim(), id=o.optString("id", "").trim();
            if (command.isEmpty() || command.length()>8192 || command.indexOf('\0')>=0 || id.isEmpty() || id.length()>128) return Recovered.invalid("invalid_command");
            ExecutionSource source; try { source=ExecutionSource.valueOf(o.optString("source", "UNKNOWN")); } catch(Exception e){ source=ExecutionSource.UNKNOWN; }
            boolean tasker = o.optBoolean("tasker");
            boolean owned = o.optBoolean("owned");
            try {
                XbsCommandSpec spec = XbsCommandSpec.recoverPersisted(id, command, source,
                    o.optString("recovery_kind", "none"), o.optString("recovery_data", ""));
                return new Recovered(true, o.optInt("execution_id"), spec, tasker, owned, "ok");
            } catch (Exception recoveryError) {
                return Recovered.invalid("revalidation_failed", tasker, owned);
            }
        } catch(Exception e){ return Recovered.invalid("invalid_envelope"); }
    }
    static boolean markOwned(Context c,String id){ return updateOwned(c,id); }
    private static boolean updateOwned(Context c,String id){
        if (c == null || !safeId(id)) return false;
        try {
            SharedPreferences p=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String raw=p.getString(id,null); if(raw==null)return false;
            JSONObject o=new JSONObject(raw); o.put("owned",true);
            return p.edit().putString(id,o.toString()).commit();
        } catch(Exception ignored){ return false; }
    }
    static void remove(Context c,String id){ if(c!=null && safeId(id)) c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(id).commit(); }
    private static boolean safeId(String s){ return s!=null && s.matches("[A-Za-z0-9_-]{1,96}"); }
    static void clearForTest(Context c) { if (c != null) c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit(); }
    static void corruptForTest(Context c, String id, String raw) { if (c != null && safeId(id)) c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, raw).commit(); }
    static final class Recovered {
        final boolean valid; final int executionId; final XbsCommandSpec spec; final boolean tasker; final boolean owned; final String reason;
        Recovered(boolean v,int e,XbsCommandSpec s,boolean t,boolean o,String r){valid=v;executionId=e;spec=s;tasker=t;owned=o;reason=r==null?"":r;}
        static Recovered invalid(String reason){return new Recovered(false,0,null,false,false,reason);}
        static Recovered invalid(String reason, boolean tasker, boolean owned){return new Recovered(false,0,null,tasker,owned,reason);}
    }
}
