package com.googlecode.fcgi4j;

import com.googlecode.fcgi4j.constant.FCGIHeaderType;
import com.googlecode.fcgi4j.constant.FCGIProtocolStatus;
import com.googlecode.fcgi4j.exceptions.FCGIException;
import com.googlecode.fcgi4j.exceptions.FCGIInvalidHeaderException;
import com.googlecode.fcgi4j.exceptions.FCGIUnKnownHeaderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FCGIConnection using a mock FastCGI server.
 * <p>
 * These tests validate the fixes for issue #6 (truncated responses)
 * and issue #7 (connection reset / STDERR handling), as well as
 * documenting known bugs (A, B, C) with @Disabled tests.
 */
class FCGIConnectionTest {

    private ServerSocketChannel serverChannel;
    private int port;
    private FCGIConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        connection = FCGIConnection.open();
        connection.connect(new InetSocketAddress("127.0.0.1", port));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
    }

    // ---- Helper methods to build FCGI protocol frames ----

    /**
     * Build an 8-byte FCGI header.
     */
    private static ByteBuffer buildHeader(FCGIHeaderType type, int contentLength, int padding) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 1); // version
        buf.put((byte) type.getId());
        buf.putShort((short) 1); // request ID
        buf.putShort((short) contentLength);
        buf.put((byte) padding);
        buf.put((byte) 0); // reserved
        buf.flip();
        return buf;
    }

    /**
     * Build an FCGI_END_REQUEST body (8 bytes): 4 bytes appStatus + 1 byte protocolStatus + 3 reserved.
     */
    private static ByteBuffer buildEndRequestBody(int appStatus, FCGIProtocolStatus protocolStatus) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(appStatus);
        buf.put((byte) protocolStatus.getId());
        buf.put(new byte[3]);
        buf.flip();
        return buf;
    }

    /**
     * Build HTTP response headers string in the format expected by readResponseHeaders().
     * Terminates with \r\n\r\n.
     */
    private static String httpHeaders(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            sb.append(keyValues[i]).append(": ").append(keyValues[i + 1]).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * Consume from the client socket (reads BeginRequest + Params + Stdin).
     * This is needed because the server must read the client's request before
     * sending a response — otherwise the client's flushParams/flushStdins
     * may block.
     */
    private static void consumeClientRequest(SocketChannel client) throws IOException {
        ByteBuffer drain = ByteBuffer.allocate(8192);
        // Non-blocking read to drain whatever the client sent
        client.configureBlocking(false);
        while (client.read(drain) > 0) {
            drain.clear();
        }
        client.configureBlocking(true);
    }

    /**
     * Write all buffers fully to the channel.
     */
    private static void writeAll(SocketChannel channel, ByteBuffer... buffers) throws IOException {
        for (ByteBuffer buf : buffers) {
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        }
    }

    /**
     * Start beginRequest on the connection (needed before any read).
     */
    private void beginRequest() throws IOException {
        connection.beginRequest("/test.php");
    }

    // ---- Issue #6: Truncated response regression tests ----

    @Test
    void singleStdoutFrameLargerThanReadBuffer() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        // Body larger than a small read buffer
        byte[] body = new byte[500];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('A' + (i % 26));
        }

        byte[] fullContent = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, fullContent, 0, headers.getBytes().length);
        System.arraycopy(body, 0, fullContent, headers.getBytes().length, body.length);

        // Send STDOUT frame with all content
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, fullContent.length, 0),
                ByteBuffer.wrap(fullContent));

        // Send empty STDOUT (end of stdout)
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));

        // Send END_REQUEST
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Read with a small buffer to force multiple reads
        ByteBuffer readBuf = ByteBuffer.allocate(100);
        ByteBuffer allData = ByteBuffer.allocate(body.length + 1024);

        while (!connection.isRequestEnded()) {
            readBuf.clear();
            int n = connection.read(readBuf);
            readBuf.flip();
            if (n > 0) {
                allData.put(readBuf);
            }
        }

        allData.flip();
        byte[] result = new byte[allData.remaining()];
        allData.get(result);

        // Verify all body data was read (no truncation)
        assertEquals(body.length, result.length,
                "All body bytes must be read without truncation (issue #6)");
        for (int i = 0; i < body.length; i++) {
            assertEquals(body[i], result[i], "Byte mismatch at position " + i);
        }

        client.close();
    }

    @Test
    void responseSpanningMultipleStdoutFrames() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("X-Test", "multi-frame");
        byte[] headerBytes = headers.getBytes();

        // Frame 1: HTTP headers + first part of body
        byte[] bodyPart1 = "FIRST_CHUNK_".getBytes();
        byte[] frame1Content = new byte[headerBytes.length + bodyPart1.length];
        System.arraycopy(headerBytes, 0, frame1Content, 0, headerBytes.length);
        System.arraycopy(bodyPart1, 0, frame1Content, headerBytes.length, bodyPart1.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, frame1Content.length, 0),
                ByteBuffer.wrap(frame1Content));

        // Frame 2: second part of body
        byte[] bodyPart2 = "SECOND_CHUNK_".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, bodyPart2.length, 0),
                ByteBuffer.wrap(bodyPart2));

        // Frame 3: third part of body
        byte[] bodyPart3 = "THIRD_CHUNK".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, bodyPart3.length, 0),
                ByteBuffer.wrap(bodyPart3));

        // End of stdout + END_REQUEST
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Read all data
        ByteBuffer readBuf = ByteBuffer.allocate(256);
        StringBuilder fullBody = new StringBuilder();

        while (!connection.isRequestEnded()) {
            readBuf.clear();
            int n = connection.read(readBuf);
            if (n > 0) {
                readBuf.flip();
                byte[] bytes = new byte[readBuf.remaining()];
                readBuf.get(bytes);
                fullBody.append(new String(bytes));
            }
        }

        String expected = "FIRST_CHUNK_SECOND_CHUNK_THIRD_CHUNK";
        assertEquals(expected, fullBody.toString(),
                "Response split across multiple STDOUT frames must be fully reassembled (issue #6)");

        client.close();
    }

    @Test
    void largeResponseWithSmallReadBuffer() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        // 10 KB body
        byte[] body = new byte[10 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0xff);
        }

        byte[] frame1 = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, frame1, 0, headers.getBytes().length);
        System.arraycopy(body, 0, frame1, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, frame1.length, 0),
                ByteBuffer.wrap(frame1));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Read with tiny 64-byte buffer
        ByteBuffer readBuf = ByteBuffer.allocate(64);
        ByteBuffer allData = ByteBuffer.allocate(body.length + 1024);

        while (!connection.isRequestEnded()) {
            readBuf.clear();
            int n = connection.read(readBuf);
            readBuf.flip();
            if (n > 0) {
                allData.put(readBuf);
            }
        }

        allData.flip();
        assertEquals(body.length, allData.remaining(),
                "Large response must not lose data with small read buffer (issue #6)");

        client.close();
    }

    // ---- Issue #7: STDERR handling regression tests ----

    @Test
    void stderrBeforeStdoutSetsStderrFlag() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        // Send STDERR first
        byte[] stderrData = "PHP Warning: something\n".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDERR, stderrData.length, 0),
                ByteBuffer.wrap(stderrData));

        // Then send STDOUT with headers + body
        String headers = httpHeaders("Content-Type", "text/html");
        byte[] body = "OK".getBytes();
        byte[] stdoutContent = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, stdoutContent, 0, headers.getBytes().length);
        System.arraycopy(body, 0, stdoutContent, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, stdoutContent.length, 0),
                ByteBuffer.wrap(stdoutContent));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // getResponseHeaders() triggers readyRead() which processes the first FCGI frame.
        // When STDERR arrives first, readyRead() buffers it and sets cleanStdErr=false,
        // but does NOT continue to read the STDOUT frame (that's Bug B, tested separately).
        connection.getResponseHeaders();

        assertTrue(connection.hasOutputOnStdErr(),
                "hasOutputOnStdErr() must be true when STDERR arrives first (issue #7)");

        client.close();
    }

    @Test
    void basicRequestLifecycle() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/html", "X-Custom", "test123");
        byte[] body = "Hello World".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Verify response headers
        assertEquals("text/html", connection.getResponseHeaders().get("Content-Type"));
        assertEquals("test123", connection.getResponseHeaders().get("X-Custom"));

        // Read body
        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();

        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("Hello World", new String(result));

        assertTrue(connection.isRequestEnded());
        assertEquals(FCGIProtocolStatus.FCGI_REQUEST_COMPLETE,
                connection.getEndRequest().getProtocolStatus());

        assertFalse(connection.hasOutputOnStdErr());

        client.close();
    }

    @Test
    void stdoutWithPaddingBytes() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "padded".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        int padding = 5;
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, padding),
                ByteBuffer.wrap(content),
                ByteBuffer.wrap(new byte[padding])); // padding bytes

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();

        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("padded", new String(result));

        client.close();
    }

    // ---- Write path tests (POST data) ----

    @Test
    void writePostDataAndReadResponse() throws IOException {
        connection.beginRequest("/post.php");
        connection.setRequestMethod("POST");
        connection.setContentType("application/x-www-form-urlencoded");

        byte[] postData = "hello=world&foo=bar".getBytes();
        connection.setContentLength(postData.length);
        connection.write(ByteBuffer.wrap(postData));

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/html");
        byte[] body = "POST OK".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        assertEquals("text/html", connection.getResponseHeaders().get("Content-Type"));

        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();
        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("POST OK", new String(result));

        client.close();
    }

    @Test
    void writeScatterBuffers() throws IOException {
        connection.beginRequest("/scatter-write.php");
        connection.setRequestMethod("POST");

        byte[] part1 = "part1".getBytes();
        byte[] part2 = "part2".getBytes();
        connection.setContentLength(part1.length + part2.length);

        ByteBuffer[] srcs = { ByteBuffer.wrap(part1), ByteBuffer.wrap(part2) };
        connection.write(srcs);

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "SCATTER OK".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.getResponseHeaders();
        connection.read(readBuf);
        readBuf.flip();
        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("SCATTER OK", new String(result));

        client.close();
    }

    // ---- Query string and beginRequest overloads ----

    @Test
    void beginRequestWithQueryString() throws IOException {
        connection.beginRequest("/search.php", "q=test&page=1");

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/html");
        byte[] body = "SEARCH".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        connection.getResponseHeaders();

        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();
        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("SEARCH", new String(result));

        client.close();
    }

    @Test
    void beginRequestWithQueryStringAndKeepAlive() throws IOException {
        connection.beginRequest("/index.php", "id=5", false);

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "OK".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        connection.getResponseHeaders();
        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();
        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("OK", new String(result));

        client.close();
    }

    // ---- Abort request ----

    @Test
    void abortRequestReadsUntilEnd() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "will be aborted".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        connection.abortRequest();

        assertTrue(connection.isRequestEnded());

        client.close();
    }

    // ---- Error guard tests ----

    @Test
    void addParamsBeforeBeginRequestThrows() {
        // connection is open but beginRequest() not called
        FCGIException ex = assertThrows(FCGIException.class,
                () -> connection.addParams("key", "value"));
        assertTrue(ex.getMessage().contains("beginRequest"));
    }

    @Test
    void addParamsAfterFlushThrows() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "x".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // getResponseHeaders triggers readyRead which flushes params
        connection.getResponseHeaders();

        FCGIException ex = assertThrows(FCGIException.class,
                () -> connection.addParams("late", "param"));
        assertTrue(ex.getMessage().contains("flushed"));

        client.close();
    }

    @Test
    void writeBeforeBeginRequestThrows() {
        assertThrows(FCGIException.class,
                () -> connection.write(ByteBuffer.wrap("data".getBytes())));
    }

    @Test
    void readAfterRequestEndedThrows() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "done".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Drain all data until request ends
        ByteBuffer buf = ByteBuffer.allocate(4096);
        while (!connection.isRequestEnded()) {
            buf.clear();
            connection.read(buf);
        }

        // Now reading again should throw
        assertThrows(FCGIException.class, () -> connection.read(ByteBuffer.allocate(64)));

        client.close();
    }

    @Test
    void writeAfterStdinFlushedThrows() throws IOException {
        connection.beginRequest("/test.php");
        connection.setRequestMethod("POST");
        connection.setContentLength(5);

        byte[] postData = "hello".getBytes();
        connection.write(ByteBuffer.wrap(postData));

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "ok".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));
        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // getResponseHeaders triggers readyRead which flushes stdins
        connection.getResponseHeaders();

        FCGIException ex = assertThrows(FCGIException.class,
                () -> connection.write(ByteBuffer.wrap("more".getBytes())));
        assertTrue(ex.getMessage().contains("flushed"));

        client.close();
    }

    // ---- Exception path tests ----

    @Test
    void unknownHeaderTypeThrows() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        // Send a header with an invalid type ID (99)
        ByteBuffer badHeader = ByteBuffer.allocate(8);
        badHeader.put((byte) 1);  // version
        badHeader.put((byte) 99); // invalid type
        badHeader.putShort((short) 1);
        badHeader.putShort((short) 0);
        badHeader.put((byte) 0);
        badHeader.put((byte) 0);
        badHeader.flip();

        writeAll(client, badHeader);

        assertThrows(FCGIUnKnownHeaderException.class,
                () -> connection.getResponseHeaders());

        client.close();
    }

    @Test
    void invalidHttpHeaderThrows() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        // Send STDOUT with truncated/malformed HTTP headers (no \r\n\r\n terminator)
        // This will cause BufferUnderflowException in readResponseHeaders -> FCGIInvalidHeaderException
        byte[] malformed = "Content-Type: text/html\r\nX-Foo: ba".getBytes();

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, malformed.length, 0),
                ByteBuffer.wrap(malformed));

        assertThrows(FCGIInvalidHeaderException.class,
                () -> connection.getResponseHeaders());

        client.close();
    }

    // ---- Close / isOpen tests ----

    @Test
    void closeMarksConnectionClosed() throws IOException {
        assertTrue(connection.isOpen());
        connection.close();
        assertFalse(connection.isOpen());
        connection = null; // prevent double-close in tearDown
    }

    // ---- Bug A/B/C: Known bugs documented as @Disabled tests ----

    @Test
    @Disabled("Known bug A: Scatter read read(ByteBuffer[]) doesn't handle FCGI_STDERR — " +
            "STDERR body is never consumed from socket, corrupting the stream on next readHeader()")
    void scatterReadWithStderrFrameConsumedCorrectly() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body1 = "part1".getBytes();
        byte[] frame1 = new byte[headers.getBytes().length + body1.length];
        System.arraycopy(headers.getBytes(), 0, frame1, 0, headers.getBytes().length);
        System.arraycopy(body1, 0, frame1, headers.getBytes().length, body1.length);

        // STDOUT frame 1
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, frame1.length, 0),
                ByteBuffer.wrap(frame1));

        // STDERR frame (bug A: scatter read doesn't consume this body)
        byte[] stderrData = "warning msg".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDERR, stderrData.length, 0),
                ByteBuffer.wrap(stderrData));

        // STDOUT frame 2
        byte[] body2 = "part2".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, body2.length, 0),
                ByteBuffer.wrap(body2));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Use scatter read
        ByteBuffer[] dsts = { ByteBuffer.allocate(512), ByteBuffer.allocate(512) };
        connection.read(dsts);

        dsts[0].flip();
        dsts[1].flip();
        byte[] all = new byte[dsts[0].remaining() + dsts[1].remaining()];
        dsts[0].get(all, 0, dsts[0].remaining());
        dsts[1].get(all, all.length - dsts[1].remaining(), dsts[1].remaining());

        String result = new String(all);
        assertTrue(result.contains("part1"), "First stdout chunk must be present");
        assertTrue(result.contains("part2"), "Second stdout chunk must be present after STDERR");
        assertFalse(result.contains("warning"), "STDERR data must NOT appear in stdout result");

        client.close();
    }

    @Test
    @Disabled("Known bug B: readyRead() doesn't handle STDERR-first sequence — " +
            "when first frame is STDERR, subsequent STDOUT with HTTP headers is never parsed, " +
            "responseHeaders stays empty")
    void stderrFirstThenStdoutParsesResponseHeaders() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        // STDERR first
        byte[] stderrData = "PHP Notice: something\n".getBytes();
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDERR, stderrData.length, 0),
                ByteBuffer.wrap(stderrData));

        // STDOUT with HTTP response headers + body
        String headers = httpHeaders("Content-Type", "text/html", "X-Status", "ok");
        byte[] body = "response body".getBytes();
        byte[] stdoutContent = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, stdoutContent, 0, headers.getBytes().length);
        System.arraycopy(body, 0, stdoutContent, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, stdoutContent.length, 0),
                ByteBuffer.wrap(stdoutContent));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Bug B: responseHeaders should be parsed from STDOUT, not from STDERR data
        assertFalse(connection.getResponseHeaders().isEmpty(),
                "Response headers must be parsed from STDOUT even when STDERR arrives first");
        assertEquals("text/html", connection.getResponseHeaders().get("Content-Type"));
        assertEquals("ok", connection.getResponseHeaders().get("X-Status"));

        // Read body and verify it doesn't contain stderr data
        ByteBuffer readBuf = ByteBuffer.allocate(1024);
        connection.read(readBuf);
        readBuf.flip();
        byte[] result = new byte[readBuf.remaining()];
        readBuf.get(result);
        assertEquals("response body", new String(result),
                "Body must contain only STDOUT data, not STDERR data");

        client.close();
    }

    @Test
    @Disabled("Known bug C: Scatter read silently drops non-STDOUT frames — " +
            "break outer fires for any non-STDOUT type without consuming the frame body, " +
            "leaving data on the socket")
    void scatterReadWithEndRequestConsumedCorrectly() throws IOException {
        beginRequest();

        SocketChannel client = serverChannel.accept();
        consumeClientRequest(client);

        String headers = httpHeaders("Content-Type", "text/plain");
        byte[] body = "scatter-data".getBytes();
        byte[] content = new byte[headers.getBytes().length + body.length];
        System.arraycopy(headers.getBytes(), 0, content, 0, headers.getBytes().length);
        System.arraycopy(body, 0, content, headers.getBytes().length, body.length);

        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_STDOUT, content.length, 0),
                ByteBuffer.wrap(content));

        writeAll(client, buildHeader(FCGIHeaderType.FCGI_STDOUT, 0, 0));
        writeAll(client,
                buildHeader(FCGIHeaderType.FCGI_END_REQUEST, 8, 0),
                buildEndRequestBody(0, FCGIProtocolStatus.FCGI_REQUEST_COMPLETE));

        // Use scatter read — should properly handle END_REQUEST
        ByteBuffer[] dsts = { ByteBuffer.allocate(512) };
        connection.read(dsts);

        assertTrue(connection.isRequestEnded(),
                "Scatter read must process FCGI_END_REQUEST and mark request as ended");
        assertEquals(FCGIProtocolStatus.FCGI_REQUEST_COMPLETE,
                connection.getEndRequest().getProtocolStatus());

        client.close();
    }
}
