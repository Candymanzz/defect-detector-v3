package com.example.iml.orchestrator.integration.fanout;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Координация fan-out: робот (TCP) и HTTP-заглушка клиента по корневому YAML.
 * Фаза 7: при активном {@code client_ws} на TCP-робота не уходят «боевые» PASS/FAIL,
 * пока нет принятого пакета из пяти эталонов (см. {@link #setRobotCombatFanoutAllowed}).
 */
public final class FanOutCoordinator implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(FanOutCoordinator.class);

    private final RobotTcpPublisher robotPublisher;
    private final ClientHttpStubServer clientServer;
    /** Если {@code false} — событие не ставится в очередь робота (client_http получает как раньше). */
    private volatile BooleanSupplier robotCombatFanoutAllowed = () -> true;

    private FanOutCoordinator(RobotTcpPublisher robotPublisher, ClientHttpStubServer clientServer) {
        this.robotPublisher = robotPublisher;
        this.clientServer = clientServer;
    }

    /**
     * Вызывается из bootstrap при включённом {@code client_ws}: робот не получает решения инспекции,
     * пока {@code supplier} не вернёт {@code true} (принятый пакет эталонов в RAM).
     */
    public void setRobotCombatFanoutAllowed(BooleanSupplier supplier) {
        this.robotCombatFanoutAllowed = supplier == null ? () -> true : supplier;
    }

    public static FanOutCoordinator fromConfig(Map<String, Object> root) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fanout = (Map<String, Object>) root.get("fanout");
        if (fanout == null) {
            throw new IllegalStateException("fanout config section is missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> robotCfg = (Map<String, Object>) fanout.get("robot_tcp");
        @SuppressWarnings("unchecked")
        Map<String, Object> clientCfg = (Map<String, Object>) fanout.get("client_http");
        if (robotCfg == null || clientCfg == null) {
            throw new IllegalStateException("fanout.robot_tcp or fanout.client_http is missing");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> robotRoot = (Map<String, Object>) root.get("robot");
        boolean robotEnabled = toBool(robotRoot == null ? null : robotRoot.get("enabled"), true);

        String robotHost = String.valueOf(robotCfg.getOrDefault("host", "127.0.0.1"));
        int robotPort = toInt(robotCfg.get("port"), 9999);
        int robotQueue = toInt(robotCfg.get("queue_size"), 256);
        int robotConnectTimeout = toInt(robotCfg.get("connect_timeout_ms"), 300);
        int robotWriteTimeout = toInt(robotCfg.get("write_timeout_ms"), 300);

        String clientHost = String.valueOf(clientCfg.getOrDefault("host", "127.0.0.1"));
        int clientPort = toInt(clientCfg.get("port"), 8088);
        int clientQueue = toInt(clientCfg.get("queue_size"), 128);
        int clientDelay = toInt(clientCfg.get("artificial_delay_ms"), 0);

        RobotTcpPublisher robotPublisher = null;
        if (robotEnabled) {
            robotPublisher = new RobotTcpPublisher(robotHost, robotPort, robotConnectTimeout, robotWriteTimeout, robotQueue);
        } else {
            log.info("fanout robot_tcp disabled (robot.enabled=false)");
        }
        try {
            ClientHttpStubServer clientServer = new ClientHttpStubServer(clientHost, clientPort, clientQueue, clientDelay);
            log.info("fanout started robot={}:{} (enabled={}) client_http={}:{} delayMs={}",
                    robotHost, robotPort, robotEnabled, clientHost, clientPort, clientDelay);
            return new FanOutCoordinator(robotPublisher, clientServer);
        } catch (IOException e) {
            if (robotPublisher != null) {
                robotPublisher.close();
            }
            throw new IllegalStateException("failed to start client http stub", e);
        }
    }

    public void publish(FanOutEvent event) {
        if (robotPublisher != null && robotCombatFanoutAllowed.getAsBoolean()) {
            robotPublisher.publish(event);
        }
        clientServer.publish(event);
    }

    public String metricsSummary() {
        String robotPart = robotPublisher == null
                ? "robot=disabled"
                : ("robot.queueDepth=" + robotPublisher.queueDepth()
                + " robot.dropped=" + robotPublisher.droppedTotal());
        return robotPart
                + " client.queueDepth=" + clientServer.queueDepth()
                + " client.dropped=" + clientServer.droppedTotal();
    }

    @Override
    public void close() {
        clientServer.close();
        if (robotPublisher != null) {
            robotPublisher.close();
        }
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
