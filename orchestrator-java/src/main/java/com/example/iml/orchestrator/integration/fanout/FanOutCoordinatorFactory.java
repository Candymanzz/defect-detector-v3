package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsConfig;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMapLoader;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Factory wiring for {@link FanOutCoordinator#fromConfig}. */
final class FanOutCoordinatorFactory {

    private FanOutCoordinatorFactory() {
    }

    record Created(PlcFinsPublisher plcPublisher, PlcRegisterMap registerMap) {
    }

    static Created createPublisher(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer,
            Logger log
    ) {
        PlcFinsPublisher plcPublisher = null;
        PlcRegisterMap registerMap = null;
        PlcFinsConfig plcCfg = PlcFinsConfig.fromRoot(root, projectRoot);
        if (plcCfg.enabled()) {
            try {
                registerMap = PlcRegisterMapLoader.load(plcCfg.registerMapPath());
                plcPublisher = PlcFinsPublisher.create(log, plcCfg, registerMap);
                log.info(
                        "inspection result plc_fins enabled host={}:{} map={} pulse_ms={} timeouts={}",
                        plcCfg.host(),
                        plcCfg.port(),
                        plcCfg.registerMapPath(),
                        plcCfg.pulseMs(),
                        registerMap.timeouts().size()
                );
            } catch (IOException e) {
                throw new IllegalStateException("failed to start plc fins publisher", e);
            }
        } else {
            log.info("inspection result plc_fins disabled (plc_fins.enabled=false)");
            try {
                registerMap = PlcRegisterMapLoader.load(plcCfg.registerMapPath());
            } catch (IOException e) {
                log.debug("plc register map not loaded while disabled: {}", e.getMessage());
            }
        }
        log.info("inspection result plc: FINS only (ready sticky + reject lines + fault; no IO-box DO1-4)");
        if (clientWsServer == null) {
            log.warn("inspection result client_ws unavailable — bucket verdict will not be sent to UI");
        }
        return new Created(plcPublisher, registerMap);
    }

    static FanOutCoordinator create(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate,
            Logger log
    ) {
        Created created = createPublisher(root, projectRoot, clientWsServer, log);
        return FanOutCoordinator.create(created.plcPublisher(), clientWsServer, inspectionGate, created.registerMap());
    }
}
