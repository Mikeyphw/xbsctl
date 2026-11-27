package io.github.mikeceead.xbscompanion;

/** Pure color policy. No Android or device state is required for contrast qualification. */
final class ThemePalettePolicy {
    private static final int[] DARK = {0xff0b0f14, 0xff121923, 0xff19222e,
        0xffeef4ff, 0xff8ea0b4, 0xff5ee49b, 0xff68d8ff, 0xffffd166, 0xffff6978, 0xffffb05c};
    private static final int[] LIGHT = {0xfff4f7fa, 0xffffffff, 0xffe4eaf0,
        0xff14212e, 0xff425469, 0xff14633f, 0xff005d76, 0xff805000, 0xffa31829, 0xff8a4200};
    private ThemePalettePolicy() {}

    static int[] fallback(boolean dark) { return (dark ? DARK : LIGHT).clone(); }

    static double luminance(int color) {
        return 0.2126 * linear((color >>> 16) & 255)
             + 0.7152 * linear((color >>> 8) & 255)
             + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a,b) + 0.05) / (Math.min(a,b) + 0.05);
    }

    /** Reject translucent, inverted or low-contrast dynamic palettes as one atomic unit. */
    static boolean usable(boolean dark, int[] colors) {
        if (colors == null || colors.length != DARK.length) return false;
        for (int color : colors) if ((color >>> 24) != 255) return false;
        for (int surface = 0; surface < 3; surface++) {
            double value = luminance(colors[surface]);
            if (dark ? value > 0.18 : value < 0.65) return false;
            for (int role = 3; role < colors.length; role++) {
                if (contrast(colors[surface], colors[role]) < 4.5) return false;
            }
        }
        return true;
    }

    static int[] choose(boolean dark, int[] dynamic) {
        return usable(dark,dynamic) ? dynamic.clone() : fallback(dark);
    }
}
