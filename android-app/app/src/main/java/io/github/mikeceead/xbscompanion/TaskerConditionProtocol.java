package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.os.Bundle;
import java.security.SecureRandom;

final class TaskerConditionProtocol {
    static final String ACTION_EDIT_EVENT = "net.dinglisch.android.tasker.ACTION_EDIT_EVENT";
    static final String ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION";
    static final String ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION";
    static final String ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY";
    static final String EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY";
    static final String EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    static final String EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB";
    static final String EXTRA_PASS_THROUGH = "net.dinglisch.android.tasker.extras.PASS_THROUGH_DATA";
    static final String EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES";
    static final String EXTRA_RELEVANT = "net.dinglisch.android.tasker.RELEVANT_VARIABLES";
    static final String MESSAGE_ID = "net.dinglisch.android.tasker.MESSAGE_ID";

    static final int SATISFIED = 16;
    static final int UNSATISFIED = 17;
    static final int UNKNOWN = 18;

    static final String KEY_KIND = "kind";
    static final String KEY_FILTER = "filter";
    static final String KEY_STATE = "state";
    static final String KIND_EVENT = "event";
    static final String KIND_STATE = "state";

    private static final SecureRandom RANDOM = new SecureRandom();
    private TaskerConditionProtocol() {}

    static Intent result(Bundle bundle, String blurb) {
        Intent result = new Intent();
        result.putExtra(EXTRA_BUNDLE, bundle);
        result.putExtra(EXTRA_BLURB, blurb);
        result.putExtra(EXTRA_RELEVANT, relevantVariables());
        return result;
    }

    static Bundle passThrough(XbsEvent event, XbsSnapshot snapshot) {
        Bundle data = event.variables(snapshot);
        data.putInt(MESSAGE_ID, positiveId());
        Bundle safe = sanitizePassThrough(data);
        return safe;
    }

    static Bundle sanitizePassThrough(Bundle input) {
        if (input == null || input.size() > 32) return null;
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            MESSAGE_ID, "%xbs_event", "%xbs_event_id", "%xbs_event_name", "%xbs_event_action",
            "%xbs_runtime_state", "%xbs_safe_mode", "%xbs_active_profiles", "%xbs_payload_json",
            "%xbs_running", "%xbs_paused", "%xbs_active_count", "%xbs_inactive_profiles", "%xbs_available"));
        Bundle out = new Bundle(); int total = 0;
        for (String key : input.keySet()) {
            if (!allowed.contains(key)) return null;
            Object value = input.get(key);
            if (MESSAGE_ID.equals(key) && value instanceof Integer) { out.putInt(key, (Integer)value); continue; }
            if (!(value instanceof String)) return null;
            String text = (String)value; if (text.length() > 4096) return null; total += key.length() + text.length();
            if (total > 8192) return null; out.putString(key, text);
        }
        return out;
    }

    static String[] relevantVariables() {
        return new String[]{
            "%xbs_event\nEvent\nXBS event type and transition.",
            "%xbs_event_id\nEvent ID\nUnique event delivery identifier.",
            "%xbs_event_name\nEvent name\nProfile or runtime event name.",
            "%xbs_event_action\nEvent action\nActive, inactive, started, stopped, or failed.",
            "%xbs_runtime_state\nRuntime state\nCurrent XBS runtime mode.",
            "%xbs_safe_mode\nSafe mode\nWhether XBS safe mode is active.",
            "%xbs_active_profiles\nActive profiles\nComma-separated active profile names.",
            "%xbs_payload_json\nPayload JSON\nComplete machine-readable event payload."
        };
    }

    private static int positiveId() {
        int value;
        do value = RANDOM.nextInt(Integer.MAX_VALUE); while (value == 0);
        return value;
    }
}
