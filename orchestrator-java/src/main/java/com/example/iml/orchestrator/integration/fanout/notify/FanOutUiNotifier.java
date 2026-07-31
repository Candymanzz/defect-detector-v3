package com.example.iml.orchestrator.integration.fanout.notify;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;

/**
 * Side-effects fan-out в UI (WebSocket): bucket verdict и привязка PLC traffic.
 */
public final class FanOutUiNotifier {
    private final ClientWebSocketServer clientWsServer;

    public FanOutUiNotifier(ClientWebSocketServer clientWsServer) {
        this.clientWsServer = clientWsServer;
    }

    public ClientWebSocketServer clientWsServer() {
        return clientWsServer;
    }

    public void bindPlcTraffic(PlcFinsPublisher plcPublisher) {
        if (plcPublisher != null && clientWsServer != null) {
            plcPublisher.setTrafficListener(clientWsServer::notifyPlcFinsTraffic);
        }
    }

    public void notifyBucket(BucketFanOutResult result) {
        if (clientWsServer != null) {
            clientWsServer.notifyInspectBucketResult(result);
        }
    }

    /**
     * Для ПЛК «инспекция включена» = задан эталон (READY/OPERATIONAL), не Start/Stop gate камер.
     */
    public boolean inspectionEnabled() {
        if (clientWsServer != null) {
            return clientWsServer.sessionState() != ClientWsSessionState.NO_REFERENCE;
        }
        return false;
    }

    public String metricsClientWsPart() {
        return "client_ws=" + (clientWsServer == null ? "disabled" : "enabled");
    }
}
