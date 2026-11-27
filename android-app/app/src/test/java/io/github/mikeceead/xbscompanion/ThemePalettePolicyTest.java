package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ThemePalettePolicyTest {
    @Test public void bothDefaultsMeetTextAndAccentContrast() {
        for(boolean dark:new boolean[]{false,true}) {
            int[] palette=ThemePalettePolicy.fallback(dark);
            assertTrue(ThemePalettePolicy.usable(dark,palette));
            for(int surface=0;surface<3;surface++)
                for(int color=3;color<palette.length;color++)
                    assertTrue(ThemePalettePolicy.contrast(palette[surface],palette[color])>=4.5);
        }
    }
    @Test public void rejectsInvertedTranslucentOrUnreadableDynamicColors() {
        for(boolean dark:new boolean[]{false,true}) {
            int[] bad=ThemePalettePolicy.fallback(!dark);
            assertArrayEquals(ThemePalettePolicy.fallback(dark),ThemePalettePolicy.choose(dark,bad));
            bad=ThemePalettePolicy.fallback(dark);bad[3]=bad[0];
            assertArrayEquals(ThemePalettePolicy.fallback(dark),ThemePalettePolicy.choose(dark,bad));
            bad=ThemePalettePolicy.fallback(dark);bad[5]&=0x00ffffff;
            assertArrayEquals(ThemePalettePolicy.fallback(dark),ThemePalettePolicy.choose(dark,bad));
            assertArrayEquals(ThemePalettePolicy.fallback(dark),ThemePalettePolicy.choose(dark,null));
        }
    }
    @Test public void mutableCallerCannotCorruptFallbackOrChosenPalette() {
        int[] palette=ThemePalettePolicy.fallback(false);
        int[] chosen=ThemePalettePolicy.choose(false,palette);
        palette[0]=0;chosen[1]=0;
        assertTrue(ThemePalettePolicy.usable(false,ThemePalettePolicy.fallback(false)));
    }
}
