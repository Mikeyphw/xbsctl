package io.github.mikeceead.xbscompanion;
import org.junit.Test;import static org.junit.Assert.*;
public class PerformanceReportTest {@Test public void evidenceIsBoundedAndFresh(){PerformanceReport r=new PerformanceReport(1000,50,20,70,4,1,2);assertTrue(r.present());assertTrue(r.fresh(1001));assertTrue(r.summary().contains("coalesced=2"));assertFalse(PerformanceReport.empty().present());}}
