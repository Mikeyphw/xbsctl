package io.github.mikeceead.xbscompanion;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public final class SecuritySealPolicyTest {
 @Test public void exactExpectedExportSetPasses(){Set<String>a=new LinkedHashSet<>(),r=new LinkedHashSet<>();for(String n:SecuritySealPolicy.ALLOWED_EXPORTED){if(n.endsWith("Receiver"))r.add(n);else a.add(n);}SecuritySealReport x=SecuritySealPolicy.evaluate(1,a,r,Collections.emptySet(),Collections.emptySet(),false,false,true,"pin verified",true,true,"fresh");assertEquals(0,x.blockerCount());}
 @Test public void missingExpectedExportFails(){SecuritySealReport x=SecuritySealPolicy.evaluate(1,Collections.emptySet(),Collections.emptySet(),Collections.emptySet(),Collections.emptySet(),false,false,true,"",true,true,"");assertTrue(x.blockerCount()>0);}
 @Test public void staleActionEvidenceFails(){Set<String>a=new LinkedHashSet<>(),r=new LinkedHashSet<>();for(String n:SecuritySealPolicy.ALLOWED_EXPORTED){if(n.endsWith("Receiver"))r.add(n);else a.add(n);}SecuritySealReport x=SecuritySealPolicy.evaluate(1,a,r,Collections.emptySet(),Collections.emptySet(),false,false,true,"pin",true,false,"stale");assertTrue(x.blockerCount()>0);}
}
