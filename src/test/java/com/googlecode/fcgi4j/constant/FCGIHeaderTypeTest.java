package com.googlecode.fcgi4j.constant;

import com.googlecode.fcgi4j.exceptions.FCGIUnKnownHeaderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FCGIHeaderTypeTest {

    @Test
    void allEnumValuesHaveExpectedIds() {
        assertEquals(1, FCGIHeaderType.FCGI_BEGIN_REQUEST.getId());
        assertEquals(2, FCGIHeaderType.FCGI_ABORT_REQUEST.getId());
        assertEquals(3, FCGIHeaderType.FCGI_END_REQUEST.getId());
        assertEquals(4, FCGIHeaderType.FCGI_PARAMS.getId());
        assertEquals(5, FCGIHeaderType.FCGI_STDIN.getId());
        assertEquals(6, FCGIHeaderType.FCGI_STDOUT.getId());
        assertEquals(7, FCGIHeaderType.FCGI_STDERR.getId());
        assertEquals(8, FCGIHeaderType.FCGI_DATA.getId());
        assertEquals(9, FCGIHeaderType.FCGI_GET_VALUES.getId());
        assertEquals(10, FCGIHeaderType.FCGI_GET_VALUES_RESULT.getId());
        assertEquals(11, FCGIHeaderType.FCGI_UNKNOWN_TYPE.getId());
    }

    @Test
    void enumHasExactlyElevenValues() {
        assertEquals(11, FCGIHeaderType.values().length);
    }

    @Test
    void valueOfIntReturnsCorrectType() {
        for (FCGIHeaderType type : FCGIHeaderType.values()) {
            assertSame(type, FCGIHeaderType.valueOf(type.getId()));
        }
    }

    @Test
    void valueOfIntRoundTrips() {
        assertSame(FCGIHeaderType.FCGI_STDOUT, FCGIHeaderType.valueOf(6));
        assertSame(FCGIHeaderType.FCGI_STDERR, FCGIHeaderType.valueOf(7));
        assertSame(FCGIHeaderType.FCGI_END_REQUEST, FCGIHeaderType.valueOf(3));
    }

    @Test
    void valueOfNegativeIdThrows() {
        assertThrows(FCGIUnKnownHeaderException.class, () -> FCGIHeaderType.valueOf(-1));
    }

    @Test
    void valueOfOutOfRangeIdThrows() {
        assertThrows(FCGIUnKnownHeaderException.class, () -> FCGIHeaderType.valueOf(99));
    }
}
