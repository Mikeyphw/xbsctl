package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class SecuritySealActivity extends XbsActivity {
    private UiKit ui; private LinearLayout content;
    @Override protected void onCreate(Bundle state){super.onCreate(state);ui=new UiKit(this);render(SecuritySealEvaluator.evaluate(this));}
    private void render(SecuritySealReport report){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.palette.background);LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(ui.dp(18),ui.dp(18),ui.dp(18),ui.dp(10));head.addView(ui.text("SECURITY SEAL",12,ui.palette.orange,true));head.addView(ui.text(report.headline(),26,report.blockerCount()>0?ui.palette.red:ui.palette.green,true));head.addView(ui.text("Passive audit of exported components, backup and cleartext policy, Tasker identity, managed paths, and stale notification actions.",13,ui.palette.muted,false));root.addView(head);ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(28));for(SecuritySealReport.Item item:report.items){LinearLayout card=ui.surface();card.addView(ui.text(item.title,17,item.pass?ui.palette.green:ui.palette.red,true));card.addView(ui.text(item.id+" · "+item.detail,12,ui.palette.muted,false));content.addView(card);}if(!report.error.isEmpty()){LinearLayout card=ui.surface();card.addView(ui.text("Audit error",17,ui.palette.red,true));card.addView(ui.text(report.error,12,ui.palette.muted,false));content.addView(card);}LinearLayout actions=ui.surface();actions.addView(ui.button("Re-run passive audit",ui.palette.orange,v->render(SecuritySealEvaluator.evaluate(this))));actions.addView(ui.secondaryButton("Open app settings",v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))));actions.addView(ui.secondaryButton("Back",v->finish()));content.addView(actions);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setXbsContentView(root);}
}
