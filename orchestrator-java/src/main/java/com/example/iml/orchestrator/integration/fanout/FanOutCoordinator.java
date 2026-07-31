package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.fanout.gate.InspectionGateQuery;
import com.example.iml.orchestrator.integration.fanout.notify.FanOutUiNotifier;
import com.example.iml.orchestrator.integration.fanout.plc.PlcSignalWritePath;
import com.example.iml.orchestrator.integration.fanout.plc.PlcTimeoutWritePath;
import com.example.iml.orchestrator.integration.fanout.plc.PlcVisionLevelController;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsApi;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcSignalState;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutDefinition;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Публикация итога инспекции по ведру: брак/ready/fault по FINS и UI (WebSocket).
 * Дискретные DO IoInputMonitor не используются — только DO5 для Line0 в мониторе.
 */
public final class FanOutCoordinator implements AutoCloseable, BucketFanOutSink, PlcFinsApi {
    private static final Logger log = LogManager.getLogger(FanOutCoordinator.class);

    private final PlcFinsPublisher plcPublisher;
    private final FanOutUiNotifier uiNotifier;
    private final InspectionGateQuery inspectionGateQuery;
    private final PlcVisionLevelController visionLevels;
    private final PlcSignalWritePath signalWritePath;
    private final PlcTimeoutWritePath timeoutWritePath;

    private FanOutCoordinator(
            PlcFinsPublisher plcPublisher,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate,
            PlcRegisterMap registerMap
    ) {
        PlcRegisterMap map = registerMap == null ? new PlcRegisterMap(Map.of()) : registerMap;
        this.plcPublisher = plcPublisher;
        this.uiNotifier = new FanOutUiNotifier(clientWsServer);
        this.inspectionGateQuery = new InspectionGateQuery(inspectionGate);
        this.visionLevels = new PlcVisionLevelController(log, plcPublisher);
        this.signalWritePath = new PlcSignalWritePath(log, plcPublisher, map);
        this.timeoutWritePath = new PlcTimeoutWritePath(plcPublisher, map);
        uiNotifier.bindPlcTraffic(plcPublisher);
    }

    static FanOutCoordinator create(
            PlcFinsPublisher plcPublisher,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate,
            PlcRegisterMap registerMap
    ) {
        return new FanOutCoordinator(plcPublisher, clientWsServer, inspectionGate, registerMap);
    }

    public static FanOutCoordinator fromConfig(
            Map<String, Object> root, Path projectRoot, ClientWebSocketServer clientWsServer) {
        return fromConfig(root, projectRoot, clientWsServer, null);
    }

    public static FanOutCoordinator fromConfig(
            Map<String, Object> root,
            Path projectRoot,
            ClientWebSocketServer clientWsServer,
            PerCameraInspectionGate inspectionGate
    ) {
        return FanOutCoordinatorFactory.create(root, projectRoot, clientWsServer, inspectionGate, log);
    }

    @Override
    public void publishBucket(BucketFanOutResult result) {
        // Эталон задан → FINS reject по линии ведра (group 0 → line1, group 1 → line2).
        if (inspectionEnabled()) {
            if (plcPublisher != null) {
                plcPublisher.publishBucket(result);
            }
        } else {
            log.debug(
                    "plc reject skip seq={} group={} — эталон не задан (capture-only)",
                    result.triggerSequence(),
                    result.groupId()
            );
        }
        uiNotifier.notifyBucket(result);
    }

    /** ready = эталон активен AND сервисы здоровы; fault = сервисы нездоровы. */
    public void onSessionState(ClientWsSessionState state) {
        visionLevels.onSessionState(state);
    }

    public void setHealthGate(ServiceHealthGate healthGate) {
        visionLevels.setHealthGate(healthGate);
    }

    /** Shutdown prep: vision_ready→0, vision_fault→1 sticky; FINS sync before exit. */
    public void enterShutdownPrep(String reason) {
        visionLevels.enterShutdownPrep(reason);
    }

    public boolean isShutdownPrepActive() {
        return visionLevels.isShutdownPrepActive();
    }

    /** Sticky vision_ready/fault from эталон + {@link ServiceHealthGate}; shutdown prep forces ready=0/fault=1. */
    public void refreshPlcLevels() {
        visionLevels.refreshPlcLevels();
    }

    public void signalVisionReady(boolean ready) {
        visionLevels.signalVisionReady(ready);
    }

    public void signalVisionFault(boolean fault) {
        visionLevels.signalVisionFault(fault);
    }

    @Override
    public boolean enabled() {
        return plcPublisher != null;
    }

    @Override
    public boolean inspectionInFlight() {
        return inspectionGateQuery.inspectionInFlight();
    }

    @Override
    public boolean inspectionEnabled() {
        return uiNotifier.inspectionEnabled();
    }

    @Override
    public boolean manualControlEditable() {
        return enabled() && !inspectionInFlight() && !inspectionEnabled();
    }

    @Override
    public boolean timeoutsEditable() {
        return enabled();
    }

    @Override
    public List<PlcTimeoutDefinition> timeoutDefinitions() {
        return timeoutWritePath.timeoutDefinitions();
    }

    @Override
    public List<PlcSignalState> listSignals() {
        return signalWritePath.listSignals();
    }

    @Override
    public List<PlcSignalState> writeSignals(Map<String, Boolean> valuesByName, Map<String, Boolean> pulseByName)
            throws IOException, InterruptedException, TimeoutException {
        return signalWritePath.writeSignals(valuesByName, pulseByName, manualControlEditable());
    }

    @Override
    public List<PlcTimeoutState> readTimeouts() throws IOException, InterruptedException, TimeoutException {
        return timeoutWritePath.readTimeouts();
    }

    @Override
    public List<PlcTimeoutState> writeTimeouts(Map<String, Integer> unitsByKey)
            throws IOException, InterruptedException, TimeoutException {
        return timeoutWritePath.writeTimeouts(unitsByKey, timeoutsEditable());
    }

    public String metricsSummary() {
        String plcPart = plcPublisher == null
                ? "plc=disabled"
                : ("plc.dropped=" + plcPublisher.droppedTotal());
        String rejectPart = plcPublisher != null ? " reject=fins" : " reject=off";
        return plcPart + rejectPart + " " + uiNotifier.metricsClientWsPart();
    }

    @Override
    public void close() {
        if (plcPublisher != null) {
            plcPublisher.close();
        }
    }
}
