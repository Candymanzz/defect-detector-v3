package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PythonWorkloadBalancerTest {
    private static AnalisSurfaceHttpBinaryRpcSupervisor client(String name, int port) {
        return new AnalisSurfaceHttpBinaryRpcSupervisor(name, "http://localhost:" + port, 1000);
    }
    @Test void duplicateHttpClientsShareOneFifoAndCancellationReleasesIt() throws Exception {
        var balancer = new PythonWorkloadBalancer(); var rr = new AtomicInteger();
        var a = client("client-a", 8000); var b = client("client-b", 8000);
        try (var first = balancer.reserve(List.of(a,b), "heavy", true, rr);
             var second = balancer.reserve(List.of(a,b), "light", true, rr)) {
            assertEquals(a.baseUrl(), ((AnalisSurfaceHttpBinaryRpcSupervisor) second.client()).baseUrl());
            assertEquals(100.0, second.queuedWorkMs());
            CountDownLatch entered = new CountDownLatch(1), ready = new CountDownLatch(1);
            Thread waiting = new Thread(() -> {
                ready.countDown();
                try { second.awaitTurn(); entered.countDown(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            waiting.start(); assertTrue(ready.await(1, TimeUnit.SECONDS));
            assertFalse(entered.await(30, TimeUnit.MILLISECONDS));
            first.close(); assertTrue(entered.await(1, TimeUnit.SECONDS)); waiting.join(1000);
        }
    }
    @Test void measuredCostAndElapsedWorkChooseLightlyLoadedServer() throws Exception {
        AtomicLong clock = new AtomicLong(); var balancer = new PythonWorkloadBalancer(clock::get);
        var a = client("a",8000); var b = client("b",8001); var pool = List.of(a,b); var rr = new AtomicInteger();
        try (var training = balancer.reserve(List.of(a), "heavy", true, rr)) {
            training.awaitTurn(); training.started(); training.completed(500);
        }
        rr.set(0);
        try (var heavy = balancer.reserve(pool, "heavy", true, rr);
             var light = balancer.reserve(pool, "light", true, rr)) {
            heavy.awaitTurn(); heavy.started(); light.awaitTurn(); light.started();
            assertSame(a, heavy.client()); assertSame(b, light.client());
            clock.addAndGet(20_000_000);
            try (var next = balancer.reserve(pool, "next", true, rr)) {
                assertSame(b, next.client()); assertEquals(80.0, next.queuedWorkMs());
            }
        }
        try (var idle = balancer.reserve(pool, "after-failure", true, rr)) { assertEquals(0, idle.queuedWorkMs()); }
    }
    @Test void idleServerWinsEvenWhenBusyRpcExceededPrediction() throws Exception {
        AtomicLong clock = new AtomicLong(); var balancer = new PythonWorkloadBalancer(clock::get);
        var a=client("a",8000); var b=client("b",8001); var rr=new AtomicInteger();
        try (var busy=balancer.reserve(List.of(a),"slow",true,rr)) {
            busy.awaitTurn(); busy.started(); clock.set(1_000_000_000L); rr.set(0);
            try(var next=balancer.reserve(List.of(a,b),"next",true,rr)) { assertSame(b,next.client()); }
        }
    }
}
