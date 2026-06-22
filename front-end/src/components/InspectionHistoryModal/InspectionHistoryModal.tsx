import { useEffect } from "react";
import { orchestratorApi } from "../../shared/api";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import type { InspectResultPayload } from "../../shared/ws";
import { HeatmapViewer } from "../HeatmapViewer";
import type { InspectionHistoryItem } from "../MainOverview/type";
import "./InspectionHistoryModal.css";

type InspectionHistoryModalProps = {
  inspectionId: string;
  results: InspectionHistoryItem[];
  onClose: () => void;
};

export function InspectionHistoryModal({ inspectionId, results, onClose }: InspectionHistoryModalProps) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div
      className="modal-backdrop"
      onMouseDown={onClose}
    >
      <section
        aria-label={`Inspection ${inspectionId}`}
        aria-modal="true"
        className="modal inspection-history-modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Inspection {inspectionId}</h2>
          <button
            aria-label="Закрыть"
            className="modal__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        <div className="inspection-history-modal__grid">
          {results.map((item) => (
            <InspectionResultCard
              item={item}
              key={item.inspectResult.camera_id}
            />
          ))}
        </div>
      </section>
    </div>
  );
}

function InspectionResultCard({ item }: { item: InspectionHistoryItem }) {
  const result = item.inspectResult;
  const imageUrl = resolveInspectionImageUrl(result);

  return (
    <article
      className="inspection-history-modal__card"
      data-result={item.result}
    >
      <header className="inspection-history-modal__card-header">
        <strong>Camera {result.camera_id}</strong>
        <span>{item.result === "pass" ? "Годен" : "Брак"}</span>
      </header>

      <div className="inspection-history-modal__media">
        <figure>
          <figcaption>Inspection frame</figcaption>
          <div className="inspection-history-modal__image-wrap">
            <PreviewImage
              alt={`Inspection ${item.inspectionId}, camera ${result.camera_id}`}
              className="inspection-history-modal__image"
              emptyLabel="Кадр недоступен"
              placeholderClassName="inspection-history-modal__placeholder"
              src={imageUrl}
            />
          </div>
        </figure>

        <figure>
          <figcaption>Heatmap</figcaption>
          {result.heatmap ? (
            <HeatmapViewer
              cameraId={result.camera_id}
              heatmap={result.heatmap}
              backgroundImageUrl={imageUrl}
            />
          ) : (
            <div className="inspection-history-modal__placeholder">Heatmap отсутствует</div>
          )}
        </figure>
      </div>

      <dl className="inspection-history-modal__summary">
        <ResultField
          label="Frame"
          value={result.frame_id}
        />
        <ResultField
          label="Action"
          value={result.action}
        />
        <ResultField
          label="Anomaly"
          value={result.anomaly_score}
        />
        <ResultField
          label="Python"
          value={result.python_status}
        />
        <ResultField
          label="Geometry"
          value={result.geometry_status}
        />
        <ResultField
          label="Product"
          value={result.detector.product_type}
        />
      </dl>

      <details className="inspection-history-modal__details">
        <summary>Все данные результата</summary>
        <pre>{JSON.stringify(result, null, 2)}</pre>
      </details>
    </article>
  );
}

function ResultField({ label, value }: { label: string; value?: string | number | boolean }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value === undefined ? "-" : String(value)}</dd>
    </div>
  );
}

function resolveInspectionImageUrl(result: InspectResultPayload) {
  if (result.artifact_bundle_id) {
    return orchestratorApi.url(
      `/api/inspection-artifacts/${encodeURIComponent(result.artifact_bundle_id)}/frame.jpg`,
    );
  }

  const imagePath = result.http_path ?? result.current.http_path;
  return imagePath ? orchestratorApi.imageUrl(imagePath, result.frame_id) : undefined;
}
