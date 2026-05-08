import { useCallback, useState } from "react";
import { BatchTestSection } from "../batch-tests/components/BatchTestSection";
import { useBatchTests } from "../batch-tests/hooks/useBatchTests";
import { HeatmapFpPanel } from "../fp-zones/components/HeatmapFpPanel";
import { useFpZones } from "../fp-zones/hooks/useFpZones";
import { InspectionLogTable } from "../features/inspection/components/InspectionLogTable";
import { useInspection } from "../features/inspection/hooks/useInspection";
import { STORAGE_KEYS } from "./constants";
import { DEFAULT_STATE, getInitialProductType, getInitialThreshold } from "./initialState";
import { useApiBaseUrl } from "./hooks/useApiBaseUrl";
import { usePersistedState } from "./hooks/usePersistedState";
import { useTrackedAction } from "./hooks/useTrackedAction";
import { AnomalyScoreMeter } from "./components/AnomalyScoreMeter";
import { AppHeader } from "./components/AppHeader";
import { OperatorControlsPanel } from "./components/OperatorControlsPanel";
import { OriginalImagePanel } from "./components/OriginalImagePanel";
import { ResultImageCard } from "./components/ResultImageCard";
import { StatusBadge } from "./components/StatusBadge";
import { ReferenceTemplatePanel } from "../roi/components/ReferenceTemplatePanel";
import { useImageBoxes } from "../roi/hooks/useImageBoxes";
import { useReferenceWorkspace } from "../roi/hooks/useReferenceWorkspace";
import { useBucketProjection } from "../roi/hooks/useBucketProjection";
import { getNormalizedPointInImageBox } from "../roi/lib/imageBox";

const getInitialImages = () => ({ ...DEFAULT_STATE.images });

function App() {
  const {
    apiBaseUrl,
    apiBaseUrlDraft,
    setApiBaseUrlDraft,
    configReady,
    saveApiBaseUrl,
    apiFetch,
    logDesktop
  } = useApiBaseUrl();

  const [busy, setBusy] = useState(false);
  const [productType, setProductType] = usePersistedState(
    STORAGE_KEYS.productType,
    getInitialProductType
  );
  const [threshold, setThreshold] = usePersistedState(STORAGE_KEYS.threshold, getInitialThreshold);
  const [cameraSource, setCameraSource] = useState(DEFAULT_STATE.cameraSource);
  const [activeContourMode, setActiveContourMode] = useState(null);

  const [referencePreview, setReferencePreview] = useState("");
  const [images, setImages] = useState(getInitialImages);
  const [capturedFile, setCapturedFile] = useState(null);
  const [roiPolygon, setRoiPolygon] = useState([]);
  const [itemPolygon, setItemPolygon] = useState([]);

  const runTracked = useTrackedAction({ logDesktop, setBusy });

  const {
    referenceContainerRef,
    heatmapContainerRef,
    referenceBox,
    heatmapBox,
    heatmapImageSize,
    handleReferenceImageLoad,
    handleHeatmapImageLoad
  } = useImageBoxes({
    referencePreview,
    heatmapPreview: images.heatmap
  });

  const {
    fpPolygonDraft,
    fpZones,
    lastRecheckedZoneIds,
    recheckStats,
    addFpPoint,
    clearFpDraft,
    clearFpZones,
    applyInspectionRecheck,
    resetInspectionRecheck,
    loadFpZones,
    saveFpZone,
    deleteFpZone
  } = useFpZones({
    apiFetch,
    logDesktop,
    productType,
    heatmapImageSize,
    setActiveContourMode,
    setBusy
  });

  const {
    status,
    score,
    logs,
    handlePickImage,
    runInspection,
    runInspectionFromCamera,
    resetInspection
  } = useInspection({
    apiFetch,
    productType,
    threshold,
    cameraSource,
    capturedFile,
    setCapturedFile,
    setImages,
    applyInspectionRecheck,
    resetInspectionRecheck,
    runTracked
  });

  const {
    roi,
    setRoi,
    roiArea,
    itemArea,
    roiCoveragePercent,
    addPolygonPoint,
    clearRoiPolygon,
    clearItemPolygon,
    uploadReference,
    uploadReferenceFromCamera,
    saveRoi,
    savePolygonRoi
  } = useReferenceWorkspace({
    apiFetch,
    productType,
    apiBaseUrl,
    configReady,
    capturedFile,
    cameraSource,
    referenceBox,
    activeContourMode,
    setActiveContourMode,
    referencePreview,
    setReferencePreview,
    roiPolygon,
    setRoiPolygon,
    itemPolygon,
    setItemPolygon,
    setImages,
    runTracked,
    loadFpZones,
    clearFpDraft,
    clearFpZones
  });

  const {
    bucketGeometry,
    setBucketGeometry,
    projectionParams,
    setProjectionParams,
    bucketSideArea,
    physicalAreaStats
  } = useBucketProjection({ itemPolygon, roiPolygon, referenceBox });

  const {
    normalTestLogs,
    normalTestSummary,
    brackTestLogs,
    brackTestSummary,
    runBatchTest,
    resetBatchTests
  } = useBatchTests({ apiFetch, productType, threshold, runTracked });

  const toggleContourMode = useCallback((mode) => {
    setActiveContourMode((prev) => (prev === mode ? null : mode));
  }, []);

  const toggleRoiMode = useCallback(() => toggleContourMode("roi"), [toggleContourMode]);
  const toggleItemMode = useCallback(() => toggleContourMode("item"), [toggleContourMode]);
  const toggleFpMode = useCallback(() => toggleContourMode("fp"), [toggleContourMode]);

  const addHeatmapFpPoint = useCallback(
    (event) => {
      if (activeContourMode !== "fp" || !images.heatmap) return;
      const point = getNormalizedPointInImageBox({
        clientX: event.clientX,
        clientY: event.clientY,
        containerRect: event.currentTarget.getBoundingClientRect(),
        imageBox: heatmapBox
      });
      if (!point) return;
      addFpPoint(point);
    },
    [activeContourMode, addFpPoint, heatmapBox, images.heatmap]
  );

  const resetAll = useCallback(() => {
    resetInspection();
    resetBatchTests();
  }, [resetBatchTests, resetInspection]);

  return (
    <main className="min-h-screen bg-transparent p-6 text-slate-100">
      <div className="mx-auto max-w-7xl space-y-6">
        <AppHeader />

        <section className="grid gap-6 lg:grid-cols-3">
          <OriginalImagePanel imageSrc={images.original} />

          <div className="space-y-4 rounded-2xl border border-slate-700 bg-panel p-4">
            <StatusBadge status={status} />

            <AnomalyScoreMeter score={score} threshold={threshold} />

            <ReferenceTemplatePanel
              referencePreview={referencePreview}
              referenceContainerRef={referenceContainerRef}
              activeContourMode={activeContourMode}
              onAddPolygonPoint={addPolygonPoint}
              onReferenceImageLoad={handleReferenceImageLoad}
              referenceBox={referenceBox}
              roiPolygon={roiPolygon}
              itemPolygon={itemPolygon}
              busy={busy}
              onToggleRoiMode={toggleRoiMode}
              onToggleItemMode={toggleItemMode}
              onClearRoiPolygon={clearRoiPolygon}
              onClearItemPolygon={clearItemPolygon}
              onSavePolygonRoi={savePolygonRoi}
              roiArea={roiArea}
              itemArea={itemArea}
              roiCoveragePercent={roiCoveragePercent}
              bucketGeometry={bucketGeometry}
              onBucketGeometryChange={setBucketGeometry}
              bucketSideArea={bucketSideArea}
              projectionParams={projectionParams}
              onProjectionParamsChange={setProjectionParams}
              physicalAreaStats={physicalAreaStats}
            />
          </div>
        </section>

        <section className="grid gap-4 rounded-2xl border border-slate-700 bg-panel p-4 md:grid-cols-3">
          <ResultImageCard title="Оригинал" imageSrc={images.original} alt="Оригинал" />
          <ResultImageCard title="Карта разницы" imageSrc={images.diff} alt="Карта разницы" />
          <HeatmapFpPanel
            imageSrc={images.heatmap}
            containerRef={heatmapContainerRef}
            activeContourMode={activeContourMode}
            onAddPoint={addHeatmapFpPoint}
            onImageLoad={handleHeatmapImageLoad}
            heatmapBox={heatmapBox}
            fpZones={fpZones}
            lastRecheckedZoneIds={lastRecheckedZoneIds}
            fpPolygonDraft={fpPolygonDraft}
            busy={busy}
            onToggleFpMode={toggleFpMode}
            onClearDraft={clearFpDraft}
            onSaveFpZone={saveFpZone}
            recheckStats={recheckStats}
            onDeleteZone={deleteFpZone}
          />
        </section>

        <OperatorControlsPanel
          productType={productType}
          onProductTypeChange={setProductType}
          onPickImage={handlePickImage}
          onUploadReference={uploadReference}
          onUploadReferenceFromCamera={uploadReferenceFromCamera}
          onRunInspection={runInspection}
          onRunInspectionFromCamera={runInspectionFromCamera}
          onRunBatchTest={runBatchTest}
          onReset={resetAll}
          capturedFile={capturedFile}
          busy={busy}
          apiBaseUrlDraft={apiBaseUrlDraft}
          onApiBaseUrlDraftChange={setApiBaseUrlDraft}
          onSaveApiBaseUrl={saveApiBaseUrl}
          cameraSource={cameraSource}
          onCameraSourceChange={setCameraSource}
          roi={roi}
          onRoiChange={setRoi}
          onSaveRoi={saveRoi}
          threshold={threshold}
          onThresholdChange={setThreshold}
        />

        <InspectionLogTable logs={logs} />

        <BatchTestSection
          title="Тесты по папке normal"
          logs={normalTestLogs}
          summary={normalTestSummary}
        />

        <BatchTestSection
          title="Тесты по папке brack"
          logs={brackTestLogs}
          summary={brackTestSummary}
        />
      </div>
    </main>
  );
}

export default App;
