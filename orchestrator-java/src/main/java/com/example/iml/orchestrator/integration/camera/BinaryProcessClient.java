package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Клиент бинарного протокола поверх stdin/stdout дочернего процесса. */
public final class BinaryProcessClient implements BinaryClient {

    private static final long DESTROY_WAIT_MS = 3_000L;

    private final Process process;
    private final DataInputStream in;
    private final DataOutputStream out;

    public BinaryProcessClient(List<String> command, Path workingDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();
        this.in = new DataInputStream(process.getInputStream());
        this.out = new DataOutputStream(process.getOutputStream());
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        return command(header, new byte[0]);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException {
        BinaryProtocol.write(out, BinaryProtocol.MSG_COMMAND, header, payload);
        return BinaryProtocol.read(in);
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        // Close pipes first so the child can exit cleanly; then destroy and reap.
        // Over multi-day runs, restart without closing streams leaks FDs in the parent.
        try {
            out.close();
        } catch (Exception ignored) {
        }
        try {
            in.close();
        } catch (Exception ignored) {
        }
        try {
            process.destroy();
            if (!process.waitFor(DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(DESTROY_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
            try {
                process.destroyForcibly();
            } catch (Exception ignored2) {
            }
        }
    }
}
