package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** User-chosen, never hard-coded event targets. An unavailable daemon cannot authorize a new binding. */
final class XbsTileConfiguration {
    private static final String PREFS="gx04_tile_configuration_v1";
    private XbsTileConfiguration() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private static String key(XbsTileDecision.Kind kind) {
        if(kind==XbsTileDecision.Kind.SAVER)return "saver_event";
        if(kind==XbsTileDecision.Kind.PROFILES)return "profiles_event";
        return "";
    }
    static String selected(Context c,XbsTileDecision.Kind kind) {
        String k=key(kind);
        if(k.isEmpty())return "";
        try{
            String value=prefs(c).getString(k,"");
            return valid(value)?value:"";
        }catch(RuntimeException corrupt){return "";}
    }
    static boolean valid(String event) {
        try { return event!=null && !event.trim().isEmpty()
            && event.equals(SuiteAction.validateArgument(event,true)); }
        catch(IllegalArgumentException error) {return false;}
    }
    static List<String> candidates(XbsSnapshot snap) {
        LinkedHashSet<String> values=new LinkedHashSet<>();
        if(snap!=null && snap.available){
            for(String name:snap.active)if(valid(name))values.add(name);
            for(String name:snap.inactive)if(valid(name))values.add(name);
        }
        return new ArrayList<>(values);
    }
    static boolean select(Context context,XbsTileDecision.Kind kind,String event,
                          XbsSnapshot snap,EventBridgeReport bridge,long now) {
        String k=key(kind);
        if(k.isEmpty() || !valid(event)
           || !XbsTileController.trusted(snap,bridge,now)
           || !candidates(snap).contains(event))return false;
        return prefs(context).edit().putString(k,event).commit();
    }
    static boolean clear(Context c,XbsTileDecision.Kind kind) {
        String k=key(kind);
        return !k.isEmpty() && prefs(c).edit().remove(k).commit();
    }
    static boolean clearAll(Context c) {return prefs(c).edit().clear().commit();}
}
