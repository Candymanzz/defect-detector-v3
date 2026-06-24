package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронная запись сигналов техзрения в ПЛК по FINS.
 */
public final class PlcFinsPublisher implements AutoCloseable {

  private sealed interface PlcJob permits WriteBitJob, PulseBitJob {
  }

  private record WriteBitJob(PlcSignalDefinition signal, boolean value) implements PlcJob {
  }

  private record PulseBitJob(PlcSignalDefinition signal, boolean activeValue, long resetAtNanos) implements PlcJob {
  }

  private final Logger log;
  private final PlcFinsConfig config;
  private final PlcRegisterMap registerMap;
  private final OmronFinsClient client;
  private final BlockingQueue<PlcJob> queue;
  private final Thread worker;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong droppedJobs = new AtomicLong();

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
    this.queue = new ArrayBlockingQueue<>(config.queueSize());
    this.worker = new Thread(this::runLoop, "plc-fins-publisher");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  public static PlcFinsPublisher create(Logger log, PlcFinsConfig config, PlcRegisterMap registerMap) throws IOException {
    OmronFinsClient client = new OmronFinsClient(
        config.host(),
        config.port(),
        config.destNode(),
        config.srcNode(),
        config.responseTimeoutMs()
    );
    return new PlcFinsPublisher(log, config, registerMap, client);
  }

  public void publishBucket(BucketFanOutResult result) {
    Optional<PlcSignalDefinition> signalOpt = registerMap.rejectSignalForGroup(result.groupId());
    if (signalOpt.isEmpty()) {
      log.warn("plc fins: no reject signal for bucket group={}", result.groupId());
      return;
    }
    if (result.overallPass()) {
      enqueue(new WriteBitJob(signalOpt.get(), false));
      return;
    }
    if (config.pulseMs() > 0) {
      enqueue(new PulseBitJob(signalOpt.get(), true, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.pulseMs())));
    } else {
      enqueue(new WriteBitJob(signalOpt.get(), true));
    }
  }

  public void setVisionReady(boolean ready) {
    enqueue(new WriteBitJob(registerMap.require(config.visionReadySignal()), ready));
  }

  public void setVisionFault(boolean fault) {
    enqueue(new WriteBitJob(registerMap.require(config.visionFaultSignal()), fault));
  }

  public long droppedTotal() {
    return droppedJobs.get();
  }

  private void enqueue(PlcJob job) {
    if (!queue.offer(job)) {
      droppedJobs.incrementAndGet();
      log.warn("plc fins queue full, job dropped total={}", droppedJobs.get());
    }
  }

  private void runLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        PlcJob job = queue.poll(200, TimeUnit.MILLISECONDS);
        if (job == null) {
          continue;
        }
        processJob(job);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        log.warn("plc fins worker error: {}", e.getMessage());
      }
    }
  }

  private void processJob(PlcJob job) throws IOException {
    if (job instanceof WriteBitJob write) {
      writeBit(write.signal(), write.value());
      return;
    }
    PulseBitJob pulse = (PulseBitJob) job;
    writeBit(pulse.signal(), pulse.activeValue());
    long waitMs = TimeUnit.NANOSECONDS.toMillis(pulse.resetAtNanos() - System.nanoTime());
    if (waitMs > 0) {
      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    writeBit(pulse.signal(), false);
  }

  private void writeBit(PlcSignalDefinition signal, boolean value) throws IOException {
    client.writeBit(signal.area(), signal.address(), value);
    log.info(
            "plc fins write signal={} area={} address={}.{} value={}",
            signal.name(),
            signal.area(),
            signal.address().word(),
            signal.address().bit(),
            value
    );
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
      setVisionReady(false);
      client.close();
    } catch (Exception e) {
      log.debug("plc fins close: {}", e.getMessage());
    }
  }
}
