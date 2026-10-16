package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class FrontendContractTest {
    @Test public void eventWritesCanonicalEnvelopeAndReadsLegacy() throws Exception {
        XbsEvent event=new XbsEvent("evt","profile","charging","active",1700000000L,"info","started",false,"{\"state\":\"active\"}");
        JSONObject json=event.toJson();
        assertEquals("1.0.0",json.getString("schema_version"));assertEquals("1.0.0",json.getString("contract_version"));assertEquals("event",json.getString("kind"));assertEquals("evt",json.getString("event_id"));assertEquals("xbs",json.getString("app"));assertEquals(1700000000000L,json.getLong("timestamp"));assertEquals("charging",json.getJSONObject("data").getString("name"));
        XbsEvent roundTrip=XbsEvent.fromJson(json);assertEquals(event.id,roundTrip.id);assertEquals(event.timestamp,roundTrip.timestamp);assertEquals(event.payloadJson,roundTrip.payloadJson);
        XbsEvent legacy=XbsEvent.fromJson(new JSONObject("{\"id\":\"old\",\"type\":\"profile\",\"name\":\"demo\",\"state\":\"active\",\"timestamp\":7,\"severity\":\"info\",\"message\":\"old\",\"replayed\":false,\"payload\":\"{}\"}"));
        assertEquals("old",legacy.id);assertEquals(7L,legacy.timestamp);
        JSONObject unsupported=new JSONObject(json.toString());unsupported.put("schema_version","2.0.0");assertNull(XbsEvent.fromJson(unsupported));
    }
    @Test public void machineResultRequiresVersionedCanonicalEnvelope() {
        MachineResult good=MachineResult.parse("{\"schema_version\":\"2.0.0\",\"ok\":true,\"app\":\"xbs\",\"action\":\"status\",\"message\":\"ready\",\"exit_code\":0,\"data\":{},\"error\":null}");
        assertTrue(good.parsed);assertTrue(good.ok);
        assertFalse(MachineResult.parse("{\"ok\":true,\"action\":\"status\"}").parsed);
        assertFalse(MachineResult.parse("{\"schema_version\":\"2.0.0\",\"ok\":true,\"app\":\"demo\",\"action\":\"status\",\"message\":\"ready\",\"exit_code\":0,\"data\":{}}").parsed);
        assertFalse(MachineResult.parse("{\"schema_version\":\"1.0.0\",\"ok\":true,\"app\":\"xbs\",\"action\":\"status\",\"message\":\"ready\",\"exit_code\":0,\"data\":{},\"error\":null}").parsed);
        assertFalse(MachineResult.parse("{\"schema_version\":\"2.0.0\",\"ok\":true,\"app\":\"demo\",\"action\":\"status\",\"message\":\"ready\",\"exit_code\":1.5,\"data\":{},\"error\":null}").parsed);
    }
}
