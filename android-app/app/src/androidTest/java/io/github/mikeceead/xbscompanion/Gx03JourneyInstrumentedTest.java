package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real offline UI, navigation and hostile-intent journeys. No privileged button is tapped. */
@RunWith(AndroidJUnit4.class)
public final class Gx03JourneyInstrumentedTest {
    private static final String[] TABS={"Overview","Events","Processes","Activity","Settings"};

    private static boolean containsExact(View root,String needle) {
        if (root instanceof TextView && needle.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup) root;
            for (int i=0;i<group.getChildCount();i++) if (containsExact(group.getChildAt(i),needle)) return true;
        }
        return false;
    }

    private static Button navButton(View root,String suffix) {
        if (root instanceof Button) {
            Button b=(Button)root;
            if (b.getText()!=null) {
                String label=b.getText().toString().trim();
                if (label.endsWith(suffix) && label.length()<=suffix.length()+8) return b;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++) {
                Button match=navButton(group.getChildAt(i),suffix);
                if(match!=null) return match;
            }
        }
        return null;
    }

    @Test public void realMainActivityRendersAndNavigatesFiveTabs() {
        Context app=ApplicationProvider.getApplicationContext();
        AppSettings.setPage(app,0);
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            scenario.onActivity(activity -> {
                for (String tab:TABS) {
                    View root=activity.getWindow().getDecorView();
                    Button navigation=navButton(root,tab);
                    assertNotNull("Missing navigation for "+tab,navigation);
                    assertTrue("Navigation not visible for "+tab,navigation.isShown());
                    navigation.performClick();
                    assertTrue("Missing selected page title "+tab,
                        containsExact(activity.getWindow().getDecorView(),tab));
                }
                Button overview=navButton(activity.getWindow().getDecorView(),"Overview");
                assertNotNull(overview);
                overview.performClick();
                assertTrue(containsExact(activity.getWindow().getDecorView(),"Overview"));
            });
        } finally {
            AppSettings.setPage(app,0);
        }
    }

    @Test public void allFivePagesHaveRealNonemptyViewTrees() {
        Context app=ApplicationProvider.getApplicationContext();
        for(int index=0;index<TABS.length;index++) {
            Intent intent=new Intent(app,MainActivity.class).putExtra("page",index);
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
                assertEquals(Lifecycle.State.RESUMED,scenario.getState());
                final String tab=TABS[index];
                scenario.onActivity(activity -> {
                    ViewGroup root=activity.findViewById(android.R.id.content);
                    assertNotNull(root);
                    assertTrue("Empty real view tree: "+tab,root.getChildCount()>0);
                    assertTrue("Real page did not render: "+tab,
                        containsExact(root,tab));
                });
            }
        }
        AppSettings.setPage(app,0);
    }

    @Test public void hostilePageInputCannotOpenAnUnboundedPage() {
        Context app=ApplicationProvider.getApplicationContext();
        AppSettings.setPage(app,0);
        Intent hostile=new Intent(app,MainActivity.class).putExtra("page",Integer.MAX_VALUE);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(hostile)) {
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            scenario.onActivity(activity -> {
                View root=activity.getWindow().getDecorView();
                boolean valid=false;
                for(String title:TABS) valid |= containsExact(root,title);
                assertTrue("Unsafe/missing fallback page",valid);
            });
        } finally {
            AppSettings.setPage(app,0);
        }
    }
}
