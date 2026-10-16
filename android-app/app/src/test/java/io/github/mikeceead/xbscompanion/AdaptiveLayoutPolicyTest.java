package io.github.mikeceead.xbscompanion;
import org.junit.Test;import static org.junit.Assert.*;
public final class AdaptiveLayoutPolicyTest {
 @Test public void classifiesAndConstrainsExpandedWindows(){assertEquals(AdaptiveLayoutPolicy.WidthClass.COMPACT,AdaptiveLayoutPolicy.classify(599));assertEquals(AdaptiveLayoutPolicy.WidthClass.MEDIUM,AdaptiveLayoutPolicy.classify(600));assertEquals(AdaptiveLayoutPolicy.WidthClass.EXPANDED,AdaptiveLayoutPolicy.classify(840));assertTrue(AdaptiveLayoutPolicy.useNavigationRail(600));assertEquals(1120,AdaptiveLayoutPolicy.maxContentWidthDp(1400));assertTrue(AdaptiveLayoutPolicy.contentShouldConstrain(1400,168));assertFalse(AdaptiveLayoutPolicy.contentShouldConstrain(1000,168));assertTrue(AdaptiveLayoutPolicy.sealReady());}
}
