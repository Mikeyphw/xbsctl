package io.github.mikeceead.xbsfixture;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Only built and installed on a clean hosted emulator. Never logs or persists signed bundles. */
public final class HostActivity extends Activity {
    private static final String XBS = "io.github.mikeceead.xbscompanion";
    private static final String TRUSTED = "net.dinglisch.android.taskerm";
    private static final String UNTRUSTED = "io.github.mikeceead.xbsfixture.untrusted";
    private static final String BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    private static final String PASS = "net.dinglisch.android.tasker.extras.PASS_THROUGH_DATA";
    private static final String FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    private static final String QUERY = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION";
    private static final int ACTION_FAILED = 2, ACTION_PENDING = 3, SATISFIED = 16, UNSATISFIED = 17, UNKNOWN = 18;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private String mode;
    private boolean finished;
    private Bundle signed;
    private boolean action;
    private int positive;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        status = new TextView(this);
        status.setText("XBS_FIXTURE_WAIT");
        setContentView(status);
        mode = getIntent().getStringExtra("mode");
        if (saved != null) { fail("unexpected_recreation"); return; }
        handler.postDelayed(() -> { if (!finished) fail("deadline"); }, 90000);
        if (UNTRUSTED.equals(getPackageName()) && "relay".equals(mode)) {
            if (getCallingActivity() == null || !TRUSTED.equals(getCallingActivity().getPackageName())) {
                fail("invalid_relay_caller"); return;
            }
            Bundle config = getIntent().getBundleExtra(BUNDLE);
            action = getIntent().getBooleanExtra("action", false);
            if (!validConfig(config, action)) { fail("invalid_relay_config"); return; }
            send(config, action, true, (code, error) -> {
                setResult(RESULT_OK, new Intent().putExtra("code", code)
                    .putExtra("sender_refusal", !action || "Tasker sender does not match the signed host.".equals(error)));
                finished = true; finish();
            });
            return;
        }
        if (UNTRUSTED.equals(getPackageName()) && "untrusted_editor".equals(mode)) {
            open("TaskerStateEditActivity", "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"); return;
        }
        if (!TRUSTED.equals(getPackageName())) { fail("wrong_fixture_package"); return; }
        action = "action".equals(mode);
        if (action) open("TaskerPluginActivity", "com.twofortyfouram.locale.intent.action.EDIT_SETTING");
        else if ("state".equals(mode)) open("TaskerStateEditActivity", "com.twofortyfouram.locale.intent.action.EDIT_CONDITION");
        else if ("event".equals(mode)) open("TaskerEventEditActivity", "net.dinglisch.android.tasker.ACTION_EDIT_EVENT");
        else fail("invalid_mode");
    }

    private void open(String editor, String intentAction) {
        startActivityForResult(new Intent(intentAction).setClassName(XBS, XBS + "." + editor), 10);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (finished) return;
        if (request == 20) {
            if (result != RESULT_OK || data == null || data.getIntExtra("code", -999) != refused() || !data.getBooleanExtra("sender_refusal", false)) {
                fail("signed_replay_not_refused"); return;
            }
            signed = null;
            finishStatus("PASS_" + mode.toUpperCase(java.util.Locale.ROOT) + "_" + positive);
            return;
        }
        if (request != 10) { fail("invalid_activity_result"); return; }
        if ("untrusted_editor".equals(mode)) {
            if (result != RESULT_CANCELED || data != null && data.hasExtra(BUNDLE)) fail("untrusted_editor_signed");
            else finishStatus("PASS_UNTRUSTED_EDITOR");
            return;
        }
        if (result != RESULT_OK || data == null) { fail("editor_not_saved"); return; }
        signed = data.getBundleExtra(BUNDLE);
        if (!validConfig(signed, action)) { fail("missing_or_wrong_signed_config"); return; }
        if (!action && (!mode.equals(signed.getString("kind")) ||
                !("event".equals(mode) ? "any" : "available").equals(signed.getString("state")))) {
            fail("unexpected_condition"); return;
        }
        send(signed, action, true, (code, error) -> {
            // A rootless action can reach the normal FGS policy and be refused there.
            // The host independently checks the action-specific receipt in the target app.
            if (action ? code != ACTION_PENDING && !(code == ACTION_FAILED && allowedLaunchRefusal(error))
                : "event".equals(mode) ? code != SATISFIED : code != SATISFIED && code != UNSATISFIED) {
                fail("valid_sender_not_distinguished"); return;
            }
            positive = code;
            send(signed, action, false, (hidden, hiddenError) -> {
                if (hidden != refused() || action && !"Tasker sender does not match the signed host.".equals(hiddenError)) { fail("hidden_sender_not_refused"); return; }
                Bundle altered = new Bundle(signed);
                altered.putString("config_signature", "0000000000000000000000000000000000000000000000000000000000000000");
                send(altered, action, true, (bad, badError) -> {
                    if (bad != refused() || action && !"Tasker configuration rejected: bad_signature".equals(badError)) { fail("tampered_signature_not_refused"); return; }
                    startActivityForResult(new Intent().setClassName(UNTRUSTED, getClass().getName())
                        .putExtra("mode", "relay").putExtra("action", action).putExtra(BUNDLE, signed), 20);
                });
            });
        });
    }

    private int refused() { return action ? ACTION_FAILED : UNKNOWN; }
    private boolean validConfig(Bundle config, boolean isAction) {
        return config != null && TRUSTED.equals(config.getString("host_package")) &&
            fixtureCertificate().equals(config.getString("host_cert_sha256", "")) &&
            config.getString("host_cert_sha256", "").matches("[0-9a-f]{64}") &&
            config.getString("config_signature", "").matches("[0-9a-f]{64}") &&
            (!isAction || "status".equals(config.getString("cli_command")) &&
                statusDefaults(config.getString("cli_values", "")) && !config.getBoolean("allow_destructive", false));
    }

    private String fixtureCertificate() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(TRUSTED,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures.length != 1) return "";
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (Exception error) { return ""; }
    }
    private static boolean statusDefaults(String encoded) {
        try {
            org.json.JSONObject values = new org.json.JSONObject(encoded);
            return values.length() == 3 && "true".equals(values.opt("_global_json")) &&
                "false".equals(values.opt("_global_quiet")) && "false".equals(values.opt("_global_no_interactive"));
        } catch (org.json.JSONException error) { return false; }
    }
    private static boolean allowedLaunchRefusal(String error) {
        return error.startsWith("ForegroundServiceStartNotAllowedException: ") ||
            error.startsWith("SecurityException: ") || error.equals("Foreground execution unavailable");
    }
    private interface Result { void accept(int code, String error); }
    private void send(Bundle config, boolean isAction, boolean shareIdentity, Result done) {
        Intent intent = new Intent(isAction ? FIRE : QUERY)
            .setClassName(XBS, XBS + (isAction ? ".TaskerPluginReceiver" : ".TaskerConditionReceiver"))
            .putExtra(BUNDLE, new Bundle(config));
        if (!isAction && "event".equals(config.getString("kind"))) {
            Bundle event = new Bundle();
            event.putInt("net.dinglisch.android.tasker.MESSAGE_ID", 3601);
            event.putString("%xbs_event", "profile_active");
            event.putString("%xbs_event_name", "hosted_fixture");
            intent.putExtra(PASS, event);
        }
        sendOrderedBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(shareIdentity).toBundle(),
            new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent result) {
                    if (!finished) {
                        Bundle extras = getResultExtras(false);
                        Bundle variables = extras == null ? null : extras.getBundle("net.dinglisch.android.tasker.extras.VARIABLES");
                        done.accept(getResultCode(), variables == null ? "" : variables.getString("%suite_error", ""));
                    }
                }
            }, handler, -999, null, null);
    }
    private void fail(String code) { finishStatus("FAIL_" + code); }
    private void finishStatus(String code) {
        finished = true; signed = null; handler.removeCallbacksAndMessages(null);
        status.setText("XBS_FIXTURE_" + code);
    }
}
