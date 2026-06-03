import { useEffect, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { getReferenceImageUrl, subscribeReferenceImages } from "../../shared/referenceImages";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import type { InspectResultPayload } from "../../shared/ws";
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
  const storedReferenceImageUrl = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImageUrl(cameraId),
    () => undefined,
  );
  const displayedReferenceImageUrl = referenceImageUrl ?? storedReferenceImageUrl;

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
            <button
              aria-label="Close modal"
              className="modal__close"
              type="button"
              onClick={onClose}
            >
              x
            </button>
          </div>
        </header>

        <div className="modal__images">
          <ImagePanel
            imageUrl={displayedReferenceImageUrl}
            label="Эталон"
          />
          <ImagePanel
            imageUrl={cameraImageUrl}
            label="Проверка камеры"
          />
          {cameraId !== undefined && inspectResult?.heatmap && (
            <HeatmapViewer
              cameraId={cameraId}
              heatmap={inspectResult.heatmap}
              backgroundImageUrl={cameraImageUrl}
            />
          )}
        </div>

        <InspectResultPanel inspectResult={inspectResult} />
      </section>
    </div>
  );
}

function ImagePanel({ label, imageUrl }: { label: string; imageUrl?: string }) {
  return (
    <figure className="modal-image-panel">
      <div className="modal-image-panel__image-wrap">
        <PreviewImage
          key={imageUrl ?? `${label}-offline`}
          alt={label}
          className="modal-image-panel__image"
          placeholderClassName="modal-image-panel__placeholder"
          src={imageUrl}
        />
      </div>
      <figcaption>{label}</figcaption>
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

function formatServerTime(serverTsMs: number) {
  if (!Number.isFinite(serverTsMs) || serverTsMs <= 0) {
    return "-";
  }

  return new Date(serverTsMs).toLocaleTimeString();
}
