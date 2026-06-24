package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.plc.PlcFinsConfig;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMapLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Публикация итога инспекции по ведру: ПЛК (FINS) и UI (WebSocket).
 */
public final class FanOutCoordinator implements AutoCloseable, BucketFanOutSink {
    private static final Logger log = LogManager.getLogger(FanOutCoordinator.class);

    private final PlcFinsPublisher plcPublisher;
    private final ClientWebSocketServer clientWsServer;

    private FanOutCoordinator(PlcFinsPublisher plcPublisher, ClientWebSocketServer clientWsServer) {
        this.plcPublisher = plcPublisher;
        this.clientWsServer = clientWsServer;
    }

    public static FanOutCoordinator fromConfig(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer
    ) {
        PlcFinsPublisher plcPublisher = null;
        PlcFinsConfig plcCfg = PlcFinsConfig.fromRoot(root, projectRoot);
        if (plcCfg.enabled()) {
            try {
                PlcRegisterMap registerMap = PlcRegisterMapLoader.load(plcCfg.registerMapPath());
                plcPublisher = PlcFinsPublisher.create(log, plcCfg, registerMap);
                log.info(
                        "inspection result plc_fins enabled host={}:{} map={} pulse_ms={}",
                        plcCfg.host(),
                        plcCfg.port(),
                        plcCfg.registerMapPath(),
                        plcCfg.pulseMs()
                );
            } catch (IOException e) {
                throw new IllegalStateException("failed to start plc fins publisher", e);
            }
        } else {
            log.info("inspection result plc_fins disabled (plc_fins.enabled=false)");
        }
        if (clientWsServer == null) {
            log.warn("inspection result client_ws unavailable — bucket verdict will not be sent to UI");
        }
        return new FanOutCoordinator(plcPublisher, clientWsServer);
    }

    @Override
    public void publishBucket(BucketFanOutResult result) {
        if (plcPublisher != null) {
            plcPublisher.publishBucket(result);
        }
        if (clientWsServer != null) {
            clientWsServer.notifyInspectBucketResult(result);
        }
    }

    public void signalVisionReady(boolean ready) {
        if (plcPublisher != null) {
            plcPublisher.setVisionReady(ready);
        }
    }

    public void signalVisionFault(boolean fault) {
        if (plcPublisher != null) {
            plcPublisher.setVisionFault(fault);
        }
    }

    public String metricsSummary() {
        String plcPart = plcPublisher == null
                ? "plc=disabled"
                : ("plc.dropped=" + plcPublisher.droppedTotal());
        return plcPart + " client_ws=" + (clientWsServer == null ? "disabled" : "enabled");
    }

    @Override
    public void close() {
        if (plcPublisher != null) {
            plcPublisher.close();
        }
    }
}
