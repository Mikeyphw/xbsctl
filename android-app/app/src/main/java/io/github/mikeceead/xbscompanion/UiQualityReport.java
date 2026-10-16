package io.github.mikeceead.xbscompanion;

/** Runtime evidence for whole-screen accessibility and adaptive layout behavior. */
final class UiQualityReport {
    static final long STALE_AFTER_MS = 7L * 24L * 60L * 60_000L;
    final long capturedAt;
    final int interactiveCount, adjustedCount, touchFailures, headingCount;
    final boolean paneTitleReady;
    final String paneTitle;
    final int widthDp, maxContentWidthDp;
    final String widthClass;
    final boolean navigationRail, contentConstrained;

    UiQualityReport(long at,int controls,int adjusted,int failures,int headings,boolean pane,String paneTitle,
                    int widthDp,String widthClass,boolean rail,int maxContentWidthDp,boolean constrained) {
        capturedAt=Math.max(0,at);interactiveCount=Math.max(0,controls);adjustedCount=Math.max(0,adjusted);
        touchFailures=Math.max(0,failures);headingCount=Math.max(0,headings);paneTitleReady=pane;
        this.paneTitle=clean(paneTitle);this.widthDp=Math.max(0,widthDp);this.widthClass=clean(widthClass);
        navigationRail=rail;this.maxContentWidthDp=Math.max(0,maxContentWidthDp);contentConstrained=constrained;
    }
    static UiQualityReport empty(){return new UiQualityReport(0,0,0,0,0,false,"",0,"",false,0,false);}
    boolean present(){return capturedAt>0;}
    boolean fresh(long now){return present()&&now>=capturedAt&&now-capturedAt<=STALE_AFTER_MS;}
    boolean accessibilityReady(){return interactiveCount>0&&touchFailures==0&&headingCount>0&&paneTitleReady;}
    boolean adaptiveReady(){if(widthDp<=0||widthClass.isEmpty())return false;if("COMPACT".equals(widthClass))return !navigationRail;if("MEDIUM".equals(widthClass))return navigationRail;return navigationRail&&maxContentWidthDp>0&&contentConstrained;}
    boolean ready(long now){return fresh(now)&&accessibilityReady()&&adaptiveReady();}
    String summary(){if(!present())return "No runtime accessibility/adaptive evidence yet";return "screen="+paneTitle+" · controls="+interactiveCount+" · touch_failures="+touchFailures+" · headings="+headingCount+" · width="+widthDp+"dp/"+widthClass+" · constrained="+contentConstrained;}
    String supportText(){return "captured_at="+capturedAt+"\npane_title="+paneTitle+"\ninteractive_controls="+interactiveCount+"\nadjusted_controls="+adjustedCount+"\ntouch_failures="+touchFailures+"\nheading_count="+headingCount+"\npane_title_ready="+paneTitleReady+"\nwidth_dp="+widthDp+"\nwidth_class="+widthClass+"\nnavigation_rail="+navigationRail+"\nmax_content_width_dp="+maxContentWidthDp+"\ncontent_constrained="+contentConstrained+"\n";}
    static final class AccessibilityResult {
        final int controls,adjusted,failures,headings;final boolean paneReady;final String paneTitle;
        AccessibilityResult(int c,int a,int f,int h,boolean p,String t){controls=c;adjusted=a;failures=f;headings=h;paneReady=p;paneTitle=clean(t);}
        static AccessibilityResult empty(){return new AccessibilityResult(0,0,1,0,false,"");}
    }
    private static String clean(String value){if(value==null)return "";String x=value.trim().replace('\n',' ').replace('\r',' ');return x.length()<=120?x:x.substring(0,120);}
}
