package com.example.iml.orchestrator.integration.ui.artifacts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.apache.logging.log4j.Logger;

/** Temp file / frozen-frame cleanup helpers for UI artifact publish. */
public final class UiArtifactFiles {

    private final Logger log;

    public UiArtifactFiles(Logger log) {
        this.log = log;
    }

    public void deleteTemporaryArtifact(Path path, String label) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("{} cleanup failed path={}: {}", label, path, e.getMessage());
        }
    }

    public void deleteFrozenFrameIfOwned(FrozenFrame frozenFrame, String label) {
        if (frozenFrame == null || !frozenFrame.deleteWhenDone()) {
            return;
        }
        deleteTemporaryArtifact(frozenFrame.path(), label);
    }
}
