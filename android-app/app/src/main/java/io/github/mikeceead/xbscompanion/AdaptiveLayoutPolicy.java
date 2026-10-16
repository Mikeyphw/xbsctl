package io.github.mikeceead.xbscompanion;

/** Window-width policy shared by phone, tablet, foldable, multi-window, and desktop modes. */
final class AdaptiveLayoutPolicy {
    enum WidthClass { COMPACT, MEDIUM, EXPANDED }
    static final int MEDIUM_DP = 600;
    static final int EXPANDED_DP = 840;
    private AdaptiveLayoutPolicy() {}
    static WidthClass classify(int widthDp) {
        if (widthDp >= EXPANDED_DP) return WidthClass.EXPANDED;
        if (widthDp >= MEDIUM_DP) return WidthClass.MEDIUM;
        return WidthClass.COMPACT;
    }
    static boolean useNavigationRail(int widthDp) { return classify(widthDp) != WidthClass.COMPACT; }
    static int contentInsetDp(int widthDp) { return classify(widthDp) == WidthClass.EXPANDED ? 28 : 16; }
    static int maxContentWidthDp(int widthDp) { return classify(widthDp) == WidthClass.EXPANDED ? 1120 : 0; }
    static boolean contentShouldConstrain(int widthDp, int navigationWidthDp) { int available=Math.max(0,widthDp-Math.max(0,navigationWidthDp));int max=maxContentWidthDp(widthDp);return classify(widthDp)==WidthClass.EXPANDED&&max>0&&available>max; }
    static boolean sealReady() { return MEDIUM_DP >= 600 && EXPANDED_DP > MEDIUM_DP && contentInsetDp(EXPANDED_DP) >= 24 && maxContentWidthDp(1400) > 0 && contentShouldConstrain(1400,168); }
}
