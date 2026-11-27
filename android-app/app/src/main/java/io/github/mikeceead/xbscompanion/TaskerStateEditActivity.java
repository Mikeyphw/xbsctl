package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import androidx.lifecycle.ViewModelProvider;
import org.json.JSONObject;
import android.widget.Toast;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

public final class TaskerStateEditActivity extends XbsActivity {
    private static final String[] TYPES = {"Runtime available", "Runtime paused", "Safe mode active", "Any event active", "Specific event active"};
    private static final String[] VALUES = {"available", "paused", "safe_mode", "any_active", "event_active"};
    private Spinner type; private EditText filter; private UiKit ui; private TaskerHostIdentity hostIdentity;
    private EditorSession session;
    private void restoreDraft(Bundle state){
        session=new ViewModelProvider(this).get(EditorSession.class);session.initialize(this,state,"tasker-state:"+hostIdentity.packageName+":"+hostIdentity.certificateSha256);
        if(session.blocked()){Toast.makeText(this,session.problem(),Toast.LENGTH_LONG).show();setResult(RESULT_CANCELED);finish();return;}
        JSONObject draft=session.snapshot();String selected=draft.optString("state","");for(int i=0;i<VALUES.length;i++)if(VALUES[i].equals(selected)){type.setSelection(i);filter.setText(draft.optString("filter",""));break;}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        if(session!=null&&type!=null&&filter!=null){JSONObject d=new JSONObject();int index=type.getSelectedItemPosition();if(index>=0&&index<VALUES.length)EditorSession.put(d,"state",VALUES[index]);EditorSession.put(d,"filter",filter.getText().toString());if(!session.save(out,d))Toast.makeText(this,session.problem(),Toast.LENGTH_LONG).show();}super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){if(session!=null&&isFinishing())session.clear();super.onDestroy();}
    @Override protected void onCreate(Bundle state){super.onCreate(state);hostIdentity=TaskerAuthorization.editorIdentity(this);if(!hostIdentity.valid){setResult(RESULT_CANCELED);finish();return;}ui=new UiKit(this);registerXbsBackHandler(this::save);setXbsContentView(build());load();restoreDraft(state);}
    private ScrollView build(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(24));c.setBackgroundColor(ui.palette.background);c.addView(ui.text("XBS COMPANION",12,ui.palette.orange,true));c.addView(ui.text("Tasker state",27,ui.palette.text,true));c.addView(ui.text("Keep a Tasker profile active while an XBS condition remains true.",14,ui.palette.muted,false));LinearLayout card=ui.surface();type=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,TYPES);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);type.setAdapter(a);card.addView(type);filter=ui.input("","Event name for specific-event state",false);card.addView(filter);card.addView(ui.button("Save state",ui.palette.orange,v->save()));card.addView(ui.secondaryButton("Cancel",v->{setResult(RESULT_CANCELED);finish();}));c.addView(card);ScrollView s=new ScrollView(this);s.addView(c);return s;}
    private void load(){Bundle b=getIntent().getBundleExtra(TaskerConditionProtocol.EXTRA_BUNDLE);if(b==null)return;if(!TaskerAuthorization.verify(this,b,true).valid)return;String value=b.getString(TaskerConditionProtocol.KEY_STATE,"available");for(int i=0;i<VALUES.length;i++)if(VALUES[i].equals(value))type.setSelection(i);filter.setText(b.getString(TaskerConditionProtocol.KEY_FILTER,""));}
    private void save(){if(session==null||session.blocked()||isFinishing())return;String input=filter.getText().toString().trim();if(input.length()>160||input.indexOf(0)>=0){filter.setError("Filter must be at most 160 characters");return;}String value=VALUES[type.getSelectedItemPosition()];String f=filter.getText().toString().trim();Bundle b=new Bundle();b.putString(TaskerConditionProtocol.KEY_KIND,TaskerConditionProtocol.KIND_STATE);b.putString(TaskerConditionProtocol.KEY_STATE,value);b.putString(TaskerConditionProtocol.KEY_FILTER,f);if(!TaskerAuthorization.sign(this,b,hostIdentity))return;setResult(RESULT_OK,TaskerConditionProtocol.result(b,TYPES[type.getSelectedItemPosition()]+(f.isEmpty()?"":" · "+f)));finish();}
}
