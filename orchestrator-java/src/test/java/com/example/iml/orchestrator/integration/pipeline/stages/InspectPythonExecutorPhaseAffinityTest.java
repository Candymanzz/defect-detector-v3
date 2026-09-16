package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class InspectPythonExecutorPhaseAffinityTest {

    @Test
    void pinsEachPhaseToItsServerWhileBalancingClientsWithinServer() {
        List<BinaryRpcSupervisor> pool = List.of(
                http("c0", "http://127.0.0.1:8000"),
                http("c1", "http://127.0.0.1:8001"),
                http("c2", "http://127.0.0.1:8000"),
                http("c3", "http://127.0.0.1:8001")
        );
        AtomicInteger rr = new AtomicInteger();

        BinaryRpcSupervisor phase0a = InspectPythonExecutor.selectPython(pool, rr, 0);
        BinaryRpcSupervisor phase1 = InspectPythonExecutor.selectPython(pool, rr, 1);
        BinaryRpcSupervisor phase0b = InspectPythonExecutor.selectPython(pool, rr, 0);

        assertEquals("http://127.0.0.1:8000", ((AnalisSurfaceHttpBinaryRpcSupervisor) phase0a).baseUrl());
        assertEquals("http://127.0.0.1:8001", ((AnalisSurfaceHttpBinaryRpcSupervisor) phase1).baseUrl());
        assertEquals("http://127.0.0.1:8000", ((AnalisSurfaceHttpBinaryRpcSupervisor) phase0b).baseUrl());
        assertNotSame(phase0a, phase0b);
    }

    @Test
    void keepsRoundRobinForLegacyCalls() {
        List<BinaryRpcSupervisor> pool = List.of(
                http("c0", "http://127.0.0.1:8000"),
                http("c1", "http://127.0.0.1:8001")
        );
        AtomicInteger rr = new AtomicInteger();

        assertNotSame(
                InspectPythonExecutor.selectPython(pool, rr, -1),
                InspectPythonExecutor.selectPython(pool, rr, -1)
        );
    }

    private static BinaryRpcSupervisor http(String name, String url) {
        return new AnalisSurfaceHttpBinaryRpcSupervisor(name, url, 1000);
    }
}
