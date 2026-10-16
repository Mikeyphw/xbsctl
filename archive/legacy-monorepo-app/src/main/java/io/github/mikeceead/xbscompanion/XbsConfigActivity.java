package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
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

    @Override protected void onCreate(Bundle state){super.onCreate(state);eventsOnly=getIntent().getBooleanExtra("events_only",false);buildShell();load();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
    @Override protected void onResume(){super.onResume();if(config!=null)load();}

    private void buildShell(){
        ui=new UiKit(this);getWindow().setStatusBarColor(ui.palette.background);getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.palette.background);
        root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(0,top,0,bottom);return insets;});
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(8));
        head.addView(ui.text("XBS FRONTEND",12,ui.palette.orange,true));head.addView(ui.text(eventsOnly?"Event profiles":"Configuration",28,ui.palette.text,true));
        head.addView(ui.text(eventsOnly?"Create, clone, edit and delete XBS events.":"Edit XBS settings and event profiles with validation and backups.",13,ui.palette.muted,false));root.addView(head);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(10));footer.addView(ui.secondaryButton("Back",v->finish()));root.addView(footer);setXbsContentView(root);
    }

    private void load(){showLoading("Loading XBS configuration…");executor.execute(()->{XbsConfigApi.Result result=XbsConfigApi.load(this,false);XbsConfigTransaction.Record latest=XbsConfigApi.latestTransaction(this);runOnUiThread(()->{transaction=latest;if(result.ok){config=result.config;render();}else showError(result.message);});});}
    private void showLoading(String text){content.removeAllViews();LinearLayout card=ui.surface();card.addView(ui.text(text,17,ui.palette.text,true));content.addView(card);}
    private void showError(String text){content.removeAllViews();LinearLayout card=ui.surface();card.addView(ui.text("Could not load configuration",18,ui.palette.red,true));card.addView(ui.text(text,13,ui.palette.muted,false));RootStatus root=XbsRootApi.cachedRootStatus(this);String guidance=root.granted?"The canonical XBS configuration could not be initialized or validated. Open Root Diagnostics to initialize and verify it, then retry.":"Event CRUD requires root access and a readable XtremeBS.json. Grant root to XBS Companion, then retry.";card.addView(ui.text(guidance,12,ui.palette.yellow,false));card.addView(ui.button("Retry",ui.palette.orange,v->load()));card.addView(ui.secondaryButton("Open all XBS tools",v->startActivity(new Intent(this,CliCommandActivity.class))));content.addView(card);}

    private void render(){
        content.removeAllViews();headerControls.clear();
        if(!eventsOnly){renderHeader();renderTransactionJournal();renderRawTools();}
        renderEvents();
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
        card.addView(ui.button("Edit default essentials list",ui.palette.green,v->startActivity(new Intent(this,XbsEssentialsActivity.class))));
        card.addView(ui.text("The essentials list protects packages globally across every simultaneously active event.",12,ui.palette.muted,false));
        card.addView(ui.button("Validate and save settings",ui.palette.orange,v->saveHeader()));content.addView(card);
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
        if(transaction==null||!transaction.canRollback()){toast("Latest transaction is not rollback-ready");return;}
        new AlertDialog.Builder(this).setTitle("Rollback latest save?")
            .setMessage("Restore every config/list file captured before transaction "+transaction.id+" and reload XBS. Rollback is refused if a newer save exists or managed files changed after commit.")
            .setNegativeButton("Cancel",null).setPositiveButton("Rollback",(d,w)->performTransactionAction(false)).show();
    }

    private void confirmRecovery(){
        if(transaction==null||!transaction.needsRecovery()){toast("No interrupted transaction requires recovery");return;}
        new AlertDialog.Builder(this).setTitle("Recover interrupted save?")
            .setMessage("Restore the complete pre-transaction file set and reload XBS.")
            .setNegativeButton("Cancel",null).setPositiveButton("Recover",(d,w)->performTransactionAction(true)).show();
    }

    private void performTransactionAction(boolean recover){
        XbsConfigTransaction.Record selected=transaction;
        showLoading(recover?"Recovering configuration transaction…":"Rolling back configuration transaction…");
        executor.execute(()->{XbsConfigApi.Result result=recover?XbsConfigApi.recoverLatest(this,selected):XbsConfigApi.rollbackLatest(this,selected);runOnUiThread(()->{toast(result.ok?(recover?"Configuration recovered":"Configuration rolled back"):result.message);load();});});
    }

    private void addText(LinearLayout card,String key,String label,String value){card.addView(ui.text(label,13,ui.palette.muted,true));EditText e=ui.input(value,label,false);card.addView(e);headerControls.put(key,e);}
    private void addInteger(LinearLayout card,String key,String label,int value){card.addView(ui.text(label,13,ui.palette.muted,true));EditText e=ui.input(String.valueOf(value),label,false);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);card.addView(e);headerControls.put(key,e);}
    private void addSwitch(LinearLayout card,String key,String label,boolean value){SwitchCompat s=ui.toggle(label,value);card.addView(s);headerControls.put(key,s);}

    private void saveHeader(){
        try{
            JSONObject header=config.getJSONObject("header");
            header.put("version",2);
            for(Map.Entry<String,View> item:headerControls.entrySet()){
                String key=item.getKey();View view=item.getValue();
                if(view instanceof SwitchCompat)header.put(key,((SwitchCompat)view).isChecked());
                else {String value=((EditText)view).getText().toString().trim();if("delay".equals(key)||"log_level".equals(key))header.put(key,Integer.parseInt(value));else header.put(key,value);}
            }
        }catch(Exception e){toast("Invalid setting: "+e.getMessage());return;}
        saveConfig("Settings saved");
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

    private void editRaw(){EditText raw=ui.input(config.toString(),"JSON",true);raw.setMinLines(16);ScrollView scroll=new ScrollView(this);LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(ui.dp(14),0,ui.dp(14),0);wrap.addView(raw);scroll.addView(wrap);AlertDialog d=new AlertDialog.Builder(this).setTitle("Raw XBS configuration").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Validate and save",null).create();d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{config=new JSONObject(raw.getText().toString());d.dismiss();saveConfig("Raw configuration saved");}catch(Exception e){toast("Invalid JSON: "+e.getMessage());}}));d.show();}

    private void openEvent(String name,boolean clone){
        if("template".equals(name)){toast("Use Edit template defaults for the reserved template profile");return;}
        Intent intent=new Intent(this,XbsEventEditorActivity.class);
        intent.putExtra(XbsEventEditorActivity.EXTRA_EVENT,name);
        intent.putExtra(XbsEventEditorActivity.EXTRA_CLONE,clone);
        startActivity(intent);
    }
    private void openTemplateEvent(){
        Intent intent=new Intent(this,XbsEventEditorActivity.class);
        intent.putExtra(XbsEventEditorActivity.EXTRA_EVENT,"template");
        intent.putExtra(XbsEventEditorActivity.EXTRA_TEMPLATE_DEFAULTS,true);
        startActivity(intent);
    }
    private void deleteEvent(String name){if(XbsConfigApi.isBuiltInEvent(name)){toast("Built-in events cannot be deleted");return;}new AlertDialog.Builder(this).setTitle("Delete "+XbsSnapshot.pretty(name)+"?").setMessage("The event will be removed from XtremeBS.json. Managed list files can also be removed.").setNegativeButton("Cancel",null).setNeutralButton("Keep list files",(d,w)->performDelete(name,false)).setPositiveButton("Delete all",(d,w)->performDelete(name,true)).show();}
    private void performDelete(String name,boolean files){showLoading("Deleting event…");executor.execute(()->{XbsConfigApi.Result result=XbsConfigApi.deleteEvent(this,name,files);runOnUiThread(()->{toast(result.ok?"Event deleted":result.message);load();});});}

    private void saveConfig(String success){showLoading("Validating and saving…");executor.execute(()->{XbsConfigApi.Result result=XbsConfigApi.save(this,config,new LinkedHashMap<>(),true);runOnUiThread(()->{toast(result.ok?success:result.message);if(result.ok)load();else render();});});}
    private void runCtl(String action,String event,boolean destructive){String command="XBSctl --json "+action+" "+SuiteAction.shellQuote(event);runCommand("event_"+action,action+" event",command,destructive);}
    private void runCommand(String id,String title,String command,boolean destructive){CommandLaunchResult result=RootCommandRunner.runCommand(this,id,title,title,command,destructive,null);toast(result.started?title+" started":result.error);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
