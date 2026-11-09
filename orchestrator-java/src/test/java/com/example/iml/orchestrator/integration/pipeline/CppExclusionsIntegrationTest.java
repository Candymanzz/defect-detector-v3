package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.clientapi.CppAnalisSurfaceHttpSupervisor;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.controller.FrameArchiveHttpController;
import com.example.iml.orchestrator.integration.http.controller.OrchestratorDetectorSettingsHttpController;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.ui.FrameArchiveConfig;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CppExclusionsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<List<List<Double>>> POLYGONS = List.of(List.of(
            List.of(.2,.3), List.of(.4,.3), List.of(.4,.6), List.of(.2,.6)));
    @TempDir Path dir;

    private static Map<String,Object> snapshot() {
        return Map.of("polygons",POLYGONS,"coordinate_space","normalized","surface_skipped",false,"remaining_area_work_px",1000);
    }

    private InspectionDecision decode(Map<String,Object> mask) throws Exception {
        var supervisor = new CppAnalisSurfaceHttpSupervisor("test","http://127.0.0.1:1",100);
        var method = CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("inspectResponse",Map.class,Map.class);
        method.setAccessible(true);
        var response = (BinaryProtocol.Message) method.invoke(supervisor,Map.of("product_type","bucket#cam=0"),Map.of(
                "status","ГОДЕН","anomaly_score",0.0,"inspection_exclusions",mask));
        return new DefaultInspectionDecisionAggregator(null).decide(0,
                new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE,Map.of("frame_id",42L),new byte[0]),response,
                new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE,Map.of("status","SKIPPED"),new byte[0]));
    }

    @Test void bindsAnImmutableMaskToTheActualFrame() throws Exception {
        List<Object> mutable = new ArrayList<>(POLYGONS);
        Map<String,Object> source = new LinkedHashMap<>(snapshot());source.put("polygons",mutable);
        var decision=decode(source);mutable.clear();source.put("surface_skipped",true);
        assertTrue(decision.overallPass());assertEquals(42,decision.frameId());
        assertEquals(snapshot(),decision.inspectionExclusions());
        assertThrows(UnsupportedOperationException.class,()->((List<?>)decision.inspectionExclusions().get("polygons")).clear());
        assertTrue(InspectionExclusionDiagnostics.fromResponse(null).isEmpty());
        assertTrue(InspectionDecision.simple(0,1,true,"ACCEPT",0,"PASS","SKIPPED").inspectionExclusions().isEmpty());
    }

    @Test void keepsMasksInArchiveAndHistoryAfterRestart() throws Exception {
        var decision=decode(snapshot());var config=new FrameArchiveConfig(true,dir.resolve("archive"),10,10);
        var frame=dir.resolve("frame.jpg");javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8,8,1),"jpg",frame.toFile());
        try(var archive=FrameArchiveService.open(config)) {
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(0,42,1,"bucket","cpp",decision,frame,null,0,0)));
        }
        try(var archive=FrameArchiveService.open(config)) {
            assertEquals(snapshot(),archive.listHistory(0).get(0).inspectionExclusions());
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var controller=new FrameArchiveHttpController(archive);
            server.createContext("/api/frame-archive",exchange->controller.handle(new HttpRequestContext(exchange)));server.start();
            try {
                var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/frame-archive/cameras/0/history");
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());
                assertEquals(JSON.valueToTree(snapshot()),JSON.readTree(response.body()).path("frames").get(0).path("inspection_exclusions"));
            }finally{server.stop(0);}
        }
        var result=JSON.readTree(Files.readString(dir.resolve("archive/camera_0/f_0000042/result.json")));
        assertEquals(JSON.valueToTree(snapshot()),result.path("inspection_exclusions"));
    }

    @Test void existingSettingsProxyPreservesZonesAndUpstreamErrors() throws Exception {
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<Map<String,Object>> settings=new AtomicReference<>(Map.of("exclusions",List.of()));
        AtomicReference<String> target=new AtomicReference<>();
        upstream.createContext("/settings/",exchange->{
            target.set(exchange.getRequestURI().toString());int status=200;
            if(exchange.getRequestMethod().equals("PUT")) {
                var body=JSON.readValue(exchange.getRequestBody(),Map.class);
                if(body.get("exclusions") instanceof String) status=400;else settings.set(body);
            }
            byte[] body=JSON.writeValueAsBytes(status==200?Map.of("settings",settings.get(),"saved",true,"analysis_profile","camera-0","detector_id","cpp-test")
                    :Map.of("detail","exclusions must be an array","error_code","bad_settings","detector_id","cpp-test"));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });upstream.start();
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var controller=new OrchestratorDetectorSettingsHttpController("http://127.0.0.1:"+upstream.getAddress().getPort(),Map.of(0,"camera-0"));
        proxy.createContext("/api/orchestrator/detector-settings",exchange->controller.handle(new HttpRequestContext(exchange)));proxy.start();
        try {
            var client=HttpClient.newHttpClient();var uri=URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/api/orchestrator/detector-settings/camera/0");
            var request=HttpRequest.newBuilder(uri).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("exclusions",POLYGONS)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            assertEquals("/settings/camera-0?camera_id=0",target.get());
            assertEquals(JSON.valueToTree(POLYGONS),JSON.readTree(response.body()).path("settings").path("exclusions"));
            var read=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,read.statusCode());
            assertEquals(JSON.valueToTree(POLYGONS),JSON.readTree(read.body()).path("settings").path("exclusions"));
            var invalid=client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"exclusions\":\"bad\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode());assertEquals("bad_settings",JSON.readTree(invalid.body()).path("error_code").asText());
        }finally{proxy.stop(0);upstream.stop(0);}
    }

    @Test void temporaryTestSettingsReachTheDetectorWithoutFiltering() throws Exception {
        var method=CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("ephemeralSettings",Map.class);method.setAccessible(true);
        var expected=Map.of("exclusions",POLYGONS);
        assertEquals(expected,method.invoke(null,Map.of("analysis_test_settings",Map.of("settings",expected))));
    }
}
