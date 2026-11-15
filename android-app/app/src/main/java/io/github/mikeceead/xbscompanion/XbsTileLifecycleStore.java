package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

/** Aggregate private SystemUI callback counts; never stores account, action or command data. */
final class XbsTileLifecycleStore {
    private static final String PREFS="gx04_tile_lifecycle_v1";
    private XbsTileLifecycleStore() {}
    static void record(Context c,XbsTileDecision.Kind kind,String callback) {
        if(c==null || kind==null || !isValid(callback))return;
        try {
            SharedPreferences p=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String key=callback+"_"+kind.name();
            int prior=Math.max(0,p.getInt(key,0));
            // Local-only diagnostic; never allow a counter overflow or block the callback for a disk sync.
            p.edit().putInt(key,prior==Integer.MAX_VALUE?prior:prior+1).apply();
        }catch(RuntimeException ignored){/* Recording is never permission to execute a command. */}
    }
    private static boolean isValid(String callback){
        return "added".equals(callback)||"clicked".equals(callback)
            ||"removed".equals(callback)||"listening".equals(callback)
            ||"stopped".equals(callback);
    }
}
