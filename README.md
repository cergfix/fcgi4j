# FCGI4j

Connect to Fast CGI from your java application. With this library you may start PHP scripts with PHP-FPM. 

This is a fork from the [subversion repo][google] at revision r17. The project was not maintained any more and I could not get in
touch with the author. I decided to maintain this library myself. r17 was the last revision where the library worked
as expected.

## Usage example

```java

//create FastCGI connection
FCGIConnection connection = FCGIConnection.open();
connection.connect(new InetSocketAddress("localhost", 5672));

String requestMethod = "GET"
String targetScript = "/var/www/foobar.php"

connection.beginRequest(targetScript);
connection.setRequestMethod(requestMethod);
connection.setQueryString("querystring=1");

connection.addParams("DOCUMENT_ROOT", "/var/www/");
connection.addParams("SCRIPT_FILENAME", targetScript);
connection.addParams("SCRIPT_NAME", targetScript);
connection.addParams("GATEWAY_INTERFACE", "FastCGI/1.0");
connection.addParams("SERVER_PROTOCOL", "HTTP/1.1");
connection.addParams("CONTENT_TYPE", "application/x-www-form-urlencoded");

//add post data only if request method is GET
if(requestMethod.equalsIgnoreCase("POST")){
    byte[] postData = "hello=world".getBytes();

    //set contentLength, it's important
    connection.setContentLength(postData.length);
    connection.write(ByteBuffer.wrap(postData));
}

//print response headers
Map<String, String> responseHeaders = connection.getResponseHeaders();
for (String key : responseHeaders.keySet()) {
    System.out.println("HTTP HEADER: " + key + "->" + responseHeaders.get(key));
}

//read response data
ByteBuffer buffer = ByteBuffer.allocate(10240);
connection.read(buffer);
buffer.flip();

byte[] data = new byte[buffer.remaining()];
buffer.get(data);

System.out.println(new String(data));

//close the connection
connection.close();

```

## Known bugs

The following bugs exist in `FCGIConnection` and have failing tests:

- **Scatter read doesn't handle STDERR.** `read(ByteBuffer[])` only checks for `FCGI_STDOUT` ([line 440](src/main/java/com/googlecode/fcgi4j/FCGIConnection.java#L440)). If an `FCGI_STDERR` frame arrives during a scatter read, its body is never consumed from the socket, corrupting the stream on the next `readHeader()` call. ([test](src/test/java/com/googlecode/fcgi4j/FCGIConnectionTest.java#L776))

- **STDERR-first breaks response header parsing.** `readyRead()` only reads one header ([lines 520-536](src/main/java/com/googlecode/fcgi4j/FCGIConnection.java#L520)). If the first frame is `FCGI_STDERR`, the subsequent `FCGI_STDOUT` frame containing HTTP response headers is never parsed — `getResponseHeaders()` returns an empty map and the stderr data leaks into the response body. ([test](src/test/java/com/googlecode/fcgi4j/FCGIConnectionTest.java#L832))

- **Scatter read drops non-STDOUT frames.** `read(ByteBuffer[])` hits `break outer` ([line 452](src/main/java/com/googlecode/fcgi4j/FCGIConnection.java#L452)) for any non-`FCGI_STDOUT` frame type without consuming the frame body. For `FCGI_END_REQUEST`, the 8-byte body is left on the socket and `isRequestEnded()` stays false. ([test](src/test/java/com/googlecode/fcgi4j/FCGIConnectionTest.java#L882))

## Specification
This library implements the [FastCGI Specification](http://www.fastcgi.com/devkit/doc/fcgi-spec.html).

[google]:https://code.google.com/p/fcgi4j/
