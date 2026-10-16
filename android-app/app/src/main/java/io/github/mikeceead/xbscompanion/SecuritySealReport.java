package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Passive manifest, path, external-entry, and stale-action security evidence. */
final class SecuritySealReport {
    static final class Item {final String id,title,detail;final boolean pass;Item(String i,String t,String d,boolean p){id=i;title=t;detail=d;pass=p;}}
    final long capturedAt; final List<Item> items; final String error;
    SecuritySealReport(long at,List<Item> items,String error){capturedAt=Math.max(0,at);this.items=Collections.unmodifiableList(new ArrayList<>(items));this.error=error==null?"":error.trim();}
    static SecuritySealReport empty(){return new SecuritySealReport(0,Collections.emptyList(),"");}
    boolean evaluated(){return capturedAt>0;}
    int blockerCount(){int n=0;for(Item i:items)if(!i.pass)n++;return n;}
    String headline(){if(!evaluated())return "Security seal not evaluated";return blockerCount()==0?"Security seal passing":blockerCount()+" security blocker(s)";}
    String supportText(){StringBuilder out=new StringBuilder();out.append("captured_at=").append(capturedAt).append(" blockers=").append(blockerCount()).append('\n');if(!error.isEmpty())out.append("error=").append(clean(error)).append('\n');for(Item i:items)out.append(i.id).append('=').append(i.pass?"pass":"blocker").append(" · ").append(clean(i.detail)).append('\n');return out.toString();}
    private static String clean(String v){String x=v==null?"":v.replace('\n',' ').replace('\r',' ').trim();return x.length()<=240?x:x.substring(0,240);}
}
