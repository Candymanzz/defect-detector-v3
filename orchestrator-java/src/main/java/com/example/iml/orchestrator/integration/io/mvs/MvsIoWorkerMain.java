package com.example.iml.orchestrator.integration.io.mvs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Native SDK boundary. A hung call is recovered by killing this process. */
public final class MvsIoWorkerMain {
    static final String PREFIX = "@IML_IO@";
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        // Reserve the original stdout exclusively for the protocol, including during SDK startup.
        ProcessHandle.current().parent().ifPresent(parent ->
                parent.onExit().thenRun(() -> Runtime.getRuntime().halt(1)));
        PrintStream protocol = System.out;
        System.setOut(System.err);
        serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
    }

    static void serve(BufferedReader input, PrintStream output) throws Exception {
        MvsIoDevice device = null;
        try {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                String op = request.path("op").asText();
                try {
                    Object result = null;
                    switch (op) {
                        case "open" -> {
                            device = MvsIoDevices.createNative(JSON.treeToValue(request.get("config"), MvsIoConfig.class));
                            device.open();
                        }
                        case "inputs" -> result = device.readInputs();
                        case "outputs" -> result = device.readOutputs();
                        case "health" -> device.checkConnection();
                        case "subscribe" -> device.subscribe(change -> send(output, Map.of("event", change)));
                        case "set" -> device.setOutput(request.path("port").asInt(), request.path("high").asBoolean());
                        case "setAll" -> device.setOutputs(JSON.convertValue(request.get("levels"),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<Integer, Boolean>>() {}));
                        case "timer" -> device.triggerTimer(request.path("timer").asText(), request.path("line").asText());
                        case "close" -> { if (device != null) device.close(); device = null; }
                        default -> throw new IllegalArgumentException("Unknown IO operation " + op);
                    }
                    var response = JSON.createObjectNode().put("ok", true);
                    response.set("result", JSON.valueToTree(result));
                    send(output, response);
                    if (op.equals("close")) return;
                } catch (RuntimeException | LinkageError e) {
                    send(output, Map.of("ok", false, "error", e.toString()));
                }
            }
        } finally {
            if (device != null) device.close();
        }
    }
    private static synchronized void send(PrintStream output, Object value) {
        try { output.println(PREFIX + JSON.writeValueAsString(value)); output.flush(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
