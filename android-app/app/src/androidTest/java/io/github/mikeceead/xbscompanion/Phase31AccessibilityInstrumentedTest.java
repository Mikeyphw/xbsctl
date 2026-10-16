package io.github.mikeceead.xbscompanion;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.test.espresso.accessibility.AccessibilityChecks;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Espresso Accessibility checks plus production pane, heading, and 48dp contracts. */
@RunWith(AndroidJUnit4.class)
public final class Phase31AccessibilityInstrumentedTest {
    @BeforeClass public static void enableAccessibilityChecks() {
        AccessibilityChecks.enable().setRunChecksFromRootView(true);
    }

    @Rule public final ActivityScenarioRule<FinalReleaseSealActivity> activityRule =
        new ActivityScenarioRule<>(FinalReleaseSealActivity.class);

    @Test public void finalReleaseSealPassesEspressoAccessibilityAndProductionSemantics() {
        onView(isRoot()).check(matches(isDisplayed()));
        activityRule.getScenario().onActivity(activity -> {
            View root = activity.getWindow().getDecorView();
            CharSequence pane = findPane(root);
            assertNotNull("accessibility pane title missing", pane);
            assertFalse(pane.toString().trim().isEmpty());
            assertTrue("primary heading missing", hasHeading(root));
            assertTouchTargets(root, Math.round(48f * activity.getResources().getDisplayMetrics().density));
        });
    }

    private static CharSequence findPane(View view) {
        CharSequence own = ViewCompat.getAccessibilityPaneTitle(view);
        if (own != null && !own.toString().trim().isEmpty()) return own;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CharSequence found = findPane(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasHeading(View view) {
        if (ViewCompat.isAccessibilityHeading(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasHeading(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void assertTouchTargets(View view, int minimum) {
        if (view.isClickable() || view.isLongClickable()) {
            assertTrue("short touch target: " + view.getClass().getSimpleName(),
                view.getMinimumHeight() >= minimum && view.getMinimumWidth() >= minimum);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertTouchTargets(group.getChildAt(i), minimum);
            }
        }
    }
}
