package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронная запись сигналов техзрения в ПЛК по FINS.
 */
public final class PlcFinsPublisher implements AutoCloseable {

    private final Logger log;
    private final PlcFinsConfig config;
    private final PlcRegisterMap registerMap;
    private final OmronFinsClient client;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> lastSignalValues =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final PlcFinsQueueSupport queue;
    private final PlcFinsJobWorker workerLogic;
    private final Thread worker;
    private final PlcFinsPublishOps ops;
    private final PlcSignalBitReader bitReader;

    public PlcFinsPublisher(
            Logger log,
            PlcFinsConfig config,
            PlcRegisterMap registerMap,
            OmronFinsClient client
    ) {
        this.log = log;
        this.config = config;
        this.registerMap = registerMap;
        this.client = client;
        BlockingQueue<PlcFinsJobs.PlcJob> jobQueue = new ArrayBlockingQueue<>(config.queueSize());
        AtomicLong droppedJobs = new AtomicLong();
        this.queue = new PlcFinsQueueSupport(log, jobQueue, droppedJobs, config.responseTimeoutMs());
        this.workerLogic = new PlcFinsJobWorker(log, client, jobQueue, running, lastSignalValues);
        this.ops = new PlcFinsPublishOps(log, config, registerMap, lastSignalValues, queue);
        this.bitReader = new PlcSignalBitReader(lastSignalValues, ops::readWords);
        this.worker = new Thread(workerLogic, "plc-fins-publisher");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public static PlcFinsPublisher create(Logger log, PlcFinsConfig config, PlcRegisterMap registerMap)
            throws IOException {
        OmronFinsClient client = new OmronFinsClient(
                config.host(),
                config.port(),
                config.destNode(),
                config.srcNode(),
                config.responseTimeoutMs()
        );
        return new PlcFinsPublisher(log, config, registerMap, client);
    }

    public void setTrafficListener(PlcFinsTrafficListener listener) {
        client.setTrafficListener(listener);
    }

    public void addTrafficObserver(PlcFinsTrafficListener observer) {
        client.addTrafficObserver(observer);
    }

    public void removeTrafficObserver(PlcFinsTrafficListener observer) {
        client.removeTrafficObserver(observer);
    }

    public void publishBucket(BucketFanOutResult result) {
        ops.publishBucket(result);
    }

    /**
     * Sticky-уровень готовности техзрения (W0.04 vision_ready). Повтор с тем же значением не шлём.
     */
    public void setVisionReady(boolean ready) {
        ops.setVisionReady(ready);
    }

    /**
     * Авария техзрения / сервисов (CIO vision_fault). Повтор с тем же значением не шлём.
     */
    public void setVisionFault(boolean fault) {
        ops.setVisionFault(fault);
    }

    /**
     * Синхронная запись ready/fault до teardown (DI4 БП / shutdown prep).
     */
    public void flushVisionLevels(boolean ready, boolean fault)
            throws IOException, InterruptedException, TimeoutException {
        ops.flushVisionLevels(ready, fault);
    }

    public int[] readWords(PlcMemoryArea area, int startWord, int count, String signal)
            throws IOException, InterruptedException, TimeoutException {
        return ops.readWords(area, startWord, count, signal);
    }

    public void writeWords(PlcMemoryArea area, int startWord, int[] words, String signal)
            throws IOException, InterruptedException, TimeoutException {
        ops.writeWords(area, startWord, words, signal);
    }

    public void writeSignal(String name, boolean value, boolean pulse)
            throws IOException, InterruptedException, TimeoutException {
        ops.writeSignal(name, value, pulse);
    }

    public Boolean lastSignalValue(String name) {
        return lastSignalValues.get(name);
    }

    /**
     * Читает текущие биты сигналов с ПЛК (словными Memory Area Read).
     * Слова с дырами читаются отдельными диапазонами (не тянем CIO140…240 одним куском).
     */
    public Map<String, Boolean> readSignalBits(Collection<PlcSignalDefinition> signals)
            throws IOException, InterruptedException, TimeoutException {
        return bitReader.readSignalBits(signals);
    }

    public long droppedTotal() {
        return queue.droppedTotal();
    }

    private void forceVisionReadyOff() {
        try {
            workerLogic.writeBit(registerMap.require(config.visionReadySignal()), false);
        } catch (Exception e) {
            log.debug("plc fins force vision_ready off: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
        try {
            worker.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            forceVisionReadyOff();
            client.close();
        } catch (Exception e) {
            log.debug("plc fins close: {}", e.getMessage());
        }
    }
}
