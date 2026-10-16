package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class XbsStateStore {
    static final String ACTION_STATE_UPDATED = "io.github.mikeceead.xbscompanion.STATE_UPDATED";
    private static final String PREFS = "xbs_state";
    private static final String SNAPSHOT = "snapshot";
    private static final String EVENTS = "events";
    private static final int MAX_EVENTS = 180;
    private static final int MAX_SNAPSHOT_CHARS = 128 * 1024;
    private static final int MAX_EVENTS_CHARS = 256 * 1024;

    private XbsStateStore() {}

    static synchronized XbsSnapshot current(Context context) {
        try {
            String raw = prefs(context).getString(SNAPSHOT, "");
            if (raw == null || raw.isEmpty()) return XbsSnapshot.unavailable("Status has not been refreshed yet");
            if (raw.length() > MAX_SNAPSHOT_CHARS) return XbsSnapshot.unavailable("Stored state exceeds debug cache bounds");
            return XbsSnapshot.fromJson(new JSONObject(raw));
        } catch (Exception error) { return XbsSnapshot.unavailable("Stored state is unreadable"); }
    }

    static synchronized void save(Context context, XbsSnapshot snapshot, XbsEvent event) {
        save(context, snapshot, event == null ? Collections.emptyList() : Collections.singletonList(event));
    }

    static synchronized void save(Context context, XbsSnapshot snapshot, List<XbsEvent> newEvents) {
        SharedPreferences.Editor editor = prefs(context).edit();
        try { editor.putString(SNAPSHOT, snapshot.toJson().toString()); } catch (Exception ignored) {}
        if (newEvents != null && !newEvents.isEmpty()) {
            List<XbsEvent> all = events(context);
            for (int i = newEvents.size() - 1; i >= 0; i--) {
                XbsEvent event = newEvents.get(i); if (event == null) continue;
                boolean duplicate = false; for (XbsEvent old : all) if (old.id.equals(event.id)) { duplicate = true; break; }
                if (!duplicate) all.add(0, event);
            }
            if (all.size() > MAX_EVENTS) all = new ArrayList<>(all.subList(0, MAX_EVENTS));
            JSONArray array = new JSONArray(); for (XbsEvent item : all) try { array.put(item.toJson()); } catch (Exception ignored) {}
            editor.putString(EVENTS, array.toString());
        }
        editor.apply();
        EvidenceStateCoordinator.publish(context, "runtime", snapshot.capturedAt);
        context.sendBroadcast(new Intent(ACTION_STATE_UPDATED).setPackage(context.getPackageName()));
    }

    static synchronized List<XbsEvent> events(Context context) {
        List<XbsEvent> out = new ArrayList<>();
        try { String raw = prefs(context).getString(EVENTS, "[]"); if (raw != null && raw.length() <= MAX_EVENTS_CHARS) { JSONArray array = new JSONArray(raw); for (int i=0;i<array.length() && out.size()<MAX_EVENTS;i++){JSONObject obj=array.optJSONObject(i);if(obj!=null){XbsEvent event=XbsEvent.fromJson(obj);if(event!=null)out.add(event);}} } }
        catch (Exception ignored) {}
        return out;
    }

    static synchronized void clearEvents(Context context) { prefs(context).edit().remove(EVENTS).apply(); context.sendBroadcast(new Intent(ACTION_STATE_UPDATED).setPackage(context.getPackageName())); }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
