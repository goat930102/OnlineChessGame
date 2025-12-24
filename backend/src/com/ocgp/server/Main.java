package com.ocgp.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_WS_PORT = 8091;

    public static void main(String[] args) throws IOException {
        initLogging();
        int port = resolvePort();
        DataStore dataStore = new DataStore();
        int wsPort = resolveWsPort();
        WebSocketHub wsHub = new WebSocketHub(wsPort, dataStore);
        dataStore.setWebSocketHub(wsHub);
        wsHub.start();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        Path staticDir = resolveStaticPath();
        server.createContext("/api", new ApiHandler(dataStore, wsHub));
        server.createContext("/", new StaticFileHandler(staticDir));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("OCGP server started on port %d%n", port);
        System.out.printf("Serving static assets from %s%n", staticDir);
        System.out.printf("WebSocket server started on port %d%n", wsPort);
        addShutdownHook(server, dataStore, wsHub);
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

    private static void addShutdownHook(HttpServer server, DataStore dataStore, WebSocketHub wsHub) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            try {
                server.stop((int) Duration.ofSeconds(2).toSeconds());
                try {
                    wsHub.stop(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                dataStore.close();
            }
        }));
    }

    private static void initLogging() {
        try {
            Path logDir = Path.of("backend", "out", "logs").toAbsolutePath().normalize();
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("ocgp.log");

            Logger root = Logger.getLogger("");
            for (Handler handler : root.getHandlers()) {
                root.removeHandler(handler);
            }
            FileHandler fh = new FileHandler(logFile.toString(), true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);

            root.addHandler(fh);
            root.setLevel(Level.INFO);
            System.out.printf("Logging to %s%n", logFile);
        } catch (IOException e) {
            System.err.printf("Failed to initialize logging: %s%n", e.getMessage());
        }
    }

    private static int resolveWsPort() {
        String env = System.getenv("OCGP_WS_PORT");
        if (env == null || env.isBlank()) {
            return DEFAULT_WS_PORT;
        }
        try {
            return Integer.parseInt(env.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_WS_PORT;
        }
    }
}
