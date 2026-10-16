package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores only bounded timing counters, never traces or user content. Writes are asynchronous because this evidence is not security-critical. */
final class PerformanceStore {
    private static final String PREFS="ui_performance_v1";
    private static final long SLOW_REBUILD_MS=48L;
    private PerformanceStore(){}
    static void recordStartup(Context context,long durationMs){if(context==null)return;SharedPreferences p=prefs(context);p.edit().putLong("at",System.currentTimeMillis()).putLong("startup",bounded(durationMs)).apply();}
    static void recordRebuild(Context context,long durationMs){if(context==null)return;SharedPreferences p=prefs(context);long d=bounded(durationMs);int count=safeInc(p.getInt("count",0));int slow=p.getInt("slow",0);if(d>SLOW_REBUILD_MS)slow=safeInc(slow);long max=Math.max(p.getLong("max",0),d);p.edit().putLong("at",System.currentTimeMillis()).putLong("last",d).putLong("max",max).putInt("count",count).putInt("slow",slow).apply();}
    static void recordCoalesced(Context context){if(context==null)return;SharedPreferences p=prefs(context);p.edit().putLong("at",System.currentTimeMillis()).putInt("coalesced",safeInc(p.getInt("coalesced",0))).apply();}
    static PerformanceReport cached(Context context){SharedPreferences p=prefs(context);return new PerformanceReport(p.getLong("at",0),p.getLong("startup",0),p.getLong("last",0),p.getLong("max",0),p.getInt("count",0),p.getInt("slow",0),p.getInt("coalesced",0));}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static long bounded(long value){return Math.max(0,Math.min(value,120_000L));}
    private static int safeInc(int value){return value>=1_000_000?1_000_000:value+1;}
}
