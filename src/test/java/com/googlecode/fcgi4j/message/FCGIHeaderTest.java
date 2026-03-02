package com.googlecode.fcgi4j.message;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FCGIHeaderTest {

    @Test
    void constructorSetsVersionTypeIdAndLength() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_STDOUT, 256);

        assertEquals(FCGIHeader.FCGI_VERSION_1, header.getVersion());
        assertEquals(FCGIHeaderType.FCGI_STDOUT, header.getType());
        assertEquals(FCGIHeader.ID, header.getId());
        assertEquals(256, header.getLength());
    }

    @Test
    void getByteBufferProducesEightBytes() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_BEGIN_REQUEST, 8);
        ByteBuffer buf = header.getByteBuffer();

        assertEquals(FCGIHeader.FCGI_HEADER_LEN, buf.remaining());
    }

    @Test
    void getByteBufferSerializesVersionFirst() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_STDIN, 0);
        ByteBuffer buf = header.getByteBuffer();

        assertEquals(1, buf.get(0)); // FCGI_VERSION_1
    }

    @Test
    void getByteBufferSerializesTypeSecond() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_PARAMS, 0);
        ByteBuffer buf = header.getByteBuffer();

        assertEquals(FCGIHeaderType.FCGI_PARAMS.getId(), buf.get(1));
    }

    @Test
    void getByteBufferSerializesRequestIdAsShort() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_STDOUT, 0);
        ByteBuffer buf = header.getByteBuffer();

        assertEquals(0, buf.get(2)); // high byte of request ID (1)
        assertEquals(1, buf.get(3)); // low byte of request ID (1)
    }

    @Test
    void getByteBufferSerializesContentLength() {
        FCGIHeader header = new FCGIHeader(FCGIHeaderType.FCGI_STDOUT, 300);
        ByteBuffer buf = header.getByteBuffer();

        int length = ((buf.get(4) & 0xff) << 8) | (buf.get(5) & 0xff);
        assertEquals(300, length);
    }

    @Test
    void parseRoundTrips() {
        FCGIHeader original = new FCGIHeader(FCGIHeaderType.FCGI_STDOUT, 1024);
        ByteBuffer buf = original.getByteBuffer();

        FCGIHeader parsed = FCGIHeader.parse(buf);

        assertEquals(original.getVersion(), parsed.getVersion());
        assertEquals(original.getType(), parsed.getType());
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.getLength(), parsed.getLength());
    }

    @Test
    void parseExtractsPadding() {
        // Manually build a header buffer with padding = 5
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 1);  // version
        buf.put((byte) 6);  // FCGI_STDOUT
        buf.putShort((short) 1); // request ID
        buf.putShort((short) 100); // content length
        buf.put((byte) 5);  // padding
        buf.put((byte) 0);  // reserved
        buf.flip();

        FCGIHeader header = FCGIHeader.parse(buf);
        assertEquals(5, header.getPadding());
        assertEquals(100, header.getLength());
    }

    @Test
    void parseHandlesZeroLength() {
        FCGIHeader original = new FCGIHeader(FCGIHeaderType.FCGI_PARAMS, 0);
        ByteBuffer buf = original.getByteBuffer();

        FCGIHeader parsed = FCGIHeader.parse(buf);
        assertEquals(0, parsed.getLength());
        assertEquals(FCGIHeaderType.FCGI_PARAMS, parsed.getType());
    }

    @Test
    void parseHandlesMaxContentLength() {
        // Max 16-bit unsigned = 65535
        FCGIHeader original = new FCGIHeader(FCGIHeaderType.FCGI_STDOUT, 65535);
        ByteBuffer buf = original.getByteBuffer();

        FCGIHeader parsed = FCGIHeader.parse(buf);
        assertEquals(65535, parsed.getLength());
    }

    @Test
    void headerLenConstantIsEight() {
        assertEquals(8, FCGIHeader.FCGI_HEADER_LEN);
    }
}
