package io.github.mikeceead.xbscompanion;

/** Bounded local UI timing evidence. Values are observational, never release-performance claims. */
final class PerformanceReport {
    static final long STALE_AFTER_MS = 7L * 24L * 60L * 60_000L;
    final long capturedAt, startupMs, lastRebuildMs, maxRebuildMs;
    final int rebuildCount, slowRebuildCount, coalescedUpdateCount;
    PerformanceReport(long capturedAt,long startupMs,long lastRebuildMs,long maxRebuildMs,int rebuildCount,int slow,int coalesced){
        this.capturedAt=Math.max(0,capturedAt);this.startupMs=Math.max(0,startupMs);this.lastRebuildMs=Math.max(0,lastRebuildMs);
        this.maxRebuildMs=Math.max(0,maxRebuildMs);this.rebuildCount=Math.max(0,rebuildCount);this.slowRebuildCount=Math.max(0,slow);this.coalescedUpdateCount=Math.max(0,coalesced);
    }
    static PerformanceReport empty(){return new PerformanceReport(0,0,0,0,0,0,0);}
    boolean present(){return capturedAt>0;}
    boolean fresh(long now){return present()&&now>=capturedAt&&now-capturedAt<=STALE_AFTER_MS;}
    String summary(){if(!present())return "No local UI timing evidence yet";return "startup="+startupMs+"ms · rebuild="+lastRebuildMs+"ms · slow="+slowRebuildCount+" · coalesced="+coalescedUpdateCount;}
    String supportText(){return "captured_at="+capturedAt+"\nstartup_ms="+startupMs+"\nlast_rebuild_ms="+lastRebuildMs+"\nmax_rebuild_ms="+maxRebuildMs+"\nrebuild_count="+rebuildCount+"\nslow_rebuild_count="+slowRebuildCount+"\ncoalesced_updates="+coalescedUpdateCount+"\n";}
}
