package io.github.mikeceead.xbscompanion;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

final class XbsStateProcessor {
    private XbsStateProcessor() {}

    static XbsEvent process(Context context, XbsSnapshot current, boolean emitFeedback) {
        XbsSnapshot previous = XbsStateStore.current(context);
        boolean stateChanged = !current.equivalentState(previous);
        List<XbsEvent> delivered = new ArrayList<>();
        XbsEvent journal = current.lastChange;
        if (journal != null && (previous.lastChange == null || !journal.id.equals(previous.lastChange.id))) delivered.add(journal);
        for (XbsEvent event : synthesizeAll(previous, current)) {
            boolean duplicate = false; for (XbsEvent existing : delivered) if (existing.type.equals(event.type) && existing.name.equals(event.name) && existing.state.equals(event.state)) { duplicate=true; break; }
            if (!duplicate) delivered.add(event);
        }
        XbsEvent last = delivered.isEmpty() ? current.lastChange : delivered.get(delivered.size()-1);
        XbsSnapshot stored = new XbsSnapshot(current.available,current.safeMode,current.paused,current.mode,current.active,current.inactive,last,current.rawJson,current.error,current.capturedAt);
        XbsStateStore.save(context, stored, delivered);
        if (AppSettings.monitorEnabled(context)) new NotificationController(context).postStatus(stored);
        if (emitFeedback) {
            for (XbsEvent event : delivered) {
                EventFeedbackController.onEvent(context, event);
                if (event.isImportant()) new NotificationController(context).postImportant(event);
                TaskerEventDispatcher.eventChanged(context, event, stored);
            }
            if (delivered.isEmpty() && stateChanged) TaskerEventDispatcher.stateChanged(context);
        }
        return delivered.isEmpty() ? null : delivered.get(0);
    }

    private static List<XbsEvent> synthesizeAll(XbsSnapshot previous, XbsSnapshot current) {
        List<XbsEvent> out = new ArrayList<>(); if (previous == null) return out;
        if (previous.available != current.available) out.add(current.available ? XbsEvent.runtime("runtime","recovered","Runtime recovered","info") : XbsEvent.runtime("runtime","unavailable","Runtime unavailable","error"));
        if (previous.paused != current.paused) out.add(current.paused ? XbsEvent.runtime("runtime","paused","Runtime paused","warning") : XbsEvent.runtime("runtime","resumed","Runtime resumed","info"));
        if (previous.safeMode != current.safeMode) out.add(XbsEvent.runtime("safe_mode",current.safeMode?"active":"inactive",current.safeMode?"Safe mode enabled":"Safe mode disabled",current.safeMode?"warning":"info"));
        for (String name : current.active) if (!previous.active.contains(name)) out.add(XbsEvent.profile(name,true));
        for (String name : previous.active) if (!current.active.contains(name)) out.add(XbsEvent.profile(name,false));
        return out;
    }
}
