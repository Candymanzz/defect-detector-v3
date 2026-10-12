package com.example.iml.orchestrator.integration.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** A single timestamped trace directory shared by the three inspection services. */
public final class ServiceTraceSession {

    public static final String ENV_NAME = "IML_TRACE_SESSION_DIR";
    private static final DateTimeFormatter DIRECTORY_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static volatile Path currentDirectory;

    private ServiceTraceSession() {
    }

    public static synchronized Path start(Path projectRoot) throws IOException {
        currentDirectory = null;
        Path parent = projectRoot.toAbsolutePath().normalize().resolve("logs").resolve("service-traces");
        Files.createDirectories(parent);
        OffsetDateTime startedAt = OffsetDateTime.now();
        String stamp = startedAt.format(DIRECTORY_STAMP);
        Path session = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = parent.resolve(stamp + (attempt == 0 ? "" : "-" + attempt));
            try {
                session = Files.createDirectory(candidate);
                break;
            } catch (FileAlreadyExistsException ignored) {
                // Another startup in the same millisecond gets a separate directory.
            }
        }
        if (session == null) {
            throw new IOException("Cannot allocate a unique service trace directory under " + parent);
        }

        Files.createDirectory(session.resolve("java-positioning"));
        Files.createDirectory(session.resolve("java-geometry"));
        Files.createDirectory(session.resolve("analisSurface"));
        Files.writeString(
                session.resolve("session.txt"),
                "started_at=" + startedAt + System.lineSeparator()
                        + "session_dir=" + session + System.lineSeparator()
                        + "services=java-positioning,java-geometry,analisSurface" + System.lineSeparator()
                        + "java_rpc=java-positioning/rpc-<pid>.jsonl,java-geometry/rpc-<pid>.jsonl" + System.lineSeparator()
                        + "python_http=analisSurface/worker-<pid>-<time>/http.jsonl" + System.lineSeparator()
                        + "pipeline_timing=pipeline-stages.jsonl" + System.lineSeparator()
                        + "binary_payloads=omitted; payload_bytes is recorded" + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        currentDirectory = session;
        return session;
    }

    public static void addToChildEnvironment(Map<String, String> environment) {
        Path session = currentDirectory;
        if (session != null) {
            environment.put(ENV_NAME, session.toString());
        }
    }

    public static Path currentDirectory() {
        return currentDirectory;
    }
}
