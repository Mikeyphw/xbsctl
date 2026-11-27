package io.github.mikeceead.xbscompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Debug target-process fixture: never claims real root or authorized Tasker-host acceptance. */
@RunWith(AndroidJUnit4.class)
public final class Gx10EditorInstrumentedTest {
    private Fixture fixture;
    private Context context;
    @Before public void install(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixture=new Fixture();EditorBackendProvider.installForInstrumentation(fixture);
    }
    @After public void cleanup(){fixture.release.countDown();EditorBackendProvider.installForInstrumentation(null);}
    private static final class Fixture implements EditorBackend {
        JSONObject config=new JSONObject();
        String essentials="android\n";
        final AtomicInteger saves=new AtomicInteger(),reads=new AtomicInteger();
        final AtomicBoolean fail=new AtomicBoolean(),hold=new AtomicBoolean();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Fixture(){
            JSONObject header=new JSONObject(),events=new JSONObject();
            EditorSession.put(header,"version",2);EditorSession.put(header,"delay",3);
            EditorSession.put(header,"essentials_allowlist",XbsConfigApi.LIST_DIR+"/essentials.allowlist");
            for(String name:XbsConfigApi.BUILT_IN_EVENTS)EditorSession.put(events,name,new JSONObject());
            JSONObject sample=new JSONObject();EditorSession.put(sample,"allowlist",XbsConfigApi.LIST_DIR+"/sample.allowlist");
            EditorSession.put(events,"sample",sample);EditorSession.put(config,"header",header);EditorSession.put(config,"events",events);
        }
        public synchronized XbsConfigApi.Result load(Context c,boolean force){return XbsConfigApi.Result.ok(EditorSession.copy(config));}
        public synchronized String readText(Context c,String path,boolean force){reads.incrementAndGet();return path.endsWith("/essentials.allowlist")?essentials:"";}
        public XbsConfigTransaction.Record latest(Context c){return XbsConfigTransaction.Record.unavailable("fixture");}
        public XbsConfigApi.Result save(Context c,JSONObject proposed,Map<String,String> files){
            saves.incrementAndGet();entered.countDown();
            if(hold.get())try{if(!release.await(20,TimeUnit.SECONDS))return XbsConfigApi.Result.error("fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();return XbsConfigApi.Result.error("fixture interrupted");}
            if(fail.get())return XbsConfigApi.Result.error("fixture refused write");
            synchronized(this){config=EditorSession.copy(proposed);String text=files.get(XbsConfigApi.LIST_DIR+"/essentials.allowlist");if(text!=null)essentials=text;return XbsConfigApi.Result.ok(EditorSession.copy(config),"fixture receipt");}
        }
        public XbsConfigApi.Result delete(Context c,String n,boolean f){throw new AssertionError("unexpected delete");}
        public XbsConfigApi.Result transaction(Context c,XbsConfigTransaction.Record r,boolean recover){throw new AssertionError("unexpected transaction");}
        public XbsConfigApi.Result defaults(Context c){return XbsConfigApi.Result.ok(null,"android\ncom.android.systemui\n");}
        synchronized int delay(){return config.optJSONObject("header").optInt("delay");}
    }
    private static void await(String message,BooleanSupplier condition){
        long end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(condition.getAsBoolean())return;SystemClock.sleep(80);}fail(message);
    }
    private static <A extends Activity> void visible(ActivityScenario<A> s,int id){
        await("visible editor field "+id,()->{AtomicBoolean ok=new AtomicBoolean();s.onActivity(a->{View v=a.findViewById(id);ok.set(v!=null&&v.isShown());});return ok.get();});
    }
    private static <A extends Activity> void text(ActivityScenario<A> s,int id,String expected){
        s.onActivity(a->assertEquals(expected,((EditText)a.findViewById(id)).getText().toString()));
    }
    private static Button button(View root,String label){
        if(root instanceof Button&&label.contentEquals(((Button)root).getText()))return (Button)root;
        if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){Button found=button(g.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    private static <A extends Activity> void click(ActivityScenario<A> s,String label){s.onActivity(a->{Button b=button(a.getWindow().getDecorView(),label);assertNotNull(label,b);assertTrue(b.isEnabled());assertTrue(b.performClick());});}
    @Test public void configurationDraftSurvivesResumeRecreationAndFailedSave(){
        try(ActivityScenario<XbsConfigActivity> s=ActivityScenario.launch(XbsConfigActivity.class)){
            visible(s,R.id.xbs_config_delay);s.onActivity(a->((EditText)a.findViewById(R.id.xbs_config_delay)).setText("19"));
            s.moveToState(Lifecycle.State.CREATED);s.moveToState(Lifecycle.State.RESUMED);visible(s,R.id.xbs_config_delay);text(s,R.id.xbs_config_delay,"19");
            s.recreate();visible(s,R.id.xbs_config_delay);text(s,R.id.xbs_config_delay,"19");assertEquals(0,fixture.saves.get());
            fixture.fail.set(true);click(s,"Validate and save settings");await("first write",()->fixture.saves.get()==1);visible(s,R.id.xbs_config_delay);text(s,R.id.xbs_config_delay,"19");assertEquals(3,fixture.delay());
            fixture.fail.set(false);click(s,"Validate and save settings");await("confirmed save",()->fixture.saves.get()==2&&fixture.delay()==19);visible(s,R.id.xbs_config_delay);text(s,R.id.xbs_config_delay,"19");
        }
    }
    @Test public void pendingSaveRecreationDoesNotDispatchTwice(){
        fixture.hold.set(true);
        try(ActivityScenario<XbsConfigActivity> s=ActivityScenario.launch(XbsConfigActivity.class)){
            visible(s,R.id.xbs_config_delay);s.onActivity(a->((EditText)a.findViewById(R.id.xbs_config_delay)).setText("23"));click(s,"Validate and save settings");
            await("save entered",()->fixture.saves.get()==1);s.recreate();assertEquals(1,fixture.saves.get());fixture.release.countDown();await("save complete",()->fixture.delay()==23);visible(s,R.id.xbs_config_delay);text(s,R.id.xbs_config_delay,"23");assertEquals(1,fixture.saves.get());
        }finally{fixture.release.countDown();}
    }
    @Test public void essentialsRejectsProtectedPathsAndRetainsDraftOnFailure(){
        try(ActivityScenario<XbsEssentialsActivity> s=ActivityScenario.launch(XbsEssentialsActivity.class)){
            visible(s,R.id.xbs_essentials_path);s.onActivity(a->{((EditText)a.findViewById(R.id.xbs_essentials_path)).setText("/etc/passwd");a.findViewById(R.id.xbs_essentials_save).performClick();});assertEquals(0,fixture.saves.get());
            s.onActivity(a->{((EditText)a.findViewById(R.id.xbs_essentials_path)).setText(XbsConfigApi.LIST_DIR+"/sample.allowlist");a.findViewById(R.id.xbs_essentials_save).performClick();});assertEquals(0,fixture.saves.get());
            String raw="# local draft\nandroid\ncom.example.app\n";
            s.onActivity(a->{((EditText)a.findViewById(R.id.xbs_essentials_path)).setText(XbsConfigApi.LIST_DIR+"/essentials.allowlist");((EditText)a.findViewById(R.id.xbs_essentials_list)).setText(raw);});
            s.recreate();visible(s,R.id.xbs_essentials_list);text(s,R.id.xbs_essentials_list,raw);fixture.fail.set(true);click(s,"Validate and save essentials");await("first write",()->fixture.saves.get()==1);visible(s,R.id.xbs_essentials_list);text(s,R.id.xbs_essentials_list,raw);
            fixture.fail.set(false);click(s,"Validate and save essentials");await("second write",()->fixture.saves.get()==2);visible(s,R.id.xbs_essentials_list);text(s,R.id.xbs_essentials_list,"android\ncom.example.app\n");
        }
    }
    @Test public void customEventDraftSurvivesRecreationAndCancelDoesNotWrite(){
        Intent intent=new Intent(context,XbsEventEditorActivity.class).putExtra(XbsEventEditorActivity.EXTRA_EVENT,"sample");
        try(ActivityScenario<XbsEventEditorActivity> s=ActivityScenario.launch(intent)){
            visible(s,R.id.xbs_event_name);s.onActivity(a->{EditText name=a.findViewById(R.id.xbs_event_name);assertTrue(name.isEnabled());name.setText("sample_renamed");});
            s.recreate();visible(s,R.id.xbs_event_name);text(s,R.id.xbs_event_name,"sample_renamed");click(s,"Cancel");assertEquals(0,fixture.saves.get());
        }
        Intent protectedEvent=new Intent(context,XbsEventEditorActivity.class).putExtra(XbsEventEditorActivity.EXTRA_EVENT,"boot");
        try(ActivityScenario<XbsEventEditorActivity> s=ActivityScenario.launch(protectedEvent)){
            visible(s,R.id.xbs_event_name);s.onActivity(a->assertFalse(a.findViewById(R.id.xbs_event_name).isEnabled()));s.recreate();visible(s,R.id.xbs_event_name);text(s,R.id.xbs_event_name,"boot");s.onActivity(a->assertFalse(a.findViewById(R.id.xbs_event_name).isEnabled()));assertEquals(0,fixture.saves.get());
        }
    }
    @Test public void privateDraftRestoresLargeTextAndRefusesUnknownWriteOutcome(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            EditorSession first=new EditorSession();first.initialize(context,null,"fixture-scope");JSONObject d=new JSONObject();StringBuilder text=new StringBuilder();for(int i=0;i<600000;i++)text.append('x');EditorSession.put(d,"text",text.toString());Bundle state=new Bundle();assertTrue(first.save(state,d));
            EditorSession restored=new EditorSession();restored.initialize(context,state,"fixture-scope");assertFalse(restored.blocked());assertEquals(text.toString(),restored.snapshot().optString("text"));
            EditorSession foreign=new EditorSession();foreign.initialize(context,state,"other-scope");assertTrue(foreign.blocked());assertFalse(foreign.start(d,()->{throw new AssertionError("foreign write");}));
            CountDownLatch release=new CountDownLatch(1);try{assertTrue(first.start(d,()->{release.await(5,TimeUnit.SECONDS);return XbsConfigApi.Result.error("fixture");}));assertTrue(first.save(state,d));EditorSession uncertain=new EditorSession();uncertain.initialize(context,state,"fixture-scope");assertTrue(uncertain.blocked());assertFalse(uncertain.start(d,()->{throw new AssertionError("automatic replay");}));}finally{release.countDown();first.clear();first.onCleared();restored.onCleared();foreign.onCleared();}
        });
    }
}
