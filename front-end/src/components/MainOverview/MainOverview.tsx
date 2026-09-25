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
import { createCameraCards, createDefaultInspectionProducts, createSelectedCamera } from "./MainController";
import { resolveCardInspectImageUrl } from "./MainController";
import { useMainOverview } from "./useMainOverview";
import type { CameraCardData, InspectionHistoryItem, InspectionProduct, InspectionStats } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";
import "../SettingList/TestSettingsPanels.css";

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
  const cameraCardById = new Map(cameraCards.map((camera) => [camera.cameraId, camera]));
  const products = controller.inspectionProducts.length
    ? orderProductsForGrid(controller.inspectionProducts)
    : orderProductsForGrid(createDefaultInspectionProducts(controller.cameraIds));
  const cameraCardGroups: Array<InspectionProduct & { productNumber: number; cameras: CameraCardData[] }> =
    products.map((product, index) => ({
    ...product,
    productNumber: index + 1,
    cameras: product.cameraIds.map((cameraId) => cameraCardById.get(cameraId)).filter((camera) => camera != null),
  }));
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
      className={`camera-overviews ${cameraCardGroups.length > 2 ? "camera-overviews--multi-column" : ""}`}
      ref={rootRef}
    >
      {cameraCardGroups.map((cameraGroup) => {
        const isCaptureOnlyProduct =
          Object.keys(cameraGroup.resultsByCameraId).length > 0 &&
          Object.values(cameraGroup.resultsByCameraId).every((result) => isCaptureOnlyInspectResult(result));
        return (
        <section
          className={`camera-overview ${cameraGroup.overallPass === false ? "camera-overview--fail" : cameraGroup.overallPass === true ? "camera-overview--pass" : isCaptureOnlyProduct ? "camera-overview--capture" : ""}`}
          aria-label={`Кадры камер для изделия ${cameraGroup.productNumber}`}
          key={cameraGroup.key}
        >
          <header className="camera-overview__header">
            <div>
              <h2>Изделие {cameraGroup.productNumber}</h2>
              <span>Камеры {formatCameraRange(cameraGroup.cameraIds)}</span>
            </div>
            <strong className={cameraGroup.overallPass === false ? "is-fail" : cameraGroup.overallPass === true ? "is-pass" : isCaptureOnlyProduct ? "is-capture" : ""}>
              {cameraGroup.overallPass === false ? "● БРАК" : cameraGroup.overallPass === true ? "● ГОДЕН" : isCaptureOnlyProduct ? "● СЪЁМКА" : "Ожидание"}
            </strong>
            {cameraGroup.triggerSequence != null && (
              <div className="camera-overview__inspection">
                <span>{isCaptureOnlyProduct ? "Последняя съёмка" : "Последняя инспекция"}</span>
                <b>#{cameraGroup.triggerSequence}</b>
              </div>
            )}
          </header>
          <div className="camera-grid">
            {cameraGroup.cameras.map((camera) => {
              const inspectionControlState = controller.inspectionControlByCameraId[camera.cameraId];
              // A camera participates in two phases. Only a completed bucket result
              // belongs to this exact product; the camera-wide latest result may be
              // from the other phase and must not appear here prematurely.
              const inspectResult =
                cameraGroup.resultsByCameraId[camera.cameraId] ??
                scopedInspectResult(
                  controller.inspectResultsByCameraId[camera.cameraId],
                  cameraGroup.phaseId,
                  cameraGroup.groupId,
                );
              const artifactCandidate = controller.inspectArtifactResultsByCameraId[camera.cameraId];
              const artifactInspectResult =
                artifactCandidate &&
                (artifactCandidate.phase_id ?? 0) === cameraGroup.phaseId &&
                (artifactCandidate.group_id ?? -1) === cameraGroup.groupId
                  ? artifactCandidate
                  : inspectResult;
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
                  inspectionFrameId={inspectionResultState ? inspectResult?.frame_id : undefined}
                  isSelected={selectedSettingsCameraId === camera.cameraId}
                  isInspectionEnabled={isInspectionEnabled}
                  isInspectionActionDisabled={!controller.hasReference || isInspectionActionPending}
                  inspectionActionLabel={getInspectionActionLabel(inspectionControlState?.state, isInspectionEnabled)}
                  inspectionStatus={inspectionControlState?.message}
                  inspectionResult={inspectionResultState}
                  onOpen={() =>
                    controller.openInspectionModal(
                      createSelectedCamera(camera),
                      {
                        productKey: cameraGroup.key,
                        phaseId: cameraGroup.phaseId,
                        groupId: cameraGroup.groupId,
                      },
                      inspectResult,
                      showInspectionArtifacts ? artifactInspectResult : undefined,
                      controller.previewFrameIdsByCameraId[camera.cameraId],
                      controller.previewImageUrlsByCameraId[camera.cameraId],
                      controller.inspectionHistoryByProductKey[cameraGroup.key]?.[camera.cameraId] ??
                        scopedInspectionHistory(
                          controller.inspectionHistoryByCameraId[camera.cameraId],
                          cameraGroup.phaseId,
                          cameraGroup.groupId,
                        ),
                    )
                  }
                  onSelect={() => onSettingsCameraToggle(camera.cameraId)}
                  onInspectionToggle={() => void controller.toggleInspection(camera.cameraId)}
                />
              );
            })}
          </div>

          <InspectionHistory
            cameraIds={cameraGroup.cameraIds}
            historyByCameraId={
              hasProductHistory(controller.inspectionHistoryByProductKey[cameraGroup.key])
                ? (controller.inspectionHistoryByProductKey[cameraGroup.key] ?? {})
                : scopedHistoryByCameraId(
                    controller.inspectionHistoryByCameraId,
                    cameraGroup.cameraIds,
                    cameraGroup.phaseId,
                    cameraGroup.groupId,
                  )
            }
            archiveHistoryState={controller.archiveHistoryState}
            archiveHistoryMessage={controller.archiveHistoryMessage}
            onLoadArchivedHistory={(ids) =>
              void controller.loadArchivedHistory(ids, {
                productKey: cameraGroup.key,
                phaseId: cameraGroup.phaseId,
                groupId: cameraGroup.groupId,
              })
            }
          />
        </section>
        );
      })}

      {controller.modalSnapshot && (
        <ModalWrapper
          isOpen
          cameraId={controller.modalSnapshot.cameraId}
          phaseId={controller.modalSnapshot.phaseId}
          groupId={controller.modalSnapshot.groupId}
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
          cameraIds={
            controller.archiveProductContext
              ? cameraCardGroups.find((group) => group.key === controller.archiveProductContext?.productKey)
                  ?.cameraIds ?? controller.cameraIds
              : controller.cameraIds
          }
          historyByCameraId={controller.archivedHistoryByCameraId}
          isProductScoped={Boolean(controller.archiveProductContext)}
          onClose={controller.closeArchiveViewer}
          onChanged={() =>
            controller.loadArchivedHistory(
              controller.archiveProductContext
                ? cameraCardGroups.find((group) => group.key === controller.archiveProductContext?.productKey)
                    ?.cameraIds ?? controller.cameraIds
                : controller.cameraIds,
              controller.archiveProductContext,
            )
          }
        />
      )}
    </div>
  );
}

function formatCameraRange(cameraIds: number[]) {
  if (cameraIds.length === 0) {
    return "—";
  }
  const sorted = [...cameraIds].sort((left, right) => left - right);
  return sorted.length === 1 ? String(sorted[0]) : `${sorted[0]}–${sorted[sorted.length - 1]}`;
}

function scopedInspectResult(
  result: InspectResultPayload | undefined,
  phaseId: number,
  groupId: number,
) {
  if (!result) {
    return undefined;
  }
  if (result.group_id != null && result.group_id >= 0) {
    return (result.phase_id ?? 0) === phaseId && result.group_id === groupId ? result : undefined;
  }
  return (result.phase_id ?? 0) === phaseId ? result : undefined;
}

function scopedInspectionHistory(
  items: InspectionHistoryItem[] | undefined,
  phaseId: number,
  groupId: number,
) {
  return (items ?? []).filter((item) => matchesProductScope(item.inspectResult, phaseId, groupId));
}

function hasProductHistory(historyByCameraId: Record<number, InspectionHistoryItem[]> | undefined) {
  return Object.values(historyByCameraId ?? {}).some((items) => items.length > 0);
}

function scopedHistoryByCameraId(
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
  cameraIds: number[],
  phaseId: number,
  groupId: number,
) {
  return Object.fromEntries(
    cameraIds.map((cameraId) => [cameraId, scopedInspectionHistory(historyByCameraId[cameraId], phaseId, groupId)]),
  );
}

function matchesProductScope(
  result: { phase_id?: number; group_id?: number },
  phaseId: number,
  groupId: number,
) {
  if (result.group_id != null && result.group_id >= 0) {
    return (result.phase_id ?? 0) === phaseId && result.group_id === groupId;
  }
  return (result.phase_id ?? 0) === phaseId;
}

function orderProductsForGrid<T extends InspectionProduct>(products: T[]) {
  if (products.length <= 2) {
    return products;
  }
  return [...products].sort((left, right) => {
    return left.phaseId - right.phaseId || left.groupId - right.groupId;
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
