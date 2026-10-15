package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Reservations include queued work; HTTP clients sharing a URL share one CPU/FIFO lane. */
public final class PythonWorkloadBalancer {
    private static final double INITIAL_MS = 100.0;
    private final LongSupplier clock;
    private final Map<Object, Backend> backends = new HashMap<>();
    private final LinkedHashMap<String, Double> costs = new LinkedHashMap<>(16, .75f, true);

    public PythonWorkloadBalancer() { this(System::nanoTime); }
    PythonWorkloadBalancer(LongSupplier clock) { this.clock = clock; }

    private static final class Backend { final Deque<Reservation> queue = new ArrayDeque<>(); }
    public final class Reservation implements AutoCloseable {
        private final Backend backend;
        private final BinaryRpcSupervisor client;
        private final String scope;
        private final double expectedMs, queuedWorkMs;
        private long startedNs;
        private boolean started, closed;
        private Reservation(Backend backend, BinaryRpcSupervisor client, String scope, double expectedMs, double queuedWorkMs) {
            this.backend = backend; this.client = client; this.scope = scope;
            this.expectedMs = expectedMs; this.queuedWorkMs = queuedWorkMs;
        }
        public BinaryRpcSupervisor client() { return client; }
        public double expectedMs() { return expectedMs; }
        public double queuedWorkMs() { return queuedWorkMs; }
        public void awaitTurn() throws InterruptedException {
            synchronized (PythonWorkloadBalancer.this) {
                while (!closed && backend.queue.peekFirst() != this) PythonWorkloadBalancer.this.wait();
                if (closed) throw new IllegalStateException("Python reservation already closed");
            }
        }
        public void started() {
            synchronized (PythonWorkloadBalancer.this) {
                if (closed || backend.queue.peekFirst() != this || started)
                    throw new IllegalStateException("Python reservation is not at the head of its server FIFO");
                startedNs = clock.getAsLong(); started = true;
            }
        }
        public void completed(double measuredMs) {
            synchronized (PythonWorkloadBalancer.this) {
                if (closed || !started) throw new IllegalStateException("Python reservation is not running");
                if (Double.isFinite(measuredMs) && measuredMs > 0 && measuredMs <= 60_000) {
                    double previous = costs.getOrDefault(scope, measuredMs);
                    costs.put(scope, previous * .75 + measuredMs * .25);
                    while (costs.size() > 128) costs.remove(costs.keySet().iterator().next());
                }
            }
        }
        @Override public void close() {
            synchronized (PythonWorkloadBalancer.this) {
                if (closed) return;
                closed = true; backend.queue.remove(this); PythonWorkloadBalancer.this.notifyAll();
            }
        }
    }

    public synchronized Reservation reserve(List<? extends BinaryRpcSupervisor> pool, String scope,
                                            boolean weighted, AtomicInteger roundRobin) {
        Map<Object, BinaryRpcSupervisor> unique = new LinkedHashMap<>();
        for (var client : pool) unique.putIfAbsent(key(client), client);
        if (unique.isEmpty()) throw new IllegalArgumentException("Python pool is empty");
        List<BinaryRpcSupervisor> clients = new ArrayList<>(unique.values());
        int first = Math.floorMod(roundRobin.getAndIncrement(), clients.size());
        BinaryRpcSupervisor selected = clients.get(first);
        double best = remaining(backends.computeIfAbsent(key(selected), ignored -> new Backend()));
        if (weighted) for (int i = 1; i < clients.size(); i++) {
            var candidate = clients.get((first + i) % clients.size());
            double work = remaining(backends.computeIfAbsent(key(candidate), ignored -> new Backend()));
            if (work < best) { selected = candidate; best = work; }
        }
        Backend backend = backends.get(key(selected));
        Reservation reservation = new Reservation(backend, selected, scope, costs.getOrDefault(scope, INITIAL_MS), best);
        backend.queue.addLast(reservation);
        return reservation;
    }

    private double remaining(Backend backend) {
        double total = 0;
        for (var job : backend.queue) {
            double elapsed = job.started ? Math.max(0, clock.getAsLong() - job.startedNs) / 1_000_000.0 : 0;
            // An overrunning RPC is still busy; always prefer a truly idle server.
            total += Math.max(job.started ? 1.0 : 0.0, job.expectedMs - elapsed);
        }
        return total;
    }
    private static Object key(BinaryRpcSupervisor client) {
        return client instanceof AnalisSurfaceHttpBinaryRpcSupervisor http ? http.baseUrl() : client;
    }
}
