package com.taskflow.assistant.llm;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class FakeUpstreamServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private volatile Reply reply = new Reply(200, Map.of(), "{}", 0);

    FakeUpstreamServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply current = reply;
            sleep(current.delayMillis());
            current.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(current.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    void respond(int status, String body) {
        reply = new Reply(status, Map.of(), body, 0);
    }

    void respond(int status, Map<String, String> headers, String body) {
        reply = new Reply(status, headers, body, 0);
    }

    void respondAfter(long delayMillis, int status, String body) {
        reply = new Reply(status, Map.of(), body, delayMillis);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    int hits() {
        return hits.get();
    }

    String lastPath() {
        return lastPath.get();
    }

    String lastBody() {
        return lastBody.get();
    }

    String lastApiKey() {
        return lastApiKey.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Reply(int status, Map<String, String> headers, String body, long delayMillis) {}
}
