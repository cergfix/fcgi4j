package com.googlecode.fcgi4j.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FCGIRoleTest {

    @Test
    void allEnumValuesHaveExpectedIds() {
        assertEquals(1, FCGIRole.RESPONDER.getId());
        assertEquals(2, FCGIRole.AUTHORIZER.getId());
        assertEquals(3, FCGIRole.FILTER.getId());
    }

    @Test
    void enumHasExactlyThreeValues() {
        assertEquals(3, FCGIRole.values().length);
    }

    @Test
    void valueOfIntReturnsCorrectRole() {
        for (FCGIRole role : FCGIRole.values()) {
            assertSame(role, FCGIRole.valueOf(role.getId()));
        }
    }

    @Test
    void valueOfZeroReturnsNull() {
        // roleMap[0] is never assigned since role IDs start at 1
        assertNull(FCGIRole.valueOf(0));
    }

    @Test
    void valueOfOutOfRangeThrows() {
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> FCGIRole.valueOf(99));
    }
}
