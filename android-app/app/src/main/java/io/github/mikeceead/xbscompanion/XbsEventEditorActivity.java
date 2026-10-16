package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class XbsEventEditorActivity extends XbsActivity {
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_CLONE = "clone";
    public static final String EXTRA_TEMPLATE_DEFAULTS = "template_defaults";
    private static final int PICK_ALLOW=301, PICK_DENY=302, PICK_PROCESS=303;
    private static final Set<String> MANAGED_KEYS = new HashSet<>(Arrays.asList(
        "allowlist", "denylist", "proc_file", "keep_on_charge", "quit", "handle_cores",
        "disable_cores", "handle_apps", "handle_gms", "handle_proc", "low_ram", "doze", "kill_wifi"
    ));

    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private UiKit ui;
    private LinearLayout content;
    private JSONObject config,event;
    private String originalName;
    private boolean clone,template,builtIn;
    private XbsCapabilityRegistry capabilities;

    private EditText name,allow,deny,proc,handleCoresCustom,disableCoresCustom;
    private SwitchCompat inheritAllow,inheritDeny,inheritProc,keepOnCharge,quit,handleProc,lowRam,killWifi;
    private Spinner handleApps,handleGms,doze,handleCores,disableCores;
    private Button saveButton;
    private TextView validationSummary;
    private boolean validationBound;
    private boolean saving;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        originalName=getIntent().getStringExtra(EXTRA_EVENT);
        if(originalName==null)originalName="";
        originalName=originalName.trim();
        clone=getIntent().getBooleanExtra(EXTRA_CLONE,false);
        boolean templateDefaults=getIntent().getBooleanExtra(EXTRA_TEMPLATE_DEFAULTS,false);
        template=templateDefaults&&"template".equals(originalName);
        if("template".equals(originalName)&&!template){
            Toast.makeText(this,"Template defaults are edited from the dedicated Template defaults action",Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        builtIn=XbsConfigApi.isBuiltInEvent(originalName);
        capabilities=XbsCapabilityRegistry.cached(this);
        build();
        load();
    }

    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}

    private void build(){
        ui=new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.palette.background);
        root.setOnApplyWindowInsetsListener((v,i)->{
            int top,bottom;
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets x=i.getInsets(WindowInsets.Type.systemBars());top=x.top;bottom=x.bottom;}
            else{top=i.getSystemWindowInsetTop();bottom=i.getSystemWindowInsetBottom();}
            v.setPadding(0,top,0,bottom);return i;
        });
        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(8));
        head.addView(ui.text("XBS EVENT EDITOR",12,ui.palette.orange,true));
        head.addView(ui.text(template?"Template defaults":originalName.isEmpty()?"New event":clone?"Clone event":XbsSnapshot.pretty(originalName),28,ui.palette.text,true));
        head.addView(ui.text("Configure event behavior with typed controls, then edit lists manually or with app/process pickers.",13,ui.palette.muted,false));
        root.addView(head);
        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(24));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        validationSummary=ui.text("Validation starts after the event loads.",12,ui.palette.muted,false);
        validationSummary.setPadding(ui.dp(16),ui.dp(5),ui.dp(16),ui.dp(3));
        root.addView(validationSummary);
        LinearLayout foot=new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(10));
        Button cancelButton=ui.secondaryButton("Cancel",v->finish());
        saveButton=ui.button(template?"Validate and save template":"Validate and save event",ui.palette.green,v->save());
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(0,-2,1);
        cancelParams.setMargins(0,0,ui.dp(4),0);
        foot.addView(cancelButton,cancelParams);
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,-2,1);
        saveParams.setMargins(ui.dp(4),0,0,0);
        foot.addView(saveButton,saveParams);
        setSaveEnabled(false);
        root.addView(foot);
        setXbsContentView(root);
    }

    private void load(){
        saving=false;
        validationBound=false;
        setSaveEnabled(false);
        content.removeAllViews();
        LinearLayout card=ui.surface();card.addView(ui.text("Loading event profile…",17,ui.palette.text,true));content.addView(card);
        executor.execute(()->{
            XbsConfigApi.Result result=XbsConfigApi.load(this,false);
            if(!result.ok){runOnUiThread(()->error(result.message));return;}
            config=result.config;
            JSONObject events=config.optJSONObject("events");
            if(events==null){runOnUiThread(()->error("Missing events object"));return;}
            JSONObject source=originalName.isEmpty()?new JSONObject():events.optJSONObject(originalName);
            if(source==null){
                String missing=originalName;
                runOnUiThread(()->error("Event not found: "+missing));
                return;
            }
            try{event=new JSONObject(source.toString());}catch(Exception e){event=new JSONObject();}
            JSONObject templateObj=events.optJSONObject("template");
            String allowPath=resolvePath("allowlist",templateObj);
            String denyPath=resolvePath("denylist",templateObj);
            String procPath=resolvePath("proc_file",templateObj);
            String allowText=XbsConfigApi.readText(this,allowPath,false);
            String denyText=XbsConfigApi.readText(this,denyPath,false);
            String procText=XbsConfigApi.readText(this,procPath,false);
            runOnUiThread(()->render(allowText,denyText,procText,allowPath,denyPath,procPath));
        });
    }

    private String resolvePath(String key,JSONObject templateObj){
        if(event.has(key))return event.optString(key,"");
        return templateObj==null?"":templateObj.optString(key,"");
    }

    private void error(String message){
        saving=false;
        validationBound=false;
        setSaveEnabled(false);
        content.removeAllViews();
        LinearLayout card=ui.surface();
        card.addView(ui.text("Unable to edit event",18,ui.palette.red,true));
        card.addView(ui.text(ResultFormatter.notificationText(message),13,ui.palette.muted,false));
        card.addView(ui.button("Check root and retry",ui.palette.orange,v->executor.execute(()->{
            RootStatus status=XbsRootApi.checkRoot(this,true);
            runOnUiThread(()->{toast(status.summary());load();});
        })));
        content.addView(card);
    }

    private void render(String allowText,String denyText,String procText,String allowPath,String denyPath,String procPath){
        content.removeAllViews();
        capabilities=XbsCapabilityRegistry.cached(this);
        XbsCompatibilityReport compatibility=capabilities.report;
        content.addView(ui.sectionTitle("Compatibility"));
        LinearLayout compatibilityCard=ui.surface();
        compatibilityCard.addView(ui.text(compatibility.headline(),17,compatibility.compatible()?ui.palette.green:ui.palette.yellow,true));
        compatibilityCard.addView(ui.text("Only fields advertised by the installed module are written. Unsupported existing fields are preserved unchanged.",12,ui.palette.muted,false));
        if(!compatibility.error.isEmpty())compatibilityCard.addView(ui.text(compatibility.error,12,ui.palette.yellow,false));
        content.addView(compatibilityCard);
        content.addView(ui.sectionTitle("Identity"));
        LinearLayout identity=ui.surface();
        String suggested=clone?(originalName+"_copy"):originalName;
        name=ui.input(suggested,"Event name",false);
        boolean protectedIdentity=template||(!clone&&builtIn);
        name.setEnabled(!protectedIdentity);
        identity.addView(ui.text("Event name",13,ui.palette.muted,true));
        identity.addView(name);
        if(protectedIdentity)identity.addView(ui.text("Protected built-in event: behavior and lists are editable, but rename and deletion are disabled.",12,ui.palette.yellow,true));
        else identity.addView(ui.text("Custom names may contain letters, numbers, dots, underscores and hyphens.",12,ui.palette.muted,false));
        content.addView(identity);

        if(!template)renderBehavior();
        else{
            content.addView(ui.sectionTitle("Template behavior"));
            LinearLayout note=ui.surface();
            note.addView(ui.text("Template controls list inheritance only",17,ui.palette.yellow,true));
            note.addView(ui.text("XBS applies behavior properties per event. Configure behavior on boot, charging, screen off, low power, manual, or custom events.",12,ui.palette.muted,false));
            content.addView(note);
        }

        content.addView(ui.sectionTitle("Application allowlist"));
        LinearLayout allowCard=ui.surface();
        inheritAllow=ui.toggle("Inherit template allowlist",!template&&!event.has("allowlist"));
        inheritAllow.setEnabled(!template);
        allowCard.addView(inheritAllow);
        allow=ui.input(allowText,"One package per line",true);allow.setMinLines(7);allowCard.addView(allow);
        Button allowPicker=ui.secondaryButton("Select installed apps",v->openAppPicker(PICK_ALLOW,"Select allowlisted apps",allow));
        allowPicker.setEnabled(supportsField("allowlist"));
        allowCard.addView(allowPicker);
        allowCard.addView(readOnlyPath("Managed list path",allowPath));
        inheritAllow.setOnCheckedChangeListener((button,on)->allow.setEnabled(!on));
        allow.setEnabled(!inheritAllow.isChecked()&&supportsField("allowlist"));
        inheritAllow.setEnabled(!template&&supportsField("allowlist"));
        content.addView(allowCard);

        content.addView(ui.sectionTitle("Application denylist"));
        LinearLayout denyCard=ui.surface();
        inheritDeny=ui.toggle("Inherit template denylist",!template&&!event.has("denylist"));
        inheritDeny.setEnabled(!template);
        denyCard.addView(inheritDeny);
        deny=ui.input(denyText,"One package per line",true);deny.setMinLines(7);denyCard.addView(deny);
        Button denyPicker=ui.secondaryButton("Select installed apps",v->openAppPicker(PICK_DENY,"Select denylisted apps",deny));
        denyPicker.setEnabled(supportsField("denylist"));
        denyCard.addView(denyPicker);
        denyCard.addView(readOnlyPath("Managed list path",denyPath));
        inheritDeny.setOnCheckedChangeListener((button,on)->deny.setEnabled(!on));
        deny.setEnabled(!inheritDeny.isChecked()&&supportsField("denylist"));
        inheritDeny.setEnabled(!template&&supportsField("denylist"));
        content.addView(denyCard);

        content.addView(ui.sectionTitle("Process rules"));
        LinearLayout processCard=ui.surface();
        inheritProc=ui.toggle("Inherit template process rules",!template&&!event.has("proc_file"));
        inheritProc.setEnabled(!template);
        processCard.addView(inheritProc);
        proc=ui.input(procText,"One rule per line: process.name 10",true);proc.setMinLines(7);processCard.addView(proc);
        Button processPicker=ui.secondaryButton("Select live processes",v->startActivityForResult(new Intent(this,ProcessMonitorActivity.class).putExtra(ProcessMonitorActivity.EXTRA_PICK,true),PICK_PROCESS));
        processPicker.setEnabled(supportsField("proc_file"));
        processCard.addView(processPicker);
        processCard.addView(readOnlyPath("Managed list path",procPath));
        processCard.addView(ui.text("The process picker samples live CPU/RAM and maps each process to its owning Android app. Selected rules are appended with your chosen Linux nice value.",12,ui.palette.muted,false));
        inheritProc.setOnCheckedChangeListener((button,on)->proc.setEnabled(!on));
        proc.setEnabled(!inheritProc.isChecked()&&supportsField("proc_file"));
        inheritProc.setEnabled(!template&&supportsField("proc_file"));
        content.addView(processCard);

        content.addView(ui.sectionTitle("Saving"));
        LinearLayout save=ui.surface();
        save.addView(ui.text("Atomic and validated",17,ui.palette.orange,true));
        save.addView(ui.text("List paths are app-managed and cannot be edited. Use the fixed Validate and save button below. The app captures an immutable draft, validates identity and rule syntax, then commits config and managed lists through the Phase 14 journal with rollback protection.",12,ui.palette.muted,false));
        content.addView(save);
        bindValidation();
        validationBound=true;
        validateDraft(false);
    }

    private void renderBehavior(){
        content.addView(ui.sectionTitle("Event behavior"));
        LinearLayout behavior=ui.surface();
        handleApps=choice(behavior,"App handling",new String[]{"Disabled","Lower priority","Force stop","Suspend"},new String[]{"false","nice","kill","suspend"},stringValue("handle_apps","false"));
        behavior.addView(ui.text("Suspend is the strongest reversible option. Force stop closes apps without suspending them.",11,ui.palette.muted,false));
        handleGms=choice(behavior,"Google Play services handling",new String[]{"Disabled","Lower priority","Disable and stop"},new String[]{"false","nice","kill"},stringValue("handle_gms","false"));
        doze=choice(behavior,"Doze mode",new String[]{"Disabled","Light doze","Deep doze"},new String[]{"false","light","deep"},stringValue("doze","false"));
        handleProc=ui.toggle("Continuously apply process nice rules",boolValue("handle_proc",false));behavior.addView(handleProc);
        lowRam=ui.toggle("Enable Android low-RAM mode",boolValue("low_ram",false));behavior.addView(lowRam);
        killWifi=ui.toggle("Disable Wi-Fi while active",boolValue("kill_wifi",false));behavior.addView(killWifi);
        keepOnCharge=ui.toggle("Keep event active while charging",boolValue("keep_on_charge",false));behavior.addView(keepOnCharge);
        quit=ui.toggle("Quit XBS daemon after activation",boolValue("quit",false));behavior.addView(quit);
        behavior.addView(ui.text("Quit is intended for the boot event. keep_on_charge is preserved for XBS compatibility.",11,ui.palette.muted,false));
        keepOnCharge.setEnabled(supportsField("keep_on_charge"));
        quit.setEnabled(supportsField("quit"));
        handleApps.setEnabled(supportsField("handle_apps"));
        handleGms.setEnabled(supportsField("handle_gms"));
        handleProc.setEnabled(supportsField("handle_proc"));
        lowRam.setEnabled(supportsField("low_ram"));
        doze.setEnabled(supportsField("doze"));
        killWifi.setEnabled(supportsField("kill_wifi"));
        content.addView(behavior);

        content.addView(ui.sectionTitle("CPU controls"));
        LinearLayout cpu=ui.surface();
        String handleValue=stringValue("handle_cores","false");
        handleCores=coreChoice(cpu,"Set powersave governor",handleValue);
        handleCores.setEnabled(supportsField("handle_cores"));
        handleCoresCustom=ui.input(customCoreValue(handleValue),"Custom cores, for example: cpu0 cpu1",false);
        cpu.addView(handleCoresCustom);
        bindCoreInput(handleCores,handleCoresCustom);
        String disableValue=stringValue("disable_cores","false");
        disableCores=coreChoice(cpu,"Disable CPU cores",disableValue);
        disableCoresCustom=ui.input(customCoreValue(disableValue),"Custom cores, for example: cpu6 cpu7",false);
        cpu.addView(disableCoresCustom);
        bindCoreInput(disableCores,disableCoresCustom);
        cpu.addView(ui.text("Auto uses XBS CPU topology detection. Custom values are space-separated Linux CPU directory names.",11,ui.palette.muted,false));
        content.addView(cpu);
    }

    private android.widget.TextView readOnlyPath(String label,String path){
        String value = path == null || path.isEmpty() ? getString(R.string.xbs_read_only_path_empty) : path;
        return ui.text(getString(R.string.xbs_read_only_path, label, value),10,ui.palette.muted,false);
    }

    private Spinner choice(LinearLayout parent,String label,String[] labels,String[] values,String selected){
        parent.addView(ui.text(label,13,ui.palette.muted,true));
        Spinner spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index=0;for(int i=0;i<values.length;i++)if(values[i].equalsIgnoreCase(selected)){index=i;break;}
        spinner.setSelection(index);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,ui.dp(6),0,ui.dp(8));spinner.setLayoutParams(params);
        spinner.setTag(values);
        parent.addView(spinner);
        return spinner;
    }

    private Spinner coreChoice(LinearLayout parent,String label,String current){
        String mode="custom";
        if("false".equalsIgnoreCase(current)||current.isEmpty())mode="false";
        else if("auto".equalsIgnoreCase(current))mode="auto";
        return choice(parent,label,new String[]{"Disabled","Automatic","Custom core list"},new String[]{"false","auto","custom"},mode);
    }

    private void bindCoreInput(Spinner spinner,EditText input){
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                input.setEnabled("custom".equals(choiceValue(spinner)));
                validateDraft(false);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){input.setEnabled(false);validateDraft(false);}
        });
        input.setEnabled("custom".equals(choiceValue(spinner)));
    }

    private String choiceValue(Spinner spinner){
        String[] values=(String[])spinner.getTag();
        int index=spinner.getSelectedItemPosition();
        return index>=0&&index<values.length?values[index]:values[0];
    }

    private String customCoreValue(String value){
        return "false".equalsIgnoreCase(value)||"auto".equalsIgnoreCase(value)?"":value;
    }

    private String stringValue(String key,String fallback){
        Object value=event.opt(key);
        if(value==null||value==JSONObject.NULL)return fallback;
        return String.valueOf(value);
    }

    private boolean boolValue(String key,boolean fallback){
        Object value=event.opt(key);
        if(value==null||value==JSONObject.NULL)return fallback;
        if(value instanceof Boolean)return (Boolean)value;
        String text=String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text)||"1".equals(text)||"yes".equalsIgnoreCase(text)||"on".equalsIgnoreCase(text);
    }

    private void openAppPicker(int request,String title,EditText field){
        Intent intent=new Intent(this,AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_TITLE,title);
        intent.putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED,new ArrayList<>(packageLines(field.getText().toString())));
        startActivityForResult(intent,request);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==PICK_ALLOW||request==PICK_DENY){
            ArrayList<String> selected=data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED);
            if(selected!=null){StringBuilder out=new StringBuilder();for(String value:selected)out.append(value).append('\n');(request==PICK_ALLOW?allow:deny).setText(out.toString());}
        }else if(request==PICK_PROCESS){
            String rules=data.getStringExtra(ProcessMonitorActivity.EXTRA_RULES);
            if(rules!=null&&!rules.isEmpty())proc.setText(mergeRules(proc.getText().toString(),rules));
        }
    }

    private void bindValidation(){
        TextWatcher watcher=new TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){}
            public void afterTextChanged(Editable value){validateDraft(false);}
        };
        for(EditText field:new EditText[]{name,allow,deny,proc,handleCoresCustom,disableCoresCustom})if(field!=null)field.addTextChangedListener(watcher);
        bindToggleValidation(inheritAllow,allow);
        bindToggleValidation(inheritDeny,deny);
        bindToggleValidation(inheritProc,proc);
        if(!template){
            for(SwitchCompat toggle:new SwitchCompat[]{keepOnCharge,quit,handleProc,lowRam,killWifi})if(toggle!=null)toggle.setOnCheckedChangeListener((button,on)->validateDraft(false));
            bindSpinnerValidation(handleApps);
            bindSpinnerValidation(handleGms);
            bindSpinnerValidation(doze);
        }
    }

    private void bindToggleValidation(SwitchCompat toggle,EditText field){
        if(toggle==null)return;
        toggle.setOnCheckedChangeListener((button,on)->{
            if(field!=null)field.setEnabled(!on);
            validateDraft(false);
        });
    }

    private void bindSpinnerValidation(Spinner spinner){
        if(spinner==null)return;
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){validateDraft(false);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){validateDraft(false);}
        });
    }

    private XbsEventDraftValidator.Result validateDraft(boolean focusFirstError){
        if(!validationBound||saving)return new XbsEventDraftValidator.Result(new ArrayList<>());
        EditorState state=captureState();
        XbsEventDraftValidator.Result result=XbsEventDraftValidator.validate(state.validation);
        applyValidation(result,focusFirstError);
        return result;
    }

    private void applyValidation(XbsEventDraftValidator.Result result,boolean focusFirstError){
        clearError(name);clearError(allow);clearError(deny);clearError(proc);clearError(handleCoresCustom);clearError(disableCoresCustom);
        setError(name,result.firstError(XbsEventDraftValidator.FIELD_NAME));
        setError(allow,result.firstError(XbsEventDraftValidator.FIELD_ALLOWLIST));
        setError(deny,result.firstError(XbsEventDraftValidator.FIELD_DENYLIST));
        setError(proc,result.firstError(XbsEventDraftValidator.FIELD_PROCESS_RULES));
        setError(handleCoresCustom,result.firstError(XbsEventDraftValidator.FIELD_HANDLE_CORES));
        setError(disableCoresCustom,result.firstError(XbsEventDraftValidator.FIELD_DISABLE_CORES));
        if(validationSummary!=null){
            if(result.hasErrors()){
                validationSummary.setText(result.errorCount()+" error"+(result.errorCount()==1?"":"s")+" must be fixed before saving");
                validationSummary.setTextColor(ui.palette.red);
            }else if(result.hasWarnings()){
                validationSummary.setText(result.warningCount()+" warning"+(result.warningCount()==1?"":"s")+" will be reviewed before saving");
                validationSummary.setTextColor(ui.palette.yellow);
            }else{
                validationSummary.setText("Ready: identity, lists, process rules, and CPU controls are valid");
                validationSummary.setTextColor(ui.palette.green);
            }
        }
        setSaveEnabled(!result.hasErrors());
        if(focusFirstError&&result.hasErrors())focusField(result.firstErrorField());
    }

    private static void clearError(EditText field){if(field!=null)field.setError(null);}
    private static void setError(EditText field,String error){if(field!=null&&!error.isEmpty())field.setError(error);}

    private void focusField(String field){
        EditText target=null;
        if(XbsEventDraftValidator.FIELD_NAME.equals(field))target=name;
        else if(XbsEventDraftValidator.FIELD_ALLOWLIST.equals(field))target=allow;
        else if(XbsEventDraftValidator.FIELD_DENYLIST.equals(field))target=deny;
        else if(XbsEventDraftValidator.FIELD_PROCESS_RULES.equals(field))target=proc;
        else if(XbsEventDraftValidator.FIELD_HANDLE_CORES.equals(field))target=handleCoresCustom;
        else if(XbsEventDraftValidator.FIELD_DISABLE_CORES.equals(field))target=disableCoresCustom;
        if(target!=null){target.requestFocus();target.setSelection(target.getText().length());}
    }

    private EditorState captureState(){
        String requestedName=name==null?originalName:name.getText().toString().trim();
        String target=template?"template":(!clone&&builtIn?originalName:requestedName);
        Set<String> existing=new LinkedHashSet<>();
        JSONObject events=config==null?null:config.optJSONObject("events");
        if(events!=null){java.util.Iterator<String> names=events.keys();while(names.hasNext())existing.add(names.next());}
        String allowValue=text(allow),denyValue=text(deny),procValue=text(proc);
        String handleAppsValue=template?"false":choiceValue(handleApps);
        boolean handleProcessValue=!template&&handleProc.isChecked();
        String handleMode=template?"false":choiceValue(handleCores);
        String disableMode=template?"false":choiceValue(disableCores);
        XbsEventDraftValidator.Draft validation=new XbsEventDraftValidator.Draft(
            target,originalName,template,clone,builtIn,existing,
            allowValue,denyValue,procValue,
            checked(inheritAllow),checked(inheritDeny),checked(inheritProc),
            handleAppsValue,handleProcessValue,
            handleMode,text(handleCoresCustom),disableMode,text(disableCoresCustom),
            !template&&quit.isChecked());
        return new EditorState(
            validation,target,allowValue,denyValue,procValue,
            checked(inheritAllow),checked(inheritDeny),checked(inheritProc),
            template?false:keepOnCharge.isChecked(),template?false:quit.isChecked(),
            handleAppsValue,template?"false":choiceValue(handleGms),handleProcessValue,
            template?false:lowRam.isChecked(),template?"false":choiceValue(doze),template?false:killWifi.isChecked(),
            handleMode,text(handleCoresCustom),disableMode,text(disableCoresCustom));
    }

    private static boolean checked(SwitchCompat toggle){return toggle!=null&&toggle.isChecked();}
    private static String text(EditText field){return field==null?"":field.getText().toString();}

    private void save(){
        EditorState state=captureState();
        XbsEventDraftValidator.Result result=XbsEventDraftValidator.validate(state.validation);
        applyValidation(result,true);
        if(result.hasErrors())return;
        if(result.hasWarnings()){
            new AlertDialog.Builder(this)
                .setTitle("Review event warnings")
                .setMessage(result.warningText(8))
                .setNegativeButton("Keep editing",null)
                .setPositiveButton("Continue",(dialog,which)->confirmReplacementOrSave(state))
                .show();
            return;
        }
        confirmReplacementOrSave(state);
    }

    private void confirmReplacementOrSave(EditorState state){
        try{
            JSONObject events=config.getJSONObject("events");
            if(!template&&events.has(state.target)&&(!state.target.equals(originalName)||clone)){
                new AlertDialog.Builder(this)
                    .setTitle("Replace existing event?")
                    .setMessage(state.target+" already exists. Its configuration and managed lists will be replaced transactionally.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Replace",(dialog,which)->performSave(state))
                    .show();
                return;
            }
        }catch(Exception exception){toast(exception.getMessage());return;}
        performSave(state);
    }

    private void performSave(EditorState state){
        saving=true;
        validationBound=false;
        setSaveEnabled(false);
        if(saveButton!=null)saveButton.setText(R.string.xbs_saving);
        content.removeAllViews();
        LinearLayout card=ui.surface();card.addView(ui.text("Saving event…",17,ui.palette.text,true));content.addView(card);
        executor.execute(()->{
            try{
                JSONObject workingConfig=new JSONObject(config.toString());
                JSONObject events=workingConfig.getJSONObject("events");
                JSONObject updated=new JSONObject();
                Map<String,String> files=new LinkedHashMap<>();
                if(!template)putBehavior(updated,state);
                bind(updated,files,state.target,"allowlist",".allowlist",state.inheritAllow,state.allowValue);
                bind(updated,files,state.target,"denylist",".denylist",state.inheritDeny,state.denyValue);
                bind(updated,files,state.target,"proc_file",".proc",state.inheritProc,state.procValue);
                java.util.Iterator<String> keys=event.keys();
                while(keys.hasNext()){
                    String key=keys.next();
                    if((!MANAGED_KEYS.contains(key)||!supportsField(key))&&!updated.has(key))updated.put(key,event.get(key));
                }
                events.put(state.target,updated);
                if(!template&&!clone&&!originalName.isEmpty()&&!originalName.equals(state.target)){
                    if(!XbsConfigApi.isCustomEvent(originalName))throw new IllegalStateException("Protected event identity changed unexpectedly");
                    events.remove(originalName);
                }
                XbsConfigApi.Result result=XbsConfigApi.save(this,workingConfig,files,true);
                runOnUiThread(()->{
                    saving=false;
                    if(result.ok){toast("Event saved in transaction "+result.message);finish();}
                    else{toast(result.message);load();}
                });
            }catch(Exception exception){runOnUiThread(()->{saving=false;toast(exception.getMessage());load();});}
        });
    }

    private void putBehavior(JSONObject target,EditorState state)throws Exception{
        putSupported(target,"keep_on_charge",state.keepOnCharge);
        putSupported(target,"quit",state.quit);
        putSupported(target,"handle_apps",state.handleApps);
        putSupported(target,"handle_gms",state.handleGms);
        putSupported(target,"handle_proc",state.handleProc);
        putSupported(target,"low_ram",state.lowRam);
        putSupported(target,"doze",state.doze);
        putSupported(target,"kill_wifi",state.killWifi);
        putSupported(target,"handle_cores",coreValue(state.handleCoresMode,state.handleCoresCustom));
        putSupported(target,"disable_cores",coreValue(state.disableCoresMode,state.disableCoresCustom));
    }

    private void putSupported(JSONObject target,String key,Object value)throws Exception{
        if(supportsField(key))target.put(key,value);
    }

    private boolean supportsField(String key){
        return capabilities==null||capabilities.canWriteEventField(key);
    }

    private static String coreValue(String mode,String custom){
        return "custom".equals(mode)?normalizeCoreList(custom):mode;
    }

    private static String normalizeCoreList(String value){
        StringBuilder out=new StringBuilder();
        for(String token:(value==null?"":value).trim().split("[\\s,]+")){
            if(token.isEmpty())continue;
            if(out.length()>0)out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    private void bind(JSONObject target,Map<String,String> files,String eventName,String key,String suffix,boolean inherit,String text)throws Exception{
        if(!supportsField(key))return;
        if(!template&&inherit)return;
        String path=XbsConfigApi.managedPath(eventName,suffix);
        target.put(key,path);
        files.put(path,normalize(text));
    }

    private static final class EditorState{
        final XbsEventDraftValidator.Draft validation;
        final String target,allowValue,denyValue,procValue,handleApps,handleGms,doze,handleCoresMode,handleCoresCustom,disableCoresMode,disableCoresCustom;
        final boolean inheritAllow,inheritDeny,inheritProc,keepOnCharge,quit,handleProc,lowRam,killWifi;
        EditorState(XbsEventDraftValidator.Draft validation,String target,String allowValue,String denyValue,String procValue,
                boolean inheritAllow,boolean inheritDeny,boolean inheritProc,boolean keepOnCharge,boolean quit,
                String handleApps,String handleGms,boolean handleProc,boolean lowRam,String doze,boolean killWifi,
                String handleCoresMode,String handleCoresCustom,String disableCoresMode,String disableCoresCustom){
            this.validation=validation;this.target=target;this.allowValue=allowValue;this.denyValue=denyValue;this.procValue=procValue;
            this.inheritAllow=inheritAllow;this.inheritDeny=inheritDeny;this.inheritProc=inheritProc;this.keepOnCharge=keepOnCharge;this.quit=quit;
            this.handleApps=handleApps;this.handleGms=handleGms;this.handleProc=handleProc;this.lowRam=lowRam;this.doze=doze;this.killWifi=killWifi;
            this.handleCoresMode=handleCoresMode;this.handleCoresCustom=handleCoresCustom;this.disableCoresMode=disableCoresMode;this.disableCoresCustom=disableCoresCustom;
        }
    }

    private static Set<String> packageLines(String value){
        Set<String> result=new LinkedHashSet<>();
        for(String line:(value==null?"":value).replace("\r","").split("\n")){
            String item=line.trim();if(!item.isEmpty()&&!item.startsWith("#"))result.add(item);
        }
        return result;
    }

    private static String mergeRules(String current,String added){
        LinkedHashSet<String> lines=new LinkedHashSet<>();
        for(String source:new String[]{current,added})for(String line:(source==null?"":source).replace("\r","").split("\n")){
            String value=line.trim();if(!value.isEmpty())lines.add(value);
        }
        StringBuilder out=new StringBuilder();for(String line:lines)out.append(line).append('\n');return out.toString();
    }

    private String normalize(String value){
        StringBuilder out=new StringBuilder();
        for(String line:value.replace("\r","").split("\n")){String item=line.trim();if(!item.isEmpty()&&!item.startsWith("#"))out.append(item).append('\n');}
        return out.toString();
    }

    private void setSaveEnabled(boolean enabled){
        if(saveButton==null)return;
        saveButton.setEnabled(enabled);
        saveButton.setAlpha(enabled?1f:0.45f);
        if(!saving)saveButton.setText(template?R.string.xbs_validate_save_template:R.string.xbs_validate_save_event);
    }

    private void toast(String message){Toast.makeText(this,ResultFormatter.notificationText(message),Toast.LENGTH_LONG).show();}
}
