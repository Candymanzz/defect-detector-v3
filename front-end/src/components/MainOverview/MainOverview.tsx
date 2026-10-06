import { useEffect } from "react";
import type { Ref } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { InspectionHistory } from "../InspectionHistory";
import { ArchiveHistoryViewer } from "../ArchiveHistoryViewer/ArchiveHistoryViewer";
import { resolveInspectionResultState, isCaptureOnlyInspectResult } from "../../shared/inspectResult";
import { StatusCard } from "../../shared/ui/StatusCard";
import { createCameraCards, createSelectedCamera } from "./MainController";
import { resolveCardInspectImageUrl } from "./MainController";
import { useMainOverview } from "./useMainOverview";
import type { InspectionHistoryItem, InspectionStats } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import { createInspectionBuckets } from "../../shared/inspectionBuckets";
import type { InspectionBucket } from "../../shared/inspectionBuckets";
import "./MainOverview.css";

function frameForBucket(result: InspectResultPayload | undefined, bucket: InspectionBucket) {
  if (!result || !bucket.cameraIds.includes(result.camera_id)) {
    return undefined;
  }
  if ((result.phase_id ?? 0) !== bucket.phaseId) {
    return undefined;
  }
  if (result.group_id != null && result.group_id !== bucket.groupId) {
    return undefined;
  }
  return result;
}

function previewMatchesBucket(phaseId: number | undefined, bucket: InspectionBucket) {
  return (phaseId ?? 0) === bucket.phaseId;
}

function historyForBucket(items: InspectionHistoryItem[] | undefined, bucket: InspectionBucket) {
  return (items ?? []).filter((item) => frameForBucket(item.inspectResult, bucket));
}

type MainOverviewProps = {
  inspectionResetVersion: number;
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
  onInspectionStatsChange?: (stats: InspectionStats) => void;
  rootRef?: Ref<HTMLDivElement>;
};

export function MainOverview({
  inspectionResetVersion,
  selectedSettingsCameraId,
  onSettingsCameraToggle,
  onInspectionStatsChange,
  rootRef,
}: MainOverviewProps) {
  const controller = useMainOverview(inspectionResetVersion);
  const cameraCards = createCameraCards(controller.cameraIds, controller.previewImageUrlsByCameraId);
  const cameraCardById = new Map(cameraCards.map((camera) => [camera.cameraId, camera]));
  const buckets = createInspectionBuckets(controller.cameraIds);
  const modalInspectionControlState = controller.modalSnapshot
    ? controller.inspectionControlByCameraId[controller.modalSnapshot.cameraId]
    : undefined;

  useEffect(() => {
    onInspectionStatsChange?.(controller.inspectionStats);
  }, [controller.inspectionStats, onInspectionStatsChange]);

  return (
    <div className={`camera-overviews ${buckets.length > 2 ? "camera-overviews--four" : ""}`} ref={rootRef}>
      {buckets.map((bucket) => (
        <section
          className="camera-overview"
          aria-label={bucket.label}
          key={bucket.key}
        >
          <header className="camera-overview__header">
            <h2>{bucket.label}</h2>
            <span>Фаза {bucket.phaseId + 1} · камеры {bucket.cameraIds.join(", ")}</span>
          </header>
          <div className="camera-grid">
            {bucket.cameraIds.map((cameraId) => {
              const camera = cameraCardById.get(cameraId);
              if (!camera) {
                return null;
              }
              const inspectionControlState = controller.inspectionControlByCameraId[camera.cameraId];
              const inspectResult = frameForBucket(
                controller.framesByBucketKey[bucket.key]?.[camera.cameraId] ??
                  controller.inspectResultsByCameraId[camera.cameraId],
                bucket,
              );
              const artifactInspectResult = frameForBucket(
                controller.inspectArtifactResultsByCameraId[camera.cameraId],
                bucket,
              );
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
                previewMatchesBucket(controller.previewPhaseByCameraId[camera.cameraId], bucket)
                  ? controller.previewFrameIdsByCameraId[camera.cameraId]
                  : undefined,
                previewMatchesBucket(controller.previewPhaseByCameraId[camera.cameraId], bucket)
                  ? controller.previewImageUrlsByCameraId[camera.cameraId]
                  : undefined,
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
                      createSelectedCamera({ ...camera, objectName: bucket.label }),
                      inspectResult,
                      showInspectionArtifacts ? artifactInspectResult : undefined,
                      previewMatchesBucket(controller.previewPhaseByCameraId[camera.cameraId], bucket)
                        ? controller.previewFrameIdsByCameraId[camera.cameraId]
                        : undefined,
                      previewMatchesBucket(controller.previewPhaseByCameraId[camera.cameraId], bucket)
                        ? controller.previewImageUrlsByCameraId[camera.cameraId]
                        : undefined,
                      historyForBucket(controller.inspectionHistoryByCameraId[camera.cameraId], bucket),
                      bucket,
                    )
                  }
                  onSelect={() => onSettingsCameraToggle(camera.cameraId)}
                  onInspectionToggle={() => void controller.toggleInspection(camera.cameraId)}
                />
              );
            })}
          </div>

          <InspectionHistory
            cameraIds={bucket.cameraIds}
            historyByCameraId={Object.fromEntries(
              bucket.cameraIds.map((cameraId) => [
                cameraId,
                historyForBucket(controller.inspectionHistoryByCameraId[cameraId], bucket),
              ]),
            )}
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
          inspectResult={controller.modalSnapshot.inspectResult}
          title={`${controller.modalSnapshot.objectName} / Камера ${controller.modalSnapshot.cameraId}`}
          onInspectionSelect={controller.selectModalInspection}
          onClose={controller.closeInspectionModal}
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
