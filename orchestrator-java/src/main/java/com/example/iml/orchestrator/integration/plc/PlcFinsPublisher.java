package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронная запись сигналов техзрения в ПЛК по FINS.
 */
public final class PlcFinsPublisher implements AutoCloseable {

  private sealed interface PlcJob permits WriteBitJob, PulseBitJob, ReadWordsJob, WriteWordsJob {
  }

  private record WriteBitJob(
      PlcSignalDefinition signal,
      boolean value,
      CompletableFuture<Void> future
  ) implements PlcJob {
    WriteBitJob(PlcSignalDefinition signal, boolean value) {
      this(signal, value, null);
    }
  }

  private record PulseBitJob(
      PlcSignalDefinition signal,
      boolean activeValue,
      long resetAtNanos,
      CompletableFuture<Void> future
  ) implements PlcJob {
    PulseBitJob(PlcSignalDefinition signal, boolean activeValue, long resetAtNanos) {
      this(signal, activeValue, resetAtNanos, null);
    }
  }

  private record ReadWordsJob(
      PlcMemoryArea area,
      int startWord,
      int count,
      String signal,
      CompletableFuture<int[]> future
  ) implements PlcJob {
  }

  private record WriteWordsJob(
      PlcMemoryArea area,
      int startWord,
      int[] words,
      String signal,
      CompletableFuture<Void> future
  ) implements PlcJob {
  }

  private final Logger log;
  private final PlcFinsConfig config;
  private final PlcRegisterMap registerMap;
  private final OmronFinsClient client;
  private final BlockingQueue<PlcJob> queue;
  private final Thread worker;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong droppedJobs = new AtomicLong();
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> lastSignalValues =
      new java.util.concurrent.ConcurrentHashMap<>();

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

  public void setTrafficListener(PlcFinsTrafficListener listener) {
    client.setTrafficListener(listener);
  }

  public PlcRegisterMap registerMap() {
    return registerMap;
  }

  public void publishBucket(BucketFanOutResult result) {
    // ready держится отдельно (sticky HIGH); здесь только вердикт reject.
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
    // DO1/X4 не шлём.
  }

  private void forceVisionReadyOff() {
    // no-op
  }

  public void setVisionFault(boolean fault) {
    enqueue(new WriteBitJob(registerMap.require(config.visionFaultSignal()), fault));
  }

  public int[] readWords(PlcMemoryArea area, int startWord, int count, String signal)
      throws IOException, InterruptedException, TimeoutException {
    CompletableFuture<int[]> future = new CompletableFuture<>();
    if (!enqueue(new ReadWordsJob(area, startWord, count, signal, future))) {
      throw new IOException("plc fins queue full");
    }
    return await(future);
  }

  public void writeWords(PlcMemoryArea area, int startWord, int[] words, String signal)
      throws IOException, InterruptedException, TimeoutException {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (!enqueue(new WriteWordsJob(area, startWord, words, signal, future))) {
      throw new IOException("plc fins queue full");
    }
    await(future);
  }

  public void writeSignal(String name, boolean value, boolean pulse)
      throws IOException, InterruptedException, TimeoutException {
    PlcSignalDefinition signal = registerMap.require(name);
    CompletableFuture<Void> future = new CompletableFuture<>();
    boolean enqueued;
    if (pulse && value && config.pulseMs() > 0) {
      enqueued = enqueue(new PulseBitJob(
          signal,
          true,
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.pulseMs()),
          future
      ));
    } else {
      enqueued = enqueue(new WriteBitJob(signal, value, future));
    }
    if (!enqueued) {
      throw new IOException("plc fins queue full");
    }
    await(future);
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
    Map<String, Boolean> values = new java.util.LinkedHashMap<>();
    if (signals == null || signals.isEmpty()) {
      return values;
    }
    Map<PlcMemoryArea, TreeMap<Integer, List<PlcSignalDefinition>>> byArea = new EnumMap<>(PlcMemoryArea.class);
    for (PlcSignalDefinition signal : signals) {
      byArea
          .computeIfAbsent(signal.area(), ignored -> new TreeMap<>())
          .computeIfAbsent(signal.address().word(), ignored -> new ArrayList<>())
          .add(signal);
    }
    for (Map.Entry<PlcMemoryArea, TreeMap<Integer, List<PlcSignalDefinition>>> areaEntry : byArea.entrySet()) {
      PlcMemoryArea area = areaEntry.getKey();
      TreeMap<Integer, List<PlcSignalDefinition>> byWord = areaEntry.getValue();
      if (byWord.isEmpty()) {
        continue;
      }
      List<Integer> words = new ArrayList<>(byWord.keySet());
      int rangeStart = 0;
      while (rangeStart < words.size()) {
        int startWord = words.get(rangeStart);
        int rangeEnd = rangeStart;
        while (rangeEnd + 1 < words.size() && words.get(rangeEnd + 1) == words.get(rangeEnd) + 1) {
          rangeEnd++;
        }
        int endWord = words.get(rangeEnd);
        int count = endWord - startWord + 1;
        int[] raw = readWords(area, startWord, count, "signals_" + area.name() + "_" + startWord + "_" + endWord);
        for (int i = rangeStart; i <= rangeEnd; i++) {
          int wordAddr = words.get(i);
          int wordValue = raw[wordAddr - startWord] & 0xFFFF;
          for (PlcSignalDefinition signal : byWord.get(wordAddr)) {
            boolean bit = ((wordValue >> signal.address().bit()) & 1) == 1;
            values.put(signal.name(), bit);
            lastSignalValues.put(signal.name(), bit);
          }
        }
        rangeStart = rangeEnd + 1;
      }
    }
    return values;
  }

  public long droppedTotal() {
    return droppedJobs.get();
  }

  private <T> T await(CompletableFuture<T> future) throws IOException, InterruptedException, TimeoutException {
    try {
      return future.get(Math.max(1000L, config.responseTimeoutMs() * 3L), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IOException(cause.getMessage(), cause);
    }
  }

  private boolean enqueue(PlcJob job) {
    if (!queue.offer(job)) {
      droppedJobs.incrementAndGet();
      log.warn("plc fins queue full, job dropped total={}", droppedJobs.get());
      if (job instanceof ReadWordsJob read) {
        read.future().completeExceptionally(new IOException("plc fins queue full"));
      } else if (job instanceof WriteWordsJob write) {
        write.future().completeExceptionally(new IOException("plc fins queue full"));
      } else if (job instanceof WriteBitJob writeBit && writeBit.future() != null) {
        writeBit.future().completeExceptionally(new IOException("plc fins queue full"));
      } else if (job instanceof PulseBitJob pulse && pulse.future() != null) {
        pulse.future().completeExceptionally(new IOException("plc fins queue full"));
      }
      return false;
    }
    return true;
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
      try {
        writeBit(write.signal(), write.value());
        if (write.future() != null) {
          write.future().complete(null);
        }
      } catch (Exception e) {
        if (write.future() != null) {
          write.future().completeExceptionally(e);
        } else {
          throw e instanceof IOException io ? io : new IOException(e);
        }
      }
      return;
    }
    if (job instanceof PulseBitJob pulse) {
      try {
        writeBit(pulse.signal(), pulse.activeValue());
        long waitMs = TimeUnit.NANOSECONDS.toMillis(pulse.resetAtNanos() - System.nanoTime());
        if (waitMs > 0) {
          try {
            Thread.sleep(waitMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pulse.future() != null) {
              pulse.future().completeExceptionally(e);
            }
            return;
          }
        }
        writeBit(pulse.signal(), false);
        if (pulse.future() != null) {
          pulse.future().complete(null);
        }
      } catch (Exception e) {
        if (pulse.future() != null) {
          pulse.future().completeExceptionally(e);
        } else if (e instanceof IOException io) {
          throw io;
        } else {
          throw new IOException(e);
        }
      }
      return;
    }
    if (job instanceof ReadWordsJob read) {
      try {
        int[] words = client.readWords(read.area(), read.startWord(), read.count(), read.signal());
        read.future().complete(words);
      } catch (Exception e) {
        read.future().completeExceptionally(e);
      }
      return;
    }
    WriteWordsJob writeWords = (WriteWordsJob) job;
    try {
      client.writeWords(writeWords.area(), writeWords.startWord(), writeWords.words(), writeWords.signal());
      writeWords.future().complete(null);
    } catch (Exception e) {
      writeWords.future().completeExceptionally(e);
    }
  }

  private void writeBit(PlcSignalDefinition signal, boolean value) throws IOException {
    client.writeBit(signal.area(), signal.address(), value, signal.name());
    lastSignalValues.put(signal.name(), value);
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
      forceVisionReadyOff();
      client.close();
    } catch (Exception e) {
      log.debug("plc fins close: {}", e.getMessage());
    }
  }
}
