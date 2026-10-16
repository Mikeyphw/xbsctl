package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AppCatalog {
    private static final String CACHE_KEY = "installed_apps_v2";
    private static final long CACHE_AGE = 30L * 60L * 1000L;
    private AppCatalog() {}

    static List<InstalledApp> load(Context context, boolean force) {
        if (!force) {
            String cached = XbsCache.get(context, CACHE_KEY, CACHE_AGE);
            List<InstalledApp> parsed = parse(cached);
            if (!parsed.isEmpty()) return parsed;
        }
        Map<String, InstalledApp> apps = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo info : installed) {
                CharSequence labelValue = pm.getApplicationLabel(info);
                String label = labelValue == null ? info.packageName : labelValue.toString();
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                apps.put(info.packageName, new InstalledApp(info.packageName, label, info.uid, system));
            }
        } catch (Exception ignored) {}

        // Root fallback fills packages hidden by Android package-visibility rules.
        XbsRootApi.CommandOutput output = XbsRootApi.runRoot("(cmd package list packages -U 2>/dev/null || pm list packages -U 2>/dev/null) | sort -u");
        if (output.exitCode == 0) {
            for (String line : output.stdout.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("package:")) continue;
                String body = trimmed.substring(8);
                int uidIndex = body.lastIndexOf(" uid:");
                String pkg = uidIndex >= 0 ? body.substring(0, uidIndex).trim() : body.trim();
                int uid = -1;
                if (uidIndex >= 0) try { uid = Integer.parseInt(body.substring(uidIndex + 5).trim()); } catch (Exception ignored) {}
                if (pkg.isEmpty() || apps.containsKey(pkg)) continue;
                apps.put(pkg, new InstalledApp(pkg, pkg, uid, uid >= 0 && uid < 10000));
            }
        }

        List<InstalledApp> result = new ArrayList<>(apps.values());
        Collections.sort(result, Comparator.comparing((InstalledApp app) -> app.label.toLowerCase(java.util.Locale.ROOT))
            .thenComparing(app -> app.packageName));
        JSONArray array = new JSONArray(); for (InstalledApp app : result) array.put(app.toJson());
        XbsCache.put(context, CACHE_KEY, array.toString());
        return result;
    }

    static Map<String, InstalledApp> byPackage(Context context) {
        Map<String, InstalledApp> map = new LinkedHashMap<>();
        for (InstalledApp app : load(context, false)) map.put(app.packageName, app);
        return map;
    }

    static Map<Integer, List<InstalledApp>> byUid(Context context) {
        Map<Integer, List<InstalledApp>> map = new LinkedHashMap<>();
        for (InstalledApp app : load(context, false)) map.computeIfAbsent(app.uid, ignored -> new ArrayList<>()).add(app);
        return map;
    }

    static void invalidate(Context context) { XbsCache.invalidate(context, CACHE_KEY); }

    private static List<InstalledApp> parse(String raw) {
        List<InstalledApp> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) { JSONObject obj = array.optJSONObject(i); if (obj != null) result.add(InstalledApp.fromJson(obj)); }
        } catch (Exception ignored) {}
        return result;
    }
}
