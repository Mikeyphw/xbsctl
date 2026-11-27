package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;
import java.util.Map;

/** Only these editor operations are injectable in debug instrumentation. Release always uses ROOT. */
interface EditorBackend {
    XbsConfigApi.Result load(Context context, boolean force);
    RootFileSnapshot readList(Context context, String path);
    XbsConfigTransaction.Record latest(Context context);
    XbsConfigApi.Result save(Context context, JSONObject config, Map<String,String> files, String expectedConfig, Map<String,String> expectedLists);
    XbsConfigApi.Result delete(Context context, String name, boolean files);
    XbsConfigApi.Result transaction(Context context, XbsConfigTransaction.Record record, boolean recover);
    XbsConfigApi.Result defaults(Context context);

    EditorBackend ROOT = new EditorBackend() {
        public XbsConfigApi.Result load(Context c, boolean f) { return XbsConfigApi.load(c,f); }
        public RootFileSnapshot readList(Context c,String p) { return XbsConfigApi.readList(p); }
        public XbsConfigTransaction.Record latest(Context c) { return XbsConfigApi.latestTransaction(c); }
        public XbsConfigApi.Result save(Context c,JSONObject o,Map<String,String> f,String g,Map<String,String> lists) { return XbsConfigApi.save(c,o,f,java.util.Collections.emptySet(),true,g,lists); }
        public XbsConfigApi.Result delete(Context c,String n,boolean f) { return XbsConfigApi.deleteEvent(c,n,f); }
        public XbsConfigApi.Result transaction(Context c,XbsConfigTransaction.Record r,boolean recover) { return recover?XbsConfigApi.recoverLatest(c,r):XbsConfigApi.rollbackLatest(c,r); }
        public XbsConfigApi.Result defaults(Context c) { return XbsConfigApi.readDefaultEssentials(c); }
    };
}
