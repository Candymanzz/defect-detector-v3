package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Command / URL / health helpers for {@link AnalisSurfaceLauncher}. */
final class AnalisSurfaceLaunchSupport {

    static final String DEFAULT_BACKEND_DIR = "analisSurface/backend";
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 8000;

    private AnalisSurfaceLaunchSupport() {
    }

    record ParsedBase(String host, int port) {
    }

    static List<String> buildBaseUrlPool(String pythonDetectorBaseUrl, int poolSize) {
        int size = Math.max(1, poolSize);
        ParsedBase parsed = parseDetectorBaseUrl(pythonDetectorBaseUrl);
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(formatBaseUrl(parsed.host(), parsed.port() + i));
        }
        return List.copyOf(out);
    }

    static List<String> withPort(List<String> command, int port) {
        List<String> out = new ArrayList<>(command.size() + 2);
        for (int i = 0; i < command.size(); i++) {
            if ("--port".equals(command.get(i)) && i + 1 < command.size()) {
                out.add("--port");
                out.add(String.valueOf(port));
                i++;
                continue;
            }
            out.add(command.get(i));
        }
        if (!out.contains("--port")) {
            out.add("--port");
            out.add(String.valueOf(port));
        }
        return List.copyOf(out);
    }

    static ParsedBase parseDetectorBaseUrl(String pythonDetectorBaseUrl) {
        String base = pythonDetectorBaseUrl == null || pythonDetectorBaseUrl.isBlank()
                ? "http://" + DEFAULT_HOST + ":" + DEFAULT_PORT
                : pythonDetectorBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank()) {
                host = DEFAULT_HOST;
            }
            if (port <= 0) {
                port = DEFAULT_PORT;
            }
            return new ParsedBase(host, port);
        } catch (Exception ignored) {
            return new ParsedBase(DEFAULT_HOST, DEFAULT_PORT);
        }
    }

    static String formatBaseUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }

    static List<String> defaultCommand(Path projectRoot, boolean isWindows) {
        Path backend = projectRoot.resolve(DEFAULT_BACKEND_DIR);
        Path venvPython = isWindows
                ? backend.resolve(".venv/Scripts/python.exe")
                : backend.resolve(".venv/bin/python");
        if (Files.isRegularFile(venvPython)) {
            return List.of(
                    venvPython.toAbsolutePath().toString(),
                    "-m",
                    "uvicorn",
                    "app.main:app",
                    "--host",
                    DEFAULT_HOST,
                    "--port",
                    String.valueOf(DEFAULT_PORT)
            );
        }
        return List.of(
                isWindows ? "python" : "python3",
                "-m",
                "uvicorn",
                "app.main:app",
                "--host",
                DEFAULT_HOST,
                "--port",
                String.valueOf(DEFAULT_PORT)
        );
    }

    static List<String> resolveCommandPaths(List<String> command, Path projectRoot) {
        if (command.isEmpty()) {
            return command;
        }
        List<String> out = new ArrayList<>(command.size());
        String first = command.get(0);
        Path p = Path.of(first);
        if (!p.isAbsolute() && (first.contains("/") || first.contains("\\"))) {
            out.add(projectRoot.resolve(first).normalize().toString());
        } else {
            out.add(first);
        }
        for (int i = 1; i < command.size(); i++) {
            out.add(command.get(i));
        }
        return out;
    }

    static void waitForHealth(String healthUrl, int timeoutMs, ExternalServiceProcess process)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("analisSurface process exited before health check OK");
            }
            try {
                HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("analisSurface health timeout: " + healthUrl);
    }
}
