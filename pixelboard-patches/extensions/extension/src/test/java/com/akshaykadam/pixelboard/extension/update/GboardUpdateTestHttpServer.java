package com.akshaykadam.pixelboard.extension.update;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 server for tests. Unit tests compile against android.jar, which has no
 * {@code com.sun.net.httpserver}. One response is configured at a time; every request is
 * recorded as {@code "<path> header=value ..."} for the headers named in {@link #recordedHeaders}.
 */
final class GboardUpdateTestHttpServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final Thread thread;
    private final List<String> requests = new ArrayList<String>();
    private final String[] recordedHeaders;
    private volatile int code = 200;
    private volatile byte[] body = new byte[0];
    private final Map<String, String> headers = new LinkedHashMap<String, String>();

    GboardUpdateTestHttpServer(String... recordedHeaders) throws IOException {
        this.recordedHeaders = recordedHeaders;
        serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::serve, "GboardUpdateTestHttpServer");
        thread.setDaemon(true);
        thread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    synchronized void respond(int code, byte[] body, Map<String, String> headers) {
        this.code = code;
        this.body = body == null ? new byte[0] : body;
        this.headers.clear();
        if (headers != null) {
            this.headers.putAll(headers);
        }
    }

    List<String> requests() {
        synchronized (requests) {
            return new ArrayList<String>(requests);
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    private void serve() {
        while (!serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept()) {
                handle(socket);
            } catch (IOException ignored) {
                // Closed by the test.
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return;
        }
        Map<String, String> requestHeaders = new LinkedHashMap<String, String>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                requestHeaders.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        StringBuilder record = new StringBuilder(requestLine.split(" ")[1]);
        for (String name : recordedHeaders) {
            record.append(' ').append(name).append('=')
                    .append(requestHeaders.get(name.toLowerCase(Locale.US)));
        }
        synchronized (requests) {
            requests.add(record.toString());
        }

        int responseCode;
        byte[] responseBody;
        Map<String, String> responseHeaders;
        synchronized (this) {
            responseCode = code;
            responseBody = responseCode == 304 ? new byte[0] : body;
            responseHeaders = new LinkedHashMap<String, String>(headers);
        }
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(responseCode).append(" X\r\n");
        for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        if (responseCode != 304) {
            head.append("Content-Length: ").append(responseBody.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(responseBody);
        output.flush();
    }
}
