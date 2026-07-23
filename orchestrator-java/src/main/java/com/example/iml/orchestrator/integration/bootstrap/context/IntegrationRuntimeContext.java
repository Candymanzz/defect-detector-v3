package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.CloseableIntegrationComponent;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Mutable bag ресурсов интеграции на время {@code start()} (зеркало {@link IntegrationShutdownCoordinator.ShutdownResources}).
 */
public final class IntegrationRuntimeContext {

    private final Logger log;
    private final Map<String, Object> root;
    private final Path projectRoot;
    private final boolean windows;

    private Map<String, Object> integration;
    private List<Map<String, Object>> cameras = List.of();
    private List<Map<String, Object>> activeCameras = List.of();
    private IntegrationBootConfig bootConfig;
    private Path workerBin;
    private Path workerConfigPath;

    private ServicePoolLifecycle servicePools;
    private List<BinaryRpcSupervisor> pythonPool = List.of();
    private List<ServiceProcessSupervisor> geometryPool = List.of();
    private List<ServiceProcessSupervisor> positioningPool = List.of();
    private List<ExternalServiceProcess> analisSurfaceProcesses = List.of();
    private ExternalServiceProcess lightServerProcess;
    private ExternalServiceProcess ioInputMonitorProcess;
    private ExternalServiceProcess frontendProcess;

    private GeometrySnapshotCache geometrySnapshotCache;
    private GeometryRuntimeConfig geometryRuntimeConfig;
    private PerCameraInspectionGate inspectionGate;
    private ManualLineDirectionService manualLineDirection;
    private PlcFinsServiceHolder plcFinsHolder;
    private ClientApiMount clientApiMount;
    private WorkerCaptureCoordinator captureCoordinator;
    private UiArtifactsSidecar uiSidecar;
    private InspectionPipeline inspectionPipeline;
    private PipelineReferenceRegistry pipelineReferenceRegistry;
    private Map<Integer, String> detectorByCamera = Map.of();
    private Map<Integer, ReferenceSnapshot> referenceByCamera;

    private LightBrightnessStore lightBrightnessStore;
    private LightTriggerClient lightClient;
    private int flashLeadMs;

    private CameraSettingsStore cameraSettingsStore;
    private FrameArchiveService frameArchiveService;
    private UiHttpServer uiServer;
    private ClientWebSocketServer clientWsServer;
    private BinaryRpcSupervisor uiVisualsPython;
    private ExecutorService uiArtifactsExecutor;

    private ScheduledExecutorService shmJanitorScheduler;
    private PipelineStagesLog pipelineStagesLog;
    private FanOutCoordinator fanOut;
    private Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
    private CameraStreamService cameraStreamService;
    private LivePreviewPublisher livePreview;
    private final LivePreviewGate livePreviewGate = new LivePreviewGate();
    private LineSynchronizedCaptureCoordinator lineCaptureCoordinator;
    private InspectionTriggerRuntime triggerRuntime;
    private IntervalFlashController intervalFlashController;
    private BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster;
    private BucketInspectionAggregator bucketInspectionAggregator;
    private InspectionTriggerStrategy sharedTriggerStrategy;

    private ExecutorService cameraExecutor;
    private ExecutorService captureStageExecutor;
    private ExecutorService pythonStageExecutor;
    private ExecutorService geometryStageExecutor;
    private ExecutorService decisionStageExecutor;

    private Map<String, Object> pythonCfg;
    private Map<String, Object> geometryCfg;
    private Map<String, Object> positioningCfg;
    private Map<String, Object> uiCfg;

    public IntegrationRuntimeContext(Logger log, Map<String, Object> root, Path projectRoot, boolean windows) {
        this.log = log;
        this.root = root;
        this.projectRoot = projectRoot;
        this.windows = windows;
    }

    public Logger log() {
        return log;
    }

    public Map<String, Object> root() {
        return root;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public boolean windows() {
        return windows;
    }

    public Map<String, Object> integration() {
        return integration;
    }

    public void setIntegration(Map<String, Object> integration) {
        this.integration = integration;
    }

    public List<Map<String, Object>> cameras() {
        return cameras;
    }

    public void setCameras(List<Map<String, Object>> cameras) {
        this.cameras = cameras;
    }

    public List<Map<String, Object>> activeCameras() {
        return activeCameras;
    }

    public void setActiveCameras(List<Map<String, Object>> activeCameras) {
        this.activeCameras = activeCameras;
    }

    public IntegrationBootConfig bootConfig() {
        return bootConfig;
    }

    public void setBootConfig(IntegrationBootConfig bootConfig) {
        this.bootConfig = bootConfig;
    }

    public Path workerBin() {
        return workerBin;
    }

    public void setWorkerBin(Path workerBin) {
        this.workerBin = workerBin;
    }

    public Path workerConfigPath() {
        return workerConfigPath;
    }

    public void setWorkerConfigPath(Path workerConfigPath) {
        this.workerConfigPath = workerConfigPath;
    }

    public ServicePoolLifecycle servicePools() {
        return servicePools;
    }

    public void setServicePools(ServicePoolLifecycle servicePools) {
        this.servicePools = servicePools;
    }

    public List<BinaryRpcSupervisor> pythonPool() {
        return pythonPool;
    }

    public void setPythonPool(List<BinaryRpcSupervisor> pythonPool) {
        this.pythonPool = pythonPool == null ? List.of() : pythonPool;
    }

    public List<ServiceProcessSupervisor> geometryPool() {
        return geometryPool;
    }

    public void setGeometryPool(List<ServiceProcessSupervisor> geometryPool) {
        this.geometryPool = geometryPool == null ? List.of() : geometryPool;
    }

    public List<ServiceProcessSupervisor> positioningPool() {
        return positioningPool;
    }

    public void setPositioningPool(List<ServiceProcessSupervisor> positioningPool) {
        this.positioningPool = positioningPool == null ? List.of() : positioningPool;
    }

    public List<ExternalServiceProcess> analisSurfaceProcesses() {
        return analisSurfaceProcesses;
    }

    public void setAnalisSurfaceProcesses(List<ExternalServiceProcess> analisSurfaceProcesses) {
        this.analisSurfaceProcesses = analisSurfaceProcesses == null ? List.of() : analisSurfaceProcesses;
    }

    public ExternalServiceProcess lightServerProcess() {
        return lightServerProcess;
    }

    public void setLightServerProcess(ExternalServiceProcess lightServerProcess) {
        this.lightServerProcess = lightServerProcess;
    }

    public ExternalServiceProcess ioInputMonitorProcess() {
        return ioInputMonitorProcess;
    }

    public void setIoInputMonitorProcess(ExternalServiceProcess ioInputMonitorProcess) {
        this.ioInputMonitorProcess = ioInputMonitorProcess;
    }

    public ExternalServiceProcess frontendProcess() {
        return frontendProcess;
    }

    public void setFrontendProcess(ExternalServiceProcess frontendProcess) {
        this.frontendProcess = frontendProcess;
    }

    public GeometrySnapshotCache geometrySnapshotCache() {
        return geometrySnapshotCache;
    }

    public void setGeometrySnapshotCache(GeometrySnapshotCache geometrySnapshotCache) {
        this.geometrySnapshotCache = geometrySnapshotCache;
    }

    public GeometryRuntimeConfig geometryRuntimeConfig() {
        return geometryRuntimeConfig;
    }

    public void setGeometryRuntimeConfig(GeometryRuntimeConfig geometryRuntimeConfig) {
        this.geometryRuntimeConfig = geometryRuntimeConfig;
    }

    public PerCameraInspectionGate inspectionGate() {
        return inspectionGate;
    }

    public void setInspectionGate(PerCameraInspectionGate inspectionGate) {
        this.inspectionGate = inspectionGate;
    }

    public ManualLineDirectionService manualLineDirection() {
        return manualLineDirection;
    }

    public void setManualLineDirection(ManualLineDirectionService manualLineDirection) {
        this.manualLineDirection = manualLineDirection;
    }

    public PlcFinsServiceHolder plcFinsHolder() {
        return plcFinsHolder;
    }

    public void setPlcFinsHolder(PlcFinsServiceHolder plcFinsHolder) {
        this.plcFinsHolder = plcFinsHolder;
    }

    public ClientApiMount clientApiMount() {
        return clientApiMount;
    }

    public void setClientApiMount(ClientApiMount clientApiMount) {
        this.clientApiMount = clientApiMount;
    }

    public WorkerCaptureCoordinator captureCoordinator() {
        return captureCoordinator;
    }

    public void setCaptureCoordinator(WorkerCaptureCoordinator captureCoordinator) {
        this.captureCoordinator = captureCoordinator;
    }

    public UiArtifactsSidecar uiSidecar() {
        return uiSidecar;
    }

    public void setUiSidecar(UiArtifactsSidecar uiSidecar) {
        this.uiSidecar = uiSidecar;
    }

    public InspectionPipeline inspectionPipeline() {
        return inspectionPipeline;
    }

    public void setInspectionPipeline(InspectionPipeline inspectionPipeline) {
        this.inspectionPipeline = inspectionPipeline;
    }

    public PipelineReferenceRegistry pipelineReferenceRegistry() {
        return pipelineReferenceRegistry;
    }

    public void setPipelineReferenceRegistry(PipelineReferenceRegistry pipelineReferenceRegistry) {
        this.pipelineReferenceRegistry = pipelineReferenceRegistry;
        this.referenceByCamera = pipelineReferenceRegistry == null ? null : pipelineReferenceRegistry.byCamera();
    }

    public Map<Integer, String> detectorByCamera() {
        return detectorByCamera;
    }

    public void setDetectorByCamera(Map<Integer, String> detectorByCamera) {
        this.detectorByCamera = detectorByCamera == null ? Map.of() : detectorByCamera;
    }

    public Map<Integer, ReferenceSnapshot> referenceByCamera() {
        return referenceByCamera;
    }

    public LightBrightnessStore lightBrightnessStore() {
        return lightBrightnessStore;
    }

    public void setLightBrightnessStore(LightBrightnessStore lightBrightnessStore) {
        this.lightBrightnessStore = lightBrightnessStore;
    }

    public LightTriggerClient lightClient() {
        return lightClient;
    }

    public void setLightClient(LightTriggerClient lightClient) {
        this.lightClient = lightClient;
    }

    public int flashLeadMs() {
        return flashLeadMs;
    }

    public void setFlashLeadMs(int flashLeadMs) {
        this.flashLeadMs = flashLeadMs;
    }

    public CameraSettingsStore cameraSettingsStore() {
        return cameraSettingsStore;
    }

    public void setCameraSettingsStore(CameraSettingsStore cameraSettingsStore) {
        this.cameraSettingsStore = cameraSettingsStore;
    }

    public FrameArchiveService frameArchiveService() {
        return frameArchiveService;
    }

    public void setFrameArchiveService(FrameArchiveService frameArchiveService) {
        this.frameArchiveService = frameArchiveService;
    }

    public UiHttpServer uiServer() {
        return uiServer;
    }

    public void setUiServer(UiHttpServer uiServer) {
        this.uiServer = uiServer;
    }

    public ClientWebSocketServer clientWsServer() {
        return clientWsServer;
    }

    public void setClientWsServer(ClientWebSocketServer clientWsServer) {
        this.clientWsServer = clientWsServer;
    }

    public BinaryRpcSupervisor uiVisualsPython() {
        return uiVisualsPython;
    }

    public void setUiVisualsPython(BinaryRpcSupervisor uiVisualsPython) {
        this.uiVisualsPython = uiVisualsPython;
    }

    public ExecutorService uiArtifactsExecutor() {
        return uiArtifactsExecutor;
    }

    public void setUiArtifactsExecutor(ExecutorService uiArtifactsExecutor) {
        this.uiArtifactsExecutor = uiArtifactsExecutor;
    }

    public ScheduledExecutorService shmJanitorScheduler() {
        return shmJanitorScheduler;
    }

    public void setShmJanitorScheduler(ScheduledExecutorService shmJanitorScheduler) {
        this.shmJanitorScheduler = shmJanitorScheduler;
    }

    public PipelineStagesLog pipelineStagesLog() {
        return pipelineStagesLog;
    }

    public void setPipelineStagesLog(PipelineStagesLog pipelineStagesLog) {
        this.pipelineStagesLog = pipelineStagesLog;
    }

    public FanOutCoordinator fanOut() {
        return fanOut;
    }

    public void setFanOut(FanOutCoordinator fanOut) {
        this.fanOut = fanOut;
    }

    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workersByCamera;
    }

    public void setWorkersByCamera(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        this.workersByCamera = workersByCamera == null ? new LinkedHashMap<>() : workersByCamera;
    }

    public CameraStreamService cameraStreamService() {
        return cameraStreamService;
    }

    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        this.cameraStreamService = cameraStreamService;
    }

    public LivePreviewPublisher livePreview() {
        return livePreview;
    }

    public void setLivePreview(LivePreviewPublisher livePreview) {
        this.livePreview = livePreview;
    }

    public LivePreviewGate livePreviewGate() {
        return livePreviewGate;
    }

    public LineSynchronizedCaptureCoordinator lineCaptureCoordinator() {
        return lineCaptureCoordinator;
    }

    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator lineCaptureCoordinator) {
        this.lineCaptureCoordinator = lineCaptureCoordinator;
    }

    public InspectionTriggerRuntime triggerRuntime() {
        return triggerRuntime;
    }

    public void setTriggerRuntime(InspectionTriggerRuntime triggerRuntime) {
        this.triggerRuntime = triggerRuntime;
    }

    public IntervalFlashController intervalFlashController() {
        return intervalFlashController;
    }

    public void setIntervalFlashController(IntervalFlashController intervalFlashController) {
        this.intervalFlashController = intervalFlashController;
    }

    public BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster() {
        return bucketLineTriggerBroadcaster;
    }

    public void setBucketLineTriggerBroadcaster(BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster) {
        this.bucketLineTriggerBroadcaster = bucketLineTriggerBroadcaster;
    }

    public BucketInspectionAggregator bucketInspectionAggregator() {
        return bucketInspectionAggregator;
    }

    public void setBucketInspectionAggregator(BucketInspectionAggregator bucketInspectionAggregator) {
        this.bucketInspectionAggregator = bucketInspectionAggregator;
    }

    public InspectionTriggerStrategy sharedTriggerStrategy() {
        return sharedTriggerStrategy;
    }

    public void setSharedTriggerStrategy(InspectionTriggerStrategy sharedTriggerStrategy) {
        this.sharedTriggerStrategy = sharedTriggerStrategy;
    }

    public ExecutorService cameraExecutor() {
        return cameraExecutor;
    }

    public void setCameraExecutor(ExecutorService cameraExecutor) {
        this.cameraExecutor = cameraExecutor;
    }

    public ExecutorService captureStageExecutor() {
        return captureStageExecutor;
    }

    public void setCaptureStageExecutor(ExecutorService captureStageExecutor) {
        this.captureStageExecutor = captureStageExecutor;
    }

    public ExecutorService pythonStageExecutor() {
        return pythonStageExecutor;
    }

    public void setPythonStageExecutor(ExecutorService pythonStageExecutor) {
        this.pythonStageExecutor = pythonStageExecutor;
    }

    public ExecutorService geometryStageExecutor() {
        return geometryStageExecutor;
    }

    public void setGeometryStageExecutor(ExecutorService geometryStageExecutor) {
        this.geometryStageExecutor = geometryStageExecutor;
    }

    public ExecutorService decisionStageExecutor() {
        return decisionStageExecutor;
    }

    public void setDecisionStageExecutor(ExecutorService decisionStageExecutor) {
        this.decisionStageExecutor = decisionStageExecutor;
    }

    public Map<String, Object> pythonCfg() {
        return pythonCfg;
    }

    public void setPythonCfg(Map<String, Object> pythonCfg) {
        this.pythonCfg = pythonCfg;
    }

    public Map<String, Object> geometryCfg() {
        return geometryCfg;
    }

    public void setGeometryCfg(Map<String, Object> geometryCfg) {
        this.geometryCfg = geometryCfg;
    }

    public Map<String, Object> positioningCfg() {
        return positioningCfg;
    }

    public void setPositioningCfg(Map<String, Object> positioningCfg) {
        this.positioningCfg = positioningCfg;
    }

    public Map<String, Object> uiCfg() {
        return uiCfg;
    }

    public void setUiCfg(Map<String, Object> uiCfg) {
        this.uiCfg = uiCfg;
    }

    public List<IntegrationComponent> managedRuntimeComponents() {
        List<IntegrationComponent> components = new ArrayList<>();
        components.add(CloseableIntegrationComponent.ofNullable(bucketLineTriggerBroadcaster));
        components.add(CloseableIntegrationComponent.ofNullable(bucketInspectionAggregator));
        components.add(CloseableIntegrationComponent.ofNullable(livePreview));
        components.add(CloseableIntegrationComponent.ofNullable(cameraStreamService));
        components.add(CloseableIntegrationComponent.ofNullable(triggerRuntime));
        return components.stream().filter(c -> c != null).toList();
    }

    public IntegrationShutdownCoordinator.ShutdownResources toShutdownResources() {
        return new IntegrationShutdownCoordinator.ShutdownResources(
                pipelineStagesLog,
                cameraExecutor,
                captureStageExecutor,
                pythonStageExecutor,
                geometryStageExecutor,
                decisionStageExecutor,
                shmJanitorScheduler,
                workersByCamera,
                pythonPool,
                geometryPool,
                positioningPool,
                lightServerProcess,
                ioInputMonitorProcess,
                frontendProcess,
                analisSurfaceProcesses,
                lightClient,
                uiVisualsPython,
                uiArtifactsExecutor,
                fanOut,
                clientWsServer,
                uiServer,
                frameArchiveService,
                servicePools,
                log
        );
    }
}
