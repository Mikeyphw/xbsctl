package io.github.mikeceead.xbscompanion;

/** Pure QS status policy. A launched command is never evidence of daemon completion. */
final class XbsTileDecision {
    enum Kind { PAUSE, SAFE, SAVER, PROFILES, RESTORE }
    enum Phase { UNAVAILABLE, INACTIVE, ACTIVE, PENDING, FAILED, RECOVERY_REQUIRED }
    final Phase phase;
    final String title;
    final String detail;
    final String action;
    private XbsTileDecision(Phase phase,String title,String detail,String action){
        this.phase=phase;this.title=title;this.detail=detail;this.action=action;
    }
    static XbsTileDecision evaluate(Kind kind,boolean observed,boolean paused,boolean safe,
                                    boolean pending,boolean failed,boolean recovery) {
        if(recovery)return new XbsTileDecision(Phase.RECOVERY_REQUIRED,label(kind),"Review in XBS",null);
        if(failed)return new XbsTileDecision(Phase.FAILED,label(kind),"Last request failed",null);
        if(pending)return new XbsTileDecision(Phase.PENDING,label(kind),"Awaiting verified state",null);
        if(kind==Kind.RESTORE)return new XbsTileDecision(Phase.INACTIVE,label(kind),"App confirmation required",null);
        if(kind==Kind.SAVER||kind==Kind.PROFILES)
            return new XbsTileDecision(Phase.INACTIVE,label(kind),"Choose an event in XBS",null);
        if(!observed)return new XbsTileDecision(Phase.UNAVAILABLE,label(kind),"State not verified",null);
        boolean enabled=kind==Kind.PAUSE?paused:safe;
        String action=kind==Kind.PAUSE?(paused?"resume":"pause"):(safe?"safe_off":"safe_on");
        return new XbsTileDecision(enabled?Phase.ACTIVE:Phase.INACTIVE,label(kind),
            enabled?"Verified active":"Verified inactive",action);
    }
    static XbsTileDecision event(Kind kind,boolean observed,boolean configured,boolean active,
                                  boolean safeOrPaused,boolean pending,boolean failed,boolean recovery) {
        if(kind!=Kind.SAVER && kind!=Kind.PROFILES)
            return new XbsTileDecision(Phase.UNAVAILABLE,label(kind),"Unsupported event tile",null);
        if(recovery)return new XbsTileDecision(Phase.RECOVERY_REQUIRED,label(kind),"Review in XBS",null);
        if(failed)return new XbsTileDecision(Phase.FAILED,label(kind),"Last request failed",null);
        if(pending)return new XbsTileDecision(Phase.PENDING,label(kind),"Awaiting verified state",null);
        if(!configured)return new XbsTileDecision(Phase.INACTIVE,label(kind),"Choose an event in XBS",null);
        if(!observed)return new XbsTileDecision(Phase.UNAVAILABLE,label(kind),"State not verified",null);
        if(safeOrPaused)return new XbsTileDecision(Phase.UNAVAILABLE,label(kind),"Runtime paused or in safe mode",null);
        return new XbsTileDecision(active?Phase.ACTIVE:Phase.INACTIVE,label(kind),
            active?"Event confirmed active":"Event confirmed inactive",active?"event_stop":"event_run");
    }
    private static String label(Kind kind){
        switch(kind){
            case PAUSE:return "XBS Pause";
            case SAFE:return "XBS Safe Mode";
            case SAVER:return "XBS Saver";
            case PROFILES:return "XBS Profiles";
            default:return "XBS Restore Defaults";
        }
    }
}
