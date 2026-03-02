package com.googlecode.fcgi4j.message;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import com.googlecode.fcgi4j.exceptions.FCGIException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FCGIParamsTest {

    @Test
    void nullKeyThrows() {
        assertThrows(FCGIException.class, () -> new FCGIParams(null, "value"));
    }

    @Test
    void nullValueThrows() {
        assertThrows(FCGIException.class, () -> new FCGIParams("key", null));
    }

    @Test
    void getByteBuffersReturnsTwoBuffers() {
        FCGIParams params = new FCGIParams("key", "val");
        ByteBuffer[] bufs = params.getByteBuffers();

        assertEquals(2, bufs.length);
    }

    @Test
    void firstBufferIsParamsHeader() {
        FCGIParams params = new FCGIParams("key", "val");
        ByteBuffer headerBuf = params.getByteBuffers()[0];

        FCGIHeader header = FCGIHeader.parse(headerBuf);
        assertEquals(FCGIHeaderType.FCGI_PARAMS, header.getType());
    }

    @Test
    void shortKeyValueEncoding() {
        // "key" (3 bytes) + "val" (3 bytes) → both < 128, so 1-byte length each
        // body = 1 + 1 + 3 + 3 = 8 bytes
        FCGIParams params = new FCGIParams("key", "val");
        ByteBuffer[] bufs = params.getByteBuffers();

        FCGIHeader header = FCGIHeader.parse(bufs[0]);
        assertEquals(8, header.getLength());
        assertEquals(8, bufs[1].remaining());
    }

    @Test
    void shortKeyValueBodyContainsLengthsAndData() {
        FCGIParams params = new FCGIParams("AB", "CD");
        ByteBuffer body = params.getByteBuffers()[1];

        assertEquals(2, body.get() & 0xff); // key length
        assertEquals(2, body.get() & 0xff); // value length
        byte[] keyBytes = new byte[2];
        body.get(keyBytes);
        assertEquals("AB", new String(keyBytes));
        byte[] valBytes = new byte[2];
        body.get(valBytes);
        assertEquals("CD", new String(valBytes));
    }

    @Test
    void longKeyUseFourByteLength() {
        // Key >= 128 bytes should use 4-byte length encoding (high bit set)
        StringBuilder longKey = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            longKey.append('x');
        }
        FCGIParams params = new FCGIParams(longKey.toString(), "v");
        ByteBuffer body = params.getByteBuffers()[1];

        int firstByte = body.get() & 0xff;
        assertTrue((firstByte & 0x80) != 0, "High bit must be set for 4-byte length encoding");
    }

    @Test
    void longValueUsesFourByteLength() {
        StringBuilder longVal = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longVal.append('y');
        }
        FCGIParams params = new FCGIParams("k", longVal.toString());
        ByteBuffer body = params.getByteBuffers()[1];

        // First byte is 1-byte key length
        assertEquals(1, body.get() & 0xff);
        // Next should be 4-byte value length with high bit set
        int firstValByte = body.get() & 0xff;
        assertTrue((firstValByte & 0x80) != 0, "High bit must be set for 4-byte length encoding");
    }

    @Test
    void utf8StringsEncodedCorrectly() {
        // Multi-byte UTF-8 chars: each char is 3 bytes in UTF-8
        FCGIParams params = new FCGIParams("key", "\u00e9\u00e9"); // é is 2 bytes in UTF-8
        ByteBuffer body = params.getByteBuffers()[1];

        assertEquals(3, body.get() & 0xff);  // "key" = 3 bytes
        assertEquals(4, body.get() & 0xff);  // "éé" = 4 bytes in UTF-8
    }

    @Test
    void nullSentinelProducesOneBuffer() {
        ByteBuffer[] bufs = FCGIParams.NULL.getByteBuffers();
        assertEquals(1, bufs.length);
    }

    @Test
    void nullSentinelHeaderHasZeroLength() {
        ByteBuffer headerBuf = FCGIParams.NULL.getByteBuffers()[0];
        FCGIHeader header = FCGIHeader.parse(headerBuf);

        assertEquals(FCGIHeaderType.FCGI_PARAMS, header.getType());
        assertEquals(0, header.getLength());
    }
}
