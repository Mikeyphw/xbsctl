package io.github.mikeceead.xbscompanion;

final class ExternalEntryReport {
    final long capturedAt; final String surface; final String code; final String hostPackage;
    final boolean accepted; final boolean senderVerified; final int protocolVersion;
    ExternalEntryReport(long at,String surface,String code,String host,boolean accepted,boolean senderVerified,int version){this.capturedAt=at;this.surface=clean(surface);this.code=clean(code);this.hostPackage=clean(host);this.accepted=accepted;this.senderVerified=senderVerified;this.protocolVersion=version;}
    static ExternalEntryReport empty(){return new ExternalEntryReport(0,"","missing","",false,false,0);}
    String summary(){if(capturedAt<=0)return "No external-entry evidence cached";return (accepted?"Accepted":"Rejected")+" "+surface+" · "+code+(senderVerified?" · sender verified":" · signature-bound host");}
    String supportText(){return "captured_at="+capturedAt+"\nsurface="+surface+"\ncode="+code+"\nhost="+hostPackage+"\naccepted="+accepted+"\nsender_verified="+senderVerified+"\nprotocol="+protocolVersion;}
    private static String clean(String s){if(s==null)return "";String x=s.trim().replace('\n',' ').replace('\r',' ');return x.length()<=160?x:x.substring(0,160);}
}
