import { useEffect } from "react";
import { useState } from "react";
import type { Ref } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { InspectionHistory } from "../InspectionHistory";
import { ArchiveHistoryViewer } from "../ArchiveHistoryViewer/ArchiveHistoryViewer";
import { AnalysisSettingsPanel } from "../SettingList/AnalysisSettingsPanel";
import { resolveInspectionResultState, isCaptureOnlyInspectResult } from "../../shared/inspectResult";
import { orchestratorApi } from "../../shared/api";
import { StatusCard } from "../../shared/ui/StatusCard";
import { createCameraCards, createSelectedCamera } from "./MainController";
import { resolveCardInspectImageUrl } from "./MainController";
import { useMainOverview } from "./useMainOverview";
import type { InspectionStats } from "./type";
import "./MainOverview.css";

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
  const cameraCards = createCameraCards(controller.cameraIds, controller.previewImageUrlsByCameraId);
  const cameraCardGroups = chunkItems(cameraCards, CAMERAS_PER_OVERVIEW);
  const modalInspectionControlState = controller.modalSnapshot
    ? controller.inspectionControlByCameraId[controller.modalSnapshot.cameraId]
    : undefined;

  useEffect(() => {
    onInspectionStatsChange?.(controller.inspectionStats);
  }, [controller.inspectionStats, onInspectionStatsChange]);

  return (
    <div className="camera-overviews" ref={rootRef}>
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
                Boolean(inspectResult) &&
                (!controller.hasReference || isInspectionEnabled || isCaptureOnlyFrame);
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
              <div className="modal__analysis-settings">
                <h3>Настройки анализа · Камера {controller.modalSnapshot.cameraId}</h3>
                <AnalysisSettingsPanel
                  selectedCameraId={controller.modalSnapshot.cameraId}
                  testFrameId={controller.modalSnapshot.inspectResult?.frame_id}
                  onSaveComplete={async () => {
                    await orchestratorApi.setTestMode(false);
                    const inspectionState = await orchestratorApi.startAllInspections();
                    window.dispatchEvent(new CustomEvent("inspection-control-changed", { detail: inspectionState }));
                    setShowModalAnalysisSettings(false);
                  }}
                />
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
                modalInspectionControlState?.state === "stopping"
              }
              title={modalInspectionControlState?.message}
              onClick={() => void controller.toggleInspection(controller.modalSnapshot!.cameraId)}
            >
              {getModalInspectionActionLabel(
                modalInspectionControlState?.state,
                modalInspectionControlState?.isEnabled ?? true,
              )}
            </button>
          }
          headerActions={
            <button
              className="modal__action"
              type="button"
              onClick={async () => {
                const cameraId = controller.modalSnapshot!.cameraId;
                await onAnalysisSettingsOpen(cameraId);
                setShowModalAnalysisSettings(true);
              }}
            >
              Изменить настройки анализа
            </button>
          }
          inspectResult={controller.modalSnapshot.inspectResult}
          title={`${controller.modalSnapshot.objectName} / Камера ${controller.modalSnapshot.cameraId}`}
          onInspectionSelect={controller.selectModalInspection}
          onClose={() => {
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
