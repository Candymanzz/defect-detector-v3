import { useEffect, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { orchestratorApi } from "../../shared/api";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import type { HeatmapDescriptor, InspectResultPayload, InterestPointNorm } from "../../shared/ws";
import { HeatmapViewer } from "../HeatmapViewer";
import "./ModalWrapper.css";

type ModalWrapperProps = {
  isOpen: boolean;
  title: string;
  cameraId?: number;
  cameraImageUrl?: string;
  inspectResult?: InspectResultPayload;
  referenceImageUrl?: string;
  headerActions?: ReactNode;
  onClose: () => void;
};

export function ModalWrapper({
  isOpen,
  title,
  cameraId,
  cameraImageUrl,
  inspectResult,
  referenceImageUrl,
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
  const inspectResultImageUrl = inspectResult ? createInspectResultImageUrl(inspectResult) : undefined;
  const synchronizedHeatmap = inspectResult ? getSynchronizedHeatmap(inspectResult) : null;
  const displayedCurrentImageUrl = inspectResult ? inspectResultImageUrl : cameraImageUrl;
  const inspectResultSyncState = getInspectResultSyncState(inspectResult, inspectResultImageUrl, synchronizedHeatmap);

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
        className="modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>{title}</h2>
          <div className="modal__header-actions">
            {headerActions}
            <Button
              aria-label="Закрыть"
              className="modal__close"
              type="button"
              variant="ghost"
              onClick={onClose}
            >
              x
            </Button>
          </div>
        </header>

        <div className="modal__media-grid">
          <ImagePanel
            imageUrl={displayedReferenceImageUrl}
            label="Эталон"
            roiPoints={displayedReferenceRoiPoints}
          />
          <ImagePanel
            imageUrl={displayedCurrentImageUrl}
            label="Проверка камеры"
            emptyLabel={inspectResult ? `Exact image for frame ${inspectResult.frame_id} is unavailable` : undefined}
          />
          <HeatmapPanel
            cameraId={cameraId}
            cameraImageUrl={displayedCurrentImageUrl}
            inspectResult={inspectResult}
            heatmap={synchronizedHeatmap}
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
  emptyLabel,
}: {
  label: string;
  imageUrl?: string;
  roiPoints?: InterestPointNorm[];
  emptyLabel?: string;
}) {
  const svgPoints = roiPoints?.map((point) => `${point.x},${point.y}`).join(" ");

  return (
    <figure className="modal-image-panel">
      <figcaption>{label}</figcaption>
      <div className="modal-image-panel__image-wrap">
        <PreviewImage
          key={imageUrl ?? `${label}-offline`}
          alt={label}
          className="modal-image-panel__image"
          placeholderClassName="modal-image-panel__placeholder"
          src={imageUrl}
          emptyLabel={emptyLabel}
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
  inspectResult,
  heatmap,
}: {
  cameraId?: number;
  cameraImageUrl?: string;
  inspectResult?: InspectResultPayload;
  heatmap: HeatmapDescriptor | null;
}) {
  return (
    <figure className="modal-image-panel">
      <figcaption>Heatmap</figcaption>
      {cameraId !== undefined && inspectResult ? (
        <HeatmapViewer
          cameraId={cameraId}
          heatmap={heatmap}
          backgroundImageUrl={cameraImageUrl}
        />
      ) : (
        <div className="modal-image-panel__image-wrap">
          <div className="modal-image-panel__placeholder">No inspect result yet</div>
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

          <pre className="modal-inspect-result__raw">{JSON.stringify(inspectResult, null, 2)}</pre>
        </>
      ) : (
        <div className="modal-inspect-result__empty">No inspect result yet</div>
      )}
    </section>
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

function createInspectResultImageUrl(inspectResult: InspectResultPayload) {
  const imagePath = inspectResult.http_path ?? inspectResult.current.http_path;

  if (!imagePath || isMutableCurrentImagePath(imagePath)) {
    return undefined;
  }

  return orchestratorApi.imageUrl(imagePath, inspectResult.frame_id);
}

function getInspectResultSyncState(
  inspectResult: InspectResultPayload | undefined,
  inspectResultImageUrl: string | undefined,
  heatmap: HeatmapDescriptor | null,
) {
  if (!inspectResult) {
    return null;
  }

  if (inspectResultImageUrl && heatmap) {
    return {
      state: "synced" as const,
      label: `Image, result and heatmap are fixed to frame ${inspectResult.frame_id}`,
    };
  }

  if (inspectResultImageUrl) {
    return {
      state: "partial" as const,
      label: `Image and result are fixed to frame ${inspectResult.frame_id}, but heatmap source is incomplete`,
    };
  }

  const imagePath = inspectResult.http_path ?? inspectResult.current.http_path;
  if (imagePath && isMutableCurrentImagePath(imagePath)) {
    return {
      state: "partial" as const,
      label: `Frame ${inspectResult.frame_id} result received, but its image source is live; image overlay is disabled to prevent mismatch`,
    };
  }

  return {
    state: "partial" as const,
    label: `Inspect result for frame ${inspectResult.frame_id} has no dedicated preview image`,
  };
}

function isMutableCurrentImagePath(imagePath: string) {
  return /^\/api\/camera\/\d+\/current\.jpg$/.test(readUrlPathname(imagePath));
}

function getSynchronizedHeatmap(inspectResult: InspectResultPayload) {
  const heatmap = inspectResult.heatmap;
  if (!heatmap) {
    return null;
  }

  if (heatmap.artifact_id) {
    return heatmap;
  }

  return heatmap.http_path && !isMutableHeatmapPath(heatmap.http_path) ? heatmap : null;
}

function isMutableHeatmapPath(heatmapPath: string) {
  return /^\/api\/camera\/\d+\/heatmap\.u8$/.test(readUrlPathname(heatmapPath));
}

function readUrlPathname(value: string) {
  try {
    return new URL(value, "http://localhost").pathname;
  } catch {
    return value.split(/[?#]/, 1)[0];
  }
}
