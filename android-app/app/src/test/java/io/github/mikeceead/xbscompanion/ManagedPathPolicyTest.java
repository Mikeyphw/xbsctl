package io.github.mikeceead.xbscompanion;
import org.junit.Test;import static org.junit.Assert.*;
public class ManagedPathPolicyTest {
 @Test public void acceptsOnlyStrictDirectChildren(){String r="/data/adb/XtremeBS/lists";assertTrue(ManagedPathPolicy.isDirectChild(r,r+"/essentials.allowlist"));assertFalse(ManagedPathPolicy.isDirectChild(r,r+"/../config.json"));assertFalse(ManagedPathPolicy.isDirectChild(r,r+"/.hidden"));assertFalse(ManagedPathPolicy.isDirectChild(r,r+"/a/b"));assertFalse(ManagedPathPolicy.isDirectChild(r,r+"/%2e%2e"));assertFalse(ManagedPathPolicy.isDirectChild(r,r+"/a\\b"));}
 @Test public void safeLeafNeverStartsWithDot(){assertEquals("event",ManagedPathPolicy.safeLeafFrom("../../", "event"));assertEquals("my_event",ManagedPathPolicy.safeLeafFrom("my event","event"));}
}
