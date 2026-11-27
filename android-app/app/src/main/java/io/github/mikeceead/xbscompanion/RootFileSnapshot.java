package io.github.mikeceead.xbscompanion;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/** Exact-byte snapshots, never a digest of trimmed or reserialized JSON. */
final class RootFileSnapshot {
    static final int CONFIG_LIMIT = 2 * 1024 * 1024;
    static final int LIST_LIMIT = 512 * 1024;
    final boolean ok, present;
    final String text, generation, message;
    private RootFileSnapshot(boolean ok, boolean present, String text, String generation, String message) {
        this.ok=ok;this.present=present;this.text=text;this.generation=generation;this.message=message;
    }
    static RootFileSnapshot error(String message) { return new RootFileSnapshot(false,false,"","",message); }
    static RootFileSnapshot absent() { return new RootFileSnapshot(true,false,"","-",""); }
    static String digest(byte[] data) {
        try {
            StringBuilder hex=new StringBuilder(64);
            for(byte b:MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format(Locale.ROOT,"%02x",b&255));
            return hex.toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    static boolean validGeneration(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
    static boolean validPreimage(String value) { return "-".equals(value)||validGeneration(value); }
    static RootFileSnapshot present(byte[] bytes, int limit) {
        if(bytes.length>limit)return error("Managed file exceeds its size bound");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new RootFileSnapshot(true,true,text,digest(bytes),"");
        }catch(Exception malformed){return error("Managed file is not valid UTF-8");}
    }
    static RootFileSnapshot decode(String output,int limit) {
        if("XBS_SNAPSHOT_ABSENT".equals(output))return absent();
        if(output==null||!output.startsWith("XBS_SNAPSHOT_V1:")||!output.endsWith("\nXBS_SNAPSHOT_END"))
            return error("Incomplete managed-file snapshot");
        int lineEnd=output.indexOf('\n');
        if(lineEnd<0)return error("Incomplete managed-file snapshot header");
        int expected;
        try{String size=output.substring("XBS_SNAPSHOT_V1:".length(),lineEnd).trim();if(!size.matches("[0-9]{1,8}"))return error("Invalid managed-file size");expected=Integer.parseInt(size);}
        catch(NumberFormatException invalid){return error("Invalid managed-file size");}
        if(expected>limit)return error("Managed file exceeds its size bound");
        String payload=output.substring(lineEnd+1,output.length()-"\nXBS_SNAPSHOT_END".length()).replace("\r","").replace("\n","");
        if(payload.length()>4L*((limit+2L)/3L))return error("Managed file exceeds its size bound");
        try{byte[] bytes=Base64.getDecoder().decode(payload);if(bytes.length!=expected)return error("Managed file changed while being read");return present(bytes,limit);}catch(IllegalArgumentException malformed){return error("Invalid managed-file snapshot encoding");}
    }
    static String q(String value){return "'"+value.replace("'","'\\''")+"'";}
    static String parent(String path){return path.substring(0,path.lastIndexOf('/'));}
    // The platform storage root may be an Android alias; only its XBS-owned descendants are checked.
    static String directoryGuard(String configRoot,String listRoot){
        return "for D in "+q(parent(configRoot))+" "+q(configRoot)+" "+q(listRoot)+"; do "
            +"if [ -L \"$D\" ] || { [ -e \"$D\" ] && [ ! -d \"$D\" ]; }; then printf 'Unsafe managed directory\\n' >&2; exit 73; fi; done; ";
    }
    static String readShell(String path,String configRoot,String listRoot,int limit){
        if(!path.equals(configRoot+"/XtremeBS.json")&&!ManagedPathPolicy.isDirectChild(listRoot,path))throw new IllegalArgumentException("Unsafe managed read path");
        return "set -eu; "+directoryGuard(configRoot,listRoot)+"F="+q(path)+"; "
            +"if [ -L \"$F\" ] || { [ -e \"$F\" ] && [ ! -f \"$F\" ]; }; then printf 'Unsafe managed file\\n' >&2; exit 73; fi; "
            +"if [ ! -e \"$F\" ]; then printf XBS_SNAPSHOT_ABSENT; exit 0; fi; "
            +"N=$(wc -c < \"$F\"); [ \"$N\" -le "+limit+" ] || exit 79; "
            +"printf 'XBS_SNAPSHOT_V1:%s\\n' \"$N\"; base64 < \"$F\"; printf '\\nXBS_SNAPSHOT_END\\n';";
    }
}
