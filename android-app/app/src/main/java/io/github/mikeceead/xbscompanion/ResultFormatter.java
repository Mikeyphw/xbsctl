package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

final class ResultFormatter {
    private ResultFormatter() {}

    static String summary(int exitCode, String stdout, String stderr, String transportError, String successFallback) {
        MachineResult machine = MachineResult.parse(stdout);
        if (machine.parsed) {
            String message = clean(machine.message);
            String machineError = clean(machine.error);
            if (!message.isEmpty() && !looksStructured(message)) return limit(message, 280);
            if (!machineError.isEmpty() && !looksStructured(machineError)) return humanError(machineError);
            String derived = summarizeJson(machine.object());
            if (!derived.isEmpty()) return derived;
            if (!machine.ok || exitCode != 0) return "The command failed. Open diagnostics for technical details.";
            return clean(successFallback).isEmpty() ? "Completed successfully" : clean(successFallback);
        }

        String transport = clean(transportError);
        if (!transport.isEmpty()) return humanError(transport);
        String rejected = commandRejection(stderr, stdout);
        if (!rejected.isEmpty()) return rejected;
        if (looksBinary(stdout) || looksBinary(stderr)) return "The command returned binary data. Open diagnostics to inspect the captured output.";
        String raw = firstUseful(stderr, stdout);
        if (looksStructured(raw)) return "The command returned unreadable structured data. Open diagnostics for the raw response.";
        if (!raw.isEmpty()) return limit(raw, 280);
        if (exitCode == 0) return clean(successFallback).isEmpty() ? "Completed successfully" : clean(successFallback);
        return "The command failed with exit code " + exitCode + ". Open diagnostics for details.";
    }

    static String notificationText(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "Open the app for details";
        if (looksStructured(clean) || looksUsage(clean)) return "Open the app for a readable result and diagnostics";
        return limit(clean, 180);
    }

    static String clean(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if (ch=='\n' || ch=='\r' || ch=='\t' || Character.isWhitespace(ch)) { if (!space && out.length()>0) out.append(' '); space=true; continue; }
            if (Character.isISOControl(ch)) continue;
            out.append(ch); space=false;
        }
        return out.toString().trim();
    }

    private static String summarizeJson(JSONObject root) {
        if (root == null) return "";
        String status = scalar(root.opt("status"));
        String completion = scalar(root.opt("completion"));
        String requested = scalar(root.opt("requested_action"));
        String event = scalar(root.opt("event"));
        if (!status.isEmpty() || !completion.isEmpty()) {
            StringBuilder out = new StringBuilder();
            if (!requested.isEmpty()) out.append(pretty(requested));
            if (!event.isEmpty()) { if (out.length()>0) out.append(" · "); out.append(pretty(event)); }
            if (!completion.isEmpty()) { if (out.length()>0) out.append(" · "); out.append(pretty(completion)); }
            if (!status.isEmpty() && !"ok".equalsIgnoreCase(status)) { if (out.length()>0) out.append(" · "); out.append(pretty(status)); }
            if (out.length()>0) return limit(out.toString(),280);
        }
        Object data = root.opt("data");
        if (data instanceof JSONObject) {
            JSONObject obj=(JSONObject)data;
            String summary=clean(obj.optString("summary","")); if(!summary.isEmpty()) return limit(summary,280);
            String path=clean(obj.optString("path","")); if(!path.isEmpty()) return "Completed · " + limit(path,220);
            String image=clean(obj.optString("image","")); if(image.isEmpty()) image=clean(obj.optString("image_path",""));
            String source=clean(obj.optString("source","")); String target=clean(obj.optString("target",""));
            if(!image.isEmpty()||!source.isEmpty()) { StringBuilder out=new StringBuilder("Wallpaper ready"); if(!source.isEmpty()) out.append(" · ").append(source); if(!target.isEmpty()) out.append(" · ").append(target); return limit(out.toString(),280); }
            if(obj.has("moved")||obj.has("duplicates")||obj.has("conflicts")||obj.has("errors")) return countSummary(obj);
            if(obj.has("active_events")) return activeSummary(obj.optJSONArray("active_events"), obj.optString("mode",""));
        }
        if (root.has("moved")||root.has("duplicates")||root.has("conflicts")||root.has("errors")) return countSummary(root);
        if (root.has("active_events")) return activeSummary(root.optJSONArray("active_events"), root.optString("mode",""));
        if (root.has("available")) return root.optBoolean("available") ? "Runtime is available" : "Runtime is unavailable";
        return "";
    }

    private static String countSummary(JSONObject obj) {
        return obj.optInt("moved",0)+" moved/proposed · "+obj.optInt("duplicates",0)+" duplicates · "+obj.optInt("conflicts",0)+" conflicts · "+obj.optInt("errors",0)+" errors";
    }
    private static String activeSummary(JSONArray active,String mode) {
        int count=active==null?0:active.length(); String suffix=clean(mode); return count+" active event"+(count==1?"":"s")+(suffix.isEmpty()?"":" · "+pretty(suffix));
    }
    private static String commandRejection(String stderr,String stdout) {
        String combined=(stderr==null?"":stderr)+"\n"+(stdout==null?"":stdout);
        String cleaned=clean(combined);
        if (!looksUsage(cleaned)) return "";
        String[] lines=combined.replace("\r","").split("\n");
        for(int i=lines.length-1;i>=0;i--) {
            String line=clean(lines[i]); String lower=line.toLowerCase(Locale.ROOT);
            int marker=lower.indexOf(": error:");
            if(marker>=0) return humanError(line.substring(marker+8).trim());
            if(lower.startsWith("error:")) return humanError(line.substring(6).trim());
            if(lower.contains("unrecognized arguments")||lower.contains("invalid choice")||lower.contains("unknown command")) return humanError(line);
        }
        return "The installed CLI rejected this command. It may be unsupported or use different arguments.";
    }
    private static String humanError(String value) {
        String text=clean(value); String lower=text.toLowerCase(Locale.ROOT);
        if(lower.contains("invalid choice")||lower.contains("unrecognized argument")||lower.contains("unknown command")||lower.contains("no such command")) return limit("Unsupported command or option: "+text,280);
        if(lower.contains("permission denied")) return "Permission denied. Check root authorization and XBS module access.";
        if(lower.contains("not found")) return limit("Required command or file was not found: "+text,280);
        if(lower.contains("timed out")||lower.contains("timeout")) return limit("The operation timed out: "+text,280);
        return limit(text,280);
    }
    private static boolean looksUsage(String value) { String v=value==null?"":value.toLowerCase(Locale.ROOT); return v.contains("usage:")&&(v.contains("error:")||v.contains("invalid choice")||v.contains("unrecognized arguments")||v.contains("the following arguments are required")); }
    private static boolean looksStructured(String value) { String v=value==null?"":value.trim(); return (v.startsWith("{")&&v.endsWith("}"))||(v.startsWith("[")&&v.endsWith("]")); }
    private static boolean looksBinary(String value) {
        if(value==null||value.isEmpty())return false; if(value.startsWith("\u007fELF"))return true; int controls=0,replacements=0,checked=Math.min(value.length(),4096);
        for(int i=0;i<checked;i++){char ch=value.charAt(i);if(ch==0)return true;if(ch=='\uFFFD')replacements++;if(Character.isISOControl(ch)&&ch!='\n'&&ch!='\r'&&ch!='\t')controls++;}
        return replacements>3||controls>Math.max(8,checked/20);
    }
    private static String firstUseful(String... values) {
        for(String value:values){String clean=clean(value);if(clean.isEmpty()||looksUsage(clean))continue;return clean;} return "";
    }
    private static String scalar(Object value) { return value==null||value==JSONObject.NULL?"":clean(String.valueOf(value)); }
    private static String pretty(String value) { String v=clean(value).replace('_',' ').replace('-',' '); return v.isEmpty()?v:Character.toUpperCase(v.charAt(0))+v.substring(1); }
    private static String limit(String value,int max) { String text=clean(value); return text.length()<=max?text:text.substring(0,Math.max(0,max-1))+"…"; }
}
