package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import android.widget.TextView;

final class UiKit {
    private final Context context;
    final AppPalette palette;
    UiKit(Context context){this.context=context; this.palette=AppPalette.resolve(context);}
    int dp(int v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
    TextView text(String value,int sp,int color,boolean bold){TextView v=new TextView(context);v.setText(value);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);v.setLineSpacing(0,1.12f);return v;}
    TextView primaryTitle(String value){TextView v=text(value,27,palette.text,true);ViewCompat.setAccessibilityHeading(v,true);return v;}
    <T extends View> T touchTarget(T view){if(view==null)return null;view.setMinimumHeight(dp(48));view.setMinimumWidth(dp(48));return view;}
    TextView sectionTitle(String value){TextView v=text(value,13,palette.cyan,true);v.setAllCaps(true);v.setLetterSpacing(.08f);v.setPadding(0,dp(22),0,dp(7));ViewCompat.setAccessibilityHeading(v,true);v.setFocusable(false);return v;}
    LinearLayout surface(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(14),dp(16),dp(14));l.setBackground(flat(palette.surface,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));l.setLayoutParams(p);return l;}
    Button button(String label,int color,View.OnClickListener listener){Button b=new Button(context);b.setText(label);b.setTextSize(14);b.setTextColor(palette.background);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setPadding(dp(14),dp(11),dp(14),dp(11));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackground(flat(color,12));b.setOnClickListener(listener);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,0);b.setLayoutParams(p);return b;}
    Button secondaryButton(String label,View.OnClickListener listener){Button b=button(label,palette.surfaceRaised,listener);b.setTextColor(palette.text);return b;}
    Button navButton(String symbol,String label,boolean selected,View.OnClickListener listener){Button b=new Button(context);b.setText(context.getString(R.string.xbs_nav_button_label, symbol, label));b.setTextSize(10);b.setAllCaps(false);b.setTextColor(selected?palette.green:palette.muted);b.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);b.setGravity(Gravity.CENTER);b.setBackground(flat(selected?withAlpha(palette.green,22):palette.background,15));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(3),dp(6),dp(3),dp(5));b.setOnClickListener(listener);return b;}
    EditText input(String value,String hint,boolean multiline){EditText e=new EditText(context);e.setText(value);e.setHint(hint);e.setHintTextColor(palette.muted);e.setTextColor(palette.text);e.setTextSize(14);e.setSingleLine(!multiline);e.setMinLines(multiline?3:1);e.setMinHeight(dp(48));e.setGravity(multiline?Gravity.TOP:Gravity.CENTER_VERTICAL);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(flat(palette.surfaceRaised,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,0);e.setLayoutParams(p);return e;}
    SwitchCompat toggle(String label,boolean checked){SwitchCompat s=new SwitchCompat(context);s.setText(label);s.setTextSize(15);s.setTextColor(palette.text);s.setChecked(checked);s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{palette.green,palette.muted}));s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{withAlpha(palette.green,110),withAlpha(palette.muted,72)}));s.setPadding(0,dp(8),0,dp(8));s.setMinHeight(dp(48));return s;}
    GradientDrawable flat(int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;}
    static int withAlpha(int color,int alpha){return (color&0x00ffffff)|((alpha&0xff)<<24);}
}
