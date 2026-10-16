package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class XbsRuleIndex {
    private static final String CACHE_KEY = "process_rule_index_v1";
    private static final long CACHE_AGE = 30_000L;
    private XbsRuleIndex() {}

    static Map<String, List<String>> load(Context context, boolean force) {
        if (!force) {
            String cached = XbsCache.get(context, CACHE_KEY, CACHE_AGE);
            Map<String, List<String>> parsed = parse(cached);
            if (!parsed.isEmpty()) return parsed;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        XbsConfigApi.Result loaded = XbsConfigApi.load(context, false);
        if (!loaded.ok) return result;
        JSONObject events = loaded.config.optJSONObject("events");
        if (events == null) return result;
        JSONObject template = events.optJSONObject("template");
        String templatePath = template == null ? "" : template.optString("proc_file", "");
        Iterator<String> names = events.keys();
        while (names.hasNext()) {
            String eventName = names.next(); if ("template".equals(eventName)) continue;
            JSONObject event = events.optJSONObject(eventName); if (event == null) continue;
            String path = event.has("proc_file") ? event.optString("proc_file", "") : templatePath;
            if (path.isEmpty()) continue;
            String text = XbsConfigApi.readText(context, path, false);
            for (String line : text.replace("\r", "").split("\n")) {
                String value = line.trim(); if (value.isEmpty() || value.startsWith("#")) continue;
                String process = value.split("\\s+", 2)[0].trim(); if (process.isEmpty()) continue;
                result.computeIfAbsent(process, ignored -> new ArrayList<>()).add(eventName);
            }
        }
        JSONObject json = new JSONObject();
        try { for (Map.Entry<String,List<String>> entry : result.entrySet()) json.put(entry.getKey(), new org.json.JSONArray(entry.getValue())); }
        catch (Exception ignored) {}
        XbsCache.put(context, CACHE_KEY, json.toString());
        return result;
    }

    static void invalidate(Context context) { XbsCache.invalidate(context, CACHE_KEY); }

    private static Map<String, List<String>> parse(String raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(raw); Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next(); org.json.JSONArray array = obj.optJSONArray(key); List<String> values = new ArrayList<>();
                if (array != null) for (int i = 0; i < array.length(); i++) { String value = array.optString(i); if (!value.isEmpty()) values.add(value); }
                result.put(key, Collections.unmodifiableList(values));
            }
        } catch (Exception ignored) {}
        return result;
    }
}
