package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-trigger-sequence state for line-synchronized capture. */
public final class LineCaptureRound {

    public final ConcurrentHashMap<Integer, WorkerProcessSupervisor> participants = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Integer, BinaryProtocol.Message> results = new ConcurrentHashMap<>();
    public final AtomicBoolean fired = new AtomicBoolean(false);
    public final AtomicBoolean triggerPrefired = new AtomicBoolean(false);
    public final AtomicBoolean framesLatched = new AtomicBoolean(false);
    public final AtomicBoolean firstFrameNotified = new AtomicBoolean(false);
    public final CountDownLatch framesReady = new CountDownLatch(1);
    public final AtomicInteger consumedFrames = new AtomicInteger(0);
    public final CountDownLatch fireDone = new CountDownLatch(1);
    public final AtomicInteger framesPending = new AtomicInteger(0);
    public final AtomicInteger activeParticipants = new AtomicInteger(0);
    public final Object awaitLock = new Object();

    private final long barrierWaitMs;
    public volatile long deadlineMs;
    public volatile long firstArriveMs;
    public volatile long lastArriveMs;
    public volatile long prefireStartedNs;
    public volatile long triggerEpochMs;
    public volatile Exception failure = null;

    public LineCaptureRound(long barrierWaitMs) {
        this.barrierWaitMs = barrierWaitMs;
        this.deadlineMs = System.currentTimeMillis() + barrierWaitMs;
    }

    public void arrive(int cameraId, WorkerProcessSupervisor worker) {
        participants.put(cameraId, worker);
        activeParticipants.incrementAndGet();
        long now = System.currentTimeMillis();
        if (firstArriveMs == 0L) {
            firstArriveMs = now;
        }
        lastArriveMs = now;
        synchronized (awaitLock) {
            deadlineMs = now + barrierWaitMs;
            awaitLock.notifyAll();
        }
    }

    public void releaseParticipant() {
        activeParticipants.decrementAndGet();
    }
}
