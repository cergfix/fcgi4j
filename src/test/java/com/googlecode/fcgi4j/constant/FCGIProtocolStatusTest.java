package com.googlecode.fcgi4j.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FCGIProtocolStatusTest {

    @Test
    void allEnumValuesHaveExpectedIds() {
        assertEquals(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE.getId());
        assertEquals(1, FCGIProtocolStatus.FCGI_CANT_MPX_CONN.getId());
        assertEquals(2, FCGIProtocolStatus.FCGI_OVERLOADED.getId());
        assertEquals(3, FCGIProtocolStatus.FCGI_UNKNOWN_ROLE.getId());
    }

    @Test
    void enumHasExactlyFourValues() {
        assertEquals(4, FCGIProtocolStatus.values().length);
    }

    @Test
    void valueOfIntReturnsCorrectStatus() {
        for (FCGIProtocolStatus status : FCGIProtocolStatus.values()) {
            assertSame(status, FCGIProtocolStatus.valueOf(status.getId()));
        }
    }

    @Test
    void valueOfOutOfRangeThrows() {
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> FCGIProtocolStatus.valueOf(99));
    }
}
