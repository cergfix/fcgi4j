package com.googlecode.fcgi4j.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class IoUtilsTest {

    @Test
    void safePutCopiesAllWhenDstHasEnoughRoom() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3});
        ByteBuffer dst = ByteBuffer.allocate(10);

        IoUtils.safePut(dst, src);

        assertEquals(3, dst.position());
        assertFalse(src.hasRemaining());
    }

    @Test
    void safePutCopiesPartialWhenDstIsTooSmall() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBuffer dst = ByteBuffer.allocate(3);

        IoUtils.safePut(dst, src);

        assertEquals(3, dst.position());
        assertEquals(2, src.remaining()); // 2 bytes left in src
    }

    @Test
    void safePutCopiesExactFit() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{10, 20, 30});
        ByteBuffer dst = ByteBuffer.allocate(3);

        IoUtils.safePut(dst, src);

        assertEquals(3, dst.position());
        assertFalse(src.hasRemaining());
    }

    @Test
    void safePutRestoresSourceLimit() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        int originalLimit = src.limit();

        ByteBuffer dst = ByteBuffer.allocate(2);
        IoUtils.safePut(dst, src);

        assertEquals(originalLimit, src.limit(), "Source limit must be restored after partial copy");
    }

    @Test
    void safePutReturnsDstRemaining() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3});
        ByteBuffer dst = ByteBuffer.allocate(10);

        int result = IoUtils.safePut(dst, src);
        assertEquals(10, result); // dst.remaining() was 10 before the put
    }

    @Test
    void safePutPreservesDataIntegrity() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{10, 20, 30, 40, 50});
        ByteBuffer dst = ByteBuffer.allocate(3);

        IoUtils.safePut(dst, src);
        dst.flip();

        assertEquals(10, dst.get());
        assertEquals(20, dst.get());
        assertEquals(30, dst.get());

        // Continue reading remaining from src
        assertEquals(40, src.get());
        assertEquals(50, src.get());
    }

    @Test
    void safePutWithEmptySource() {
        ByteBuffer src = ByteBuffer.wrap(new byte[0]);
        ByteBuffer dst = ByteBuffer.allocate(10);

        IoUtils.safePut(dst, src);

        assertEquals(0, dst.position());
    }
}
