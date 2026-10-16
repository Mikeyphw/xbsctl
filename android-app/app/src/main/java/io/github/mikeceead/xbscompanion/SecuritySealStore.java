package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

final class SecuritySealStore {
    private static final String PREFS="security_seal_v1",KEY="report";
    private SecuritySealStore(){}
    static void save(Context c,SecuritySealReport r){if(c==null||r==null)return;try{JSONObject o=new JSONObject().put("at",r.capturedAt).put("error",r.error);JSONArray a=new JSONArray();for(SecuritySealReport.Item i:r.items)a.put(new JSONObject().put("id",i.id).put("title",i.title).put("detail",i.detail).put("pass",i.pass));o.put("items",a);c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,o.toString()).apply();}catch(Exception ignored){}}
    static SecuritySealReport cached(Context c){try{String raw=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");if(raw.isEmpty())return SecuritySealReport.empty();JSONObject o=new JSONObject(raw);JSONArray a=o.optJSONArray("items");ArrayList<SecuritySealReport.Item> items=new ArrayList<>();if(a!=null)for(int n=0;n<a.length();n++){JSONObject i=a.optJSONObject(n);if(i!=null)items.add(new SecuritySealReport.Item(i.optString("id"),i.optString("title"),i.optString("detail"),i.optBoolean("pass")));}return new SecuritySealReport(o.optLong("at"),items,o.optString("error"));}catch(Exception ignored){return SecuritySealReport.empty();}}
}
