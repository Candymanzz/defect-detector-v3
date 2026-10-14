package com.example.iml.orchestrator.integration.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontendDiagnosticsTest {
    @Test
    void acceptsMetadataAndRejectsInvalidOrOversizedBatches() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FrontendDiagnostics.register(server);
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/diagnostics/frontend");
            var client = HttpClient.newHttpClient();
            assertEquals(204, post(client, uri, "[{\"channel\":\"decisions\",\"event\":\"inspect\",\"details\":{\"state\":\"accepted\",\"image\":\"omitted\"}}]"));
            assertEquals(400, post(client, uri, "{}"));
            assertEquals(400, post(client, uri, "invalid JSON"));
            assertEquals(413, post(client, uri, "x".repeat(16385)));
        } finally { server.stop(0); }
    }

    private int post(HttpClient client, URI uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
