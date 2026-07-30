package com.example.iml.orchestrator.integration.bootstrap.service.api;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Общая база bootstrap-сервисов: logger и повторяющееся открытие optional JSON-store.
 */
public abstract class AbstractBootstrapService {

    protected final Logger log;

    protected AbstractBootstrapService(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Открывает persisted store; при ошибке логирует warn и возвращает {@code null}.
     */
    protected final <T> T openOptionalStore(
            Path storagePath,
            String storeLabel,
            StoreOpener<T> opener
    ) {
        try {
            return opener.open(storagePath);
        } catch (IOException e) {
            log.warn("{} unavailable path={}: {}", storeLabel, storagePath.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * То же, что {@link #openOptionalStore(Path, String, StoreOpener)}, но путь собирается из projectRoot.
     */
    protected final <T> T openOptionalStoreUnderProject(
            Path projectRoot,
            String relativePath,
            String storeLabel,
            StoreOpener<T> opener
    ) {
        return openOptionalStore(projectRoot.resolve(relativePath), storeLabel, opener);
    }

    @FunctionalInterface
    public interface StoreOpener<T> {
        T open(Path path) throws IOException;
    }
}
