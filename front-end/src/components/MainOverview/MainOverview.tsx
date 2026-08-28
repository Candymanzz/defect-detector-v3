import { useEffect } from "react";
import { useRef, useState } from "react";
import type { Ref } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { InspectionHistory } from "../InspectionHistory";
import { ArchiveHistoryViewer } from "../ArchiveHistoryViewer/ArchiveHistoryViewer";
import { AnalysisSettingsPanel } from "../SettingList/AnalysisSettingsPanel";
import { notifyAnalysisSettingsChanged } from "../SettingList/analysisSettingsEvents";
import type { AnalysisSettingsPanelHandle } from "../SettingList/AnalysisSettingsPanel";
import { GeometryTestSettingsPanel } from "../SettingList/GeometryTestSettingsPanel";
import type { GeometryTestSettingsPanelHandle } from "../SettingList/GeometryTestSettingsPanel";
import { resolveInspectionResultState, isCaptureOnlyInspectResult } from "../../shared/inspectResult";
import { orchestratorApi } from "../../shared/api";
import type { InspectionStateResponse } from "../../shared/api/types";
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
  const pendingTestRef = useRef<{
    cameraId: number;
    frameId: string;
    pinId: string;
    jpegSha256: string;
    jobId: string;
    previousServerTs: number;
  }>({
    cameraId: -1,
    frameId: "",
    pinId: "",
    jpegSha256: "",
    jobId: "",
    previousServerTs: 0,
  });
  const analysisSettingsRef = useRef<AnalysisSettingsPanelHandle>(null);
  const geometrySettingsRef = useRef<GeometryTestSettingsPanelHandle>(null);
  const inspectionStateBeforeTestRef = useRef<InspectionStateResponse | null>(null);
  const cameraCards = createCameraCards(controller.cameraIds, controller.previewImageUrlsByCameraId);
  const cameraCardGroups = chunkItems(cameraCards, CAMERAS_PER_OVERVIEW);
  const modalInspectionControlState = controller.modalSnapshot
    ? controller.inspectionControlByCameraId[controller.modalSnapshot.cameraId]
    : undefined;

  const restoreInspectionStateAfterTest = async () => {
    await orchestratorApi.setTestMode(false);

    const previousState = inspectionStateBeforeTestRef.current;
    if (previousState) {
      // Keep explicitly disabled cameras disabled. Restore disabled cameras first so
      // they cannot briefly start inspecting while the remaining state is restored.
      for (const cameraId of previousState.disabledCameraIds) {
        await orchestratorApi.setInspectionEnabled(cameraId, false);
      }
      for (const cameraId of previousState.enabledCameraIds) {
        await orchestratorApi.setInspectionEnabled(cameraId, true);
      }
    }

    const inspectionState = await orchestratorApi.getInspectionStatus();
    window.dispatchEvent(new CustomEvent("inspection-control-changed", { detail: inspectionState }));
    inspectionStateBeforeTestRef.current = null;
  };

  const exitTestModeAndResume = async () => {
    await restoreInspectionStateAfterTest();
    setShowModalAnalysisSettings(false);
    setTestFrameId(undefined);
    setTestAnalyzeState("idle");
    setTestAnalyzeMessage("");
  };

  const pinFrameForTest = async (
    inspectResult: NonNullable<NonNullable<typeof controller.modalSnapshot>["inspectResult"]>,
  ) => {
    const cameraId = inspectResult.camera_id;
    const frameId = inspectResult.frame_id;
    // Always pin the archived JPEG for this frameId — never current.jpg / live artifact.
    const pinned = await orchestratorApi.pinTestFrame({
      cameraId,
      frameId,
      source: "archive",
    });
    if (String(pinned.frameId) !== String(frameId)) {
      throw new Error(
        `Сервер зафиксировал кадр ${pinned.frameId} вместо выбранного кадра ${frameId}`,
      );
    }
    const pinHttpPath = pinned.imageHttpPath;
    // Always show the durable on-disk pin (not a blob) — blob revoke / remount looked like a frame swap.
    const pinImageUrl = orchestratorApi.imageUrl(
      `${pinHttpPath}?pin=${encodeURIComponent(pinned.pinId)}`,
      pinned.jpegSha256,
    );
    controller.freezeModalTestFrame(
      String(pinned.frameId),
      pinImageUrl,
      pinHttpPath,
      pinned.pinId,
      pinned.jpegSha256,
    );
    setTestFrameId(String(pinned.frameId));
    return pinned;
  };

  const handleTestInspectionSelect = async (frameId: string) => {
    if (!showModalAnalysisSettings) {
      controller.selectModalInspection(frameId);
      return;
    }
    const snapshot = controller.modalSnapshot;
    const item = snapshot?.inspectionItems.find((candidate) => candidate.frameId === frameId);
    if (!item?.inspectResult) {
      controller.selectModalInspection(frameId);
      return;
    }
    try {
      setTestAnalyzeState("submitting");
      setTestAnalyzeMessage(`Смена кадра теста на ${frameId}…`);
      controller.selectModalInspection(frameId);
      await pinFrameForTest(item.inspectResult);
      setTestAnalyzeState("idle");
      setTestAnalyzeMessage(`Кадр ${frameId} записан на диск для теста (предыдущий pin перезаписан).`);
    } catch (error) {
      setTestAnalyzeState("error");
      setTestAnalyzeMessage(error instanceof Error ? error.message : "Не удалось сменить кадр теста");
    }
  };

  const applySettingsAndInspect = async (action: "check" | "save") => {
    const snapshot = controller.modalSnapshot;
    const frameId = testFrameId ?? snapshot?.pinnedTestFrameId ?? snapshot?.inspectResult?.frame_id;
    const pinId = snapshot?.pinnedTestPinId;
    const jpegSha256 = snapshot?.pinnedTestJpegSha256;
    if (!snapshot || !frameId || !pinId || !jpegSha256 || testAnalyzeState === "submitting" || testAnalyzeState === "awaiting") {
      return;
    }

    // Do NOT re-freeze / touch cameraImageUrl here — that remounts <img> and looks like a frame swap.
    setTestAnalyzeState("submitting");
    setTestAnalyzeMessage(
      action === "save"
        ? `Сохранение настроек и проверка кадра ${frameId}…`
        : `Проверка кадра ${frameId}…`,
    );
    try {
      if (action === "save") {
        await Promise.all([geometrySettingsRef.current?.save(), analysisSettingsRef.current?.save()]);
        notifyAnalysisSettingsChanged(snapshot.cameraId);
      }
      const analysisDraft = analysisSettingsRef.current?.getDraft();
      const analysis =
        analysisDraft == null
          ? undefined
          : {
              simple: analysisDraft.simple as Record<string, number>,
              detailed: analysisDraft.strengths as Record<string, number>,
            };
      const accepted = await orchestratorApi.testAnalyzePinnedFrame(snapshot.cameraId, pinId, frameId, {
        geometry: geometrySettingsRef.current?.getDraft(),
        analysis,
      });
      if (
        accepted.pinId !== pinId
        || String(accepted.frameId) !== String(frameId)
        || accepted.pinJpegSha256 !== jpegSha256
      ) {
        throw new Error("Сервер принял для проверки другой pin, кадр или JPEG");
      }
      controller.setPendingTestJob(accepted.jobId);
      pendingTestRef.current = {
        cameraId: snapshot.cameraId,
        frameId,
        pinId,
        jpegSha256,
        jobId: accepted.jobId,
        previousServerTs: snapshot.inspectResult?.server_ts_ms ?? 0,
      };
      setTestAnalyzeState("awaiting");
      setTestAnalyzeMessage(`Проверка кадра ${frameId} запущена, ожидание результата…`);
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
      result.test_pin_id !== pending.pinId ||
      result.test_analyze_job_id !== pending.jobId ||
      result.pin_jpeg_sha256 !== pending.jpegSha256 ||
      result.server_ts_ms <= pending.previousServerTs
    ) {
      return;
    }
    const resultState = resolveInspectionResultState(result);
    const anomalyPercent =
      typeof result.anomaly_score === "number" ? `${(result.anomaly_score * 100).toFixed(2)}%` : "—";
    setTestAnalyzeState("complete");
    setTestAnalyzeMessage(
      `Кадр ${pending.frameId}: ${resultState === "pass" ? "годен" : resultState === "fail" ? "брак" : "результат получен"}, аномалия ${anomalyPercent}.`,
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
            onCameraOpen={(item) => {
              const camera = cameraCards.find((candidate) => candidate.cameraId === item.inspectResult.camera_id);
              if (!camera) return;
              controller.openInspectionModal(
                createSelectedCamera(camera),
                item.inspectResult,
                item.inspectResult,
                item.frameId,
                controller.previewImageUrlsByCameraId[camera.cameraId],
                controller.inspectionHistoryByCameraId[camera.cameraId] ?? [],
              );
            }}
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
                <div className="modal__test-settings-header">
                  <h3>Настройки камеры {controller.modalSnapshot.cameraId}</h3>
                  <p className="modal__test-settings-frame-id" title="Выбранный кадр для анализа и дообучения">
                    кадр {testFrameId ?? controller.modalSnapshot.pinnedTestFrameId ?? controller.modalSnapshot.inspectResult?.frame_id ?? "—"}
                    {controller.modalSnapshot.pinnedTestPinId
                      ? ` · pin ${controller.modalSnapshot.pinnedTestPinId.slice(0, 8)}`
                      : ""}
                  </p>
                </div>
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
                  Анализ и дообучение только для этого frameId (JPEG из архива → pin на диске). При выборе другого кадра
                  pin перезаписывается.
                </p>
                <div className="modal__test-settings-grid">
                  <details className="modal__test-settings-section modal__test-settings-section--collapsible">
                    <summary>
                      <span>Геометрия / стык</span>
                      <span className="modal__test-settings-chevron" aria-hidden="true" />
                    </summary>
                    <GeometryTestSettingsPanel
                      ref={geometrySettingsRef}
                      selectedCameraId={controller.modalSnapshot.cameraId}
                      testFrameId={testFrameId}
                      testPinId={controller.modalSnapshot.pinnedTestPinId}
                      hideSaveAction
                    />
                  </details>
                  <section className="modal__test-settings-section">
                    <h4>Python-анализ поверхности</h4>
                    <AnalysisSettingsPanel
                      ref={analysisSettingsRef}
                      selectedCameraId={controller.modalSnapshot.cameraId}
                      testFrameId={testFrameId}
                      testPinId={controller.modalSnapshot.pinnedTestPinId}
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
              <>
                <button
                  className="modal__action"
                  type="button"
                  disabled={testAnalyzeState === "submitting"}
                  onClick={async () => {
                  const cameraId = controller.modalSnapshot!.cameraId;
                  const inspectResult = controller.modalSnapshot!.inspectResult;
                  const frameId = inspectResult?.frame_id;
                  if (!frameId || !inspectResult) {
                    return;
                  }
                  try {
                    setTestAnalyzeState("submitting");
                    setTestAnalyzeMessage(`Копирование кадра ${frameId} на диск для теста…`);
                    setTestFrameId(frameId);
                    inspectionStateBeforeTestRef.current = await orchestratorApi.getInspectionStatus();
                    // Enter TEST mode first (the server rejects pinning outside it).
                    await onAnalysisSettingsOpen(cameraId);
                    await pinFrameForTest(inspectResult);
                    setShowModalAnalysisSettings(true);
                    setTestAnalyzeState("idle");
                    setTestAnalyzeMessage(
                      `Кадр ${frameId} на диске. При выборе другого кадра pin перезапишется.`,
                    );
                  } catch (error) {
                    setTestFrameId(undefined);
                    setShowModalAnalysisSettings(false);
                    setTestAnalyzeState("error");
                    setTestAnalyzeMessage(
                      error instanceof Error
                        ? (error.message.includes("404") || error.message.toLowerCase().includes("not found")
                          ? `Кадр ${frameId} не найден в архиве — pin только из архива`
                          : error.message)
                        : "Не удалось зафиксировать кадр для теста",
                    );
                    try {
                      await restoreInspectionStateAfterTest();
                    } catch {
                      // leave error message from pin/open failure
                    }
                  }
                }}
                >
                  {testAnalyzeState === "submitting" ? "Фиксация кадра…" : "Изменить настройки анализа"}
                </button>
                {testAnalyzeState === "error" && testAnalyzeMessage && (
                  <span className="modal__test-settings-status" data-state="error" role="alert">
                    {testAnalyzeMessage}
                  </span>
                )}
              </>
            )
          }
          inspectResult={controller.modalSnapshot.inspectResult}
          title={`${controller.modalSnapshot.objectName} / Камера ${controller.modalSnapshot.cameraId}`}
          onInspectionSelect={(frameId) => void handleTestInspectionSelect(frameId)}
          onClose={() => {
            if (showModalAnalysisSettings) {
              void (async () => {
                try {
                  await exitTestModeAndResume();
                  controller.closeInspectionModal();
                } catch (error) {
                  setTestAnalyzeState("error");
                  setTestAnalyzeMessage(
                    error instanceof Error ? error.message : "Не удалось сохранить настройки анализа",
                  );
                }
              })();
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
