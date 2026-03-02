package com.googlecode.fcgi4j.message;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import com.googlecode.fcgi4j.constant.FCGIRole;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FCGIBeginRequestTest {

    @Test
    void getByteBuffersReturnsTwoBuffers() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, false);
        ByteBuffer[] bufs = req.getByteBuffers();

        assertEquals(2, bufs.length);
    }

    @Test
    void firstBufferIsValidHeader() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, true);
        ByteBuffer headerBuf = req.getByteBuffers()[0];

        FCGIHeader header = FCGIHeader.parse(headerBuf);
        assertEquals(FCGIHeaderType.FCGI_BEGIN_REQUEST, header.getType());
        assertEquals(FCGIBeginRequest.FCGI_BEGIN_REQUEST_LEN, header.getLength());
    }

    @Test
    void secondBufferIsEightBytes() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, false);
        ByteBuffer bodyBuf = req.getByteBuffers()[1];

        assertEquals(FCGIBeginRequest.FCGI_BEGIN_REQUEST_LEN, bodyBuf.remaining());
    }

    @Test
    void responderRoleEncoded() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, false);
        ByteBuffer body = req.getByteBuffers()[1];

        int role = body.getShort() & 0xffff;
        assertEquals(FCGIRole.RESPONDER.getId(), role);
    }

    @Test
    void authorizerRoleEncoded() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.AUTHORIZER, false);
        ByteBuffer body = req.getByteBuffers()[1];

        int role = body.getShort() & 0xffff;
        assertEquals(FCGIRole.AUTHORIZER.getId(), role);
    }

    @Test
    void filterRoleEncoded() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.FILTER, false);
        ByteBuffer body = req.getByteBuffers()[1];

        int role = body.getShort() & 0xffff;
        assertEquals(FCGIRole.FILTER.getId(), role);
    }

    @Test
    void keepAliveTrue() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, true);
        ByteBuffer body = req.getByteBuffers()[1];
        body.getShort(); // skip role

        assertEquals(FCGIBeginRequest.FCGI_KEEP_CONN, body.get() & 0xff);
    }

    @Test
    void keepAliveFalse() {
        FCGIBeginRequest req = new FCGIBeginRequest(FCGIRole.RESPONDER, false);
        ByteBuffer body = req.getByteBuffers()[1];
        body.getShort(); // skip role

        assertEquals(0, body.get() & 0xff);
    }
}
