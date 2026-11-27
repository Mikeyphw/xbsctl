package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class XbsEssentialsActivity extends XbsActivity {
    private static final int PICK_APPS=410;
    private static final String DEFAULT=XbsConfigApi.LIST_DIR+"/essentials.allowlist";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private UiKit ui; private LinearLayout content; private EditText path,list;
    private TextView statusView;
    private EditorSession session;private EditorBackend backend;
    private JSONObject config; private String originalPath="",originalText="",pendingSelection;
    private boolean loading;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);backend=EditorBackendProvider.get();session=new ViewModelProvider(this).get(EditorSession.class);
        session.initialize(this,state,"essentials");build();registerXbsBackHandler(this::cancelEditor);
        JSONObject draft=session.snapshot();
        try{
            if(draft.has("config")){
                config=new JSONObject(draft.getString("config"));originalPath=draft.getString("originalPath");originalText=draft.getString("originalText");
                pendingSelection=draft.has("pendingSelection")?draft.getString("pendingSelection"):null;
                render(draft.getString("path"),draft.getString("text"));
            }else if(session.blocked())showError(session.problem());else load();
        }catch(Exception e){showError("Saved essentials draft could not be restored. Reopen the editor.");}
        setBusy(session.busy());session.attach(this::completed);
    }
    @Override protected void onSaveInstanceState(Bundle out){if(session!=null&&!session.save(out,snapshot()))toast(session.problem());super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(session!=null){session.detach();if(isFinishing()&&!session.busy())session.clear();}executor.shutdownNow();super.onDestroy();}
    private boolean live(){return !isDestroyed()&&!isFinishing();}
    private JSONObject snapshot(){
        JSONObject d=session.snapshot();if(config!=null&&path!=null&&list!=null){
            EditorSession.put(d,"config",config.toString());EditorSession.put(d,"originalPath",originalPath);EditorSession.put(d,"originalText",originalText);
            EditorSession.put(d,"path",path.getText().toString());EditorSession.put(d,"text",list.getText().toString());
        }
        if(pendingSelection!=null)EditorSession.put(d,"pendingSelection",pendingSelection);else d.remove("pendingSelection");return d;
    }
    private void cancelEditor(){if(session.busy()){toast("Wait for the current transaction to finish");return;}finish();}
    private void build(){
        ui=new UiKit(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.palette.background);
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(8));
        head.addView(ui.text("XBS ESSENTIALS",12,ui.palette.orange,true));head.addView(ui.text("Default essentials list",24,ui.palette.text,true));root.addView(head);
        statusView=ui.text("",13,ui.palette.muted,false);statusView.setPadding(ui.dp(16),0,ui.dp(16),0);root.addView(statusView);statusView.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout foot=new LinearLayout(this);foot.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(10));foot.addView(ui.secondaryButton("Back",v->cancelEditor()));root.addView(foot);setXbsContentView(root);
    }
    private static String pathError(JSONObject config,String target){return XbsConfigApi.essentialsPathError(config,target);}
    private static String configuredPath(JSONObject c){JSONObject header=c.optJSONObject("header");return header==null?DEFAULT:header.optString("essentials_allowlist",DEFAULT);}
    private void load(){
        if(loading||session.busy()||session.blocked())return;loading=true;status("Loading essentials…");
        Context app=getApplicationContext();executor.execute(()->{
            XbsConfigApi.Result r=backend.load(app,true);
            if(!r.ok){runOnUiThread(()->{if(live()){loading=false;showError(r.message);}});return;}
            String target=configuredPath(r.config);String error=pathError(r.config,target);
            // A malicious configured path is never read through the privileged backend.
            String text=error.isEmpty()?backend.readText(app,target,true):"";
            runOnUiThread(()->{if(!live())return;loading=false;config=r.config;originalPath=target;originalText=text;render(target,text);status(error);});
        });
    }
    private void status(String message){statusView.setText(message);statusView.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
    private void showError(String message){status(message);if(path!=null&&list!=null)return;content.removeAllViews();LinearLayout card=ui.surface();card.addView(ui.text("Unable to load essentials",18,ui.palette.red,true));card.addView(ui.text(message,13,ui.palette.muted,false));if(!session.blocked())card.addView(ui.button("Retry",ui.palette.orange,v->load()));content.addView(card);}
    private void render(String listPath,String text){
        content.removeAllViews();content.addView(ui.sectionTitle("Storage"));LinearLayout storage=ui.surface();storage.addView(ui.text("Essentials file",13,ui.palette.muted,true));
        path=ui.input(listPath,"Essentials path",false);path.setId(R.id.xbs_essentials_path);path.setSaveEnabled(false);storage.addView(path);storage.addView(ui.text("Only direct, non-event .allowlist files inside the canonical XBS lists directory are accepted.",11,ui.palette.muted,false));storage.addView(ui.secondaryButton("Use managed essentials path",v->path.setText(DEFAULT)));content.addView(storage);
        content.addView(ui.sectionTitle("Protected applications"));LinearLayout card=ui.surface();list=ui.input(text,"One package per line",true);list.setId(R.id.xbs_essentials_list);list.setSaveEnabled(false);list.setMinLines(7);card.addView(list);card.addView(ui.button("Select installed apps",ui.palette.orange,v->openPicker()));card.addView(ui.secondaryButton("Restore module defaults",v->confirmRestore()));content.addView(card);
        LinearLayout save=ui.surface();save.addView(ui.text("Protection applies globally",16,ui.palette.yellow,true));save.addView(ui.text("Removing core Android packages may let an event stop essential device components.",12,ui.palette.muted,false));android.widget.Button button=ui.button("Validate and save essentials",ui.palette.green,v->save());button.setId(R.id.xbs_essentials_save);save.addView(button);content.addView(save);
        if(pendingSelection!=null){list.setText(pendingSelection);pendingSelection=null;}
        AccessibilityUiPolicy.apply(this,content,"Default essentials");
    }
    private void openPicker(){if(session.busy()||session.blocked())return;Intent intent=new Intent(this,AppPickerActivity.class);intent.putExtra(AppPickerActivity.EXTRA_TITLE,"Select essential applications");intent.putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED,new ArrayList<>(packages(list.getText().toString())));startActivityForResult(intent,PICK_APPS);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_APPS&&result==RESULT_OK&&data!=null){ArrayList<String> selected=data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED);if(selected!=null){String value=String.join("\n",selected)+"\n";if(list==null)pendingSelection=value;else if(!session.busy())list.setText(value);}}}
    private void save(){
        if(loading||session.busy()||session.blocked()||config==null||path==null)return;
        final String target=path.getText().toString().trim();String error=pathError(config,target);if(!error.isEmpty()){path.setError(error);status(error);return;}
        if(!target.equals(originalPath)&&!target.equals(DEFAULT)){status("Only the configured path or the canonical essentials path may be selected");return;}
        final String normalized;try{normalized=EssentialsDraftPolicy.normalize(list.getText().toString());}catch(IllegalArgumentException e){list.setError(e.getMessage());status(e.getMessage());return;}
        Context app=getApplicationContext();EditorBackend b=backend;String baselinePath=originalPath,baselineText=originalText;
        if(session.start(snapshot(),()->{
            XbsConfigApi.Result fresh=b.load(app,true);if(!fresh.ok)return fresh;
            String invalid=pathError(fresh.config,target);if(!invalid.isEmpty())return XbsConfigApi.Result.error(invalid);
            if(!target.equals(baselinePath)&&!b.readText(app,target,true).isEmpty())return XbsConfigApi.Result.error("The new essentials path already contains data; review it before replacing it.");
            if(!baselinePath.equals(configuredPath(fresh.config)))return XbsConfigApi.Result.error("Essentials location changed; review current configuration before saving.");
            if(pathError(fresh.config,baselinePath).isEmpty()&&!baselineText.equals(b.readText(app,baselinePath,true)))return XbsConfigApi.Result.error("Essentials changed externally; review current configuration before saving.");
            JSONObject proposed=EditorSession.copy(fresh.config);proposed.getJSONObject("header").put("essentials_allowlist",target);
            java.util.Map<String,String> files=new LinkedHashMap<>();files.put(target,normalized);return b.save(app,proposed,files);
        }))setBusy(true);else status(session.problem());
    }
    private void setBusy(boolean busy){if(busy){content.setVisibility(View.INVISIBLE);status("Saving essentials; wait for the transaction…");}else{content.setVisibility(View.VISIBLE);if(session.blocked()){content.setVisibility(View.INVISIBLE);status(session.problem());}}}
    private void completed(XbsConfigApi.Result result){if(!live())return;setBusy(false);if(result.ok){session.clear();path=null;list=null;config=null;content.removeAllViews();status("Essentials saved");toast("Essentials saved");load();}else{status(result.message);toast(result.message);}}
    private void confirmRestore(){
        if(session.busy()||session.blocked()||loading||config==null)return;String target=path.getText().toString().trim();String invalid=pathError(config,target);
        if(!invalid.isEmpty()){path.setError(invalid);status(invalid);return;}
        // Loading defaults into the draft is read-only. The user reviews and explicitly saves them.
        new AlertDialog.Builder(this).setTitle("Load module defaults?").setMessage("Replace the draft text with module defaults. Nothing is written until Validate and save essentials is selected.").setNegativeButton("Cancel",null).setPositiveButton("Load defaults",(d,w)->{
            if(loading||session.busy())return;loading=true;Context app=getApplicationContext();EditorBackend b=backend;
            executor.execute(()->{XbsConfigApi.Result defaults=b.defaults(app);runOnUiThread(()->{if(!live())return;loading=false;if(defaults.ok){list.setText(defaults.message);status("Defaults loaded into draft; review and save to apply");}else status(defaults.message);});});
        }).show();
    }
    private static Set<String> packages(String value){Set<String> result=new LinkedHashSet<>();for(String line:value.replace("\r","").split("\n")){String item=line.trim();if(!item.isEmpty()&&!item.startsWith("#"))result.add(item);}return result;}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
