package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.clientapi.CppAnalisSurfaceHttpSupervisor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LearningSessionResetTest {
    private static final class Detector implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger deletes = new AtomicInteger();
        volatile int cases = 2;
        volatile int deleteStatus = 200;
        volatile String deletionResponse = "{\"deleted\":true}";
        volatile String listResponse;

        Detector() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/learning/accepted-cases", exchange -> {
                boolean delete = "DELETE".equals(exchange.getRequestMethod());
                String body;
                if (delete) {
                    deletes.incrementAndGet();
                    if (deleteStatus == 200) cases = 0;
                    body = deletionResponse;
                } else body = listResponse != null ? listResponse : cases == 0 ? "{\"cases\":[]}" : "{\"cases\":[{\"id\":\"old\"}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(delete ? deleteStatus : 200, bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.createContext("/detector/health", exchange -> {
                byte[] bytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        CppAnalisSurfaceHttpSupervisor client(String suffix) { return new CppAnalisSurfaceHttpSupervisor("test", url()+suffix, 2000); }
        public void close() { server.stop(0); }
    }

    @Test void clearsEveryBackendOnceAndPreservesNormalsDuringRecovery() throws Exception {
        try (var a = new Detector(); var b = new Detector(); var first = a.client("");
             var duplicate = a.client("/"); var second = b.client("")) {
            ChildProcessStartupService.resetLearnedNormalsForApplicationStart(List.of(first, duplicate, second));
            assertEquals(1, a.deletes.get());assertEquals(1, b.deletes.get());
            assertEquals(0, a.cases);assertEquals(0, b.cases);
            a.cases = 1;
            first.start();first.restart();duplicate.start();
            assertEquals(1, a.deletes.get(), "client recovery must not erase the current run's learning");
            assertEquals(1, a.cases);
            ChildProcessStartupService.resetLearnedNormalsForApplicationStart(List.of(duplicate, first));
            assertEquals(2, a.deletes.get(), "a new application bootstrap must reset again");
            assertEquals(0, a.cases);
        }
    }

    @Test void acceptsAnAlreadyEmptyDetector() throws Exception {
        try (var detector = new Detector(); var client = detector.client("")) {
            detector.cases = 0;detector.deletionResponse = "{\"deleted\":false}";
            assertDoesNotThrow(client::resetLearnedNormalsForApplicationStart);
        }
    }

    @Test void refusesFailedOrUnconfirmedReset() throws Exception {
        try (var detector = new Detector(); var client = detector.client("")) {
            detector.deleteStatus = 500;
            assertThrows(IOException.class, client::resetLearnedNormalsForApplicationStart);
            detector.deleteStatus = 200;
            for (String body : List.of("{}", "null", "[]", "{\"deleted\":\"true\"}")) {
                detector.deletionResponse = body;
                assertThrows(IOException.class, client::resetLearnedNormalsForApplicationStart);
            }
            detector.deletionResponse = "{\"deleted\":true}";
            for (String body : List.of("{}", "null", "{\"cases\":[{\"id\":\"still-present\"}]}")) {
                detector.listResponse = body;
                assertThrows(IOException.class, client::resetLearnedNormalsForApplicationStart);
            }
        }
    }
}
