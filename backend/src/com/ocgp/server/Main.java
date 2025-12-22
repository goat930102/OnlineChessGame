package com.ocgp.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

public class Main {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        int port = resolvePort();
        DataStore dataStore = new DataStore();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        Path staticDir = resolveStaticPath();
        server.createContext("/api", new ApiHandler(dataStore));
        server.createContext("/", new StaticFileHandler(staticDir));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("OCGP server started on port %d%n", port);
        System.out.printf("Serving static assets from %s%n", staticDir);
        addShutdownHook(server, dataStore);
    }

    private static int resolvePort() {
        String env = System.getenv("OCGP_PORT");
        if (env == null || env.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(env.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_PORT;
        }
    }

    private static Path resolveStaticPath() {
        String override = System.getenv("OCGP_STATIC_DIR");
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path fromBackend = Path.of("frontend").toAbsolutePath().normalize();
        if (Files.exists(fromBackend)) {
            return fromBackend;
        }
        Path sibling = Path.of("..", "frontend").toAbsolutePath().normalize();
        if (Files.exists(sibling)) {
            return sibling;
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static void addShutdownHook(HttpServer server, DataStore dataStore) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            try {
                server.stop((int) Duration.ofSeconds(2).toSeconds());
            } finally {
                dataStore.close();
            }
        }));
    }
}
