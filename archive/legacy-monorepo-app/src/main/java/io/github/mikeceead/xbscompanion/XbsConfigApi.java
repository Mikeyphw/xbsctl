package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Environment;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class XbsConfigApi {
    private static final String EXTERNAL_STORAGE_ROOT = externalStorageRoot();
    static final String CONFIG = EXTERNAL_STORAGE_ROOT + "/.config/xbs/XtremeBS.json";
    static final String LIST_DIR = EXTERNAL_STORAGE_ROOT + "/.config/xbs/lists";
    static final String BACKUP_DIR = EXTERNAL_STORAGE_ROOT + "/.config/xbs/backups";
    static final Set<String> BUILT_IN_EVENTS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "template", "boot", "charging", "screen_off", "low_power", "manual")));
    private static final String CONFIG_CACHE = "xbs_config_v2";
    private static final long CONFIG_CACHE_AGE = 15_000L;
    private XbsConfigApi(){}

    private static String externalStorageRoot() {
        try {
            String path = Environment.getExternalStorageDirectory().getPath();
            if (path != null && !path.trim().isEmpty()) return path;
        } catch (RuntimeException ignored) {}
        String env = System.getenv("EXTERNAL_STORAGE");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        return "/storage/emulated/0";
    }

    static String logPath(String name) {
        return EXTERNAL_STORAGE_ROOT + "/.config/xbs/logs/" + ManagedPathPolicy.safeLeafFrom(name, "log");
    }

    static Result load(){ return load(null, true); }

    static Result load(Context context, boolean force){
        if (context != null && !force) {
            String cached = XbsCache.get(context, CONFIG_CACHE, CONFIG_CACHE_AGE);
            if (cached != null) try { return Result.ok(new JSONObject(cached)); } catch (Exception ignored) {}
        }
        String configDir = CONFIG.substring(0, CONFIG.lastIndexOf('/'));
        String shell = RootDiagnosticsProbe.canonicalConfigEnvironmentShell(configDir)
            + "if [ ! -s " + q(CONFIG) + " ]; then " + xbs("--json init") + " >/dev/null || exit $?; fi; "
            + "test -s " + q(CONFIG) + " || { printf 'XBS init did not create the canonical configuration' >&2; exit 66; }; "
            + xbs("--json validate") + " >/dev/null || exit $?; cat " + q(CONFIG);
        XbsRootApi.CommandOutput out=XbsRootApi.runRoot(shell);
        if(out.exitCode!=0)return Result.error(out.error.isEmpty()?out.stdout:out.error);
        try {
            JSONObject config = new JSONObject(out.stdout);
            if (context != null) XbsCache.put(context, CONFIG_CACHE, config.toString());
            return Result.ok(config);
        } catch(Exception e){return Result.error("Invalid XBS config: "+e.getMessage());}
    }

    static String readText(String path){ return readText(null, path, true); }

    static String readText(Context context, String path, boolean force){
        if(path==null||path.trim().isEmpty())return "";
        String cacheKey = "file_" + Integer.toHexString(path.hashCode());
        if (context != null && !force) {
            String cached = XbsCache.get(context, cacheKey, 30_000L);
            if (cached != null) return cached;
        }
        XbsRootApi.CommandOutput out=XbsRootApi.runRoot("if [ -f "+q(path)+" ]; then cat "+q(path)+"; fi");
        String value = out.exitCode==0?out.stdout:"";
        if (context != null && out.exitCode == 0) XbsCache.put(context, cacheKey, value);
        return value;
    }

    static Result writeText(Context context, String path, String text) {
        if (!XbsConfigTransaction.isManagedListPath(path)) return Result.error("List path is outside the managed XBS list directory");
        Result loaded = load(context, true);
        if (!loaded.ok) return loaded;
        Map<String, String> files = new LinkedHashMap<>();
        files.put(path, normalizeLines(text));
        Result saved = save(context, loaded.config, files, Collections.emptySet(), false);
        return saved.ok ? Result.ok(loaded.config, "List saved in transaction " + saved.message) : saved;
    }

    static Result restoreDefaultEssentials(Context context, String targetPath) {
        String path = targetPath == null || targetPath.trim().isEmpty() ? LIST_DIR + "/essentials.allowlist" : targetPath;
        if (!XbsConfigTransaction.isManagedListPath(path)) return Result.error("Essentials path is outside the managed XBS list directory");
        String fallback = "android\ncom.android.systemui\ncom.android.settings\ncom.android.launcher3\ncom.google.android.gms\ncom.android.vending\ncom.android.phone\ncom.google.android.providers.media.module\n";
        XbsRootApi.CommandOutput out = XbsRootApi.runRoot(XbsRuntimeResolver.resolveDefaultEssentials("ESSENTIALS_SOURCE")
            + "if [ -f \"$ESSENTIALS_SOURCE\" ]; then cat \"$ESSENTIALS_SOURCE\"; fi");
        if (out.exitCode != 0) return Result.error(out.error.isEmpty() ? out.stdout : out.error);
        String value = out.stdout.trim().isEmpty() ? fallback : out.stdout;
        Result saved = writeText(context, path, value);
        return saved.ok ? Result.ok(saved.config, "Default essentials restored with rollback protection") : saved;
    }

    static Result save(JSONObject config, Map<String,String> files, boolean reload){ return save(null, config, files, Collections.emptySet(), reload); }
    static Result save(Context context, JSONObject config, Map<String,String> files, boolean reload){ return save(context, config, files, Collections.emptySet(), reload); }

    static Result save(Context context, JSONObject config, Map<String,String> files, Set<String> deletes, boolean reload){
        try{
            if(config==null)return Result.error("Config is missing");
            Map<String,String> safeFiles=files==null?Collections.emptyMap():files;
            Set<String> safeDeletes=deletes==null?Collections.emptySet():deletes;
            JSONObject header=config.optJSONObject("header"); JSONObject events=config.optJSONObject("events");
            if(header==null||events==null)return Result.error("Config must contain header and events objects");
            if(header.optInt("version",2)!=2)return Result.error("Only XBS config version 2 is supported");
            for (String builtIn : BUILT_IN_EVENTS) if (!events.has(builtIn)) return Result.error("Built-in event cannot be removed: " + builtIn);
            String eventError=validateEvents(events);if(!eventError.isEmpty())return Result.error(eventError);
            XbsConfigTransaction.Plan plan = XbsConfigTransaction.create(config, safeFiles, safeDeletes, reload);
            XbsRootApi.CommandOutput out = XbsConfigTransaction.execute(plan);
            if(out.exitCode!=0){
                String detail=out.error.isEmpty()?out.stdout:out.error;
                if(context!=null)XbsConfigTransactionStore.save(context,XbsConfigTransaction.latest());
                return Result.error("Configuration transaction failed safely: "+detail);
            }
            if (context != null) {
                XbsCache.put(context, CONFIG_CACHE, config.toString());
                for (String path : safeFiles.keySet()) XbsCache.invalidate(context, "file_" + Integer.toHexString(path.hashCode()));
                for (String path : safeDeletes) XbsCache.invalidate(context, "file_" + Integer.toHexString(path.hashCode()));
                XbsRuleIndex.invalidate(context); ProcessRepository.invalidate(context);
                XbsConfigTransactionStore.save(context, XbsConfigTransaction.latest());
            }
            return Result.ok(config,plan.id);
        }catch(Exception e){return Result.error(e.getMessage());}
    }

    static XbsConfigTransaction.Record latestTransaction(){ return XbsConfigTransaction.latest(); }
    static XbsConfigTransaction.Record latestTransaction(Context context){
        XbsConfigTransaction.Record record=XbsConfigTransaction.latest();
        XbsConfigTransactionStore.save(context,record);
        return record;
    }

    static Result rollbackLatest(Context context, XbsConfigTransaction.Record record){
        XbsRootApi.CommandOutput out=XbsConfigTransaction.rollbackLatest(record);
        if(out.exitCode!=0)return Result.error(out.error.isEmpty()?out.stdout:out.error);
        if(context!=null){XbsCache.clear(context);XbsConfigTransactionStore.save(context,XbsConfigTransaction.latest());}
        return load(context,true);
    }

    static Result recoverLatest(Context context, XbsConfigTransaction.Record record){
        XbsRootApi.CommandOutput out=XbsConfigTransaction.recoverLatest(record);
        if(out.exitCode!=0)return Result.error(out.error.isEmpty()?out.stdout:out.error);
        if(context!=null){XbsCache.clear(context);XbsConfigTransactionStore.save(context,XbsConfigTransaction.latest());}
        return load(context,true);
    }

    static Result deleteEvent(String name, boolean deleteManagedFiles){ return deleteEvent(null, name, deleteManagedFiles); }

    static Result deleteEvent(Context context, String name, boolean deleteManagedFiles){
        if (isBuiltInEvent(name)) return Result.error("Built-in events cannot be deleted: " + name);
        Result loaded=load(context, true);if(!loaded.ok)return loaded;
        try{
            JSONObject events=loaded.config.getJSONObject("events"); JSONObject event=events.optJSONObject(name);
            if(event==null)return Result.error("Event not found: "+name);
            String[] paths={event.optString("allowlist",""),event.optString("denylist",""),event.optString("proc_file","")};
            events.remove(name);
            Set<String> deletes=new LinkedHashSet<>();
            if(deleteManagedFiles)for(String path:paths)if(XbsConfigTransaction.isManagedListPath(path))deletes.add(path);
            Result saved=save(context, loaded.config,new LinkedHashMap<>(),deletes,true);
            if (context != null && saved.ok) invalidate(context, paths);
            return saved;
        }catch(Exception e){return Result.error(e.getMessage());}
    }

    static boolean isBuiltInEvent(String name) { return BUILT_IN_EVENTS.contains(name); }
    static boolean isCustomEvent(String name) { return name != null && !name.isEmpty() && !isBuiltInEvent(name); }
    static String managedPath(String event,String suffix){return LIST_DIR+"/"+safeName(event)+suffix;}
    static String safeName(String name){return ManagedPathPolicy.safeLeafFrom(name,"event");}
    static boolean validEventName(String name){return name!=null&&name.matches("[A-Za-z0-9_.-]+")&&!name.equals("template");}
    static boolean validNewEventName(String name){ return validEventName(name) && !isBuiltInEvent(name); }


    private static String validateEvents(JSONObject events) {
        java.util.Iterator<String> names=events.keys();
        while(names.hasNext()){
            String name=names.next();JSONObject event=events.optJSONObject(name);
            if(event==null)return "Event must be an object: "+name;
            for(String key:new String[]{"allowlist","denylist","proc_file"}){
                Object value=event.opt(key);if(value!=null&&value!=JSONObject.NULL&&!(value instanceof String))return "events."+name+"."+key+" must be a path string";
            }
            for(String key:new String[]{"keep_on_charge","quit","handle_proc","low_ram","kill_wifi"}){
                Object value=event.opt(key);if(value==null||value==JSONObject.NULL)continue;
                if(value instanceof Boolean)continue;String text=String.valueOf(value);
                if(!"true".equalsIgnoreCase(text)&&!"false".equalsIgnoreCase(text))return "events."+name+"."+key+" must be true or false";
            }
            String error=choiceError(event,name,"handle_apps",new String[]{"false","nice","kill","suspend"});if(!error.isEmpty())return error;
            error=choiceError(event,name,"handle_gms",new String[]{"false","nice","kill"});if(!error.isEmpty())return error;
            error=choiceError(event,name,"doze",new String[]{"false","light","deep"});if(!error.isEmpty())return error;
            for(String key:new String[]{"handle_cores","disable_cores"}){
                Object value=event.opt(key);if(value==null||value==JSONObject.NULL)continue;String text=String.valueOf(value).trim();
                if(text.isEmpty())return "events."+name+"."+key+" cannot be empty";
            }
        }
        return "";
    }

    private static String choiceError(JSONObject event,String eventName,String key,String[] allowed){
        Object value=event.opt(key);if(value==null||value==JSONObject.NULL)return "";String text=String.valueOf(value);
        for(String item:allowed)if(item.equalsIgnoreCase(text))return "";
        return "events."+eventName+"."+key+" must be one of "+java.util.Arrays.toString(allowed);
    }

    private static void invalidate(Context context, String... paths) {
        if (context == null) return;
        XbsCache.invalidate(context, CONFIG_CACHE);
        for (String path : paths) if (path != null && !path.isEmpty()) XbsCache.invalidate(context, "file_" + Integer.toHexString(path.hashCode()));
        XbsRuleIndex.invalidate(context); ProcessRepository.invalidate(context);
    }

    private static String normalizeLines(String value){StringBuilder out=new StringBuilder();for(String line:(value==null?"":value).replace("\r","").split("\n")){String v=line.trim();if(v.isEmpty())continue;out.append(v).append('\n');}return out.toString();}
    private static String xbs(String args){return XbsRuntimeResolver.xbs(args);}
    static String q(String value){return "'"+(value==null?"":value).replace("'","'\\''")+"'";}

    static final class Result{
        final boolean ok;final JSONObject config;final String message;
        private Result(boolean ok,JSONObject config,String message){this.ok=ok;this.config=config;this.message=message==null?"":message;}
        static Result ok(JSONObject config){return new Result(true,config,"");}
        static Result ok(JSONObject config,String message){return new Result(true,config,message);}
        static Result error(String message){return new Result(false,null,message);}
    }
}
