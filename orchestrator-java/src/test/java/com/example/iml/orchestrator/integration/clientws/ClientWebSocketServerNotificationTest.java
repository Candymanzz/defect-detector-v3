package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.LogManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWebSocketServerNotificationTest {

    @Test
    void blockedClientDoesNotHoldBucketOrFinsProducer() throws Exception {
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<String> resultMessage = new AtomicReference<>();
        AtomicBoolean stall = new AtomicBoolean();
        WebSocket client = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "isOpen" -> true;
                        case "getRemoteSocketAddress" -> new InetSocketAddress("127.0.0.1", 12345);
                        case "send" -> {
                            if (stall.get()) {
                                enteredSend.countDown();
                                releaseSend.await();
                            }
                            if (args[0] instanceof String message && message.contains("\"type\":\"server.inspect_result\"")) {
                                resultMessage.set(message);
                                resultSent.countDown();
                            }
                            yield null;
                        }
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
        ClientWebSocketServer server = new ClientWebSocketServer(LogManager.getLogger(getClass()), ClientWsConfig.disabled());
        var producer = Executors.newSingleThreadExecutor();
        try {
            HandshakeImpl1Client handshake = new HandshakeImpl1Client();
            handshake.setResourceDescriptor("/");
            server.onWebsocketOpen(client, handshake);
            stall.set(true);
            var first = producer.submit(() -> server.notifyInspectBucketResult(
                    new BucketFanOutResult(0, 100L, true, List.of(0), Map.of())));
            assertTrue(enteredSend.await(1, TimeUnit.SECONDS), "UI send must reach the blocked client");
            first.get(1, TimeUnit.SECONDS);
            producer.submit(() -> {
                server.notifyPlcFinsTraffic(new PlcFinsTrafficEvent("response", "write_bit", "reject_line_1",
                        "W", "0.6", false, "", 1, "0000", true, null, System.currentTimeMillis()));
                server.notifyInspectBucketResult(new BucketFanOutResult(1, 100L, true, List.of(5), Map.of()));
            }).get(1, TimeUnit.SECONDS);
            Map<String, Object> capture = new LinkedHashMap<>(Map.of(
                    "frame_id", 42L, "shm_name", "/iml_line_pin_cam0_f42", "width", 2, "height", 2, "stride", 6));
            producer.submit(() -> server.notifyInspectResult(0, "bench", "test", 100L,
                    InspectionDecision.simple(0, 42L, true, "ACCEPT", 0.01, "PASS", "PASS"), capture,
                    Path.of("already-cleaned-temporary-heatmap.u8"), 2, 2, null, "heatmap-42", false, null
            )).get(1, TimeUnit.SECONDS);
            capture.put("frame_id", 99L);
            releaseSend.countDown();
            assertTrue(resultSent.await(1, TimeUnit.SECONDS));
            assertTrue(resultMessage.get().contains("\"frame_id\":\"42\""), "queued message must own its metadata");
            assertTrue(resultMessage.get().contains("\"artifact_id\":\"heatmap-42\""), "registered heatmap must survive source cleanup");
        } finally {
            releaseSend.countDown();
            producer.shutdownNow();
            server.close();
        }
    }
}
