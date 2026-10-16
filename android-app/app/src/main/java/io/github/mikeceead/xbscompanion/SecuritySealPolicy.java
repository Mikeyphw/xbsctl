package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure security-seal policy; missing and unexpected exported surfaces fail closed. */
final class SecuritySealPolicy {
    static final Set<String> ALLOWED_EXPORTED = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(
        "io.github.mikeceead.xbscompanion.MainActivity",
        "io.github.mikeceead.xbscompanion.TaskerPluginActivity",
        "io.github.mikeceead.xbscompanion.TaskerEventEditActivity",
        "io.github.mikeceead.xbscompanion.TaskerStateEditActivity",
        "io.github.mikeceead.xbscompanion.TaskerPluginReceiver",
        "io.github.mikeceead.xbscompanion.TaskerConditionReceiver")));
    private SecuritySealPolicy(){}
    static SecuritySealReport evaluate(long now,Set<String> exportedActivities,Set<String> exportedReceivers,Set<String> exportedServices,Set<String> exportedProviders,boolean backupAllowed,boolean cleartextAllowed,boolean taskerIdentityReady,String taskerDetail,boolean managedPathReady,boolean staleActionReady,String staleActionDetail){
        List<SecuritySealReport.Item> items=new ArrayList<>();Set<String> actual=new LinkedHashSet<>();if(exportedActivities!=null)actual.addAll(exportedActivities);if(exportedReceivers!=null)actual.addAll(exportedReceivers);
        Set<String> unexpected=new LinkedHashSet<>(actual);unexpected.removeAll(ALLOWED_EXPORTED);Set<String> missing=new LinkedHashSet<>(ALLOWED_EXPORTED);missing.removeAll(actual);
        boolean exportsReady=unexpected.isEmpty()&&missing.isEmpty()&&safeEmpty(exportedServices)&&safeEmpty(exportedProviders);
        String exportDetail=exportsReady?"Exact launcher and signed Tasker export set verified":"missing="+missing+" unexpected="+unexpected+" services="+safe(exportedServices)+" providers="+safe(exportedProviders);
        add(items,"exported_components","Exported components",exportsReady,exportDetail);
        add(items,"backup_disabled","Backup policy",!backupAllowed,!backupAllowed?"Platform backup is disabled":"Application data backup is enabled");
        add(items,"cleartext_disabled","Cleartext traffic",!cleartextAllowed,!cleartextAllowed?"Cleartext network traffic is disabled":"Cleartext network traffic is permitted");
        add(items,"tasker_identity","Tasker identity pin",taskerIdentityReady,detailOr(taskerDetail,taskerIdentityReady?"Tasker host is unconfigured or certificate-pinned":"Tasker certificate pin evidence failed"));
        add(items,"managed_paths","Managed path policy",managedPathReady,managedPathReady?"Strict direct-child path contract v"+ManagedPathPolicy.VERSION:"Managed path policy self-check failed");
        add(items,"stale_actions","Stale action guard",staleActionReady,detailOr(staleActionDetail,staleActionReady?"Fresh durable action evidence is available":"Runtime action replay guard is unavailable or stale"));
        return new SecuritySealReport(now,items,"");
    }
    private static boolean safeEmpty(Set<String> value){return value==null||value.isEmpty();}
    private static Set<String> safe(Set<String> value){return value==null?Collections.emptySet():value;}
    private static String detailOr(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value.trim();}
    private static void add(List<SecuritySealReport.Item> out,String id,String title,boolean pass,String detail){out.add(new SecuritySealReport.Item(id,title,detail,pass));}
}
