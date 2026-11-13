package io.github.mikeceead.xbscompanion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Process-local bounded duplicate suppression for repeated authorized Tasker broadcasts.
 * Not a durable idempotency protocol; the root/daemon receipt remains authoritative.
 */
final class R05aActionDedup {
    private static final long WINDOW_NS=1_500_000_000L;
    private static final int MAX_KEYS=128;
    private static final LinkedHashMap<String,Long> RECENT=new LinkedHashMap<>();
    private R05aActionDedup(){}

    static synchronized boolean claim(String signedIdentity,long nanos){
        String key=digest(signedIdentity);
        if(key.isEmpty())return false;
        Iterator<Map.Entry<String,Long>> it=RECENT.entrySet().iterator();
        while(it.hasNext()){
            long elapsed=nanos-it.next().getValue();
            if(elapsed<0 || elapsed>=WINDOW_NS)it.remove();
        }
        if(RECENT.containsKey(key))return false;
        if(RECENT.size()>=MAX_KEYS)RECENT.remove(RECENT.keySet().iterator().next());
        RECENT.put(key,nanos);
        return true;
    }
    static synchronized void release(String signedIdentity){RECENT.remove(digest(signedIdentity));}
    static synchronized void resetForTest(){RECENT.clear();}
    private static String digest(String value){
        if(value==null||value.length()<20||value.length()>20000)return "";
        try{
            byte[] h=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(64);
            for(byte b:h){int v=b&0xff;hex.append(Character.forDigit(v>>>4,16)).append(Character.forDigit(v&15,16));}
            return hex.toString();
        }catch(Exception ignored){return "";}
    }
}
