package com.example.iml.orchestrator.integration.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Журнал обмена между оркестратором и всеми сервисами (camera-worker, geometry, positioning, analisSurface,
 * LightServer, IoInputMonitor, PLC FINS, UI).
 * <p>
 * Раскладка: {@code <корень приложения>/log_p/<yyyy-MM-dd_HH-mm-ss (старт приложения)>/<сервис>/requests.log} и {@code responses.log}.
 * {@code requests.log} — то, что оркестратор отправил сервису; {@code responses.log} — то, что пришло от сервиса
 * (в том числе сообщения без запроса, например UDP от IoInputMonitor). Строка начинается с времени,
 * затем {@code #id} — пара запрос/ответ связана одним id.
 * <p>
 * Включается и настраивается одним блоком {@code integration.traffic_log} в {@code config/blocks/01-core.yaml}.
 * Запись асинхронная: горячий путь инспекции только кладёт строку в очередь.
 */
public final class TrafficLog {

    private static final Logger LOG = LogManager.getLogger(TrafficLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SESSION_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** Папка запуска: дата и время старта приложения (одна на весь запуск). */
    private static volatile String sessionDir = LocalDateTime.now().format(SESSION_DIR);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final int QUEUE_CAPACITY = 100_000;
    private static final AtomicLong IDS = new AtomicLong();

    private static volatile boolean enabled;
    private static volatile Path baseDir;
    private static volatile int maxBodyChars = 4000;
    private static volatile Writer writer;

    private TrafficLog() {
    }

    /** Читает {@code integration.traffic_log}; без блока или при {@code enabled: false} журнал выключен. */
    @SuppressWarnings("unchecked")
    public static synchronized void configure(Map<String, Object> root, Path projectRoot) {
        Object integration = root == null ? null : root.get("integration");
        Object raw = integration instanceof Map<?, ?> m ? m.get("traffic_log") : null;
        Map<String, Object> cfg = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        boolean on = toBool(cfg.get("enabled"), false);
        if (!on) {
            shutdown();
            enabled = false;
            LOG.info("traffic_log: disabled");
            return;
        }
        String dir = String.valueOf(cfg.getOrDefault("dir", "log_p")).trim();
        Path base = Path.of(dir.isEmpty() ? "log_p" : dir);
        if (!base.isAbsolute() && projectRoot != null) {
            base = projectRoot.resolve(base);
        }
        maxBodyChars = Math.max(200, toInt(cfg.get("max_body_chars"), 4000));
        baseDir = base.normalize();
        if (writer == null) {
            sessionDir = LocalDateTime.now().format(SESSION_DIR);
            writer = new Writer();
            writer.start();
        }
        enabled = true;
        LOG.info("traffic_log: enabled dir={} max_body_chars={}", baseDir, maxBodyChars);
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Фиксирует исходящий запрос. Возвращает id для {@link #response}; {@code 0} — журнал выключен. */
    public static long request(String service, String op, Object body) {
        if (!enabled) {
            return 0L;
        }
        long id = IDS.incrementAndGet();
        enqueue(service, "requests.log", id, op, null, body, -1L);
        return id;
    }

    /** Фиксирует ответ на {@link #request}; {@code startNanos} — {@code System.nanoTime()} перед отправкой. */
    public static void response(String service, long id, String op, Object status, Object body, long startNanos) {
        if (!enabled || id == 0L) {
            return;
        }
        long ms = startNanos > 0 ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) : -1L;
        enqueue(service, "responses.log", id, op, status, body, ms);
    }

    /** Фиксирует входящее сообщение без запроса (UDP от IoInputMonitor, события). */
    public static void inbound(String service, String op, Object body) {
        if (!enabled) {
            return;
        }
        enqueue(service, "responses.log", IDS.incrementAndGet(), op, null, body, -1L);
    }

    /** Сообщение, которое оркестратор сам отправил клиенту без запроса (push в UI по WebSocket). */
    public static void push(String service, String op, Object body) {
        inbound(service, op, body);
    }

    /**
     * Внутренний шаг конвейера (триггер → съёмка → вердикт → ПЛК/UI): {@code <сервис>/events.log}.
     * Нужен, чтобы по времени и ключам (камера, seq, frame_id) проследить путь кадра.
     */
    public static void event(String service, String op, Object body) {
        if (!enabled) {
            return;
        }
        enqueue(service, "events.log", IDS.incrementAndGet(), op, null, body, -1L);
    }

    /**
     * Обёртка над {@code HttpClient.send}: пишет запрос и ответ (статус, тело, длительность) под именем сервиса.
     * Без включённого журнала просто делегирует вызов.
     */
    public static <T> HttpResponse<T> http(
            String service,
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            Object requestBody
    ) throws IOException, InterruptedException {
        if (!enabled) {
            return client.send(request, handler);
        }
        String op = request.method() + " " + request.uri().getRawPath()
                + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery());
        long id = request(service, op, requestBody);
        long start = System.nanoTime();
        try {
            HttpResponse<T> response = client.send(request, handler);
            Object body = response.body();
            response(service, id, op, response.statusCode(),
                    body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body, start);
            return response;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failure(service, id, op, e, start);
            throw e;
        }
    }

    /** Фиксирует сбой запроса (таймаут, обрыв) в {@code responses.log}. */
    public static void failure(String service, long id, String op, Throwable error, long startNanos) {
        if (!enabled || id == 0L) {
            return;
        }
        long ms = startNanos > 0 ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) : -1L;
        enqueue(service, "responses.log", id, op, "ERROR", String.valueOf(error), ms);
    }

    private static void enqueue(
            String service,
            String file,
            long id,
            String op,
            Object status,
            Object body,
            long durationMs
    ) {
        Writer w = writer;
        if (w == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        StringBuilder line = new StringBuilder(160);
        line.append(TS.format(now)).append(" #").append(id);
        if (op != null && !op.isEmpty()) {
            line.append(' ').append(op);
        }
        if (status != null) {
            line.append(" status=").append(status);
        }
        if (durationMs >= 0) {
            line.append(" duration_ms=").append(durationMs);
        }
        if (body != null) {
            line.append(' ').append(render(body));
        }
        w.offer(new Entry(now.toLocalDate(), safeName(service), file, line.append('\n').toString()));
    }

    private static String render(Object body) {
        String text;
        if (body instanceof CharSequence cs) {
            text = cs.length() > maxBodyChars * 2 ? cs.subSequence(0, maxBodyChars * 2).toString() + "<cut>" : cs.toString();
        } else if (body instanceof byte[] bytes) {
            text = "<" + bytes.length + " bytes>";
        } else {
            try {
                text = MAPPER.writeValueAsString(sanitize(body, 0));
            } catch (Exception e) {
                text = String.valueOf(body);
            }
        }
        text = text.replace('\r', ' ').replace('\n', ' ');
        int max = maxBodyChars;
        return text.length() <= max ? text : text.substring(0, max) + "...<+" + (text.length() - max) + " chars>";
    }

    private static Object sanitize(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        if (depth > 6) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), sanitize(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            for (Object o : it) {
                out.add(sanitize(o, depth + 1));
            }
            return out;
        }
        return String.valueOf(value);
    }

    private static String safeName(String service) {
        String s = service == null ? "unknown" : service.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9_.-]+", "_");
        return s.isEmpty() ? "unknown" : s;
    }

    private static boolean toBool(Object v, boolean fallback) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static int toInt(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static synchronized void shutdown() {
        Writer w = writer;
        writer = null;
        if (w != null) {
            w.close();
        }
    }

    private record Entry(LocalDate date, String service, String file, String text) {
    }

    /** Один фоновый поток: очередь → файлы по дате/сервису. */
    private static final class Writer implements Runnable {
        private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        private final Map<Path, BufferedWriter> open = new HashMap<>();
        private final Thread thread = new Thread(this, "traffic-log");
        private volatile boolean running = true;
        private long dropped;

        void start() {
            thread.setDaemon(true);
            thread.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "traffic-log-flush"));
        }

        void offer(Entry entry) {
            if (!queue.offer(entry)) {
                dropped++;
            }
        }

        @Override
        public void run() {
            while (running || !queue.isEmpty()) {
                try {
                    Entry first = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        flushAll();
                        continue;
                    }
                    write(first);
                    for (Entry next; (next = queue.poll()) != null; ) {
                        write(next);
                    }
                    flushAll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            flushAll();
        }

        private void write(Entry e) {
            Path base = baseDir;
            if (base == null) {
                return;
            }
            Path dir = base.resolve(sessionDir).resolve(e.service());
            Path file = dir.resolve(e.file());
            try {
                BufferedWriter w = open.get(file);
                if (w == null) {
                    Files.createDirectories(dir);
                    w = Files.newBufferedWriter(
                            file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    open.put(file, w);
                    closeStale(e.date());
                }
                w.write(e.text());
            } catch (IOException ex) {
                LOG.warn("traffic_log write failed {}: {}", file, ex.getMessage());
            }
        }

        /** Закрывает дескрипторы прошлых дат (смена суток). */
        private void closeStale(LocalDate current) {
            Path base = baseDir;
            if (base == null) {
                return;
            }
            Path currentDir = base.resolve(sessionDir);
            open.entrySet().removeIf(entry -> {
                if (entry.getKey().startsWith(currentDir)) {
                    return false;
                }
                try {
                    entry.getValue().close();
                } catch (IOException ignored) {
                }
                return true;
            });
        }

        private void flushAll() {
            for (BufferedWriter w : open.values()) {
                try {
                    w.flush();
                } catch (IOException ignored) {
                }
            }
            if (dropped > 0) {
                LOG.warn("traffic_log queue overflow, dropped {} lines", dropped);
                dropped = 0;
            }
        }

        void close() {
            running = false;
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushAll();
            for (BufferedWriter w : open.values()) {
                try {
                    w.close();
                } catch (IOException ignored) {
                }
            }
            open.clear();
        }
    }
}
