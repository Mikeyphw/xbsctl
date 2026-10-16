package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

/** Bounded runtime evidence. Writes are asynchronous and contain no user content. */
final class UiQualityStore {
    private static final String PREFS="ui_quality_v2";
    private UiQualityStore(){}
    static void recordAccessibility(Context c,UiQualityReport.AccessibilityResult r){if(c==null||r==null)return;prefs(c).edit().putLong("at",System.currentTimeMillis()).putInt("controls",r.controls).putInt("adjusted",r.adjusted).putInt("failures",r.failures).putInt("headings",r.headings).putBoolean("pane_ready",r.paneReady).putString("pane_title",r.paneTitle).apply();}
    static void recordLayout(Context c,int widthDp,AdaptiveLayoutPolicy.WidthClass widthClass,boolean rail,int maxWidthDp,boolean constrained){if(c==null)return;prefs(c).edit().putLong("at",System.currentTimeMillis()).putInt("width",Math.max(0,widthDp)).putString("width_class",widthClass==null?"":widthClass.name()).putBoolean("rail",rail).putInt("max_width",Math.max(0,maxWidthDp)).putBoolean("constrained",constrained).apply();}
    static UiQualityReport cached(Context c){SharedPreferences p=prefs(c);return new UiQualityReport(p.getLong("at",0),p.getInt("controls",0),p.getInt("adjusted",0),p.getInt("failures",0),p.getInt("headings",0),p.getBoolean("pane_ready",false),p.getString("pane_title",""),p.getInt("width",0),p.getString("width_class",""),p.getBoolean("rail",false),p.getInt("max_width",0),p.getBoolean("constrained",false));}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
}
