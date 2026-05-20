package com.example.iml.orchestrator.integration.clientws.service;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Рассылка команд синхронизации в пул analisSurface (HTTP binary RPC).
 */
public final class ClientWsKopcheniBroadcaster {

    private final Logger log;
    private final ClientWsConfig cfg;
    private final AtomicReference<List<? extends BinaryRpcSupervisor>> poolRef;

    public ClientWsKopcheniBroadcaster(Logger log, ClientWsConfig cfg) {
        this.log = log;
        this.cfg = cfg;
        this.poolRef = new AtomicReference<>(List.of());
    }

    public void setPool(List<? extends BinaryRpcSupervisor> pool) {
        poolRef.set(pool == null ? List.of() : List.copyOf(pool));
    }

    public List<? extends BinaryRpcSupervisor> pool() {
        return poolRef.get();
    }

    public void broadcast(Map<String, Object> header) throws ClientWsKopcheniSyncException {
        if (!cfg.kopcheniBundleSyncEnabled()) {
            log.debug("client_ws skip kopcheni sync (kopcheni_bundle_sync_enabled=false) op={}", header.get("op"));
            return;
        }
        List<? extends BinaryRpcSupervisor> pool = poolRef.get();
        if (pool.isEmpty()) {
            return;
        }
        ClientWsKopcheniSyncException last = null;
        for (BinaryRpcSupervisor py : pool) {
            try {
                py.command(header);
            } catch (IOException e) {
                last = new ClientWsKopcheniSyncException(
                        "kopcheni command failed worker=" + py.supervisorLabel() + ": " + e.getMessage(),
                        e
                );
                log.warn("client_ws kopcheni command failed worker={}: {}", py.supervisorLabel(), e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
    }
}
