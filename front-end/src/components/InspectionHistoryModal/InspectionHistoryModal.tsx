import { useEffect } from "react";
import { orchestratorApi } from "../../shared/api";
import { getReferenceImage } from "../../shared/referenceImages";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import type { HeatmapDescriptor, InspectResultPayload } from "../../shared/ws";
import { HeatmapViewer } from "../HeatmapViewer";
import type { InspectionHistoryItem } from "../MainOverview/type";
import "./InspectionHistoryModal.css";

type InspectionHistoryModalProps = {
  inspectionId: string;
  results: InspectionHistoryItem[];
  historyItems?: Array<{
    groupKey: string;
    inspectionId: string;
    result: "pass" | "fail" | "capture";
  }>;
  selectedGroupKey?: string;
  onHistorySelect?: (groupKey: string) => void;
  onClose: () => void;
};

export function InspectionHistoryModal({
  inspectionId,
  results,
  historyItems = [],
  selectedGroupKey,
  onHistorySelect,
  onClose,
}: InspectionHistoryModalProps) {
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
        aria-label={`Инспекция ${inspectionId}`}
        aria-modal="true"
        className="modal inspection-history-modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Инспекция {inspectionId}</h2>
          <button
            aria-label="Закрыть"
            className="modal__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        {historyItems.length > 0 && (
          <section
            className="inspection-history-modal__navigation"
            aria-label="Общие результаты инспекций"
          >
            <header>Общие результаты инспекций</header>
            <div className="inspection-history-modal__navigation-tiles">
              {historyItems.map((item) => (
                <button
                  className="inspection-history-modal__navigation-tile"
                  data-active={item.groupKey === selectedGroupKey}
                  data-result={item.result}
                  key={item.groupKey}
                  type="button"
                  aria-pressed={item.groupKey === selectedGroupKey}
                  onClick={() => onHistorySelect?.(item.groupKey)}
                >
                  {item.inspectionId}
                </button>
              ))}
            </div>
          </section>
        )}

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
  const referenceImageUrl = getReferenceImage(result.camera_id)?.imageUrl;
  const comparisonImageUrl = referenceImageUrl ?? imageUrl;
  const heatmap = resolveInspectionHeatmap(result);

  return (
    <article
      className="inspection-history-modal__card"
      data-result={item.result}
    >
      <header className="inspection-history-modal__card-header">
        <strong>Камера {result.camera_id}</strong>
        <span>{item.result === "pass" ? "Годен" : item.result === "fail" ? "Брак" : "Съёмка"}</span>
      </header>

      <div className="inspection-history-modal__media">
        <figure>
          <figcaption>Эталон</figcaption>
          <div className="inspection-history-modal__image-wrap">
            <PreviewImage
              alt={`Инспекция ${item.inspectionId}, камера ${result.camera_id}`}
              className="inspection-history-modal__image"
              emptyLabel="Эталон недоступен"
              placeholderClassName="inspection-history-modal__placeholder"
              src={comparisonImageUrl}
            />
          </div>
        </figure>

        <figure>
          <figcaption>Тепловая карта</figcaption>
          {heatmap ? (
            <HeatmapViewer
              cameraId={result.camera_id}
              heatmap={heatmap}
              backgroundImageUrl={comparisonImageUrl}
              learnedZones={result.fp_zones}
            />
          ) : (
            <div className="inspection-history-modal__placeholder">Тепловая карта отсутствует</div>
          )}
        </figure>
      </div>

      <dl className="inspection-history-modal__summary">
        <ResultField
          label="Кадр"
          value={result.frame_id}
        />
        <ResultField
          label="Действие"
          value={result.action}
        />
        <ResultField
          label="Аномалия"
          value={result.anomaly_score}
        />
        <ResultField
          label="Python"
          value={result.python_status}
        />
        <ResultField
          label="Геометрия"
          value={result.geometry_status}
        />
        <ResultField
          label="Изделие"
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
  const httpPath = result.http_path ?? result.current.http_path;
  if (httpPath?.includes("/api/frame-archive/")) {
    return orchestratorApi.imageUrl(httpPath, result.frame_id);
  }

  if (result.artifact_bundle_id) {
    return orchestratorApi.url(`/api/inspection-artifacts/${encodeURIComponent(result.artifact_bundle_id)}/frame.jpg`);
  }

  return httpPath ? orchestratorApi.imageUrl(httpPath, result.frame_id) : undefined;
}

function resolveInspectionHeatmap(result: InspectResultPayload): HeatmapDescriptor | null {
  if (!result.heatmap) {
    return null;
  }

  const httpPath = result.http_path ?? result.current.http_path;
  if (httpPath?.includes("/api/frame-archive/") && httpPath.endsWith("/frame.jpg")) {
    return {
      ...result.heatmap,
      artifact_id: undefined,
      http_path: httpPath.replace(/\/frame\.jpg$/, "/heatmap.u8"),
    };
  }

  if (result.artifact_bundle_id) {
    return {
      ...result.heatmap,
      artifact_id: undefined,
      http_path: `/api/inspection-artifacts/${encodeURIComponent(result.artifact_bundle_id)}/heatmap.u8`,
    };
  }

  return result.heatmap;
}
