package io.github.mikeceead.xbscompanion;

import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

final class MachineResult {
    final boolean parsed, ok;
    final String action, message, dataJson, error;
    private final JSONObject root;
    private MachineResult(boolean parsed,boolean ok,String action,String message,String dataJson,String error,JSONObject root){this.parsed=parsed;this.ok=ok;this.action=action;this.message=message;this.dataJson=dataJson;this.error=error;this.root=root;}
    static MachineResult parse(String stdout){
        String raw=stdout==null?"":stdout.trim(); JSONObject obj=null;
        try{obj=new JSONObject(raw);}catch(JSONException ignored){
            String[] lines=raw.split("\\R");for(int i=lines.length-1;i>=0;i--){String line=lines[i].trim();if(!line.startsWith("{"))continue;try{obj=new JSONObject(line);break;}catch(JSONException ignored2){}}
        }
        if(obj==null||!validEnvelope(obj))return new MachineResult(false,false,"","","","",null);
        Object err=obj.opt("error");String error=err==null||err==JSONObject.NULL?"":String.valueOf(err);
        Object data=obj.opt("data");String dataJson=data==null||data==JSONObject.NULL?"":String.valueOf(data);
        return new MachineResult(true,obj.optBoolean("ok",false),obj.optString("action",""),obj.optString("message",""),dataJson,error,obj);
    }
    private static boolean validEnvelope(JSONObject obj){
        if(!(obj.opt("schema_version") instanceof String)||!"2.0.0".equals(obj.optString("schema_version",""))||!obj.has("error"))return false;
        if(!(obj.opt("ok") instanceof Boolean)||!(obj.opt("app") instanceof String)||!(obj.opt("action") instanceof String)||!(obj.opt("message") instanceof String))return false;
        Object exit=obj.opt("exit_code");if(!(exit instanceof Number))return false;double exitValue=((Number)exit).doubleValue();if(!Double.isFinite(exitValue)||exitValue<0||exitValue!=Math.rint(exitValue))return false;
        if(!(obj.opt("data") instanceof JSONObject))return false;
        Object err=obj.opt("error");if(err!=JSONObject.NULL){if(!(err instanceof JSONObject))return false;Object message=((JSONObject)err).opt("message");if(!(message instanceof String))return false;Object type=((JSONObject)err).opt("type");if(type!=null&&type!=JSONObject.NULL&&!(type instanceof String))return false;}
        return true;
    }
    JSONObject object(){return root;}
    JSONObject dataObject(){if(root==null)return null;Object d=root.opt("data");return d instanceof JSONObject?(JSONObject)d:null;}
    void addVariables(Bundle vars,String module){
        vars.putString("%suite_ok",String.valueOf(ok));vars.putString("%suite_action",action);vars.putString("%suite_message",message);vars.putString("%suite_data",dataJson);vars.putString("%suite_machine_error",error);
        if(root==null)return; Object data=root.opt("data"); if(data instanceof JSONObject) flatten(vars,"%"+module+"_",(JSONObject)data,0);
        flattenRootCommon(vars,module,root);
    }
    private static void flattenRootCommon(Bundle vars,String module,JSONObject obj){for(String k:new String[]{"moved","duplicates","conflicts","errors","skipped","summary","running","paused","safe_mode","active_event","active_profiles","image","source","collection","target","no_op"}){if(obj.has(k))vars.putString("%"+module+"_"+sanitize(k),scalar(obj.opt(k)));}}
    private static void flatten(Bundle vars,String prefix,JSONObject obj,int depth){if(depth>1)return;Iterator<String> keys=obj.keys();int count=0;while(keys.hasNext()&&count<40){String key=keys.next();Object value=obj.opt(key);String name=prefix+sanitize(key);if(value instanceof JSONObject)flatten(vars,name+"_",(JSONObject)value,depth+1);else if(!(value instanceof JSONArray))vars.putString(name,scalar(value));count++;}}
    private static String scalar(Object v){return v==null||v==JSONObject.NULL?"":String.valueOf(v);}
    private static String sanitize(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]","_");}
}
