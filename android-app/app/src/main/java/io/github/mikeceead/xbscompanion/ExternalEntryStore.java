package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

final class ExternalEntryStore {
    private static final String PREFS="external_entry_v2";
    private ExternalEntryStore(){}
    static void record(Context c,String surface,String code,String host,boolean accepted,boolean senderVerified){long now=System.currentTimeMillis();c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putLong("at",now).putString("surface",surface).putString("code",code).putString("host",host).putBoolean("accepted",accepted).putBoolean("sender",senderVerified).putInt("version",TaskerRequestPolicy.VERSION).apply();}
    static ExternalEntryReport cached(Context c){SharedPreferences p=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);return new ExternalEntryReport(p.getLong("at",0),p.getString("surface",""),p.getString("code","missing"),p.getString("host",""),p.getBoolean("accepted",false),p.getBoolean("sender",false),p.getInt("version",0));}
}
