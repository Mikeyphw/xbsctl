package control

import "time"

// confirmedObservation separates daemon acks from actual observed runtime state.
// Reload requires a changed timestamp rather than a nonempty stale timestamp;
// R05B must additionally bind the committed configuration content digest.
func confirmedObservation(action, event string, before, after StatusSnapshot) bool {
    if !after.Available { return false }
    switch action {
    case "start", "stop":
        if event=="" {return false}
        present:=false
        for _,v:=range after.ActiveEvents {if v==event {present=true}}
        return present==(action=="start")
    case "pause":return after.Mode=="paused"
    case "resume":return after.Mode=="normal"
    case "safe":return after.Mode=="safe"
    case "reload":return after.LastReloadTime!="" && after.LastReloadTime!=before.LastReloadTime
    default:return false
    }
}

// A client-side timeout never decides whether a queued command executed.
// Poll only read-only status until a matching acknowledgement has *already*
// been obtained, and report unknown if the observation cannot be proven.
func waitForObservedState(action, event string, before StatusSnapshot, timeout time.Duration) bool {
    deadline:=time.Now().Add(timeout)
    for {
        after,code,_:=readSnapshot()
        if code==0 && confirmedObservation(action,event,before,after){return true}
        if !time.Now().Before(deadline){return false}
        time.Sleep(100*time.Millisecond)
    }
}
