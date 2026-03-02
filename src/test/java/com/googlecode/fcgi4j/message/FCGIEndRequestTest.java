package com.googlecode.fcgi4j.message;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import com.googlecode.fcgi4j.constant.FCGIProtocolStatus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FCGIEndRequestTest {

    private FCGIEndRequest parseWith(long appStatus, int protocolStatusId) {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_END_REQUEST, 8);

        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt((int) appStatus);
        buf.put((byte) protocolStatusId);
        buf.put(new byte[3]); // reserved
        buf.flip();

        return FCGIEndRequest.parse(header, buf);
    }

    @Test
    void parseWithRequestComplete() {
        FCGIEndRequest end = parseWith(0, 0);

        assertEquals(0, end.getAppStatus());
        assertEquals(FCGIProtocolStatus.FCGI_REQUEST_COMPLETE, end.getProtocolStatus());
    }

    @Test
    void parseWithNonZeroAppStatus() {
        FCGIEndRequest end = parseWith(42, 0);

        assertEquals(42, end.getAppStatus());
    }

    @Test
    void parsePreservesHeader() {
        FCGIEndRequest end = parseWith(0, 0);

        assertNotNull(end.getHeader());
        assertEquals(FCGIHeaderType.FCGI_END_REQUEST, end.getHeader().getType());
    }

    @Test
    void parseWithOverloaded() {
        FCGIEndRequest end = parseWith(0, 2);

        assertEquals(FCGIProtocolStatus.FCGI_OVERLOADED, end.getProtocolStatus());
    }

    @Test
    void parseWithUnknownRole() {
        FCGIEndRequest end = parseWith(1, 3);

        assertEquals(1, end.getAppStatus());
        assertEquals(FCGIProtocolStatus.FCGI_UNKNOWN_ROLE, end.getProtocolStatus());
    }
}
