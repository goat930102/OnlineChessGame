package com.ocgp.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.*;

public class Main {
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        initLogging();

        int port = resolvePort();
        DataStore dataStore = new DataStore();

        // WebSocket（跟 HTTP 共用同一個 port）
        //WebSocketHub wsHub = new WebSocketHub(port, dataStore);
        //dataStore.setWebSocketHub(wsHub);
        //wsHub.start();

        // HTTP Server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        Path staticDir = resolveStaticPath();

        server.createContext("/api", new ApiHandler(dataStore, null));
        server.createContext("/", new StaticFileHandler(staticDir));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("OCGP server started on port %d%n", port);
        System.out.printf("Serving static assets from %s%n", staticDir);
        System.out.printf("WebSocket server started on port %d%n", port);

        addShutdownHook(server, dataStore, wsHub);
    }

    private static int resolvePort() {
        String env = System.getenv("PORT"); // Render 用 PORT
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

        Path frontend = Path.of("frontend").toAbsolutePath().normalize();
        if (Files.exists(frontend)) {
            return frontend;
        }

        return Path.of(".").toAbsolutePath().normalize();
    }

    private static void addShutdownHook(HttpServer server, DataStore dataStore, WebSocketHub wsHub) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            try {
                server.stop((int) Duration.ofSeconds(2).toSeconds());
                /*try {
                    wsHub.stop(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }*/
            } finally {
                dataStore.close();
            }
        }));
    }

    private static void initLogging() {
        try {
            Path logDir = Path.of("out", "logs").toAbsolutePath().normalize();
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
        } catch (IOException e) {
            System.err.println("Failed to initialize logging: " + e.getMessage());
        }
    }
}
