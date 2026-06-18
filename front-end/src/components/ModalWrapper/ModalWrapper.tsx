import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import type { InspectResultPayload, InterestPointNorm } from "../../shared/ws";
import { HeatmapViewer } from "../HeatmapViewer";
import "./ModalWrapper.css";

type ModalWrapperProps = {
  isOpen: boolean;
  title: string;
  cameraId?: number;
  cameraImageUrl?: string;
  inspectHeatmapUrl?: string;
  inspectResult?: InspectResultPayload;
  referenceImageUrl?: string;
  dangerHeaderAction?: ReactNode;
  headerActions?: ReactNode;
  onClose: () => void;
};

export function ModalWrapper({
  isOpen,
  title,
  cameraId,
  cameraImageUrl,
  inspectHeatmapUrl,
  inspectResult,
  referenceImageUrl,
  dangerHeaderAction,
  headerActions,
  onClose,
}: ModalWrapperProps) {
  const storedReferenceImage = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImage(cameraId),
    () => undefined,
  );
  const displayedReferenceImageUrl = referenceImageUrl ?? storedReferenceImage?.imageUrl;
  const displayedReferenceRoiPoints = referenceImageUrl ? undefined : storedReferenceImage?.roiPoints;
  const displayedCurrentImageUrl = inspectResult ? cameraImageUrl : undefined;
  const inspectResultSyncState = getInspectResultSyncState(inspectResult, displayedCurrentImageUrl, inspectHeatmapUrl);
  const inspectionResultState = resolveInspectionResultState(inspectResult);
  const modalClassName = inspectionResultState ? `modal modal--${inspectionResultState}` : "modal";

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="modal-backdrop"
      onMouseDown={onClose}
    >
      <section
        aria-label={title}
        aria-modal="true"
        className={modalClassName}
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>{title}</h2>
          <div className="modal__header-actions">
            {dangerHeaderAction}
            {headerActions}
            <button
              aria-label="Закрыть"
              className="modal__close"
              type="button"
              onClick={onClose}
            >
              x
            </button>
          </div>
        </header>

        {inspectionResultState && (
          <div
            className="modal__inspection-indicator"
            data-result={inspectionResultState}
          >
            {inspectionResultState === "pass" ? "Годен" : "Брак"}
          </div>
        )}

        <div className="modal__media-grid">
          <ImagePanel
            imageUrl={displayedReferenceImageUrl}
            label="Эталон"
            roiPoints={displayedReferenceRoiPoints}
          />
          <ImagePanel
            imageUrl={displayedCurrentImageUrl}
            label="Последний кадр инспекции"
          />
          <HeatmapPanel
            cameraId={cameraId}
            cameraImageUrl={displayedCurrentImageUrl}
            heatmapUrl={inspectHeatmapUrl}
            inspectResult={inspectResult}
          />
        </div>

        {inspectResultSyncState && (
          <div
            className="modal__frame-sync"
            data-state={inspectResultSyncState.state}
          >
            {inspectResultSyncState.label}
          </div>
        )}

        <InspectResultPanel inspectResult={inspectResult} />
      </section>
    </div>
  );
}

function ImagePanel({
  label,
  imageUrl,
  roiPoints,
  fetchPriority = "high",
}: {
  label: string;
  imageUrl?: string;
  roiPoints?: InterestPointNorm[];
  fetchPriority?: "high" | "low" | "auto";
}) {
  const svgPoints = roiPoints?.map((point) => `${point.x},${point.y}`).join(" ");

  return (
    <figure className="modal-image-panel">
      <figcaption>{label}</figcaption>
      <div className="modal-image-panel__image-wrap">
        <PreviewImage
          alt={label}
          className="modal-image-panel__image"
          decoding="async"
          fetchPriority={fetchPriority}
          placeholderClassName="modal-image-panel__placeholder"
          src={imageUrl}
        />
        {imageUrl && svgPoints && roiPoints && roiPoints.length >= 3 && (
          <svg
            aria-hidden="true"
            className="modal-image-panel__roi-overlay"
            preserveAspectRatio="none"
            viewBox="0 0 1 1"
          >
            <polygon points={svgPoints} />
          </svg>
        )}
      </div>
    </figure>
  );
}

function HeatmapPanel({
  cameraId,
  cameraImageUrl,
  heatmapUrl,
  inspectResult,
}: {
  cameraId?: number;
  cameraImageUrl?: string;
  heatmapUrl?: string;
  inspectResult?: InspectResultPayload;
}) {
  const matchingInspectResult =
    cameraId !== undefined && inspectResult?.camera_id === cameraId ? inspectResult : undefined;
  const frozenHeatmap = useMemo(
    () =>
      matchingInspectResult?.heatmap && heatmapUrl
        ? { ...matchingInspectResult.heatmap, http_path: heatmapUrl, artifact_id: undefined }
        : null,
    [heatmapUrl, matchingInspectResult],
  );
  return (
    <figure className="modal-image-panel">
      <figcaption>Heatmap</figcaption>
      {cameraId !== undefined && matchingInspectResult ? (
        <HeatmapViewer
          cameraId={cameraId}
          heatmap={frozenHeatmap}
          backgroundImageUrl={cameraImageUrl}
        />
      ) : (
        <div className="modal-image-panel__image-wrap">
          <div className="modal-image-panel__placeholder">No synchronized inspect result yet</div>
        </div>
      )}
    </figure>
  );
}

function InspectResultPanel({ inspectResult }: { inspectResult?: InspectResultPayload }) {
  return (
    <section
      className="modal-inspect-result"
      aria-label="Inspect result"
    >
      <header className="modal-inspect-result__header">
        <h3>Inspect result</h3>
        {inspectResult && <span>frame {inspectResult.frame_id}</span>}
      </header>

      {inspectResult ? (
        <>
          <dl className="modal-inspect-result__summary">
            <InspectResultField
              label="camera"
              value={inspectResult.camera_id}
            />
            <InspectResultField
              label="state"
              value={inspectResult.session_state}
            />
            <InspectResultField
              label="product"
              value={inspectResult.detector.product_type}
            />
            <InspectResultField
              label="detector"
              value={inspectResult.detector.detector_id}
            />
            <InspectResultField
              label="active view"
              value={inspectResult.active_reference_view_index}
            />
            <InspectResultField
              label="fp zones"
              value={inspectResult.fp_zones.length}
            />
            <InspectResultField
              label="heatmap"
              value={inspectResult.heatmap ? `${inspectResult.heatmap.width}x${inspectResult.heatmap.height}` : "none"}
            />
            <InspectResultField
              label="server time"
              value={formatServerTime(inspectResult.server_ts_ms)}
            />
          </dl>

          <div className="modal-inspect-result__decision">
            {formatInspectDecisionLine(inspectResult)}
          </div>

          <InspectResultRaw inspectResult={inspectResult} />
        </>
      ) : (
        <div className="modal-inspect-result__empty">No synchronized inspect result yet</div>
      )}
    </section>
  );
}

function InspectResultRaw({ inspectResult }: { inspectResult: InspectResultPayload }) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <details
      className="modal-inspect-result__details"
      open={isOpen}
      onToggle={(event) => setIsOpen(event.currentTarget.open)}
    >
      <summary>Raw result</summary>
      {isOpen && <pre className="modal-inspect-result__raw">{JSON.stringify(inspectResult, null, 2)}</pre>}
    </details>
  );
}

function InspectResultField({ label, value }: { label: string; value?: string | number }) {
  return (
    <div className="modal-inspect-result__field">
      <dt>{label}</dt>
      <dd>{value ?? "-"}</dd>
    </div>
  );
}

function formatInspectDecisionLine(inspectResult: InspectResultPayload) {
  return [
    `overall_pass: ${formatOptionalValue(inspectResult.overall_pass)}`,
    `action: ${formatOptionalValue(inspectResult.action)}`,
    `anomaly_score: ${formatOptionalValue(inspectResult.anomaly_score)}`,
    `python_status: ${formatOptionalValue(inspectResult.python_status)}`,
    `geometry_status: ${formatOptionalValue(inspectResult.geometry_status)}`,
  ].join(" | ");
}

function formatOptionalValue(value: string | number | boolean | undefined) {
  if (value === undefined) {
    return "-";
  }

  return String(value);
}

function formatServerTime(serverTsMs: number) {
  if (!Number.isFinite(serverTsMs) || serverTsMs <= 0) {
    return "-";
  }

  return new Date(serverTsMs).toLocaleTimeString();
}

function getInspectResultSyncState(
  inspectResult: InspectResultPayload | undefined,
  inspectResultImageUrl?: string,
  inspectHeatmapUrl?: string,
) {
  if (!inspectResult) {
    return null;
  }

  if (inspectResultImageUrl && inspectHeatmapUrl) {
    return {
      state: "synced" as const,
      label: `Последняя сохранённая инспекция: кадр ${inspectResult.frame_id}`,
    };
  }

  return {
    state: "partial" as const,
    label: `Frozen artifacts for frame ${inspectResult.frame_id} are incomplete`,
  };
}

