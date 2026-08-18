import { useEffect } from "react";
import { useRef, useState } from "react";
import type { Ref } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { InspectionHistory } from "../InspectionHistory";
import { ArchiveHistoryViewer } from "../ArchiveHistoryViewer/ArchiveHistoryViewer";
import { AnalysisSettingsPanel } from "../SettingList/AnalysisSettingsPanel";
import type { AnalysisSettingsPanelHandle } from "../SettingList/AnalysisSettingsPanel";
import { GeometryTestSettingsPanel } from "../SettingList/GeometryTestSettingsPanel";
import type { GeometryTestSettingsPanelHandle } from "../SettingList/GeometryTestSettingsPanel";
import { resolveInspectionResultState, isCaptureOnlyInspectResult } from "../../shared/inspectResult";
import { orchestratorApi } from "../../shared/api";
import { StatusCard } from "../../shared/ui/StatusCard";
import { createCameraCards, createSelectedCamera } from "./MainController";
import { resolveCardInspectImageUrl } from "./MainController";
import { useMainOverview } from "./useMainOverview";
import type { InspectionStats } from "./type";
import "./MainOverview.css";
import "../SettingList/TestSettingsPanels.css";

const CAMERAS_PER_OVERVIEW = 5;

type MainOverviewProps = {
  inspectionResetVersion: number;
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
  onAnalysisSettingsOpen: (cameraId: number) => Promise<void>;
  onInspectionStatsChange?: (stats: InspectionStats) => void;
  rootRef?: Ref<HTMLDivElement>;
};

export function MainOverview({
  inspectionResetVersion,
  selectedSettingsCameraId,
  onSettingsCameraToggle,
  onAnalysisSettingsOpen,
  onInspectionStatsChange,
  rootRef,
}: MainOverviewProps) {
  const controller = useMainOverview(inspectionResetVersion);
  const [showModalAnalysisSettings, setShowModalAnalysisSettings] = useState(false);
  const [testFrameId, setTestFrameId] = useState<string | undefined>(undefined);
  const [testAnalyzeState, setTestAnalyzeState] = useState<"idle" | "submitting" | "awaiting" | "complete" | "error">(
    "idle",
  );
  const [testAnalyzeMessage, setTestAnalyzeMessage] = useState("");
  const pendingTestRef = useRef<{ cameraId: number; frameId: string; previousServerTs: number }>({
    cameraId: -1,
    frameId: "",
    previousServerTs: 0,
  });
  const analysisSettingsRef = useRef<AnalysisSettingsPanelHandle>(null);
  const geometrySettingsRef = useRef<GeometryTestSettingsPanelHandle>(null);
  const cameraCards = createCameraCards(controller.cameraIds, controller.previewImageUrlsByCameraId);
  const cameraCardGroups = chunkItems(cameraCards, CAMERAS_PER_OVERVIEW);
  const modalInspectionControlState = controller.modalSnapshot
    ? controller.inspectionControlByCameraId[controller.modalSnapshot.cameraId]
    : undefined;

  const exitTestModeAndResume = async () => {
    await orchestratorApi.setTestMode(false);
    const inspectionState = await orchestratorApi.startAllInspections();
    window.dispatchEvent(new CustomEvent("inspection-control-changed", { detail: inspectionState }));
    setShowModalAnalysisSettings(false);
    setTestFrameId(undefined);
    setTestAnalyzeState("idle");
    setTestAnalyzeMessage("");
  };

  const applySettingsAndInspect = async (action: "check" | "save") => {
    const snapshot = controller.modalSnapshot;
    const frameId = testFrameId ?? snapshot?.inspectResult?.frame_id;
    if (!snapshot || !frameId || testAnalyzeState === "submitting" || testAnalyzeState === "awaiting") {
      return;
    }

    setTestAnalyzeState("submitting");
    setTestAnalyzeMessage(
      `${action === "save" ? "Сохранение" : "Применение"} настроек и запуск инспекции кадра ${frameId}…`,
    );
    try {
      await Promise.all([geometrySettingsRef.current?.save(), analysisSettingsRef.current?.save()]);
      pendingTestRef.current = {
        cameraId: snapshot.cameraId,
        frameId,
        previousServerTs: snapshot.inspectResult?.server_ts_ms ?? 0,
      };
      const accepted = await orchestratorApi.testAnalyzeArchiveFrame(snapshot.cameraId, frameId);
      setTestAnalyzeState("awaiting");
      setTestAnalyzeMessage(`Проверка запущена (${accepted.jobId}). Ожидание полного результата кадра ${frameId}…`);
    } catch (error) {
      setTestAnalyzeState("error");
      setTestAnalyzeMessage(error instanceof Error ? error.message : "Не удалось запустить повторную инспекцию");
    }
  };

  useEffect(() => {
    onInspectionStatsChange?.(controller.inspectionStats);
  }, [controller.inspectionStats, onInspectionStatsChange]);

  useEffect(() => {
    if (testAnalyzeState !== "awaiting") {
      return;
    }
    const result = controller.modalSnapshot?.inspectResult;
    const pending = pendingTestRef.current;
    if (
      !result?.test_analyze ||
      result.camera_id !== pending.cameraId ||
      result.frame_id !== pending.frameId ||
      result.server_ts_ms <= pending.previousServerTs ||
      !result.heatmap
    ) {
      return;
    }
    const resultState = resolveInspectionResultState(result);
    setTestAnalyzeState("complete");
    setTestAnalyzeMessage(
      `Кадр ${pending.frameId} проверен с новыми настройками: ${resultState === "pass" ? "годен" : resultState === "fail" ? "брак" : "результат получен"}. Полный результат и новый хитмап отображены.`,
    );
  }, [controller.modalSnapshot?.inspectResult, testAnalyzeState]);

  return (
    <div
      className="camera-overviews"
      ref={rootRef}
    >
      {cameraCardGroups.map((cameraGroup, groupIndex) => (
        <section
          className="camera-overview"
          aria-label={`Кадры камер для объекта ${groupIndex + 1}`}
          key={groupIndex}
        >
          <div className="camera-grid">
            {cameraGroup.map((camera) => {
              const inspectionControlState = controller.inspectionControlByCameraId[camera.cameraId];
              const inspectResult = controller.inspectResultsByCameraId[camera.cameraId];
              const artifactInspectResult = controller.inspectArtifactResultsByCameraId[camera.cameraId];
              const isInspectionEnabled = inspectionControlState?.isEnabled ?? true;
              // Soft-stop: inspection is off, but capture-only frames must still render on the card.
              const isCaptureOnlyFrame = isCaptureOnlyInspectResult(inspectResult);
              const showLiveInspectFrame =
                Boolean(inspectResult) && (!controller.hasReference || isInspectionEnabled || isCaptureOnlyFrame);
              const showInspectionArtifacts = controller.hasReference && isInspectionEnabled;
              const inspectImageUrl = resolveCardInspectImageUrl(
                showLiveInspectFrame ? inspectResult : undefined,
                showInspectionArtifacts ? artifactInspectResult : undefined,
                controller.previewFrameIdsByCameraId[camera.cameraId],
                controller.previewImageUrlsByCameraId[camera.cameraId],
              );
              const isInspectionActionPending =
                inspectionControlState?.state === "starting" || inspectionControlState?.state === "stopping";
              const inspectionResultState = resolveInspectionResultState(
                isInspectionEnabled || isCaptureOnlyFrame ? inspectResult : undefined,
              );

              return (
                <StatusCard
                  key={camera.cameraId}
                  cameraId={camera.cameraId}
                  objectName={camera.objectName}
                  imageUrl={inspectImageUrl ?? camera.imageUrl}
                  currentFrameId={controller.previewFrameIdsByCameraId[camera.cameraId]}
                  inspectionFrameId={inspectResult?.frame_id}
                  isSelected={selectedSettingsCameraId === camera.cameraId}
                  isInspectionEnabled={isInspectionEnabled}
                  isInspectionActionDisabled={!controller.hasReference || isInspectionActionPending}
                  inspectionActionLabel={getInspectionActionLabel(inspectionControlState?.state, isInspectionEnabled)}
                  inspectionStatus={inspectionControlState?.message}
                  inspectionResult={inspectionResultState}
                  onOpen={() =>
                    controller.openInspectionModal(
                      createSelectedCamera(camera),
                      inspectResult,
                      showInspectionArtifacts ? artifactInspectResult : undefined,
                      controller.previewFrameIdsByCameraId[camera.cameraId],
                      controller.previewImageUrlsByCameraId[camera.cameraId],
                      controller.inspectionHistoryByCameraId[camera.cameraId] ?? [],
                    )
                  }
                  onSelect={() => onSettingsCameraToggle(camera.cameraId)}
                  onInspectionToggle={() => void controller.toggleInspection(camera.cameraId)}
                />
              );
            })}
          </div>

          <InspectionHistory
            cameraIds={cameraGroup.map((camera) => camera.cameraId)}
            historyByCameraId={controller.inspectionHistoryByCameraId}
            archiveHistoryState={controller.archiveHistoryState}
            archiveHistoryMessage={controller.archiveHistoryMessage}
            onLoadArchivedHistory={(ids) => void controller.loadArchivedHistory(ids)}
          />
        </section>
      ))}

      {controller.modalSnapshot && (
        <ModalWrapper
          isOpen
          cameraId={controller.modalSnapshot.cameraId}
          cameraImageUrl={controller.modalSnapshot.cameraImageUrl}
          inspectHeatmapUrl={controller.modalSnapshot.heatmapUrl}
          referenceImageUrl={controller.modalSnapshot.referenceImageUrl}
          referenceRoiPoints={controller.modalSnapshot.referenceRoiPoints}
          referenceJointRoiPoints={controller.modalSnapshot.referenceJointRoiPoints}
          referenceFpZones={controller.modalSnapshot.referenceFpZones}
          inspectionItems={controller.modalSnapshot.inspectionItems.map(({ frameId, inspectionId, result }) => ({
            frameId,
            inspectionId,
            result,
          }))}
          selectedInspectionFrameId={controller.modalSnapshot.inspectResult?.frame_id}
          analysisSettingsContent={
            showModalAnalysisSettings ? (
              <div className="modal__analysis-settings modal__test-settings">
                <h3>Настройки камеры {controller.modalSnapshot.cameraId}</h3>
                {testAnalyzeMessage && (
                  <p
                    className="modal__test-settings-status"
                    data-state={testAnalyzeState}
                    aria-live="polite"
                  >
                    {testAnalyzeMessage}
                  </p>
                )}
                <p className="modal__test-settings-hint">
                  Крутите параметры — результат geometry + python обновляется на выбранном кадре. Режим теста остаётся
                  открытым, пока не нажмёте «Завершить тест» или не закроете окно.
                </p>
                <div className="modal__test-settings-grid">
                  <section className="modal__test-settings-section">
                    <h4>Геометрия / стык</h4>
                    <GeometryTestSettingsPanel
                      ref={geometrySettingsRef}
                      selectedCameraId={controller.modalSnapshot.cameraId}
                      testFrameId={testFrameId}
                      hideSaveAction
                    />
                  </section>
                  <section className="modal__test-settings-section">
                    <h4>Python-анализ поверхности</h4>
                    <AnalysisSettingsPanel
                      ref={analysisSettingsRef}
                      selectedCameraId={controller.modalSnapshot.cameraId}
                      testFrameId={testFrameId}
                      hideSaveAction
                    />
                  </section>
                </div>
                <div className="modal__test-settings-actions">
                  <button
                    type="button"
                    className="modal__action"
                    disabled={testAnalyzeState === "submitting" || testAnalyzeState === "awaiting"}
                    onClick={() => void applySettingsAndInspect("check")}
                  >
                    {testAnalyzeState === "submitting"
                      ? "Применение…"
                      : testAnalyzeState === "awaiting"
                        ? "Ожидание результата…"
                        : "Проверить"}
                  </button>
                  <button
                    type="button"
                    className="modal__action"
                    disabled={testAnalyzeState === "submitting" || testAnalyzeState === "awaiting"}
                    onClick={() => void applySettingsAndInspect("save")}
                  >
                    Сохранить
                  </button>
                </div>
              </div>
            ) : undefined
          }
          dangerHeaderAction={
            <button
              className={
                modalInspectionControlState?.isEnabled === false
                  ? "modal__action"
                  : "modal__action modal__action--danger"
              }
              type="button"
              disabled={
                !controller.hasReference ||
                modalInspectionControlState?.state === "starting" ||
                modalInspectionControlState?.state === "stopping" ||
                showModalAnalysisSettings
              }
              title={
                showModalAnalysisSettings ? "Сначала завершите тест настроек" : modalInspectionControlState?.message
              }
              onClick={() => void controller.toggleInspection(controller.modalSnapshot!.cameraId)}
            >
              {getModalInspectionActionLabel(
                modalInspectionControlState?.state,
                modalInspectionControlState?.isEnabled ?? true,
              )}
            </button>
          }
          headerActions={
            showModalAnalysisSettings ? undefined : (
              <button
                className="modal__action"
                type="button"
                onClick={async () => {
                  const cameraId = controller.modalSnapshot!.cameraId;
                  const frameId = controller.modalSnapshot!.inspectResult?.frame_id;
                  if (!frameId) {
                    return;
                  }
                  setTestFrameId(frameId);
                  await onAnalysisSettingsOpen(cameraId);
                  setShowModalAnalysisSettings(true);
                }}
              >
                Изменить настройки анализа
              </button>
            )
          }
          inspectResult={controller.modalSnapshot.inspectResult}
          title={`${controller.modalSnapshot.objectName} / Камера ${controller.modalSnapshot.cameraId}`}
          onInspectionSelect={controller.selectModalInspection}
          onClose={() => {
            if (showModalAnalysisSettings) {
              void Promise.allSettled([
                geometrySettingsRef.current?.save() ?? Promise.resolve(),
                analysisSettingsRef.current?.save() ?? Promise.resolve(),
              ])
                .then(() => exitTestModeAndResume())
                .finally(() => {
                  controller.closeInspectionModal();
                });
              return;
            }
            setShowModalAnalysisSettings(false);
            controller.closeInspectionModal();
          }}
        />
      )}

      {controller.isArchiveViewerOpen && (
        <ArchiveHistoryViewer
          cameraIds={controller.cameraIds}
          historyByCameraId={controller.archivedHistoryByCameraId}
          onClose={controller.closeArchiveViewer}
          onChanged={() => controller.loadArchivedHistory(controller.cameraIds)}
        />
      )}
    </div>
  );
}

function chunkItems<T>(items: T[], chunkSize: number) {
  return Array.from({ length: Math.ceil(items.length / chunkSize) }, (_, groupIndex) => {
    const startIndex = groupIndex * chunkSize;
    return items.slice(startIndex, startIndex + chunkSize);
  });
}

function getInspectionActionLabel(state: "idle" | "starting" | "stopping" | "error" | undefined, isEnabled: boolean) {
  if (state === "starting") {
    return "Запуск...";
  }
  if (state === "stopping") {
    return "Остановка...";
  }
  return isEnabled ? "Остановить" : "Запустить";
}

function getModalInspectionActionLabel(
  state: "idle" | "starting" | "stopping" | "error" | undefined,
  isEnabled: boolean,
) {
  if (state === "starting") {
    return "Запуск...";
  }
  if (state === "stopping") {
    return "Остановка...";
  }
  return isEnabled ? "Остановить инспекцию" : "Запустить инспекцию";
}
