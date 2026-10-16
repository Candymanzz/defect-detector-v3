package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketGroup;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DoRejectRoutingTest {
    @TempDir Path directory;
    @Test void firstFailReachesOnlyItsDoBeforeOtherCamerasComplete() throws Exception {
        var root = Map.<String, Object>of("plc_fins", Map.of("enabled", false),
                "integration", Map.of("io_reject", Map.of("enabled", true, "mode", "hold",
                        "groups", Map.of(0, Map.of("port", 3, "active_high", false),
                                1, Map.of("port", 4, "active_high", false)))));
        try (var ws = new ClientWebSocketServer(LogManager.getLogger(getClass()), ClientWsConfig.disabled());
             var fanout = FanOutCoordinator.fromConfig(root, directory, ws);
             var aggregator = new BucketInspectionAggregator(LogManager.getLogger(getClass()),
                     new BucketInspectionConfig(true, List.of(new BucketGroup(0, List.of(0, 1)),
                             new BucketGroup(1, List.of(2, 3))), 1000L, 1000L))) {
            List<Map<Integer, Boolean>> writes = new CopyOnWriteArrayList<>();
            fanout.bindIoRejectOutputs(new com.example.iml.orchestrator.integration.io.mvs.IoRejectPublisher.Writer() {
                public void write(int port, boolean high) { fail("hold must use batch delivery"); }
                public void writeAll(Map<Integer, Boolean> levels) { writes.add(Map.copyOf(levels)); }
            });
            ws.setSessionState(ClientWsSessionState.READY);
            fanout.onSessionState(ClientWsSessionState.READY);
            writes.clear();
            List<Integer> ui = new ArrayList<>();
            aggregator.recordFrameResult(9, 2, InspectionDecision.simple(2, 902, false, "REJECT", 1, "OK", "PASS"),
                    fanout, () -> ui.add(2));
            assertEquals(List.of(Map.of(4, false)), writes, "DO4 must reject while all other frames are still pending");
            assertTrue(ui.isEmpty());
            for (int camera : List.of(0, 1, 3)) {
                aggregator.recordFrameResult(9, camera,
                        InspectionDecision.simple(camera, 900 + camera, true, "ACCEPT", 0, "OK", "PASS"),
                        fanout, () -> ui.add(camera));
            }
            assertEquals(List.of(Map.of(4, false), Map.of(3, true, 4, false)), writes);
            assertEquals(Set.of(0, 1, 2, 3), Set.copyOf(ui));
        }
    }
    @Test void syncedBucketsReachTheSdkAsOneBatchWithIndependentPolarities() throws Exception {
        var root=Map.<String,Object>of("plc_fins",Map.of("enabled",false),"integration",Map.of("io_reject",
                Map.of("enabled",true,"mode","hold","groups",Map.of(0,Map.of("port",3,"active_high",false),
                        1,Map.of("port",4,"active_high",false)))));
        try(var ws=new ClientWebSocketServer(LogManager.getLogger(getClass()),ClientWsConfig.disabled());
            var fanout=FanOutCoordinator.fromConfig(root,directory,ws)) {
            List<Map<Integer,Boolean>> batches=new CopyOnWriteArrayList<>();
            fanout.bindIoRejectOutputs(new com.example.iml.orchestrator.integration.io.mvs.IoRejectPublisher.Writer() {
                public void write(int port,boolean high) { fail("individual writes would allow a DI scan between DOs"); }
                public void writeAll(Map<Integer,Boolean> levels) { batches.add(Map.copyOf(levels)); }
            });
            ws.setSessionState(ClientWsSessionState.READY); fanout.onSessionState(ClientWsSessionState.READY);
            batches.clear();
            fanout.publishBuckets(List.of(new BucketFanOutResult(0,7,false,List.of(),Map.of()),
                    new BucketFanOutResult(1,7,true,List.of(),Map.of())));
            assertEquals(List.of(Map.of(3,false,4,true)),batches);
        }
    }
    @Test void independentDoRejectsAndReferenceResetRestoresPass() throws Exception {
        var root = Map.<String, Object>of("plc_fins", Map.of("enabled", false),
                "integration", Map.of("io_reject", Map.of("enabled", true, "mode", "hold",
                        "groups", Map.of(0, Map.of("port", 3, "active_high", false),
                                1, Map.of("port", 4, "active_high", false)))));
        try (var ws = new ClientWebSocketServer(LogManager.getLogger(getClass()), ClientWsConfig.disabled());
             var fanout = FanOutCoordinator.fromConfig(root, directory, ws)) {
            Map<Integer, Boolean> levels = new ConcurrentHashMap<>();
            fanout.bindIoRejectOutputs(levels::put);
            // Reproduce the cached PLC material flag without a physical PLC.
            var material = FanOutCoordinator.class.getDeclaredField("plasticHandleMode");
            material.setAccessible(true); material.setBoolean(fanout, true);
            ws.setSessionState(ClientWsSessionState.READY);
            fanout.onSessionState(ClientWsSessionState.READY);
            assertFalse(fanout.publishEarlyPlasticHandleReject(1, 0));
            fanout.publishBucket(new BucketFanOutResult(0, 1, false, List.of(), Map.of()));
            fanout.publishBucket(new BucketFanOutResult(1, 1, true, List.of(), Map.of()));
            assertEquals(Map.of(3, false, 4, true), levels);
            ws.setSessionState(ClientWsSessionState.NO_REFERENCE);
            fanout.onSessionState(ClientWsSessionState.NO_REFERENCE);
            assertEquals(Map.of(3, true, 4, true), levels);
            fanout.publishBucket(new BucketFanOutResult(0, 2, false, List.of(), Map.of()));
            assertEquals(Map.of(3, true, 4, true), levels);
        }
    }

    @Test void earlyHoldRejectWritesOnlyItsLineAndCannotBeClearedBySameSequencePass() throws Exception {
        var root = Map.<String, Object>of("plc_fins", Map.of("enabled", false),
                "integration", Map.of("io_reject", Map.of("enabled", true, "mode", "hold",
                        "groups", Map.of(0, Map.of("port", 3, "active_high", false),
                                1, Map.of("port", 4, "active_high", false)))));
        try (var ws = new ClientWebSocketServer(LogManager.getLogger(getClass()), ClientWsConfig.disabled());
             var fanout = FanOutCoordinator.fromConfig(root, directory, ws)) {
            List<Map<Integer, Boolean>> writes = new CopyOnWriteArrayList<>();
            fanout.bindIoRejectOutputs(new com.example.iml.orchestrator.integration.io.mvs.IoRejectPublisher.Writer() {
                public void write(int port, boolean high) { fail("hold output should use writeAll"); }
                public void writeAll(Map<Integer, Boolean> levels) { writes.add(Map.copyOf(levels)); }
            });
            ws.setSessionState(ClientWsSessionState.READY);
            fanout.onSessionState(ClientWsSessionState.READY);
            writes.clear();

            assertTrue(fanout.publishEarlyBucketReject(7, 0, 2));
            assertEquals(List.of(Map.of(3, false)), writes, "line 2 must not wait for or be changed by line 1's reject");

            fanout.publishBuckets(List.of(new BucketFanOutResult(0, 7, true, List.of(), Map.of()),
                    new BucketFanOutResult(1, 7, true, List.of(), Map.of())));
            assertEquals(Map.of(3, false, 4, true), writes.get(1), "same-sequence PASS must not undo an early reject");
        }
    }
    @Test void bucketRejectsGoToDoAndReadyFaultStayOnFins() throws Exception {
        Path map = directory.resolve("register-map.yaml");
        Files.writeString(map, """
                version: 1
                signals:
                  - {name: vision_ready, area: W, address: '0.04', data_type: bool, direction: pc_to_plc}
                  - {name: vision_fault, area: W, address: '0.05', data_type: bool, direction: pc_to_plc}
                  - {name: reject_line_1, area: W, address: '0.06', data_type: bool, direction: pc_to_plc, bucket_group_id: 0}
                  - {name: reject_line_2, area: W, address: '0.07', data_type: bool, direction: pc_to_plc, bucket_group_id: 1}
                """);
        try (var plc = new FakePlc();
             var ws = new ClientWebSocketServer(LogManager.getLogger(getClass()), ClientWsConfig.disabled())) {
            var root = Map.<String, Object>of("plc_fins", Map.of("enabled", true, "host", "127.0.0.1",
                    "port", plc.socket.getLocalPort(), "register_map_path", map.toString(), "response_timeout_ms", 500),
                    "integration", Map.of("io_reject", Map.of("enabled", true, "mode", "timer",
                            "groups", Map.of(0, Map.of("port", 6, "timer", "Timer6"), 1, Map.of("port", 7, "timer", "Timer7")))));
            List<String> outputs = new CopyOnWriteArrayList<>(); CountDownLatch idle = new CountDownLatch(2);
            try(var fanout = FanOutCoordinator.fromConfig(root, directory, ws)) {
                fanout.bindIoRejectOutputs((port, high) -> fail("Timer rejects must never write DO levels"));
                fanout.bindIoRejectTimers((timer, line) -> { outputs.add(timer + ":" + line); idle.countDown(); });
                ws.setSessionState(ClientWsSessionState.READY); fanout.onSessionState(ClientWsSessionState.READY);
                fanout.publishBucket(new BucketFanOutResult(0, 1, false, List.of(), Map.of()));
                fanout.publishBucket(new BucketFanOutResult(1, 1, false, List.of(), Map.of()));
                assertTrue(idle.await(2, TimeUnit.SECONDS));
                assertTrue(plc.readyFault.await(2, TimeUnit.SECONDS));
                assertEquals(List.of("Timer6:Out6", "Timer7:Out7"), outputs);
                ws.setSessionState(ClientWsSessionState.NO_REFERENCE);
                assertThrows(IllegalArgumentException.class, () -> fanout.writeSignals(Map.of("reject_line_1", true), Map.of()));
            }
            assertFalse(plc.frames.isEmpty());
            assertTrue(plc.frames.stream().allMatch(frame -> frame[15] == 4 || frame[15] == 5),
                    "FINS must never write reject bits 6/7 while DO routing is enabled");
        }
    }
    private static final class FakePlc implements AutoCloseable {
        final DatagramSocket socket;
        final List<byte[]> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch readyFault = new CountDownLatch(2);
        final Thread thread;
        FakePlc() throws Exception {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            thread = new Thread(() -> {
                while (!socket.isClosed()) try {
                    var request = new DatagramPacket(new byte[4096], 4096); socket.receive(request);
                    byte[] frame = Arrays.copyOf(request.getData(), request.getLength()); frames.add(frame);
                    byte[] reply = new byte[14]; System.arraycopy(frame, 0, reply, 0, 12);
                    socket.send(new DatagramPacket(reply, reply.length, request.getSocketAddress())); readyFault.countDown();
                } catch (Exception e) { if (!socket.isClosed()) throw new RuntimeException(e); }
            }, "fake-omron");
            thread.setDaemon(true); thread.start();
        }
        public void close() throws Exception { socket.close(); thread.join(2000); }
    }
}
