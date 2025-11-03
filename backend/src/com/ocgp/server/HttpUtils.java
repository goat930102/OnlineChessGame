package com.ocgp.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpUtils {

    private HttpUtils() {
    }

    public static void sendJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        sendJson(exchange, status, JsonUtil.stringify(payload));
    }

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type,X-Auth-Token");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public static void sendNoContent(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type,X-Auth-Token");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.sendResponseHeaders(204, -1);
    }

    public static void sendPlain(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
