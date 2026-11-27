package io.github.mikeceead.xbscompanion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

final class AppPalette {
    final int background, surface, surfaceRaised, text, muted, green, cyan, yellow, red, orange;
    private AppPalette(int[] c) {
        background=c[0]; surface=c[1]; surfaceRaised=c[2]; text=c[3]; muted=c[4];
        green=c[5]; cyan=c[6]; yellow=c[7]; red=c[8]; orange=c[9];
    }
    static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }
    static AppPalette resolve(Context context) {
        boolean dark=isDark(context);
        int[] fallback=ThemePalettePolicy.fallback(dark);
        if (AppSettings.useMonet(context) && Build.VERSION.SDK_INT >= 31) {
            String[] names=dark ? new String[]{"system_neutral1_1000","system_neutral1_900",
                "system_neutral1_800","system_neutral1_50","system_neutral2_200",
                "system_accent1_200","system_accent2_200","system_accent3_200"}
                : new String[]{"system_neutral1_10","system_neutral1_0","system_neutral1_100",
                "system_neutral1_900","system_neutral2_700","system_accent1_800",
                "system_accent2_800","system_accent3_800"};
            int[] dynamic=fallback.clone();
            for (int i=0;i<names.length;i++) dynamic[i]=system(context,names[i],fallback[i]);
            return new AppPalette(ThemePalettePolicy.choose(dark,dynamic));
        }
        return new AppPalette(fallback);
    }
    @SuppressLint("DiscouragedApi")
    private static int system(Context c, String name, int fallback) {
        int id=c.getResources().getIdentifier(name,"color","android");
        try { return id==0?fallback:c.getColor(id); } catch(RuntimeException e){ return fallback; }
    }
}
