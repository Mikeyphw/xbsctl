package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.view.ViewCompat;

/** Applies and audits whole-screen accessibility semantics, including controls created outside UiKit. */
final class AccessibilityUiPolicy {
    static final int MIN_TOUCH_DP = 48;
    private AccessibilityUiPolicy() {}

    static UiQualityReport.AccessibilityResult apply(Context context, View root, String paneTitle) {
        if (context == null || root == null) return UiQualityReport.AccessibilityResult.empty();
        int minPx = Math.round(MIN_TOUCH_DP * context.getResources().getDisplayMetrics().density);
        String title = clean(paneTitle);
        if (title.isEmpty()) title = "XBS Companion";
        ViewCompat.setAccessibilityPaneTitle(root, title);
        Counter counter = new Counter();
        visit(root, minPx, counter);
        if (counter.heading == null) counter.heading = firstLargeTitle(root);
        if (counter.heading != null) {
            ViewCompat.setAccessibilityHeading(counter.heading, true);
            counter.headingCount = 1;
        }
        return new UiQualityReport.AccessibilityResult(
            counter.interactiveCount, counter.adjustedCount, counter.failedCount,
            counter.headingCount, !title.isEmpty(), title);
    }

    static String screenTitle(Class<?> activityClass) {
        String name = activityClass == null ? "" : activityClass.getSimpleName();
        switch (name) {
            case "MainActivity": return "XBS Companion";
            case "ActivityWorkbenchActivity": return "Activity Workbench";
            case "AppPickerActivity": return "Application Picker";
            case "CliCommandActivity": return "Command Center";
            case "DebugWorkbenchActivity": return "Debug Workbench";
            case "EventControlActivity": return "Event Controls";
            case "GuidedFixActivity": return "Guided Fixes";
            case "MoreSettingsActivity": return "More and Settings";
            case "ProcessMonitorActivity": return "Process Monitor";
            case "ReleaseReadinessActivity": return "Release Readiness";
            case "FinalReleaseSealActivity": return "Final Release Seal";
            case "RootDiagnosticsActivity": return "Root Diagnostics";
            case "SecuritySealActivity": return "Security Seal";
            case "TaskerEventEditActivity": return "Tasker Event";
            case "TaskerPluginActivity": return "Tasker CLI Action";
            case "TaskerStateEditActivity": return "Tasker State";
            case "XbsConfigActivity": return "XBS Configuration";
            case "XbsEssentialsActivity": return "Default Essentials";
            case "XbsEventEditorActivity": return "Event Editor";
            default:
                String spaced = name.replace("Activity", "").replaceAll("([a-z])([A-Z])", "$1 $2").trim();
                return spaced.isEmpty() ? "XBS Companion" : spaced;
        }
    }

    private static void visit(View view, int minPx, Counter counter) {
        if (interactive(view)) {
            counter.interactiveCount++;
            boolean changed = false;
            if (view.getMinimumHeight() < minPx) { view.setMinimumHeight(minPx); changed = true; }
            if (view.getMinimumWidth() < minPx) { view.setMinimumWidth(minPx); changed = true; }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                if (params.height > 0 && params.height < minPx) { params.height = minPx; changed = true; }
                if (params.width > 0 && params.width < minPx) { params.width = minPx; changed = true; }
                if (changed) view.setLayoutParams(params);
            }
            if (changed) counter.adjustedCount++;
            if (view.getMinimumHeight() < minPx || view.getMinimumWidth() < minPx) counter.failedCount++;
        }
        if (view instanceof TextView && counter.heading == null) {
            TextView text = (TextView)view;
            float sp = text.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
            if (sp >= 22f && text.getText() != null && text.getText().length() > 0) counter.heading = text;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i), minPx, counter);
        }
    }

    private static TextView firstLargeTitle(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            float sp = text.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
            if (sp >= 20f && text.getText() != null && text.getText().length() > 0) return text;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstLargeTitle(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean interactive(View view) {
        return view instanceof Spinner || view instanceof CompoundButton || view instanceof EditText
            || view.isClickable() || view.isLongClickable();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static final class Counter {
        int interactiveCount, adjustedCount, failedCount, headingCount;
        TextView heading;
    }
}
