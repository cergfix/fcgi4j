package com.googlecode.fcgi4j.message;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import com.googlecode.fcgi4j.exceptions.FCGIException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FCGIStdinTest {

    @Test
    void nullByteArrayThrows() {
        assertThrows(FCGIException.class, () -> new FCGIStdin((byte[]) null));
    }

    @Test
    void nullStringThrows() {
        assertThrows(FCGIException.class, () -> new FCGIStdin((String) null));
    }

    @Test
    void byteArrayConstructor() {
        byte[] data = {1, 2, 3};
        FCGIStdin stdin = new FCGIStdin(data);

        assertEquals(3, stdin.getLength());
    }

    @Test
    void stringConstructor() {
        FCGIStdin stdin = new FCGIStdin("hello");

        assertEquals(5, stdin.getLength());
    }

    @Test
    void getByteBuffersReturnsTwoBuffers() {
        FCGIStdin stdin = new FCGIStdin("data");
        ByteBuffer[] bufs = stdin.getByteBuffers();

        assertEquals(2, bufs.length);
    }

    @Test
    void firstBufferIsStdinHeader() {
        FCGIStdin stdin = new FCGIStdin("data");
        ByteBuffer headerBuf = stdin.getByteBuffers()[0];

        FCGIHeader header = FCGIHeader.parse(headerBuf);
        assertEquals(FCGIHeaderType.FCGI_STDIN, header.getType());
        assertEquals(4, header.getLength());
    }

    @Test
    void secondBufferContainsData() {
        byte[] data = {10, 20, 30};
        FCGIStdin stdin = new FCGIStdin(data);
        ByteBuffer bodyBuf = stdin.getByteBuffers()[1];

        assertEquals(3, bodyBuf.remaining());
        assertEquals(10, bodyBuf.get());
        assertEquals(20, bodyBuf.get());
        assertEquals(30, bodyBuf.get());
    }

    @Test
    void stringDataIsPreserved() {
        FCGIStdin stdin = new FCGIStdin("test");
        ByteBuffer bodyBuf = stdin.getByteBuffers()[1];

        byte[] bytes = new byte[bodyBuf.remaining()];
        bodyBuf.get(bytes);
        assertEquals("test", new String(bytes));
    }

    @Test
    void nullSentinelHasZeroLength() {
        assertEquals(0, FCGIStdin.NULL.getLength());
    }

    @Test
    void nullSentinelProducesOneBuffer() {
        ByteBuffer[] bufs = FCGIStdin.NULL.getByteBuffers();
        assertEquals(1, bufs.length);
    }

    @Test
    void nullSentinelHeaderHasZeroLength() {
        ByteBuffer headerBuf = FCGIStdin.NULL.getByteBuffers()[0];
        FCGIHeader header = FCGIHeader.parse(headerBuf);

        assertEquals(FCGIHeaderType.FCGI_STDIN, header.getType());
        assertEquals(0, header.getLength());
    }

    @Test
    void emptyByteArrayIsValid() {
        FCGIStdin stdin = new FCGIStdin(new byte[0]);
        assertEquals(0, stdin.getLength());
        assertEquals(2, stdin.getByteBuffers().length);
    }
}
