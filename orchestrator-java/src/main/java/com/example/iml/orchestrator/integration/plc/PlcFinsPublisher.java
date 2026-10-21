package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import com.example.iml.orchestrator.integration.logging.PerformanceTrace;
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
      long pulseMs,
      CompletableFuture<Void> future
  ) implements PlcJob {
    PulseBitJob(PlcSignalDefinition signal, boolean activeValue, long pulseMs) {
      this(signal, activeValue, pulseMs, null);
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
  private static final class JobTiming {
    final long enqueued = System.nanoTime();
    final long sequence;
    final int group;
    final boolean output;
    long dequeued;
    int writes;
    JobTiming(long sequence, int group, boolean output) { this.sequence = sequence; this.group = group; this.output = output; }
  }
  private final Map<PlcJob, JobTiming> jobTimings = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
  private final ThreadLocal<JobTiming> currentTiming = new ThreadLocal<>();
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

  public void addTrafficObserver(PlcFinsTrafficListener observer) {
    client.addTrafficObserver(observer);
  }

  public void removeTrafficObserver(PlcFinsTrafficListener observer) {
    client.removeTrafficObserver(observer);
  }

  public PlcRegisterMap registerMap() {
    return registerMap;
  }

  public void publishBucket(BucketFanOutResult result) {
    publishBucket(result, false, false);
  }

  /**
   * Вердикт ведра в ПЛК. При {@code awaitEdge=true} ждёт подтверждения записи фронта
   * (PASS→false или REJECT→true).
   *
   * @param holdRejectUntilPass режим пластиковой ручки (D4405=1): reject держится HIGH до PASS;
   *                            иначе импульс {@code pulse_ms} как раньше
   */
  public void publishBucket(BucketFanOutResult result, boolean awaitEdge, boolean holdRejectUntilPass) {
    // ready держится отдельно (sticky HIGH); здесь только вердикт reject.
    Optional<PlcSignalDefinition> signalOpt = registerMap.rejectSignalForGroup(result.groupId());
    if (signalOpt.isEmpty()) {
      PerformanceTrace.cycle(result.triggerSequence(), -1, -1, "plc_output_unavailable",
          PerformanceTrace.values("group_id", result.groupId(), "reason", "no reject signal mapping"));
      log.warn("plc fins: no reject signal for bucket group={}", result.groupId());
      return;
    }
    PlcSignalDefinition signal = signalOpt.get();
    CompletableFuture<Void> edge = awaitEdge ? new CompletableFuture<>() : null;
    if (result.overallPass()) {
      Boolean last = lastSignalValues.get(signal.name());
      // Plastic-handle mode is a state level, not a per-cycle pulse:
      // PASS->PASS and FAIL->FAIL must not create any PLC activity.
      if (holdRejectUntilPass && Boolean.FALSE.equals(last)) {
        traceNoop(result, false);
        if (edge != null) {
          edge.complete(null);
        }
      } else if (!enqueue(new WriteBitJob(signal, false, edge), result.triggerSequence(), result.groupId(), true) && edge != null) {
        edge.completeExceptionally(new IOException("plc fins queue full"));
      }
    } else if (holdRejectUntilPass && config.pulseMs() > 0) {
      Boolean last = lastSignalValues.get(signal.name());
      if (Boolean.TRUE.equals(last)) {
        traceNoop(result, true);
        if (edge != null) {
          edge.complete(null);
        }
      } else if (!enqueue(new WriteBitJob(signal, true, edge), result.triggerSequence(), result.groupId(), true) && edge != null) {
        edge.completeExceptionally(new IOException("plc fins queue full"));
      }
    } else if (config.pulseMs() > 0) {
      if (!enqueue(new PulseBitJob(signal, true, config.pulseMs(), edge), result.triggerSequence(), result.groupId(), true) && edge != null) {
        edge.completeExceptionally(new IOException("plc fins queue full"));
      }
    } else {
      if (!enqueue(new WriteBitJob(signal, true, edge), result.triggerSequence(), result.groupId(), true) && edge != null) {
        edge.completeExceptionally(new IOException("plc fins queue full"));
      }
    }
    if (edge != null) {
      try {
        await(edge);
      } catch (IOException | InterruptedException | TimeoutException e) {
        PerformanceTrace.cycle(result.triggerSequence(), -1, -1, "plc_output_await_failed",
            PerformanceTrace.values("group_id", result.groupId(), "error", e.getClass().getSimpleName()));
        log.warn(
                "plc fins await edge failed seq={} group={}: {}",
                result.triggerSequence(),
                result.groupId(),
                e.getMessage()
        );
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static void traceNoop(BucketFanOutResult result, boolean value) {
    PerformanceTrace.cycle(result.triggerSequence(), -1, -1, "plc_output_unchanged",
        PerformanceTrace.values("group_id", result.groupId(), "transport", "FINS", "value", value,
            "reason", "hold mode; level already applied; no new write"));
  }

  /** Raises every configured bucket reject line and waits for the PLC acknowledgements. */
  public void publishRejectAllGroupsAndAwait(long triggerSequence) {
    List<PlcSignalDefinition> signals = registerMap.signals().stream()
        .filter(signal -> signal.bucketGroupId() != null)
        .sorted(java.util.Comparator.comparingInt(PlcSignalDefinition::bucketGroupId))
        .toList();
    for (PlcSignalDefinition signal : signals) {
      if (Boolean.TRUE.equals(lastSignalValues.get(signal.name()))) {
        continue;
      }
      CompletableFuture<Void> edge = new CompletableFuture<>();
      if (!enqueue(new WriteBitJob(signal, true, edge), triggerSequence,
          signal.bucketGroupId() == null ? -1 : signal.bucketGroupId(), true)) {
        edge.completeExceptionally(new IOException("plc fins queue full"));
      }
      try {
        await(edge);
      } catch (IOException | InterruptedException | TimeoutException e) {
        log.warn("plc fins early reject await failed seq={} signal={}: {}",
            triggerSequence, signal.name(), e.getMessage());
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Sticky-уровень готовности техзрения (W0.04 vision_ready). Повтор с тем же значением не шлём.
   */
  public void setVisionReady(boolean ready) {
    writeLevelIfChanged(config.visionReadySignal(), ready);
  }

  private void forceVisionReadyOff() {
    try {
      writeBit(registerMap.require(config.visionReadySignal()), false);
    } catch (Exception e) {
      log.debug("plc fins force vision_ready off: {}", e.getMessage());
    }
  }

  /**
   * Авария техзрения / сервисов (CIO vision_fault). Повтор с тем же значением не шлём.
   */
  public void setVisionFault(boolean fault) {
    writeLevelIfChanged(config.visionFaultSignal(), fault);
  }

  private void writeLevelIfChanged(String signalName, boolean value) {
    PlcSignalDefinition signal = registerMap.require(signalName);
    Boolean last = lastSignalValues.get(signalName);
    if (last != null && last == value) {
      return;
    }
    enqueue(new WriteBitJob(signal, value));
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
      enqueued = enqueue(new PulseBitJob(signal, true, config.pulseMs(), future));
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
    return enqueue(job, 0L, -1, false);
  }

  private boolean enqueue(PlcJob job, long sequence, int group, boolean output) {
    JobTiming timing = new JobTiming(sequence, group, output);
    jobTimings.put(job, timing);
    if (!queue.offer(job)) {
      jobTimings.remove(job);
      PerformanceTrace.cycle(sequence, -1, -1, "plc_queue_full",
          PerformanceTrace.values("group_id", group, "job", job.getClass().getSimpleName()));
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
        JobTiming timing = jobTimings.remove(job);
        if (timing != null) timing.dequeued = System.nanoTime();
        currentTiming.set(timing);
        long started = System.nanoTime();
        try {
          processJob(job);
        } finally {
          PerformanceTrace.cycle(timing == null ? 0 : timing.sequence, -1, -1, "plc_job_complete",
              PerformanceTrace.values("group_id", timing == null ? -1 : timing.group,
                  "job", job.getClass().getSimpleName(), "duration_ms", PerformanceTrace.elapsedMs(started),
                  "queue_ms", timing == null ? null : (timing.dequeued - timing.enqueued) / 1_000_000.0));
          currentTiming.remove();
        }
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
        // Длительность всегда от момента записи true — не от enqueue.
        // Иначе второй pulse в очереди (reject_line_2 после reject_line_1) сжимается до ~0 ms.
        writeBit(pulse.signal(), pulse.activeValue());
        // Фронт уже на ПЛК — отпускаем awaitEdge до sleep/сброса.
        if (pulse.future() != null) {
          pulse.future().complete(null);
        }
        long waitMs = Math.max(0L, pulse.pulseMs());
        if (waitMs > 0) {
          long holdStarted = System.nanoTime();
          try {
            Thread.sleep(waitMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          } finally {
            JobTiming timing = currentTiming.get();
            PerformanceTrace.cycle(timing == null ? 0 : timing.sequence, -1, -1, "plc_pulse_hold",
                PerformanceTrace.values("group_id", timing == null ? -1 : timing.group,
                    "duration_ms", PerformanceTrace.elapsedMs(holdStarted), "requested_ms", waitMs));
          }
        }
        writeBit(pulse.signal(), false);
      } catch (Exception e) {
        if (pulse.future() != null && !pulse.future().isDone()) {
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
        int[] words = client.timings().readWords(read.area(), read.startWord(), read.count(), read.signal());
        read.future().complete(words);
      } catch (Exception e) {
        read.future().completeExceptionally(e);
      }
      return;
    }
    WriteWordsJob writeWords = (WriteWordsJob) job;
    try {
      client.timings().writeWords(writeWords.area(), writeWords.startWord(), writeWords.words(), writeWords.signal());
      writeWords.future().complete(null);
    } catch (Exception e) {
      writeWords.future().completeExceptionally(e);
    }
  }

  private void writeBit(PlcSignalDefinition signal, boolean value) throws IOException {
    long started = System.nanoTime();
    boolean success = false;
    try {
      client.signals().writeBit(signal.area(), signal.address(), value, signal.name());
      success = true;
    } finally {
      JobTiming timing = currentTiming.get();
      boolean firstWrite = timing == null || timing.writes++ == 0;
      String event = timing != null && timing.output && firstWrite
          ? (success ? "plc_output_confirmed" : "plc_output_failed") : "plc_bit_write";
      PerformanceTrace.cycle(timing == null ? 0 : timing.sequence, -1, -1, event,
          PerformanceTrace.values("group_id", timing == null ? -1 : timing.group,
              "transport", "FINS", "signal", signal.name(), "value", value,
              "write_ms", PerformanceTrace.elapsedMs(started), "success", success,
              "pulse_reset", !firstWrite,
              "queue_ms", timing == null ? null : (timing.dequeued - timing.enqueued) / 1_000_000.0,
              "boundary", "FINS response received; not physical rejector movement"));
    }
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
    jobTimings.clear();
  }
}
