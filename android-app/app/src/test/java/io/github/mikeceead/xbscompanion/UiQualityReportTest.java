package io.github.mikeceead.xbscompanion;
import org.junit.Test;import static org.junit.Assert.*;
public final class UiQualityReportTest {
 @Test public void expandedEvidenceRequiresRealContainment(){long now=1000;UiQualityReport bad=new UiQualityReport(now,4,2,0,1,true,"Main",1400,"EXPANDED",true,1120,false);assertFalse(bad.ready(now));UiQualityReport good=new UiQualityReport(now,4,2,0,1,true,"Main",1400,"EXPANDED",true,1120,true);assertTrue(good.ready(now));}
 @Test public void missingHeadingOrTouchFailureBlocks(){long now=1000;assertFalse(new UiQualityReport(now,4,2,1,1,true,"Main",500,"COMPACT",false,0,true).ready(now));assertFalse(new UiQualityReport(now,4,2,0,0,true,"Main",500,"COMPACT",false,0,true).ready(now));}
}
