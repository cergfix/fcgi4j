package com.googlecode.fcgi4j.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FCGIExceptionTest {

    @Test
    void messageIsPreserved() {
        FCGIException ex = new FCGIException("test error");
        assertEquals("test error", ex.getMessage());
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new FCGIException("err"));
    }

    @Test
    void invalidHeaderExceptionHasFixedMessage() {
        FCGIInvalidHeaderException ex = new FCGIInvalidHeaderException();
        assertEquals("The HTTP header is invalid.", ex.getMessage());
    }

    @Test
    void invalidHeaderExceptionExtendsFCGIException() {
        assertInstanceOf(FCGIException.class, new FCGIInvalidHeaderException());
    }

    @Test
    void unknownHeaderExceptionPreservesMessage() {
        FCGIUnKnownHeaderException ex = new FCGIUnKnownHeaderException("Header: 99");
        assertEquals("Header: 99", ex.getMessage());
    }

    @Test
    void unknownHeaderExceptionExtendsFCGIException() {
        assertInstanceOf(FCGIException.class, new FCGIUnKnownHeaderException("msg"));
    }
}
