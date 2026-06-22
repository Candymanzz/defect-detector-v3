import { useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { StatusCard } from "../../shared/ui/StatusCard";
import { createCameraCards, createSelectedCamera } from "./MainController";
import type { SelectedCamera } from "./type";
import { resolveCardInspectImageUrl } from "./MainController";
import { useMainOverview } from "./useMainOverview";
import "./MainOverview.css";

const CAMERAS_PER_OVERVIEW = 5;

type MainOverviewProps = {
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
};

export function MainOverview({ selectedSettingsCameraId, onSettingsCameraToggle }: MainOverviewProps) {
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const controller = useMainOverview();
  const cameraCards = createCameraCards(controller.cameraIds, controller.previewImageUrlsByCameraId);
  const cameraCardGroups = chunkItems(cameraCards, CAMERAS_PER_OVERVIEW);
  const modalInspectionControlState = controller.modalSnapshot
    ? controller.inspectionControlByCameraId[controller.modalSnapshot.cameraId]
    : undefined;
  return (
    <div className="camera-overviews">
      {cameraCardGroups.map((cameraGroup, groupIndex) => (
        <section
          className="camera-overview"
          aria-label={`Camera frames for object ${groupIndex + 1}`}
          key={groupIndex}
        >
          <div className="camera-grid">
            {cameraGroup.map((camera) => {
              const inspectionControlState = controller.inspectionControlByCameraId[camera.cameraId];
              const inspectResult = controller.inspectResultsByCameraId[camera.cameraId];
              const artifactInspectResult = controller.inspectArtifactResultsByCameraId[camera.cameraId];
              const inspectImageUrl = resolveCardInspectImageUrl(inspectResult, artifactInspectResult);
              const isInspectionEnabled = inspectionControlState?.isEnabled ?? true;
              const isInspectionActionPending =
                inspectionControlState?.state === "starting" || inspectionControlState?.state === "stopping";

              return (
                <StatusCard
                  key={camera.cameraId}
                  cameraId={camera.cameraId}
                  objectName={camera.objectName}
                  imageUrl={controller.hasReference ? inspectImageUrl : camera.imageUrl}
                  currentFrameId={controller.previewFrameIdsByCameraId[camera.cameraId]}
                  inspectionFrameId={inspectResult?.frame_id}
                  isSelected={selectedSettingsCameraId === camera.cameraId}
                  isInspectionEnabled={isInspectionEnabled}
                  isInspectionActionDisabled={isInspectionActionPending}
                  inspectionActionLabel={getInspectionActionLabel(inspectionControlState?.state, isInspectionEnabled)}
                  inspectionStatus={inspectionControlState?.message}
                  inspectionResult={resolveInspectionResultState(inspectResult)}
                  onOpen={() =>
                    controller.openInspectionModal(
                      createSelectedCamera(camera),
                      inspectResult,
                      artifactInspectResult,
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

          <div className="inspection-history-grid">
            {cameraGroup.map((camera) => {
              const history = controller.inspectionHistoryByCameraId[camera.cameraId] ?? [];

              return (
                <section
                  className="inspection-history"
                  aria-label={`Inspection history for camera ${camera.cameraId}`}
                  key={camera.cameraId}
                >
                  <header>Camera {camera.cameraId}: latest inspections</header>
                  <div className="inspection-history__list">
                    {history.map((item) => (
                      <div
                        className="inspection-history__item"
                        data-result={item.result}
                        key={item.frameId}
                      >
                        <span>Frame {item.frameId}</span>
                        <strong>{item.result === "pass" ? "Годен" : "Брак"}</strong>
                      </div>
                    ))}
                    {history.length === 0 && <div className="inspection-history__empty">Нет результатов</div>}
                  </div>
                </section>
              );
            })}
          </div>
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
          inspectionItems={controller.modalSnapshot.inspectionItems.map(({ frameId, inspectionId, result }) => ({
            frameId,
            inspectionId,
            result,
          }))}
          selectedInspectionFrameId={controller.modalSnapshot.inspectResult?.frame_id}
          dangerHeaderAction={
            <button
              className={
                modalInspectionControlState?.isEnabled === false
                  ? "modal__action"
                  : "modal__action modal__action--danger"
              }
              type="button"
              disabled={
                modalInspectionControlState?.state === "starting" || modalInspectionControlState?.state === "stopping"
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
              onClick={() => setStreamCamera(controller.modalSnapshot)}
            >
              Открыть стрим
            </button>
          }
          inspectResult={controller.modalSnapshot.inspectResult}
          title={`${controller.modalSnapshot.objectName} / Camera ${controller.modalSnapshot.cameraId}`}
          onInspectionSelect={controller.selectModalInspection}
          onClose={controller.closeInspectionModal}
        />
      )}

      {streamCamera && (
        <ServerStream
          isOpen
          cameraId={streamCamera.cameraId}
          title={`${streamCamera.objectName} / Camera ${streamCamera.cameraId}`}
          onClose={() => setStreamCamera(null)}
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
    return "Starting...";
  }
  if (state === "stopping") {
    return "Stopping...";
  }
  return isEnabled ? "Stop" : "Start";
}

function getModalInspectionActionLabel(
  state: "idle" | "starting" | "stopping" | "error" | undefined,
  isEnabled: boolean,
) {
  if (state === "starting") {
    return "Starting...";
  }
  if (state === "stopping") {
    return "Stopping...";
  }
  return isEnabled ? "Stop inspection" : "Start inspection";
}
