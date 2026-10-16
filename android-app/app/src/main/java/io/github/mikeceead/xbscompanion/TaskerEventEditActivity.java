package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Toast;

public final class TaskerEventEditActivity extends XbsActivity {
    private static final String[] TYPES = {"Any XBS event", "Profile started", "Profile stopped", "Safe mode enabled", "Safe mode disabled", "Runtime paused", "Runtime resumed", "Runtime unavailable", "Runtime recovered"};
    private static final String[] VALUES = {"any", "profile_active", "profile_inactive", "safe_mode_active", "safe_mode_inactive", "runtime_paused", "runtime_resumed", "runtime_unavailable", "runtime_recovered"};
    private Spinner type;
    private EditText filter;
    private UiKit ui;
    private TaskerHostIdentity hostIdentity;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); hostIdentity=TaskerAuthorization.editorIdentity(this); if(!hostIdentity.valid){Toast.makeText(this,"Open this event from Tasker.",Toast.LENGTH_LONG).show();setResult(RESULT_CANCELED);finish();return;} ui = new UiKit(this); registerXbsBackHandler(this::save); setXbsContentView(build()); load();
    }
    private ScrollView build() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(24)); c.setBackgroundColor(ui.palette.background);
        c.addView(ui.text("XBS COMPANION",12,ui.palette.orange,true)); c.addView(ui.text("Tasker event",27,ui.palette.text,true));
        c.addView(ui.text("React immediately to XBS profile, safe-mode and runtime changes.",14,ui.palette.muted,false));
        LinearLayout card=ui.surface(); type=new Spinner(this); ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,TYPES);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);type.setAdapter(a);card.addView(type);
        filter=ui.input("","Optional profile/event name filter",false);card.addView(filter);card.addView(ui.button("Save event",ui.palette.orange,v->save()));card.addView(ui.secondaryButton("Cancel",v->{setResult(RESULT_CANCELED);finish();}));c.addView(card);
        ScrollView s=new ScrollView(this);s.addView(c);return s;
    }
    private void load(){Bundle b=getIntent().getBundleExtra(TaskerConditionProtocol.EXTRA_BUNDLE);if(b==null)return;if(!TaskerAuthorization.verify(this,b,true).valid)return;String value=b.getString(TaskerConditionProtocol.KEY_STATE,"any");for(int i=0;i<VALUES.length;i++)if(VALUES[i].equals(value))type.setSelection(i);filter.setText(b.getString(TaskerConditionProtocol.KEY_FILTER,""));}
    private void save(){Bundle b=new Bundle();b.putString(TaskerConditionProtocol.KEY_KIND,TaskerConditionProtocol.KIND_EVENT);b.putString(TaskerConditionProtocol.KEY_STATE,VALUES[type.getSelectedItemPosition()]);b.putString(TaskerConditionProtocol.KEY_FILTER,filter.getText().toString().trim());if(!TaskerAuthorization.sign(this,b,hostIdentity)){Toast.makeText(this,"Could not sign Tasker event",Toast.LENGTH_LONG).show();return;}Intent result=TaskerConditionProtocol.result(b,TYPES[type.getSelectedItemPosition()]+(filter.getText().toString().trim().isEmpty()?"":" · "+filter.getText().toString().trim()));setResult(RESULT_OK,result);finish();}
}
