package io.github.mikeceead.xbscompanion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

final class AppPalette {
    final int background, surface, surfaceRaised, text, muted, green, cyan, yellow, red, orange;
    private AppPalette(int background, int surface, int surfaceRaised, int text, int muted,
                       int green, int cyan, int yellow, int red, int orange) {
        this.background = background; this.surface = surface; this.surfaceRaised = surfaceRaised;
        this.text = text; this.muted = muted; this.green = green; this.cyan = cyan;
        this.yellow = yellow; this.red = red; this.orange = orange;
    }
    static AppPalette resolve(Context context) {
        if (AppSettings.useMonet(context) && Build.VERSION.SDK_INT >= 31) {
            return new AppPalette(system(context,"system_neutral1_1000",Color.rgb(11,15,20)),
                    system(context,"system_neutral1_900",Color.rgb(18,25,35)),
                    system(context,"system_neutral1_800",Color.rgb(25,34,46)),
                    system(context,"system_neutral1_50",Color.rgb(238,244,255)),
                    system(context,"system_neutral2_200",Color.rgb(142,160,180)),
                    system(context,"system_accent1_200",Color.rgb(94,228,155)),
                    system(context,"system_accent2_200",Color.rgb(104,216,255)),
                    system(context,"system_accent3_200",Color.rgb(255,209,102)),
                    Color.rgb(255,105,120), Color.rgb(255,176,92));
        }
        return new AppPalette(Color.rgb(11,15,20),Color.rgb(18,25,35),Color.rgb(25,34,46),
                Color.rgb(238,244,255),Color.rgb(142,160,180),Color.rgb(94,228,155),
                Color.rgb(104,216,255),Color.rgb(255,209,102),Color.rgb(255,105,120),Color.rgb(255,176,92));
    }
    @SuppressLint("DiscouragedApi")
    private static int system(Context c, String name, int fallback) {
        int id=c.getResources().getIdentifier(name,"color","android");
        try { return id==0?fallback:c.getColor(id); } catch(RuntimeException e){ return fallback; }
    }
}
