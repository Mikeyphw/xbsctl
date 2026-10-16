package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class XbsEssentialsActivity extends XbsActivity {
    private static final int PICK_APPS = 410;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private UiKit ui; private LinearLayout content; private EditText path,list;

    @Override protected void onCreate(Bundle state){super.onCreate(state);build();load();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}

    private void build(){ui=new UiKit(this);getWindow().setStatusBarColor(ui.palette.background);getWindow().setNavigationBarColor(ui.palette.background);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.palette.background);root.setOnApplyWindowInsetsListener((v,i)->{int t,b;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets x=i.getInsets(WindowInsets.Type.systemBars());t=x.top;b=x.bottom;}else{t=i.getSystemWindowInsetTop();b=i.getSystemWindowInsetBottom();}v.setPadding(0,t,0,b);return i;});LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(8));head.addView(ui.text("XBS ESSENTIALS",12,ui.palette.orange,true));head.addView(ui.text("Default essentials list",28,ui.palette.text,true));head.addView(ui.text("Packages in this list are protected across all events. Edit manually or select installed applications.",13,ui.palette.muted,false));root.addView(head);ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout foot=new LinearLayout(this);foot.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(10));foot.addView(ui.secondaryButton("Back",v->finish()));root.addView(foot);setXbsContentView(root);}
    private void load(){content.removeAllViews();LinearLayout loading=ui.surface();loading.addView(ui.text("Loading essentials…",17,ui.palette.text,true));content.addView(loading);executor.execute(()->{XbsConfigApi.Result result=XbsConfigApi.load(this,false);if(!result.ok){runOnUiThread(()->showError(result.message));return;}org.json.JSONObject header=result.config.optJSONObject("header");String listPath=header==null?XbsConfigApi.LIST_DIR+"/essentials.allowlist":header.optString("essentials_allowlist",XbsConfigApi.LIST_DIR+"/essentials.allowlist");String text=XbsConfigApi.readText(this,listPath,false);runOnUiThread(()->render(listPath,text));});}
    private void showError(String message){content.removeAllViews();LinearLayout card=ui.surface();card.addView(ui.text("Unable to load essentials",18,ui.palette.red,true));card.addView(ui.text(message,13,ui.palette.muted,false));card.addView(ui.button("Check root and retry",ui.palette.orange,v->{executor.execute(()->{XbsRootApi.checkRoot(this,true);runOnUiThread(this::load);});}));content.addView(card);}
    private void render(String listPath,String text){content.removeAllViews();content.addView(ui.sectionTitle("Storage"));LinearLayout storage=ui.surface();storage.addView(ui.text("Essentials file",13,ui.palette.muted,true));path=ui.input(listPath,"Essentials path",false);storage.addView(path);storage.addView(ui.text("Transactional writes are limited to direct files inside the canonical XBS lists directory.",11,ui.palette.muted,false));storage.addView(ui.secondaryButton("Use managed essentials path",v->path.setText(XbsConfigApi.LIST_DIR+"/essentials.allowlist")));content.addView(storage);content.addView(ui.sectionTitle("Protected applications"));LinearLayout card=ui.surface();list=ui.input(text,"One package per line",true);list.setMinLines(14);card.addView(list);card.addView(ui.button("Select installed apps",ui.palette.orange,v->openPicker()));card.addView(ui.secondaryButton("Restore module defaults",v->confirmRestore()));content.addView(card);content.addView(ui.sectionTitle("Save"));LinearLayout save=ui.surface();save.addView(ui.text("Protection applies globally",16,ui.palette.yellow,true));save.addView(ui.text("Removing core Android packages may let an event stop components required for the device UI, telephony or package management.",12,ui.palette.muted,false));save.addView(ui.button("Validate and save essentials",ui.palette.green,v->save()));content.addView(save);}
    private void openPicker(){Intent intent=new Intent(this,AppPickerActivity.class);intent.putExtra(AppPickerActivity.EXTRA_TITLE,"Select essential applications");intent.putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED,new ArrayList<>(packages(list.getText().toString())));startActivityForResult(intent,PICK_APPS);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_APPS&&result==RESULT_OK&&data!=null){ArrayList<String> selected=data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED);if(selected!=null){StringBuilder out=new StringBuilder();for(String value:selected)out.append(value).append('\n');list.setText(out.toString());}}}
    private void save(){String target=path.getText().toString().trim();if(!XbsConfigTransaction.isManagedListPath(target)){toast("Essentials must be stored directly inside "+XbsConfigApi.LIST_DIR);return;}String normalized=normalize(list.getText().toString());if(normalized.isEmpty()){toast("Essentials list cannot be empty");return;}content.removeAllViews();LinearLayout loading=ui.surface();loading.addView(ui.text("Saving essentials…",17,ui.palette.text,true));content.addView(loading);executor.execute(()->{XbsConfigApi.Result loaded=XbsConfigApi.load(this,true);if(!loaded.ok){runOnUiThread(()->showError(loaded.message));return;}try{loaded.config.getJSONObject("header").put("essentials_allowlist",target);}catch(Exception e){runOnUiThread(()->showError(e.getMessage()));return;}java.util.Map<String,String> files=new java.util.LinkedHashMap<>();files.put(target,normalized);XbsConfigApi.Result saved=XbsConfigApi.save(this,loaded.config,files,true);runOnUiThread(()->{toast(saved.ok?"Essentials saved":saved.message);if(saved.ok)load();else render(target,normalized);});});}
    private void confirmRestore(){new AlertDialog.Builder(this).setTitle("Restore default essentials?").setMessage("Replace the current list with the defaults shipped by the XBS module?").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->executor.execute(()->{XbsConfigApi.Result result=XbsConfigApi.restoreDefaultEssentials(this,path.getText().toString().trim());runOnUiThread(()->{toast(result.ok?result.message:result.message);load();});})).show();}
    private static Set<String> packages(String value){Set<String> result=new LinkedHashSet<>();for(String line:(value==null?"":value).replace("\r","").split("\n")){String item=line.trim();if(!item.isEmpty()&&!item.startsWith("#"))result.add(item);}return result;}
    private static String normalize(String value){StringBuilder out=new StringBuilder();for(String item:packages(value))out.append(item).append('\n');return out.toString();}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
