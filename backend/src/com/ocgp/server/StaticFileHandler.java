package com.ocgp.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class StaticFileHandler implements HttpHandler {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8"
    );

    private final Path baseDirectory;

    public StaticFileHandler(Path baseDirectory) {
        this.baseDirectory = baseDirectory.normalize().toAbsolutePath();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendNoContent(exchange);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            rawPath = "/index.html";
        }
        Path resolved = resolveSafePath(rawPath);
        if (Files.isDirectory(resolved)) {
            resolved = resolved.resolve("index.html");
        }
        if (!Files.exists(resolved)) {
            // serve index for SPA fallback
            Path fallback = baseDirectory.resolve("index.html");
            if (!Files.exists(fallback)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(fallback);
            HttpUtils.sendPlain(exchange, 200, body, CONTENT_TYPES.get("html"));
            return;
        }
        String ext = getExtension(resolved.getFileName().toString());
        String contentType = CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
        byte[] body = Files.readAllBytes(resolved);
        HttpUtils.sendPlain(exchange, 200, body, contentType);
    }

    private Path resolveSafePath(String rawPath) {
        Path candidate = baseDirectory.resolve(rawPath.substring(1)).normalize();
        if (!candidate.startsWith(baseDirectory)) {
            throw new HttpStatusException(403, "Forbidden path");
        }
        return candidate;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot == -1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
