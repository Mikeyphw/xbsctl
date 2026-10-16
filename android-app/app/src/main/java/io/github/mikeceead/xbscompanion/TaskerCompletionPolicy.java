package io.github.mikeceead.xbscompanion;

import android.content.ComponentName;
import android.content.Intent;
import java.net.URISyntaxException;

/** Rejects implicit, URI-bearing or cross-package Tasker completion redirects. */
final class TaskerCompletionPolicy {
    private static final int URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    private TaskerCompletionPolicy(){}

    static Result parse(String encoded,String hostPackage){
        if(encoded==null||encoded.trim().isEmpty())return Result.reject("missing_completion_intent");
        if(encoded.length()>16384)return Result.reject("completion_intent_too_large");
        if(!TaskerHostIdentity.supportedPackage(hostPackage))return Result.reject("unsupported_completion_host");
        try{
            Intent intent=Intent.parseUri(encoded,Intent.URI_INTENT_SCHEME);
            if(intent.getData()!=null||intent.getClipData()!=null||intent.getSelector()!=null)return Result.reject("unsafe_completion_payload");
            if(!boundedExtras(intent.getExtras()))return Result.reject("unsafe_completion_extras");
            if((intent.getFlags()&URI_GRANTS)!=0)return Result.reject("unsafe_completion_flags");
            ComponentName component=intent.getComponent();
            String packageName=component==null?intent.getPackage():component.getPackageName();
            if(packageName!=null&&!packageName.isEmpty()&&!hostPackage.equals(packageName))return Result.reject("cross_package_completion");
            String servicePackage=intent.getStringExtra("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE");
            String serviceClass=intent.getStringExtra("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE");
            if(servicePackage!=null&&!servicePackage.isEmpty()){
                if(!hostPackage.equals(servicePackage)||serviceClass==null||serviceClass.isEmpty())return Result.reject("unsafe_completion_service");
                component=new ComponentName(servicePackage,serviceClass);packageName=servicePackage;
            }
            if(!hostPackage.equals(packageName))return Result.reject("implicit_or_cross_package_completion");
            if(component==null&&intent.getPackage()==null)return Result.reject("implicit_completion");
            intent.setPackage(hostPackage);if(component!=null)intent.setComponent(component);
            return Result.allow(intent);
        }catch(URISyntaxException|RuntimeException error){return Result.reject("invalid_completion_intent");}
    }

    static android.os.Bundle sanitizeVariables(android.os.Bundle variables) {
        if (variables == null || variables.size() > 96) return null;
        android.os.Bundle out = new android.os.Bundle();
        int total = 0;
        for (String key : variables.keySet()) {
            if (key == null || !key.matches("%[a-z0-9_]{1,64}")) return null;
            Object raw = variables.get(key);
            if (!(raw instanceof String)) return null;
            String value = (String)raw;
            if (value.length() > 8192) value = value.substring(0, 8192);
            int remaining = 49_152 - total - key.length();
            if (remaining <= 0) break;
            if (value.length() > remaining) value = value.substring(0, remaining);
            out.putString(key, value);
            total += key.length() + value.length();
        }
        return out;
    }

    private static boolean boundedExtras(android.os.Bundle extras){
        if(extras==null)return true;if(extras.size()>32)return false;int total=0;
        for(String key:extras.keySet()){Object value=extras.get(key);if(!(value instanceof String)&&!(value instanceof Boolean)&&!(value instanceof Integer)&&!(value instanceof Long))return false;String text=String.valueOf(value);if(text.length()>4096)return false;total+=key.length()+text.length();if(total>16384)return false;}return true;
    }

    static final class Result{final boolean valid;final String code;final Intent intent;private Result(boolean v,String c,Intent i){valid=v;code=c;intent=i;}static Result allow(Intent i){return new Result(true,"ok",i);}static Result reject(String c){return new Result(false,c,null);}}
}
