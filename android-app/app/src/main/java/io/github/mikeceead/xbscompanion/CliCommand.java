package io.github.mikeceead.xbscompanion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CliCommand {
    static final String GLOBAL_JSON = "_global_json";
    static final String GLOBAL_QUIET = "_global_quiet";
    static final String GLOBAL_NO_INTERACTIVE = "_global_no_interactive";

    final String id, category, title, description, executable;
    final String[] path;
    final List<CliField> fields;
    final boolean destructive;
    final String special;

    CliCommand(String id,String category,String title,String description,String executable,String[] path,
               boolean destructive,String special,CliField... fields){
        this.id=id;this.category=category;this.title=title;this.description=description;this.executable=executable;
        this.path=path.clone();this.destructive=destructive;this.special=special==null?"":special;
        this.fields=java.util.Collections.unmodifiableList(java.util.Arrays.asList(fields));
    }

    String build(Map<String,String> values){
        if (!special.isEmpty()) throw new IllegalArgumentException("Native screens do not generate shell commands");
        Map<String,String> safeValues = values == null ? java.util.Collections.emptyMap() : values;
        for (CliField field : fields) if (isGlobal(field.name)) field.validate(safeValues.get(field.name));
        StringBuilder out=new StringBuilder(executable);
        boolean json = bool(safeValues, GLOBAL_JSON, true);
        if (json && ("xbs".equals(executable) || "XBSctl".equals(executable))) out.append(" --json");
        if ("xbs".equals(executable)) {
            if (bool(safeValues, GLOBAL_QUIET, false)) out.append(" --quiet");
            if (bool(safeValues, GLOBAL_NO_INTERACTIVE, false)) out.append(" --no-interactive");
        }
        for(String part:path) out.append(' ').append(part);
        for(CliField field:fields){
            if (isGlobal(field.name)) continue;
            String value=field.validate(safeValues.get(field.name));
            if(field.kind==CliField.Kind.BOOLEAN){
                if("true".equals(value)&&!field.flag.isEmpty())out.append(' ').append(field.flag);
                continue;
            }
            if(value.isEmpty()) continue;
            if(!field.flag.isEmpty())out.append(' ').append(field.flag);
            if (field.kind == CliField.Kind.WORDS) {
                for (String word : value.split("\\s+")) if (!word.isEmpty()) out.append(' ').append(SuiteAction.shellQuote(word));
            } else out.append(' ').append(SuiteAction.shellQuote(value));
        }
        return out.toString();
    }

    static Map<String,String> defaults(CliCommand command){
        Map<String,String> map=new LinkedHashMap<>();
        for(CliField field:command.fields)map.put(field.name,field.defaultValue);
        return map;
    }

    boolean isNative(){ return !special.isEmpty(); }

    private static boolean bool(Map<String,String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : "true".equalsIgnoreCase(value.trim());
    }

    private static boolean isGlobal(String name) {
        return GLOBAL_JSON.equals(name) || GLOBAL_QUIET.equals(name) || GLOBAL_NO_INTERACTIVE.equals(name);
    }
}
