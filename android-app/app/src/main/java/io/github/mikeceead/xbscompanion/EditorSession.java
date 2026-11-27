package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.ViewModel;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Activity-scoped drafts and one in-flight mutation. All lifecycle calls run on the main thread.
 * Large drafts stay in app-private, non-backed-up files, not Binder saved-state parcels.
 * A recreated process never automatically repeats an unacknowledged root transaction. */
public final class EditorSession extends ViewModel {
    private static final String TOKEN = "xbs.editor.token";
    private static final String INVALID = "xbs.editor.unavailable";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private String scope, token;
    private JSONObject snapshot = new JSONObject();
    private boolean running, uncertain;
    private String problem = "";
    private XbsConfigApi.Result completed;
    private Consumer<XbsConfigApi.Result> listener;

    void initialize(Context context, Bundle state, String identity) {
        if (app != null) {
            if (!scope.equals(identity)) throw new IllegalStateException("Editor identity changed");
            return;
        }
        app = context.getApplicationContext(); scope = identity;
        token = state == null ? null : state.getString(TOKEN);
        if(state!=null&&state.getBoolean(INVALID,false)){token=UUID.randomUUID().toString();failRestore();return;}
        if (token == null) { token = UUID.randomUUID().toString(); return; }
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            token = UUID.randomUUID().toString(); failRestore(); return;
        }
        try {
            AtomicFile file = file();
            if (file.getBaseFile().length() > MAX_BYTES) throw new IllegalStateException("Draft too large");
            byte[] bytes = file.readFully();
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("Draft too large");
            JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (envelope.getInt("version") != 1 || !identity.equals(envelope.getString("scope"))) throw new IllegalStateException("Wrong draft identity");
            snapshot = envelope.getJSONObject("draft");
            uncertain = envelope.getBoolean("unacknowledged");
            if (uncertain) problem = "Previous save outcome is unknown. Review the configuration transaction journal and reopen the editor; the draft was not submitted again.";
        } catch (Exception e) { failRestore(); }
    }

    private void failRestore() {
        uncertain = true;
        problem = "The saved editor draft could not be restored safely. Reopen the editor before making changes.";
    }
    boolean blocked() { return uncertain; }
    boolean busy() { return running; }
    String problem() { return problem; }
    JSONObject snapshot() { return copy(snapshot); }
    void remember(JSONObject draft) { snapshot = copy(draft); }
    void clear() { snapshot = new JSONObject(); if (app != null) file().delete(); }
    void attach(Consumer<XbsConfigApi.Result> callback) { listener = callback; deliver(); }
    void detach() { listener = null; }

    boolean start(JSONObject draft, Callable<XbsConfigApi.Result> action) {
        if (running || uncertain || completed != null) return false;
        remember(draft);
        // A durable pending marker precedes any write, even if the activity is stopped immediately.
        if (!persist(true)) return false;
        running = true;
        executor.execute(() -> {
            XbsConfigApi.Result result;
            try { result = action.call(); }
            catch (Exception e) { result = XbsConfigApi.Result.error("Editor operation failed: " + e.getClass().getSimpleName()); }
            final XbsConfigApi.Result observed = result;
            main.post(() -> { running = false; completed = observed; deliver(); });
        });
        return true;
    }

    private void deliver() {
        if (completed == null || listener == null) return;
        XbsConfigApi.Result result = completed;
        completed = null;
        listener.accept(result);
    }

    boolean save(Bundle out, JSONObject draft) {
        remember(draft);
        out.putString(TOKEN, token);
        boolean ok=persist(running || uncertain || completed != null);
        out.putBoolean(INVALID,!ok);return ok;
    }

    private AtomicFile file() {
        return new AtomicFile(new File(app.getNoBackupFilesDir(), "xbs-editor-" + token + ".json"));
    }
    private boolean persist(boolean pending) {
        AtomicFile file = file(); FileOutputStream stream = null;
        try {
            JSONObject envelope = new JSONObject(); envelope.put("version", 1); envelope.put("scope", scope);
            envelope.put("unacknowledged", pending); envelope.put("draft", snapshot);
            byte[] data = envelope.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_BYTES) throw new IllegalStateException("Draft exceeds storage bound");
            stream = file.startWrite(); stream.write(data); file.finishWrite(stream); return true;
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            problem = "Cannot persist the private editor draft. No new save was started.";
            return false;
        }
    }

    static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid editor state", e); }
    }
    static void put(JSONObject value, String key, Object data) {
        try { value.put(key, data); } catch (Exception e) { throw new IllegalArgumentException("Invalid editor field", e); }
    }
    static JSONObject capture(Map<String, View> controls) {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, View> field : controls.entrySet()) {
            View view = field.getValue();
            view.setSaveEnabled(false);
            if (view instanceof EditText) put(result, field.getKey(), ((EditText)view).getText().toString());
            else if (view instanceof SwitchCompat) put(result, field.getKey(), ((SwitchCompat)view).isChecked());
            else if (view instanceof Spinner) put(result, field.getKey(), ((Spinner)view).getSelectedItemPosition());
        }
        return result;
    }
    static void restore(Map<String, View> controls, JSONObject fields) {
        if (fields == null) return;
        for (Map.Entry<String, View> field : controls.entrySet()) {
            String key = field.getKey(); View view = field.getValue();
            if (!fields.has(key)) continue;
            // Restore values only; capability/identity controls remain governed by the screen.
            if (view instanceof EditText) ((EditText)view).setText(fields.optString(key, ""));
            else if (view instanceof SwitchCompat) ((SwitchCompat)view).setChecked(fields.optBoolean(key));
            else if (view instanceof Spinner) {
                int index = fields.optInt(key, -1); Spinner spinner = (Spinner)view;
                if (index >= 0 && index < spinner.getCount()) spinner.setSelection(index);
            }
        }
    }
    @Override protected void onCleared() { listener = null; executor.shutdown(); }
}
