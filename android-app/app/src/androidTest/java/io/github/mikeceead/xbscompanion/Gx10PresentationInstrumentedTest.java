package io.github.mikeceead.xbscompanion;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/** Real rootless API36 presentation assertions. Never dispatch a privileged action. */
@RunWith(AndroidJUnit4.class)
public final class Gx10PresentationInstrumentedTest {
    private static final int[] NAV={R.id.xbs_nav_overview,R.id.xbs_nav_events,
        R.id.xbs_nav_processes,R.id.xbs_nav_activity,R.id.xbs_nav_settings};
    private static final int[] LABELS={R.string.xbs_page_overview,R.string.xbs_page_events,
        R.string.xbs_page_processes,R.string.xbs_page_activity,R.string.xbs_page_settings};

    private static Instrumentation instrument() { return InstrumentationRegistry.getInstrumentation(); }
    private static Context app() { return ApplicationProvider.getApplicationContext(); }
    private static ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(app(),MainActivity.class).putExtra("page",0));
    }
    private static void waitFor(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> ready) {
        long deadline=SystemClock.elapsedRealtime()+25000;
        while (SystemClock.elapsedRealtime()<deadline) {
            instrument().waitForIdleSync();
            if (scenario.getState()==Lifecycle.State.RESUMED) {
                AtomicBoolean observed=new AtomicBoolean(false);
                scenario.onActivity(a->observed.set(ready.test(a)));
                if (observed.get()) return;
            }
            SystemClock.sleep(150);
        }
        fail("Actual activity configuration/layout did not settle before deadline");
    }
    private static void waitForLayout(ActivityScenario<MainActivity> scenario) {
        waitFor(scenario,a->{View root=a.findViewById(R.id.xbs_main_root);
            return root!=null && root.isLaidOut() && ViewCompat.getRootWindowInsets(root)!=null;});
    }
    private static void assertVisible(View view) {
        assertNotNull(view);
        Rect actual=new Rect();
        String name=view.getId()==View.NO_ID?view.getClass().getSimpleName():view.getResources().getResourceEntryName(view.getId());
        assertTrue("view not visible: "+name,view.getGlobalVisibleRect(actual));
        Rect local=new Rect();view.getLocalVisibleRect(local);
        String detail=name+" measured="+view.getWidth()+"x"+view.getHeight()+" visible="+actual+" local="+local;
        assertEquals("horizontal clipping: "+detail,view.getWidth(),actual.width());
        assertEquals("vertical clipping: "+detail,view.getHeight(),actual.height());
        assertTrue(view.getWidth()>0 && view.getHeight()>0);
    }
    private static void assertTextFits(TextView view) {
        assertVisible(view);
        Layout layout=view.getLayout();
        assertNotNull("text layout missing",layout);
        assertTrue(layout.getLineCount()>0);
        int available=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom();
        assertTrue("text clipped vertically: "+view.getId(),layout.getLineBottom(layout.getLineCount()-1)<=available+1);
        for(int line=0;line<layout.getLineCount();line++) {
            assertEquals("ellipsis on required control",0,layout.getEllipsisCount(line));
            assertTrue("text overflows right",layout.getLineRight(line)<=layout.getWidth()+1f);
            assertTrue("text overflows left",layout.getLineLeft(line)>=-1f);
        }
    }
    private static void assertNavigation(MainActivity a) {
        View root=a.findViewById(R.id.xbs_main_root);
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(root);
        assertNotNull(insets);
        Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        assertTrue(root.getPaddingLeft()>=bars.left && root.getPaddingRight()>=bars.right);
        assertTrue(root.getPaddingTop()>=bars.top && root.getPaddingBottom()>=bars.bottom);
        CharSequence pane=ViewCompat.getAccessibilityPaneTitle(root);
        assertNotNull(pane);
        assertFalse(pane.toString().isEmpty());
        TextView title=a.findViewById(R.id.xbs_main_title);
        assertTrue(ViewCompat.isAccessibilityHeading(title));
        assertTextFits(title);
        int minimum=Math.round(48*a.getResources().getDisplayMetrics().density);
        LinearLayout navigation=a.findViewById(R.id.xbs_main_navigation);
        assertFalse("multiline touch targets must not be baseline-aligned",navigation.isBaselineAligned());
        for(int i=0;i<NAV.length;i++) {
            Button nav=a.findViewById(NAV[i]);
            assertNotNull(nav);
            assertTrue(nav.getWidth()>=minimum && nav.getHeight()>=minimum);
            assertTrue("navigation target escapes its padded parent",nav.getLeft()>=navigation.getPaddingLeft()
                && nav.getRight()<=navigation.getWidth()-navigation.getPaddingRight()
                && nav.getTop()>=navigation.getPaddingTop()
                && nav.getBottom()<=navigation.getHeight()-navigation.getPaddingBottom());
            if(navigation.getOrientation()==LinearLayout.HORIZONTAL) {
                assertEquals(navigation.getPaddingTop(),nav.getTop());
                assertEquals(navigation.getHeight()-navigation.getPaddingBottom(),nav.getBottom());
            }
            assertTextFits(nav);
            AccessibilityNodeInfo node=nav.createAccessibilityNodeInfo();
            assertTrue(node.isClickable() && node.isFocusable() && node.isVisibleToUser());
            assertEquals(a.getString(LABELS[i]),String.valueOf(node.getContentDescription()));
            assertEquals(a.getString(LABELS[i]).contentEquals(title.getText()),node.isSelected());
        }
        assertTrue(a.findViewById(R.id.xbs_main_scroll).getHeight()>0);
    }
    private static void checkTheme(int mode, boolean dark) {
        instrument().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(mode));
        try(ActivityScenario<MainActivity> scenario=launch()) {
            waitFor(scenario,a->AppPalette.isDark(a)==dark && a.findViewById(R.id.xbs_main_root).isLaidOut());
            waitForLayout(scenario);
            scenario.onActivity(a->{
                assertEquals(dark,AppPalette.isDark(a));
                View root=a.findViewById(R.id.xbs_main_root);
                assertTrue(root.getBackground() instanceof ColorDrawable);
                int actual=((ColorDrawable)root.getBackground()).getColor();
                AppPalette palette=AppPalette.resolve(a);
                assertEquals(palette.background,actual);
                assertTrue(dark?ThemePalettePolicy.luminance(actual)<0.18:ThemePalettePolicy.luminance(actual)>0.65);
                assertTrue(ThemePalettePolicy.contrast(actual,palette.text)>=4.5);
                assertTrue(ThemePalettePolicy.contrast(actual,palette.muted)>=4.5);
                assertEquals(!dark,WindowCompat.getInsetsController(a.getWindow(),root).isAppearanceLightStatusBars());
                assertNavigation(a);
            });
        }
    }
    @Test public void actualDayNightActivitiesUseDistinctReadablePalettes() {
        int original=AppCompatDelegate.getDefaultNightMode();
        boolean monet=AppSettings.useMonet(app());
        try {
            AppSettings.setUseMonet(app(),false);
            checkTheme(AppCompatDelegate.MODE_NIGHT_NO,false);
            checkTheme(AppCompatDelegate.MODE_NIGHT_YES,true);
            AppSettings.setUseMonet(app(),true);
            checkTheme(AppCompatDelegate.MODE_NIGHT_NO,false);
            checkTheme(AppCompatDelegate.MODE_NIGHT_YES,true);
        } finally {
            AppSettings.setUseMonet(app(),monet);
            instrument().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(original));
            AppSettings.setPage(app(),0);
        }
    }
    @Test public void realRotationPreservesSelectedTabAndMeasuredInsets() {
        UiAutomation automation=instrument().getUiAutomation();
        try {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
            try(ActivityScenario<MainActivity> scenario=launch()) {
                waitFor(scenario,a->a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT);
                waitForLayout(scenario);
                scenario.onActivity(a->a.findViewById(R.id.xbs_nav_settings).performClick());
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90));
                waitFor(scenario,a->a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE);
                waitForLayout(scenario);
                scenario.onActivity(a->{
                    assertEquals(a.getString(R.string.xbs_page_settings),((TextView)a.findViewById(R.id.xbs_main_title)).getText().toString());
                    LinearLayout nav=a.findViewById(R.id.xbs_main_navigation);
                    assertEquals(AdaptiveLayoutPolicy.useNavigationRail(a.getResources().getConfiguration().screenWidthDp)
                        ?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL,nav.getOrientation());
                    assertNavigation(a);
                });
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
                waitFor(scenario,a->a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT);
                waitForLayout(scenario);
                scenario.onActivity(a->{assertEquals(a.getString(R.string.xbs_page_settings),((TextView)a.findViewById(R.id.xbs_main_title)).getText().toString());assertNavigation(a);});
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
            AppSettings.setPage(app(),0);
        }
    }
    @Test public void arabicMainNavigationMirrorsAndExposesLocalizedLabels() {
        UiAutomation automation=instrument().getUiAutomation();
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        try(ActivityScenario<MainActivity> scenario=launch()) {
            LocaleListCompat original=AppCompatDelegate.getApplicationLocales();
            try {
                scenario.onActivity(a->AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")));
                waitFor(scenario,a->"ar".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage()));
                waitForLayout(scenario);
                scenario.onActivity(a->{
                    View root=a.findViewById(R.id.xbs_main_root);
                    assertEquals(View.LAYOUT_DIRECTION_RTL,root.getLayoutDirection());
                    assertEquals("نظرة عامة",a.getString(R.string.xbs_page_overview));
                    assertEquals("الإعدادات",a.getString(R.string.xbs_page_settings));
                    LinearLayout nav=a.findViewById(R.id.xbs_main_navigation);
                    if(nav.getOrientation()==LinearLayout.HORIZONTAL) {
                        int[] first=new int[2],last=new int[2];
                        a.findViewById(NAV[0]).getLocationOnScreen(first);
                        a.findViewById(NAV[NAV.length-1]).getLocationOnScreen(last);
                        assertTrue("RTL navigation was not mirrored",first[0]>last[0]);
                    }
                    assertNavigation(a);
                    a.findViewById(R.id.xbs_nav_settings).performClick();
                    assertEquals("الإعدادات",((TextView)a.findViewById(R.id.xbs_main_title)).getText().toString());
                });
            } finally {
                scenario.onActivity(a->AppCompatDelegate.setApplicationLocales(original));
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
            AppSettings.setPage(app(),0);
        }
    }
    @Test public void allNavigationTabsHaveAccessibleActionsAndRealScroll() {
        int before=RunStore.all(app()).size();
        try(ActivityScenario<MainActivity> scenario=launch()) {
            waitForLayout(scenario);
            for(int index=0;index<NAV.length;index++) {
                final int page=index;
                onView(withId(NAV[page])).perform(click());
                waitForLayout(scenario);
                scenario.onActivity(a->{
                    assertNavigation(a);
                    assertEquals(a.getString(LABELS[page]),((TextView)a.findViewById(R.id.xbs_main_title)).getText().toString());
                });
                AtomicBoolean scrollable=new AtomicBoolean(false);
                scenario.onActivity(a->scrollable.set(a.findViewById(R.id.xbs_main_scroll).canScrollVertically(1)));
                if(scrollable.get()) {
                    onView(withId(R.id.xbs_main_scroll)).perform(swipeUp());
                    scenario.onActivity(a->assertTrue("Real scroll gesture made no progress",a.findViewById(R.id.xbs_main_scroll).getScrollY()>0));
                }
            }
            onView(withId(R.id.xbs_nav_overview)).perform(click());
            scenario.onActivity(a->assertEquals(a.getString(LABELS[0]),((TextView)a.findViewById(R.id.xbs_main_title)).getText().toString()));
        } finally { AppSettings.setPage(app(),0); }
        assertEquals(before,RunStore.all(app()).size());
    }
    private static void assertTileViewport(XbsTileSettingsActivity a) {
        View root=a.findViewById(R.id.xbs_tile_settings_root);
        ScrollView scroll=a.findViewById(R.id.xbs_tile_settings_scroll);
        assertNotNull(root);assertNotNull(scroll);
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(root);
        assertNotNull("tile settings insets missing",insets);
        Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        assertTrue(root.getPaddingLeft()>=bars.left && root.getPaddingRight()>=bars.right);
        assertTrue(root.getPaddingTop()>=bars.top && root.getPaddingBottom()>=bars.bottom);
        assertEquals(root.getPaddingTop(),scroll.getTop());
        assertEquals(root.getHeight()-root.getPaddingBottom(),scroll.getBottom());
        assertEquals(0,scroll.getPaddingTop());assertEquals(0,scroll.getPaddingBottom());
        assertVisible(scroll);
    }
    private static void revealResetByScrolling(ActivityScenario<XbsTileSettingsActivity> scenario) {
        for(int attempt=0;attempt<12;attempt++) {
            AtomicBoolean visible=new AtomicBoolean(false);
            scenario.onActivity(a->{
                assertTileViewport(a);
                ScrollView scroll=a.findViewById(R.id.xbs_tile_settings_scroll);
                Button reset=a.findViewById(R.id.xbs_tile_settings_reset);
                assertNotNull(reset);
                Rect rect=new Rect();
                boolean fullyVisible=reset.getGlobalVisibleRect(rect)&&rect.width()==reset.getWidth()&&rect.height()==reset.getHeight();
                if(fullyVisible) {
                    assertTextFits(reset);assertTrue(reset.isEnabled()&&reset.isClickable());visible.set(true);
                } else {
                    assertTrue("reset unreachable at scrollY="+scroll.getScrollY()+" viewport="+scroll.getHeight()+" visible="+rect,scroll.canScrollVertically(1));
                }
            });
            if(visible.get()) return;
            onView(withId(R.id.xbs_tile_settings_scroll)).perform(swipeUp());
        }
        fail("Local reset not fully visible after bounded real scroll gestures");
    }
    @Test public void tileSettingsOfflineSelectionAndResetCancellationDoNotDispatch() {
        Context app=app();
        XbsStateStore.save(app,XbsSnapshot.unavailable("GX10 rootless fixture"),Collections.emptyList());
        EventBridgeStore.save(app,EventBridgeReport.unavailable());
        String saver=XbsTileConfiguration.selected(app,XbsTileDecision.Kind.SAVER);
        String profiles=XbsTileConfiguration.selected(app,XbsTileDecision.Kind.PROFILES);
        int before=RunStore.all(app).size();
        try(ActivityScenario<XbsTileSettingsActivity> scenario=ActivityScenario.launch(XbsTileSettingsActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            onView(withText("Choose saver event")).perform(scrollTo(),click());
            onView(withText("Event selection blocked")).check(matches(isDisplayed()));
            onView(withText("OK")).perform(click());
            revealResetByScrolling(scenario);
            onView(withId(R.id.xbs_tile_settings_reset)).check(matches(withText("Review local tile shortcut reset"))).perform(click());
            onView(withText("Unlock LOCAL tile shortcut reset")).check(matches(isDisplayed()));
            onView(withText("Continue")).perform(click());
            onView(withText("Confirm local tile reset")).check(matches(isDisplayed()));
            onView(withText("Cancel")).perform(click());
        }
        assertEquals(saver,XbsTileConfiguration.selected(app,XbsTileDecision.Kind.SAVER));
        assertEquals(profiles,XbsTileConfiguration.selected(app,XbsTileDecision.Kind.PROFILES));
        assertEquals(before,RunStore.all(app).size());
    }
}
