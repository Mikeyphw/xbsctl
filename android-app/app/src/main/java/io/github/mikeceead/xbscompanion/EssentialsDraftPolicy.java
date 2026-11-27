package io.github.mikeceead.xbscompanion;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure validation shared by save and restore; reject aliases of any event-managed rule file. */
final class EssentialsDraftPolicy {
    private EssentialsDraftPolicy() {}
    static String pathError(String root, String path, Set<String> eventPaths) {
        if (path == null || !path.equals(path.trim()) || !ManagedPathPolicy.isDirectChild(root, path) || !path.endsWith(".allowlist"))
            return "Essentials must be a direct .allowlist file inside the managed lists directory";
        if (eventPaths.contains(path)) return "Essentials cannot overwrite an event-managed list";
        return "";
    }
    static String normalize(String raw) {
        if (raw == null || raw.length() > 65536) throw new IllegalArgumentException("Essentials list exceeds 65536 characters");
        String[] lines=raw.replace("\r\n","\n").split("\n",-1);
        if(lines.length>1000) throw new IllegalArgumentException("Essentials list exceeds 1000 lines");
        Set<String> packages=new LinkedHashSet<>();
        for (int i=0;i<lines.length;i++) {
            String item=lines[i].trim();
            if(item.isEmpty()||item.startsWith("#")) continue;
            if(item.length()>255||!item.matches("android|[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("Invalid Android package on line "+(i+1));
            packages.add(item);
        }
        if(packages.isEmpty()) throw new IllegalArgumentException("Essentials list cannot be empty");
        return String.join("\n",packages)+"\n";
    }
}
