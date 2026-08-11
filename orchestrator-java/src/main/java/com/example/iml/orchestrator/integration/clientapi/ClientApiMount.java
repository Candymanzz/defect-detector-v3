package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.session.InspectionCycleResumeHolder;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;

import java.util.Map;

/**
 * HTTP API для фронта на том же порту, что {@code ui_http}: прокси к analisSurface и runtime-geometry.
 */
public record ClientApiMount(
        boolean enabled,
        GeometryRuntimeConfig geometryRuntime,
        String kopcheniBaseUrl,
        Map<String, Object> javaGeometryYaml,
        Map<String, Object> pythonDetectorYaml,
        Map<Integer, String> analysisProfileByCamera,
        PerCameraInspectionGate inspectionGate,
        ManualLineDirectionService manualLineDirection,
        PlcFinsServiceHolder plcFinsHolder,
        ClientWsServiceHolder clientWsHolder,
        InspectionCycleResumeHolder inspectionResumeHolder,
        UiTestAnalyzeServiceHolder uiTestAnalyzeHolder
) {
    public static ClientApiMount disabled() {
        return new ClientApiMount(
                false, null, "", null, null, Map.of(), null, null,
                new PlcFinsServiceHolder(), new ClientWsServiceHolder(), new InspectionCycleResumeHolder(),
                new UiTestAnalyzeServiceHolder()
        );
    }

    public static ClientApiMount fromRootYaml(
            Map<String, Object> root,
            GeometryRuntimeConfig geometryRuntime,
            PerCameraInspectionGate inspectionGate,
            ManualLineDirectionService manualLineDirection
    ) {
        return fromRootYaml(
                root,
                geometryRuntime,
                inspectionGate,
                manualLineDirection,
                new PlcFinsServiceHolder(),
                new ClientWsServiceHolder(),
                new InspectionCycleResumeHolder(),
                new UiTestAnalyzeServiceHolder()
        );
    }

    public static ClientApiMount fromRootYaml(
            Map<String, Object> root,
            GeometryRuntimeConfig geometryRuntime,
            PerCameraInspectionGate inspectionGate,
            ManualLineDirectionService manualLineDirection,
            PlcFinsServiceHolder plcFinsHolder
    ) {
        return fromRootYaml(
                root,
                geometryRuntime,
                inspectionGate,
                manualLineDirection,
                plcFinsHolder,
                new ClientWsServiceHolder(),
                new InspectionCycleResumeHolder(),
                new UiTestAnalyzeServiceHolder()
        );
    }

    public static ClientApiMount fromRootYaml(
            Map<String, Object> root,
            GeometryRuntimeConfig geometryRuntime,
            PerCameraInspectionGate inspectionGate,
            ManualLineDirectionService manualLineDirection,
            PlcFinsServiceHolder plcFinsHolder,
            ClientWsServiceHolder clientWsHolder
    ) {
        return fromRootYaml(
                root,
                geometryRuntime,
                inspectionGate,
                manualLineDirection,
                plcFinsHolder,
                clientWsHolder,
                new InspectionCycleResumeHolder(),
                new UiTestAnalyzeServiceHolder()
        );
    }

    public static ClientApiMount fromRootYaml(
            Map<String, Object> root,
            GeometryRuntimeConfig geometryRuntime,
            PerCameraInspectionGate inspectionGate,
            ManualLineDirectionService manualLineDirection,
            PlcFinsServiceHolder plcFinsHolder,
            ClientWsServiceHolder clientWsHolder,
            InspectionCycleResumeHolder inspectionResumeHolder
    ) {
        return fromRootYaml(
                root,
                geometryRuntime,
                inspectionGate,
                manualLineDirection,
                plcFinsHolder,
                clientWsHolder,
                inspectionResumeHolder,
                new UiTestAnalyzeServiceHolder()
        );
    }

    @SuppressWarnings("unchecked")
    public static ClientApiMount fromRootYaml(
            Map<String, Object> root,
            GeometryRuntimeConfig geometryRuntime,
            PerCameraInspectionGate inspectionGate,
            ManualLineDirectionService manualLineDirection,
            PlcFinsServiceHolder plcFinsHolder,
            ClientWsServiceHolder clientWsHolder,
            InspectionCycleResumeHolder inspectionResumeHolder,
            UiTestAnalyzeServiceHolder uiTestAnalyzeHolder
    ) {
        PlcFinsServiceHolder holder = plcFinsHolder == null ? new PlcFinsServiceHolder() : plcFinsHolder;
        ClientWsServiceHolder wsHolder = clientWsHolder == null ? new ClientWsServiceHolder() : clientWsHolder;
        InspectionCycleResumeHolder resumeHolder =
                inspectionResumeHolder == null ? new InspectionCycleResumeHolder() : inspectionResumeHolder;
        UiTestAnalyzeServiceHolder testHolder =
                uiTestAnalyzeHolder == null ? new UiTestAnalyzeServiceHolder() : uiTestAnalyzeHolder;
        if (root == null || geometryRuntime == null) {
            return new ClientApiMount(
                    false, null, "", null, null, Map.of(), null, null, holder, wsHolder, resumeHolder, testHolder);
        }
        Object raw = root.get("client_api");
        if (!(raw instanceof Map<?, ?> m)) {
            return new ClientApiMount(
                    false, null, "", null, null, Map.of(), null, null, holder, wsHolder, resumeHolder, testHolder);
        }
        boolean en = YamlScalars.toBool(m.get("enabled"), false);
        if (!en) {
            return new ClientApiMount(
                    false, null, "", null, null, Map.of(), null, null, holder, wsHolder, resumeHolder, testHolder);
        }
        String url = "";
        Object urlObj = m.get("kopcheni_base_url");
        if (urlObj != null) {
            url = String.valueOf(urlObj).trim();
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        Map<String, Object> jg = null;
        Object jgo = root.get("java_geometry");
        if (jgo instanceof Map<?, ?> jgm) {
            jg = (Map<String, Object>) jgm;
        }
        Map<String, Object> py = null;
        Object pyo = root.get("python_detector");
        if (pyo instanceof Map<?, ?> pym) {
            py = (Map<String, Object>) pym;
        }
        return new ClientApiMount(
                true,
                geometryRuntime,
                url,
                jg,
                py,
                com.example.iml.orchestrator.integration.config.ConfiguredCameras.analysisProfileByCameraId(root),
                inspectionGate,
                manualLineDirection,
                holder,
                wsHolder,
                resumeHolder,
                testHolder
        );
    }

    public boolean kopcheniConfigured() {
        return kopcheniBaseUrl != null && !kopcheniBaseUrl.isBlank();
    }
}
