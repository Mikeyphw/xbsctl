package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Base64;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Synchronously persists replay/action/launch evidence for the runtime notification. */
final class RuntimeNotificationStore {
    private static final String PREFS = "runtime_notification_v2";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RuntimeNotificationStore() {}

    static String publish(Context context, XbsSnapshot snapshot, EventBridgeReport bridge) {
        long now = System.currentTimeMillis();
        String token = RuntimeNotificationPolicy.token(snapshot, bridge);
        String nonce = nonce();
        boolean committed = prefs(context).edit()
            .putLong("captured_at", now)
            .putLong("published_at", now)
            .putString("token", token)
            .putString("bridge_mode", bridge == null ? "" : bridge.mode)
            .putString("action_nonce", nonce).putString("consumed_actions", "")
            .putInt("guard_version", 2).putInt("cancel_guard_version", 1).putBoolean("guard_commit_ok", true)
            .putBoolean("timed_out", false)
            .commit();
        return committed ? token : "";
    }

    static void recordService(Context context, boolean active, String detail) {
        long now = System.currentTimeMillis();
        prefs(context).edit().putLong("captured_at", now).putBoolean("service_active", active)
            .putString("service_detail", bounded(detail)).apply();
    }

    static void recordLaunch(Context context, ForegroundServiceLaunchResult result) {
        if (result == null) return;
        prefs(context).edit().putLong("captured_at", result.at).putLong("launch_at", result.at)
            .putString("launch_purpose", result.purpose).putString("launch_code", result.code)
            .putString("launch_detail", bounded(result.detail)).apply();
    }

    static void recordAction(Context context, String code, String detail) {
        long now = System.currentTimeMillis();
        prefs(context).edit().putLong("captured_at", now).putLong("action_at", now)
            .putString("action_code", bounded(code)).putString("action_detail", bounded(detail)).apply();
    }

    static void recordTimeout(Context context, String detail) {
        long now = System.currentTimeMillis();
        prefs(context).edit().putLong("captured_at", now).putBoolean("timed_out", true)
            .putBoolean("service_active", false).putString("launch_code", "fgs_timeout")
            .putString("launch_detail", bounded(detail)).apply();
    }

    static RuntimeNotificationReport cached(Context context) {
        SharedPreferences p = prefs(context);
        return new RuntimeNotificationReport(
            p.getLong("captured_at", 0L), p.getLong("published_at", 0L), p.getString("token", ""),
            p.getString("bridge_mode", ""), p.getBoolean("service_active", false),
            p.getString("launch_purpose", ""), p.getString("launch_code", ""),
            p.getString("launch_detail", ""), p.getLong("launch_at", 0L),
            p.getString("action_code", ""), p.getString("action_detail", ""),
            p.getLong("action_at", 0L), p.getBoolean("timed_out", false),
            p.getString("action_nonce", ""), p.getString("consumed_actions", ""));
    }

    static String actionNonce(Context context) { return prefs(context).getString("action_nonce", ""); }

    static boolean consumeAction(Context context, String nonce, String actionKey) {
        if (context == null || nonce == null || actionKey == null) return false;
        SharedPreferences p = prefs(context);
        String current = p.getString("action_nonce", "");
        if (current.isEmpty() || !constantEquals(current, nonce)) return false;
        String digest = digest(actionKey);
        String consumed = p.getString("consumed_actions", "");
        for (String item : consumed.split(",")) if (constantEquals(item, digest)) return false;
        String next = consumed.isEmpty() ? digest : consumed + "," + digest;
        if (next.length() > 512) next = digest;
        return p.edit().putString("consumed_actions", next).putLong("last_consumed_at", System.currentTimeMillis()).putString("last_consumed_key", digest).commit();
    }

    static CancelAction publishCancelAction(Context context, String runId) {
        if (context == null || runId == null || runId.trim().isEmpty()) return CancelAction.empty();
        long issuedAt = System.currentTimeMillis(); String value = nonce();
        boolean committed = prefs(context).edit().putString("cancel_run_id", bounded(runId)).putString("cancel_nonce", value)
            .putLong("cancel_issued_at", issuedAt).putBoolean("cancel_consumed", false)
            .putInt("cancel_guard_version", 1).putBoolean("cancel_commit_ok", true).commit();
        return committed ? new CancelAction(runId, value, issuedAt) : CancelAction.empty();
    }

    static boolean consumeCancelAction(Context context, String runId, String value, long issuedAt, long now) {
        if (context == null || runId == null || value == null || issuedAt <= 0 || now < issuedAt
            || now - issuedAt > RuntimeNotificationPolicy.ACTION_MAX_AGE_MS) return false;
        SharedPreferences p = prefs(context);
        if (!constantEquals(p.getString("cancel_run_id", ""), runId)
            || !constantEquals(p.getString("cancel_nonce", ""), value)
            || p.getLong("cancel_issued_at", 0L) != issuedAt || p.getBoolean("cancel_consumed", false)) return false;
        return p.edit().putBoolean("cancel_consumed", true).putLong("last_consumed_at", now)
            .putString("last_consumed_key", digest("cancel_run/" + runId)).commit();
    }

    static ActionGuardStatus actionGuardStatus(Context context, long now) {
        SharedPreferences p = prefs(context); long published = p.getLong("published_at", 0L);
        boolean present = p.getInt("guard_version", 0) >= 2 && p.getInt("cancel_guard_version", 0) >= 1
            && p.getBoolean("guard_commit_ok", false) && !p.getString("action_nonce", "").isEmpty();
        boolean fresh = present && now >= published && now - published <= RuntimeNotificationPolicy.ACTION_MAX_AGE_MS;
        String detail = !present ? "Durable runtime/cancel action evidence is missing"
            : !fresh ? "Runtime action publication evidence is stale"
            : "Fresh durable runtime and cancel action guards are active";
        return new ActionGuardStatus(present && fresh, present, fresh, published, p.getLong("last_consumed_at", 0L), detail);
    }

    static final class CancelAction { final String runId,nonce; final long issuedAt; CancelAction(String r,String n,long at){runId=r;nonce=n;issuedAt=at;} static CancelAction empty(){return new CancelAction("","",0);} boolean ready(){return !runId.isEmpty()&&!nonce.isEmpty()&&issuedAt>0;} }
    static final class ActionGuardStatus { final boolean ready,present,fresh; final long publishedAt,lastConsumedAt; final String detail; ActionGuardStatus(boolean r,boolean p,boolean f,long at,long consumed,String d){ready=r;present=p;fresh=f;publishedAt=at;lastConsumedAt=consumed;detail=d;} }

    private static String nonce() { byte[] bytes=new byte[18];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String digest(String value) { try { byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte b:d)out.append(String.format(java.util.Locale.US,"%02x",b&0xff));return out.toString(); } catch(Exception e){ return ""; } }
    private static boolean constantEquals(String a,String b){return MessageDigest.isEqual((a==null?"":a).getBytes(StandardCharsets.UTF_8),(b==null?"":b).getBytes(StandardCharsets.UTF_8));}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String bounded(String value) {
        String clean = value == null ? "" : value.trim().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 240 ? clean : clean.substring(0, 240);
    }
}
