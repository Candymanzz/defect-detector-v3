package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DetectorMetricProfileProxyTest {
    @Test
    void forwardsCameraIdentityAndMetricBodyWithoutChangingUnits() throws Exception {
        var captured=new AtomicReference<URI>();var capturedBody=new AtomicReference<String>();
        var backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/settings",exchange->{
            captured.set(exchange.getRequestURI());capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"saved\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        var controller=new OrchestratorDetectorSettingsHttpController("http://127.0.0.1:"+backend.getAddress().getPort(),Map.of(4,"custom-profile"));
        proxy.createContext("/api/orchestrator",exchange->controller.handle(new HttpRequestContext(exchange)));
        try{
            backend.start();proxy.start();
            String body="{\"horizontal_shift_tolerance_mm\":0.5,\"vertical_shift_tolerance_mm\":4}";
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/api/orchestrator/detector-settings/camera/4"))
                    .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals("/settings/custom-profile",captured.get().getPath());
            assertEquals("camera_id=4",captured.get().getQuery());assertEquals(body,capturedBody.get());
        }finally{proxy.stop(0);backend.stop(0);}
    }
}
