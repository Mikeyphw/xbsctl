package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskerPluginActivity extends XbsActivity {
    private UiKit ui;
    private Spinner commandSpinner;
    private LinearLayout fieldsHost;
    private List<CliCommand> commands;
    private final Map<String,View> controls=new LinkedHashMap<>();
    private String savedCommand="",savedValues=""; private boolean savedDestructive;
    private TaskerHostIdentity hostIdentity;
    private SwitchCompat destructiveOptIn;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        hostIdentity=TaskerAuthorization.editorIdentity(this);
        if (!hostIdentity.valid) {
            Toast.makeText(this, "Open this action from Tasker.", Toast.LENGTH_LONG).show();setResult(RESULT_CANCELED);finish();return;
        }
        ui=new UiKit(this);getWindow().setStatusBarColor(ui.palette.background);getWindow().setNavigationBarColor(ui.palette.background);
        registerXbsBackHandler(this::save);loadSaved();setXbsContentView(build());restoreSelection();
    }
    private View build(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(20),ui.dp(18),ui.dp(20),ui.dp(24));content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text("XBS COMPANION",12,ui.palette.orange,true));content.addView(ui.text("Tasker CLI action",27,ui.palette.text,true));
        TextView subtitle=ui.text("Every literal xbs and XBSctl command is available as an allowlisted typed form. Runtime variable replacement is disabled so the signature remains exact. Tasker receives structured completion variables.",14,ui.palette.muted,false);subtitle.setPadding(0,ui.dp(6),0,ui.dp(12));content.addView(subtitle);
        LinearLayout card=ui.surface();commands=XbsCliCatalog.runnable();commandSpinner=spinner(labels(commands));card.addView(label("Command"));card.addView(commandSpinner);
        fieldsHost=new LinearLayout(this);fieldsHost.setOrientation(LinearLayout.VERTICAL);card.addView(fieldsHost);
        commandSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){renderFields(commands.get(pos),"");}});
        destructiveOptIn=ui.toggle("Allow destructive command",false);card.addView(destructiveOptIn);
        card.addView(ui.text("The saved configuration is HMAC-signed and pinned to the current Tasker signing certificate.",12,ui.palette.muted,true));card.addView(ui.button("Save Tasker action",ui.palette.orange,v->save()));card.addView(ui.secondaryButton("Cancel",v->{setResult(RESULT_CANCELED);finish();}));content.addView(card);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);return scroll;
    }

    private void loadSaved(){Bundle b=getIntent().getBundleExtra(TaskerProtocol.EXTRA_BUNDLE);if(b!=null){if(!TaskerAuthorization.verify(this,b,false).valid)return;savedCommand=b.getString(TaskerProtocol.KEY_CLI_COMMAND,"");savedValues=b.getString(TaskerProtocol.KEY_CLI_VALUES,"");savedDestructive=b.getBoolean(TaskerRequestPolicy.KEY_ALLOW_DESTRUCTIVE,false);if(savedCommand.isEmpty()){String legacy=b.getString(TaskerProtocol.KEY_ACTION,"");savedCommand=legacyCommand(legacy);String arg=b.getString(TaskerProtocol.KEY_ARGUMENT,"");if(!arg.isEmpty()&&(savedCommand.equals("event_run")||savedCommand.equals("event_stop"))){try{JSONObject o=new JSONObject();o.put("event",arg);savedValues=o.toString();}catch(Exception ignored){}}}}}
    private static String legacyCommand(String id){if("reload".equals(id))return "runtime_reload";if("pause".equals(id))return "runtime_pause";if("resume".equals(id))return "runtime_resume";if("safe_on".equals(id)||"safe_off".equals(id))return "runtime_safe";if("event_list".equals(id))return "event_list";if("event_run".equals(id))return "event_run";if("event_stop".equals(id))return "event_stop";if("validate".equals(id))return "validate";return "status";}
    private void restoreSelection(){int index=0;for(int i=0;i<commands.size();i++)if(commands.get(i).id.equals(savedCommand)){index=i;break;}commandSpinner.setSelection(index);renderFields(commands.get(index),savedValues);destructiveOptIn.setChecked(savedDestructive&&commands.get(index).destructive);}

    private void renderFields(CliCommand command,String json){
        if(fieldsHost==null)return;fieldsHost.removeAllViews();controls.clear();JSONObject saved=null;try{if(json!=null&&!json.isEmpty())saved=new JSONObject(json);}catch(Exception ignored){}
        for(CliField field:command.fields){fieldsHost.addView(label(field.label+(field.required?" *":"")));View control;String initial=field.defaultValue;if(saved!=null)initial=saved.optString(field.name,initial);
            if(field.kind==CliField.Kind.BOOLEAN){SwitchCompat sw=ui.toggle("Enable","true".equalsIgnoreCase(initial));control=sw;fieldsHost.addView(sw);}
            else if(field.kind==CliField.Kind.CHOICE){Spinner sp=spinner(field.choices);int idx=0;for(int i=0;i<field.choices.length;i++)if(field.choices[i].equals(initial))idx=i;sp.setSelection(idx);control=sp;fieldsHost.addView(sp);}
            else{EditText e=ui.input(initial,field.hint,field.kind==CliField.Kind.MULTILINE||field.kind==CliField.Kind.WORDS);if(field.kind==CliField.Kind.INTEGER)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);control=e;fieldsHost.addView(e);}controls.put(field.name,control);
        }
    }

    private void save(){
        if(commands==null||commands.isEmpty())return;CliCommand selected=commands.get(commandSpinner.getSelectedItemPosition());Map<String,String> values=collect(selected);
        if(selected.destructive&&!destructiveOptIn.isChecked()){Toast.makeText(this,"Enable destructive-command permission for this Tasker action.",Toast.LENGTH_LONG).show();return;}
        try{selected.build(values);}catch(IllegalArgumentException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();return;}
        JSONObject object=new JSONObject();try{for(Map.Entry<String,String> e:values.entrySet())object.put(e.getKey(),e.getValue());}catch(Exception e){Toast.makeText(this,"Could not save fields",Toast.LENGTH_LONG).show();return;}
        Bundle bundle=new Bundle();bundle.putString(TaskerProtocol.KEY_MODULE,"xbs");bundle.putString(TaskerProtocol.KEY_CLI_COMMAND,selected.id);bundle.putString(TaskerProtocol.KEY_CLI_VALUES,object.toString());bundle.putString(TaskerProtocol.KEY_BACKEND,"root");bundle.putBoolean(TaskerRequestPolicy.KEY_ALLOW_DESTRUCTIVE,selected.destructive&&destructiveOptIn.isChecked());if(!TaskerAuthorization.sign(this,bundle,hostIdentity)){Toast.makeText(this,"Could not sign Tasker configuration",Toast.LENGTH_LONG).show();return;}
        Intent result=new Intent();TaskerProtocol.decorate(result,bundle);setResult(RESULT_OK,result);finish();
    }

    private Map<String,String> collect(CliCommand command){Map<String,String> out=new LinkedHashMap<>();for(CliField field:command.fields){View control=controls.get(field.name);String value="";if(control instanceof EditText)value=((EditText)control).getText().toString();else if(control instanceof SwitchCompat)value=((SwitchCompat)control).isChecked()?"true":"false";else if(control instanceof Spinner&&((Spinner)control).getSelectedItem()!=null)value=((Spinner)control).getSelectedItem().toString();out.put(field.name,value);}return out;}
    private TextView label(String text){TextView v=ui.text(text,12,ui.palette.muted,true);v.setPadding(0,ui.dp(9),0,ui.dp(4));return v;}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);return s;}
    private static String[] labels(List<CliCommand> commands){String[] out=new String[commands.size()];for(int i=0;i<commands.size();i++)out[i]=commands.get(i).category+" · "+commands.get(i).title;return out;}
}
