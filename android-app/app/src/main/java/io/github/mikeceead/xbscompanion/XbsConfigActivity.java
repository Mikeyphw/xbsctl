package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class XbsConfigActivity extends XbsActivity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private UiKit ui;
    private LinearLayout content;
    private JSONObject config;
    private boolean eventsOnly;
    private XbsConfigTransaction.Record transaction;
    private final Map<String,View> headerControls=new LinkedHashMap<>();

    private EditorSession session;
    private EditorBackend backend;
    private String baselineFields="",rawDraft;
    private EditText rawInput;
    private boolean rawOpen,loading;
    private TextView statusView;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);eventsOnly=getIntent().getBooleanExtra("events_only",false);
        backend=EditorBackendProvider.get();session=new ViewModelProvider(this).get(EditorSession.class);session.initialize(this,state,"config:"+eventsOnly);
        buildShell();registerXbsBackHandler(this::requestBack);
        JSONObject d=session.snapshot();
        if(d.has("config")){
            try{config=new JSONObject(d.getString("config"));render();EditorSession.restore(headerControls,d.optJSONObject("fields"));if(d.has("raw"))rawDraft=d.getString("raw");rawOpen=d.optBoolean("rawOpen");}
            catch(Exception e){config=null;showError("Cannot restore the saved configuration draft. Reopen the editor.");}
        }else if(session.blocked())showError(session.problem());else load();
        setBusy(session.busy());session.attach(this::completed);
        if(rawOpen&&rawDraft!=null&&!session.busy()&&!session.blocked())editRaw();
    }
    @Override protected void onDestroy(){if(session!=null){session.detach();if(isFinishing()&&!session.busy())session.clear();}executor.shutdownNow();super.onDestroy();}
    @Override protected void onResume(){super.onResume();if(config!=null&&!dirty()&&!session.busy()&&!session.blocked()&&!rawOpen)load();}
    @Override protected void onSaveInstanceState(Bundle out){if(session!=null&&!session.save(out,snapshot()))toast(session.problem());super.onSaveInstanceState(out);}
    private boolean live(){return !isDestroyed()&&!isFinishing();}
    private boolean headerDirty(){return config!=null&&!baselineFields.equals(EditorSession.capture(headerControls).toString());}
    private boolean dirty(){return headerDirty()||rawDraft!=null||rawOpen;}
    private JSONObject snapshot(){
        JSONObject d=session.snapshot();
        if(config!=null){EditorSession.put(d,"config",config.toString());EditorSession.put(d,"fields",EditorSession.capture(headerControls));}
        if(rawInput!=null&&rawOpen)rawDraft=rawInput.getText().toString();
        if(rawDraft!=null)EditorSession.put(d,"raw",rawDraft);else d.remove("raw");
        EditorSession.put(d,"rawOpen",rawOpen);return d;
    }
    private void status(String value){statusView.setText(value);statusView.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);}
    private void requestBack(){
        if(session.busy()){toast("Wait for the current transaction to finish");return;}
        if(dirty())new AlertDialog.Builder(this).setTitle("Discard unsaved configuration?").setNegativeButton("Keep editing",null).setPositiveButton("Discard",(d,w)->finish()).show();else finish();
    }
    private void discardAndReload(){
        if(session.busy()||session.blocked())return;
        new AlertDialog.Builder(this).setTitle("Discard unsaved configuration?").setMessage("Reload the current configuration and discard your local draft.").setNegativeButton("Keep editing",null).setPositiveButton("Discard and reload",(d,w)->{rawDraft=null;rawOpen=false;rawInput=null;render();session.clear();load();}).show();
    }
    private boolean allowOtherMutation(){if(session.busy()||session.blocked()||loading)return false;if(dirty()){status("Save or discard the unsaved configuration before changing other settings");return false;}return true;}
    private void setBusy(boolean busy){content.setVisibility(busy||session.blocked()?View.INVISIBLE:View.VISIBLE);if(busy)status("Configuration transaction in progress…");else if(session.blocked())status(session.problem());}
    private void completed(XbsConfigApi.Result result){
        if(!live())return;setBusy(false);
        if(result.ok){rawDraft=null;rawOpen=false;rawInput=null;session.clear();if(result.config!=null){config=result.config;render();}else if(config!=null)render();status("Configuration operation completed");toast("Configuration operation completed");load();}
        else{status(result.message);toast(result.message);}
    }

    private void buildShell(){
        ui=new UiKit(this);getWindow().setStatusBarColor(ui.palette.background);getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.palette.background);
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(8));
        head.addView(ui.text("XBS FRONTEND",12,ui.palette.orange,true));head.addView(ui.text(eventsOnly?"Event profiles":"Configuration",28,ui.palette.text,true));
        head.addView(ui.text(eventsOnly?"Create, clone, edit and delete XBS events.":"Edit XBS settings and event profiles with validation and backups.",13,ui.palette.muted,false));root.addView(head);
        statusView=ui.text("",13,ui.palette.muted,false);statusView.setPadding(ui.dp(16),0,ui.dp(16),0);root.addView(statusView);statusView.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(10));footer.addView(ui.secondaryButton("Back",v->requestBack()));root.addView(footer);setXbsContentView(root);
    }

    private void load(){
        if(loading||session.busy()||session.blocked())return;
        if(dirty()){status("Unsaved configuration retained. Save it or discard and reload.");return;}
        loading=true;status("Loading XBS configuration…");content.setVisibility(View.INVISIBLE);
        Context app=getApplicationContext();executor.execute(()->{
            XbsConfigApi.Result result=backend.load(app,true);XbsConfigTransaction.Record latest=backend.latest(app);
            runOnUiThread(()->{if(!live())return;loading=false;transaction=latest;content.setVisibility(View.VISIBLE);
                if(result.ok){config=result.config;render();status("");}else if(config!=null)status(result.message);else showError(result.message);
            });
        });
    }
    private void showError(String text){content.removeAllViews();LinearLayout card=ui.surface();card.addView(ui.text("Could not load configuration",18,ui.palette.red,true));card.addView(ui.text(text,13,ui.palette.muted,false));card.addView(ui.text("Event CRUD requires root access and a readable XtremeBS.json. Grant root to XBS Companion, then retry.",12,ui.palette.yellow,false));card.addView(ui.button("Retry",ui.palette.orange,v->load()));card.addView(ui.secondaryButton("Open all XBS tools",v->startActivity(new Intent(this,CliCommandActivity.class))));content.addView(card);}

    private void render(){
        content.removeAllViews();headerControls.clear();
        if(!eventsOnly){renderHeader();renderTransactionJournal();renderRawTools();}
        renderEvents();AccessibilityUiPolicy.apply(this,content,"Configuration");baselineFields=EditorSession.capture(headerControls).toString();
    }

    private void renderHeader(){
        content.addView(ui.sectionTitle("Runtime settings"));
        LinearLayout card=ui.surface();JSONObject header=config.optJSONObject("header");if(header==null){header=new JSONObject();try{config.put("header",header);}catch(Exception ignored){}}
        addInteger(card,"delay","Action delay (seconds)",header.optInt("delay",3));
        addInteger(card,"log_level","Log level",header.optInt("log_level",3));
        addSwitch(card,"log_json","Write JSON logs",header.optBoolean("log_json",false));
        addSwitch(card,"dry_run","Global dry-run mode",header.optBoolean("dry_run",false));
        addSwitch(card,"notify","XBS notifications",header.optBoolean("notify",true));
        addText(card,"log_file","Log file",header.optString("log_file","/data/local/tmp/XtremeBS.log"));
        addText(card,"ctl_file","Control file",header.optString("ctl_file","/data/local/tmp/xbs"));
        addText(card,"lock_file","Reload lock",header.optString("lock_file","/data/local/tmp/XtremeBS/reload.lock"));
        addText(card,"essentials_allowlist","Essentials allowlist path",header.optString("essentials_allowlist", XbsConfigApi.LIST_DIR + "/essentials.allowlist"));
        card.addView(ui.button("Edit default essentials list",ui.palette.green,v->{if(allowOtherMutation())startActivity(new Intent(this,XbsEssentialsActivity.class));}));
        card.addView(ui.text("The essentials list protects packages globally across every simultaneously active event.",12,ui.palette.muted,false));
        card.addView(ui.button("Validate and save settings",ui.palette.orange,v->saveHeader()));card.addView(ui.secondaryButton("Discard unsaved settings and reload",v->discardAndReload()));content.addView(card);
    }


    private void renderTransactionJournal(){
        content.addView(ui.sectionTitle("Configuration safety"));
        LinearLayout card=ui.surface();
        card.addView(ui.text("Transaction journal",17,ui.palette.orange,true));
        if(transaction==null){
            card.addView(ui.text("Journal state is not loaded.",12,ui.palette.muted,false));
        }else if(!transaction.exists()){
            card.addView(ui.text(transaction.message.isEmpty()?"No completed configuration transaction yet.":transaction.message,12,ui.palette.muted,false));
            if(!transaction.error.isEmpty())card.addView(ui.text(transaction.error,11,ui.palette.red,false));
        }else{
            card.addView(ui.text(transaction.displayStatus()+" · "+transaction.entryCount+" managed file"+(transaction.entryCount==1?"":"s"),14,
                transaction.needsRecovery()?ui.palette.yellow:ui.palette.text,true));
            if(transaction.createdAt>0)card.addView(ui.text("Created "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(transaction.createdAt)),11,ui.palette.muted,false));
            card.addView(ui.text("ID "+transaction.id,10,ui.palette.muted,false));
            if(!transaction.message.isEmpty())card.addView(ui.text(transaction.message,12,ui.palette.muted,false));
            if(transaction.canRollback())card.addView(ui.button("Rollback latest configuration save",ui.palette.red,v->confirmRollback()));
            if(transaction.needsRecovery())card.addView(ui.button("Recover interrupted transaction",ui.palette.orange,v->confirmRecovery()));
        }
        card.addView(ui.secondaryButton("Refresh transaction journal",v->load()));
        card.addView(ui.text("Every save is serialized by a root-side lock, records old/new SHA-256 digests, validates the promoted config, and restores the complete old set if any step fails.",11,ui.palette.muted,false));
        content.addView(card);
    }

    private void confirmRollback(){
        if(!allowOtherMutation())return;
        if(transaction==null||!transaction.canRollback()){toast("Latest transaction is not rollback-ready");return;}
        new AlertDialog.Builder(this).setTitle("Rollback latest save?")
            .setMessage("Restore every config/list file captured before transaction "+transaction.id+" and reload XBS. Rollback is refused if a newer save exists or managed files changed after commit.")
            .setNegativeButton("Cancel",null).setPositiveButton("Rollback",(d,w)->performTransactionAction(false)).show();
    }

    private void confirmRecovery(){
        if(!allowOtherMutation())return;
        if(transaction==null||!transaction.needsRecovery()){toast("No interrupted transaction requires recovery");return;}
        new AlertDialog.Builder(this).setTitle("Recover interrupted save?")
            .setMessage("Restore the complete pre-transaction file set and reload XBS.")
            .setNegativeButton("Cancel",null).setPositiveButton("Recover",(d,w)->performTransactionAction(true)).show();
    }

    private void performTransactionAction(boolean recover){
        if(!allowOtherMutation())return;XbsConfigTransaction.Record selected=transaction;
        Context app=getApplicationContext();EditorBackend b=backend;
        if(session.start(snapshot(),()->b.transaction(app,selected,recover)))setBusy(true);else status(session.problem());
    }

    private void addText(LinearLayout card,String key,String label,String value){card.addView(ui.text(label,13,ui.palette.muted,true));EditText e=ui.input(value,label,false);card.addView(e);headerControls.put(key,e);}
    private void addInteger(LinearLayout card,String key,String label,int value){card.addView(ui.text(label,13,ui.palette.muted,true));EditText e=ui.input(String.valueOf(value),label,false);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);if("delay".equals(key))e.setId(R.id.xbs_config_delay);card.addView(e);headerControls.put(key,e);}
    private void addSwitch(LinearLayout card,String key,String label,boolean value){SwitchCompat s=ui.toggle(label,value);card.addView(s);headerControls.put(key,s);}

    private void saveHeader(){
        if(loading||session.busy()||session.blocked()||rawDraft!=null){status("Resolve the pending raw draft or transaction first");return;}
        JSONObject proposed=EditorSession.copy(config);
        try{
            JSONObject header=proposed.getJSONObject("header");
            header.put("version",2);
            for(Map.Entry<String,View> item:headerControls.entrySet()){
                String key=item.getKey();View view=item.getValue();
                if(view instanceof SwitchCompat)header.put(key,((SwitchCompat)view).isChecked());
                else {String value=((EditText)view).getText().toString().trim();if("delay".equals(key)||"log_level".equals(key))header.put(key,Integer.parseInt(value));else header.put(key,value);}
            }
        }catch(Exception e){toast("Invalid setting: "+e.getMessage());return;}
        String error=XbsConfigApi.essentialsPathError(proposed,proposed.optJSONObject("header").optString("essentials_allowlist"));
        if(!error.isEmpty()){status(error);return;}
        saveConfig(proposed);
    }

    private void renderEvents(){
        content.addView(ui.sectionTitle("Event profiles"));
        LinearLayout actions=ui.surface();actions.addView(ui.text("Events are native editable objects",17,ui.palette.orange,true));
        actions.addView(ui.text("List files are edited as package/process lines and stored under the canonical XBS list directory.",12,ui.palette.muted,false));
        actions.addView(ui.button("Create event",ui.palette.green,v->openEvent("",false)));actions.addView(ui.secondaryButton("Edit template defaults",v->openTemplateEvent()));content.addView(actions);
        JSONObject events=config.optJSONObject("events");if(events==null)return;
        Iterator<String> names=events.keys();java.util.List<String> ordered=new java.util.ArrayList<>();while(names.hasNext()){String name=names.next();if(!"template".equals(name))ordered.add(name);}java.util.Collections.sort(ordered);
        for(String name:ordered){JSONObject event=events.optJSONObject(name);LinearLayout card=ui.surface();card.addView(ui.text(XbsSnapshot.pretty(name),18,ui.palette.text,true));
            int explicit=0;if(event!=null){if(event.has("allowlist"))explicit++;if(event.has("denylist"))explicit++;if(event.has("proc_file"))explicit++;}
            card.addView(ui.text(explicit+" explicit list binding"+(explicit==1?"":"s")+" · missing fields inherit template",12,ui.palette.muted,false));
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            Button edit=ui.button("Edit",ui.palette.orange,v->openEvent(name,false));Button clone=ui.secondaryButton("Clone",v->openEvent(name,true));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,0,ui.dp(4),0);row.addView(edit,p);LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,-2,1);p2.setMargins(ui.dp(4),0,0,0);row.addView(clone,p2);card.addView(row);
            card.addView(ui.secondaryButton("Start event",v->runCtl("start",name,false)));card.addView(ui.secondaryButton("Stop event",v->runCtl("stop",name,true)));
            if(XbsConfigApi.isBuiltInEvent(name)) card.addView(ui.text("Built-in event · editable and cloneable, but protected from deletion",12,ui.palette.yellow,true));
            else card.addView(ui.button("Delete event",ui.palette.red,v->deleteEvent(name)));
            content.addView(card);
        }
        if(ordered.isEmpty()){LinearLayout empty=ui.surface();empty.addView(ui.text("No event profiles",17,ui.palette.text,true));content.addView(empty);}
    }

    private void renderRawTools(){
        content.addView(ui.sectionTitle("Advanced"));LinearLayout card=ui.surface();
        card.addView(ui.secondaryButton("Edit raw JSON",v->editRaw()));card.addView(ui.secondaryButton("Validate current config",v->runCommand("validate","Validate config","xbs --json validate",false)));
        card.addView(ui.secondaryButton("Reload runtime",v->runCommand("reload","Reload runtime","xbs --json runtime reload",false)));content.addView(card);
    }

    private void editRaw(){
        if(loading||session.busy()||session.blocked())return;
        if(headerDirty()){status("Save or discard typed settings before editing raw JSON");return;}
        if(rawDraft==null)rawDraft=config.toString();
        rawInput=ui.input(rawDraft,"JSON",true);rawInput.setMinLines(10);ScrollView scroll=new ScrollView(this);LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(ui.dp(14),0,ui.dp(14),0);wrap.addView(rawInput);scroll.addView(wrap);
        rawOpen=true;
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Raw XBS configuration").setView(scroll).setNegativeButton("Cancel",(dialog,w)->{rawDraft=null;rawOpen=false;rawInput=null;}).setPositiveButton("Validate and save",null).create();
        d.setOnCancelListener(dialog->{rawDraft=null;rawOpen=false;rawInput=null;});
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            rawDraft=rawInput.getText().toString();
            try{JSONObject proposed=new JSONObject(rawDraft);rawOpen=false;rawInput=null;d.dismiss();saveConfig(proposed);}catch(Exception e){toast("Invalid JSON: "+e.getMessage());}
        }));d.show();
    }

    private void openEvent(String name,boolean clone){
        if(!allowOtherMutation())return;
        if("template".equals(name)){toast("Use Edit template defaults for the reserved template profile");return;}
        Intent intent=new Intent(this,XbsEventEditorActivity.class);
        intent.putExtra(XbsEventEditorActivity.EXTRA_EVENT,name);
        intent.putExtra(XbsEventEditorActivity.EXTRA_CLONE,clone);
        startActivity(intent);
    }
    private void openTemplateEvent(){
        if(!allowOtherMutation())return;
        Intent intent=new Intent(this,XbsEventEditorActivity.class);
        intent.putExtra(XbsEventEditorActivity.EXTRA_EVENT,"template");
        intent.putExtra(XbsEventEditorActivity.EXTRA_TEMPLATE_DEFAULTS,true);
        startActivity(intent);
    }
    private void deleteEvent(String name){if(!allowOtherMutation())return;if(XbsConfigApi.isBuiltInEvent(name)){toast("Built-in events cannot be deleted");return;}new AlertDialog.Builder(this).setTitle("Delete "+XbsSnapshot.pretty(name)+"?").setMessage("The event will be removed from XtremeBS.json. Managed list files can also be removed.").setNegativeButton("Cancel",null).setNeutralButton("Keep list files",(d,w)->performDelete(name,false)).setPositiveButton("Delete all",(d,w)->performDelete(name,true)).show();}
    private void performDelete(String name,boolean files){
        if(!allowOtherMutation())return;Context app=getApplicationContext();EditorBackend b=backend;
        if(session.start(snapshot(),()->b.delete(app,name,files)))setBusy(true);else status(session.problem());
    }
    private void saveConfig(JSONObject proposed){
        if(loading||session.busy()||session.blocked())return;
        Context app=getApplicationContext();EditorBackend b=backend;String base=config.toString();
        if(session.start(snapshot(),()->{
            XbsConfigApi.Result fresh=b.load(app,true);if(!fresh.ok)return fresh;
            if(!base.equals(fresh.config.toString()))return XbsConfigApi.Result.error("Configuration changed since this draft opened. Discard and reload or keep editing; no save was dispatched.");
            return b.save(app,proposed,new LinkedHashMap<>(),fresh.generation,java.util.Collections.emptyMap());
        }))setBusy(true);else status(session.problem());
    }
    private void runCtl(String action,String event,boolean destructive){String command="XBSctl --json "+action+" "+SuiteAction.shellQuote(event);runCommand("event_"+action,action+" event",command,destructive);}
    private void runCommand(String id,String title,String command,boolean destructive){if(!allowOtherMutation())return;CommandLaunchResult result=RootCommandRunner.runCommand(this,id,title,title,command,destructive,null);toast(result.started?title+" started":result.error);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
