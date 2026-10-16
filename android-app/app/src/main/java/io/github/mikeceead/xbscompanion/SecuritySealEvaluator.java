package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import java.util.LinkedHashSet;
import java.util.Set;

final class SecuritySealEvaluator {
    private SecuritySealEvaluator(){}
    static SecuritySealReport evaluate(Context context){Context app=context.getApplicationContext();try{PackageInfo info=packageInfo(app);Set<String> activities=exported(info.activities);Set<String> receivers=exported(info.receivers);Set<String> services=exported(info.services);Set<String> providers=exportedProviders(info.providers);boolean backup=(info.applicationInfo.flags&ApplicationInfo.FLAG_ALLOW_BACKUP)!=0;boolean cleartext=(info.applicationInfo.flags&ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)!=0;ExternalEntryReport external=ExternalEntryStore.cached(app);TaskerAuthorization.HostPinStatus pin=TaskerAuthorization.pinStatus(app);boolean tasker=pin.ready&&(external.capturedAt<=0||external.hostPackage.isEmpty()||pin.packageName.equals(external.hostPackage));String taskerDetail=tasker?pin.detail:"Cached external host does not match the verified Tasker pin";RuntimeNotificationStore.ActionGuardStatus guard=RuntimeNotificationStore.actionGuardStatus(app,System.currentTimeMillis());boolean paths=ManagedPathPolicy.isDirectChild(XbsConfigApi.LIST_DIR,XbsConfigApi.LIST_DIR+"/essentials.allowlist")&&!ManagedPathPolicy.isDirectChild(XbsConfigApi.LIST_DIR,XbsConfigApi.LIST_DIR+"/../escape");SecuritySealReport report=SecuritySealPolicy.evaluate(System.currentTimeMillis(),activities,receivers,services,providers,backup,cleartext,tasker,taskerDetail,paths,guard.ready,guard.detail);SecuritySealStore.save(app,report);return report;}catch(Exception e){SecuritySealReport report=new SecuritySealReport(System.currentTimeMillis(),java.util.Collections.emptyList(),e.getClass().getSimpleName());SecuritySealStore.save(app,report);return report;}}
    private static Set<String> exported(ComponentInfo[] infos){Set<String> out=new LinkedHashSet<>();if(infos!=null)for(ComponentInfo i:infos)if(i!=null&&i.exported)out.add(i.name);return out;}
    private static Set<String> exportedProviders(ProviderInfo[] infos){Set<String> out=new LinkedHashSet<>();if(infos!=null)for(ProviderInfo i:infos)if(i!=null&&i.exported)out.add(i.name);return out;}
    private static PackageInfo packageInfo(Context c)throws Exception{PackageManager pm=c.getPackageManager();int flags=PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS;if(android.os.Build.VERSION.SDK_INT>=33)return pm.getPackageInfo(c.getPackageName(),PackageManager.PackageInfoFlags.of(flags));return legacy(pm,c.getPackageName(),flags);}
    @SuppressWarnings("deprecation") private static PackageInfo legacy(PackageManager pm,String n,int f)throws Exception{return pm.getPackageInfo(n,f);}
}
