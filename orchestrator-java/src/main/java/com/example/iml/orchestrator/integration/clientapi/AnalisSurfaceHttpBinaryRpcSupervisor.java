package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.analissurface.AnalisSurfaceHttpTransport;
import com.example.iml.orchestrator.integration.clientapi.analissurface.ClientReferenceBundleSync;
import com.example.iml.orchestrator.integration.clientapi.analissurface.FpZonesHttpOps;
import com.example.iml.orchestrator.integration.clientapi.analissurface.InspectShmHttpOps;
import com.example.iml.orchestrator.integration.clientapi.analissurface.ReferenceRoiSignatureCache;
import com.example.iml.orchestrator.integration.clientapi.analissurface.ShmFramePayloadMapper;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Вызовы детектора FastAPI analisSurface по HTTP: те же {@code op}, что ожидает пайплайн,
 * ответы приводятся к {@link BinaryProtocol.Message} для совместимости с решением и телеметрией.
 * Domain HTTP ops live in {@code clientapi.analissurface.*}.
 */
public final class AnalisSurfaceHttpBinaryRpcSupervisor implements BinaryRpcSupervisor {

    private static final Logger LOG = LogManager.getLogger(AnalisSurfaceHttpBinaryRpcSupervisor.class);

    private final String name;
    private final String baseUrl;
    private final AnalisSurfaceHttpTransport http;
    private final ReferenceRoiSignatureCache cache;
    private final InspectShmHttpOps inspectOps;
    private final FpZonesHttpOps fpZones;
    private final ClientReferenceBundleSync bundleSync;
    private int restartCount;

    public AnalisSurfaceHttpBinaryRpcSupervisor(String name, String baseUrl, int commandTimeoutMs) {
        this.name = Objects.requireNonNull(name);
        String u = Objects.requireNonNull(baseUrl, "baseUrl").trim();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        int timeoutMs = Math.max(100, commandTimeoutMs);
        this.http = new AnalisSurfaceHttpTransport(this.name, this.baseUrl, timeoutMs);
        this.cache = new ReferenceRoiSignatureCache(http.mapper());
        ShmFramePayloadMapper payloads = new ShmFramePayloadMapper(this.name);
        this.inspectOps = new InspectShmHttpOps(http, cache, payloads);
        this.fpZones = new FpZonesHttpOps(http);
        this.bundleSync = new ClientReferenceBundleSync(inspectOps, fpZones);
    }

    @Override
    public String supervisorLabel() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public int restartCount() {
        return restartCount;
    }

    @Override
    public void start() throws IOException {
        health();
    }

    @Override
    public void restart() throws IOException {
        restartCount++;
        start();
    }

    @Override
    public void close() {
        // нет локального процесса
    }

    @Override
    public BinaryProtocol.Message health() throws IOException {
        IOException last = null;
        for (String path : List.of("/detector/health", "/health")) {
            try {
                HttpResponse<byte[]> resp = http.httpGetRaw(path);
                if (resp.statusCode() / 100 == 2) {
                    Map<String, Object> h = AnalisSurfaceHttpTransport.readJson(resp.body());
                    return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("health: no path succeeded") : last;
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        try {
            return commandNoRetry(header);
        } catch (IOException first) {
            LOG.warn("{} command failed; retry once: {}", name, first.getMessage());
            restart();
            return commandNoRetry(header);
        }
    }

    @Override
    public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
        String op = String.valueOf(header.getOrDefault("op", ""));
        return switch (op) {
            case "stop" -> new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "analis-surface-http"),
                    new byte[0]
            );
            case "health" -> health();
            case "set_reference_shm" -> inspectOps.uploadRefShm(header);
            case "inspect_shm" -> inspectOps.inspectShm(header);
            case "replace_fp_zones" -> fpZones.replaceFpZones(header);
            case "sync_client_reference_bundle" -> bundleSync.syncClientReferenceBundle(header);
            case "set_active_reference_view" -> setActiveReferenceView(header);
            case "clear_inspection_context" -> clearInspectionContext();
            default -> new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "unknown op=" + op + " (http transport)", "op", op),
                    new byte[0]
            );
        };
    }

    private BinaryProtocol.Message setActiveReferenceView(Map<String, Object> header) {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", String.valueOf(header.getOrDefault("product_type", "")));
        ok.put("view_index", YamlScalars.toInt(header.get("view_index"), 0));
        ok.put("transport", "http");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    private BinaryProtocol.Message clearInspectionContext() throws IOException {
        cache.clearAll();
        HttpResponse<byte[]> resp = http.httpPostJson("/clear-inspection-context", Map.of());
        if (resp.statusCode() / 100 != 2) {
            return AnalisSurfaceHttpTransport.errorMessageToMsg(resp, "clear-inspection-context");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("op", "clear_inspection_context");
        ok.put("transport", "http");
        try {
            Map<String, Object> body = AnalisSurfaceHttpTransport.readJson(resp.body());
            if (body.get("cleared") != null) {
                ok.put("cleared", body.get("cleared"));
            }
        } catch (Exception ignored) {
            // response body optional
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    /** Delegates to {@link ShmFramePayloadMapper#logicalShmNameForHttp(String, int)}. */
    static String logicalShmNameForHttp(String shmName, int cameraId) {
        return ShmFramePayloadMapper.logicalShmNameForHttp(shmName, cameraId);
    }
}
