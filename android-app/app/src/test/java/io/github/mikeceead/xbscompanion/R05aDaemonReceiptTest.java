package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class R05aDaemonReceiptTest {
    private static final String ID="request-1234567890";
    private static String response(String state,String action,String observed){
        return "{\"status\":\""+("applied".equals(state)?"ok":"pending")+"\",\"request_id\":\""+ID+"\",\"completion\":\""+state+"\",\"requested_action\":\""+action+"\",\"event\":\"manual\",\"observed\":"+observed+"}";
    }
    @Test public void exactAppliedReceiptRequired(){
        assertTrue(R05aDaemonReceipt.inspect("XBSctl --json start manual",0,response("applied","start","{\"available\":true,\"active_events\":[\"manual\"]}"),"").applied());
        assertFalse(R05aDaemonReceipt.inspect("XBSctl --json start manual",0,response("queued","start","{\"available\":true}"),"").applied());
        assertFalse(R05aDaemonReceipt.inspect("XBSctl --json stop manual",0,response("applied","start","{\"available\":true}"),"").applied());
        assertFalse(R05aDaemonReceipt.inspect("XBSctl --json start manual",0,response("applied","start","{\"available\":true,\"active_events\":[]}"),"").applied());
    }
    @Test public void noUnknownOutcomeBecomesSuccess(){
        for(String status:new String[]{"timeout","unknown","queued","failed"}){
            assertFalse(status,R05aDaemonReceipt.inspect("XBSctl reload",0,response(status,"reload","{\"available\":true}"),"").applied());
        }
        assertFalse(R05aDaemonReceipt.inspect("XBSctl pause",0,"","").applied());
        assertFalse(R05aDaemonReceipt.inspect("XBSctl pause",124,"","").applied());
        assertFalse(R05aDaemonReceipt.inspect("XBSctl pause",0,response("applied","pause","{\"available\":true,\"mode\":\"normal\"}"),"").applied());
        assertTrue(R05aDaemonReceipt.inspect("XBSctl reload",0,response("applied","reload","{\"available\":true,\"last_reload_time\":\"2026-11-13T04:00:00Z\"}"),"").applied());
    }
}
