import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, ReactNode } from "react";
import { HttpError, orchestratorApi } from "../../shared/api";
import type { GeometryInspectResponse } from "../../shared/api";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { updateReferenceFpZones } from "../../shared/referenceImages";
import { PreviewImage } from "../../shared/ui/PreviewImage";
import { orchestratorWs } from "../../shared/ws";
import type { FpZoneNorm, InspectResultPayload, InterestPointNorm, ServerWsMessage } from "../../shared/ws";
import { FpZoneEditor } from "../FpZoneEditor";
import { GeometryDeviationViewer } from "../GeometryDeviationViewer";
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
  referenceRoiPoints?: InterestPointNorm[];
  referenceJointRoiPoints?: InterestPointNorm[];
  referenceFpZones?: FpZoneNorm[];
  inspectionItems?: InspectionNavigationItem[];
  selectedInspectionFrameId?: string;
  dangerHeaderAction?: ReactNode;
  headerActions?: ReactNode;
  analysisSettingsContent?: ReactNode;
  onInspectionSelect?: (frameId: string) => void;
  onClose: () => void;
};

type InspectionNavigationItem = {
  frameId: string;
  inspectionId: string;
  result: "pass" | "fail" | "capture";
};

type GeometrySnapshotState = {
  geometry: GeometryInspectResponse | null;
  loading: boolean;
  error: string | null;
};

const EMPTY_GEOMETRY_SNAPSHOT: GeometrySnapshotState = {
  geometry: null,
  loading: false,
  error: null,
};

type FpZonesStatus = {
  state: "idle" | "loading" | "saving" | "success" | "error";
  text: string;
};

export function ModalWrapper({
  isOpen,
  title,
  cameraId,
  cameraImageUrl,
  inspectHeatmapUrl,
  inspectResult,
  referenceImageUrl,
  referenceRoiPoints,
  referenceJointRoiPoints,
  referenceFpZones,
  inspectionItems = [],
  selectedInspectionFrameId,
  dangerHeaderAction,
  headerActions,
  analysisSettingsContent,
  onInspectionSelect,
  onClose,
}: ModalWrapperProps) {
  const displayedCurrentImageUrl = inspectResult ? cameraImageUrl : undefined;
  const inspectResultSyncState = getInspectResultSyncState(inspectResult, displayedCurrentImageUrl, inspectHeatmapUrl);
  const inspectionResultState = resolveInspectionResultState(inspectResult);
  const modalClassName = inspectionResultState ? `modal modal--${inspectionResultState}` : "modal";
  const geometrySnapshot = useGeometrySnapshot(
    isOpen,
    cameraId,
    inspectResult?.frame_id,
    inspectResult?.geometry_status,
    inspectResult?.server_ts_ms,
    inspectResult?.test_analyze,
  );
  const [editedFpZones, setEditedFpZones] = useState<FpZoneNorm[]>(() => copyFpZones(referenceFpZones ?? []));
  const [fpZonesStatus, setFpZonesStatus] = useState<FpZonesStatus>({ state: "idle", text: "" });

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
            {inspectionResultState === "fail" && inspectResult?.learned_review_id && inspectResult.detector.product_type && (
              <LearnFrameAction
                key={`${inspectResult.camera_id}-${inspectResult.frame_id}-${inspectResult.learned_review_id}`}
                inspectResult={inspectResult}
                productType={inspectResult.detector.product_type}
              />
            )}
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
            {inspectionResultState === "pass" ? "Годен" : inspectionResultState === "fail" ? "Брак" : "Съёмка"}
          </div>
        )}

        <div className="modal__media-grid modal__media-grid--with-geometry">
          <ImagePanel
            imageUrl={referenceImageUrl}
            label="Эталон"
            roiPoints={referenceRoiPoints}
            jointRoiPoints={referenceJointRoiPoints}
            fpZones={editedFpZones}
          />
          <ImagePanel
            imageUrl={displayedCurrentImageUrl}
            label="Последний кадр инспекции"
          />
          <HeatmapPanel
            key={`heatmap-${cameraId}-${inspectResult?.server_ts_ms ?? "none"}-${inspectResult?.artifact_bundle_id ?? "no-bundle"}`}
            cameraId={cameraId}
            cameraImageUrl={displayedCurrentImageUrl}
            heatmapUrl={inspectHeatmapUrl}
            inspectResult={inspectResult}
          />
          {analysisSettingsContent ?? (
            <GeometryDeviationViewer
              error={geometrySnapshot.error}
              geometry={geometrySnapshot.geometry}
              loading={geometrySnapshot.loading}
            />
          )}
        </div>

        {cameraId !== undefined && referenceImageUrl && (
          <FpZonesRuntimePanel
            cameraId={cameraId}
            disabled={fpZonesStatus.state === "saving" || fpZonesStatus.state === "loading"}
            heatmapSize={resolveFpZonesHeatmapSize(inspectResult)}
            imageUrl={referenceImageUrl}
            productType={inspectResult?.detector.product_type}
            roiPoints={referenceRoiPoints}
            status={fpZonesStatus}
            zones={editedFpZones}
            onChange={(zones) => {
              setEditedFpZones(zones);
              setFpZonesStatus({ state: "idle", text: "" });
            }}
            onStatusChange={setFpZonesStatus}
          />
        )}

        {inspectionItems.length > 0 && (
          <InspectionNavigation
            items={inspectionItems}
            selectedFrameId={selectedInspectionFrameId}
            onSelect={onInspectionSelect}
          />
        )}

        {inspectResultSyncState && (
          <div
            className="modal__frame-sync"
            data-state={inspectResultSyncState.state}
          >
            {inspectResultSyncState.label}
          </div>
        )}

        <InspectResultPanel
          key={`inspect-${inspectResult?.camera_id ?? "x"}-${inspectResult?.server_ts_ms ?? 0}-${inspectResult?.anomaly_score ?? "na"}`}
          geometry={geometrySnapshot.geometry}
          inspectResult={inspectResult}
        />
      </section>
    </div>
  );
}

function LearnFrameAction({ inspectResult, productType }: { inspectResult: InspectResultPayload; productType: string }) {
  const [state, setState] = useState<"idle" | "saving" | "success" | "error">("idle");
  const [message, setMessage] = useState("");

  const handleAccept = async () => {
    setState("saving");
    setMessage("Кадр отправляется в дообучение…");
    try {
      const result = await orchestratorApi.acceptLearnedNormals({
        frameId: inspectResult.frame_id,
        cameraId: inspectResult.camera_id,
        productType,
      });
      const count = result.accepted_count ?? result.accepted_case_ids?.length ?? result.accepted_cases?.length ?? 0;
      setState("success");
      setMessage(`Кадр добавлен в анализ${count > 0 ? `: сохранено фрагментов — ${count}` : ""}.`);
    } catch (error) {
      const status = error instanceof HttpError ? error.status : undefined;
      setState("error");
      setMessage(
        status === 404
          ? "Кадр уже не в сессии. Выберите свежий БРАК."
          : status === 409
            ? "Кадр уже добавлен или в нём нечего дообучать."
            : error instanceof Error
              ? error.message
              : "Не удалось добавить кадр в анализ.",
      );
    }
  };

  return (
    <div className="modal__learning-action" data-state={state}>
      <button className="modal__action" type="button" disabled={state === "saving" || state === "success"} onClick={handleAccept}>
        {state === "saving" ? "Добавление…" : state === "success" ? "Добавлено в анализ" : "Добавить кадр в анализ"}
      </button>
      {message && <span role={state === "error" ? "alert" : "status"}>{message}</span>}
    </div>
  );
}

function FpZonesRuntimePanel({
  cameraId,
  imageUrl,
  productType,
  roiPoints,
  zones,
  heatmapSize,
  status,
  disabled,
  onChange,
  onStatusChange,
}: {
  cameraId: number;
  imageUrl: string;
  productType?: string;
  roiPoints?: InterestPointNorm[];
  zones: FpZoneNorm[];
  heatmapSize: { width: number; height: number };
  status: FpZonesStatus;
  disabled: boolean;
  onChange: (zones: FpZoneNorm[]) => void;
  onStatusChange: (status: FpZonesStatus) => void;
}) {
  const validZoneCount = zones.filter((zone) => zone.points_norm_heatmap.length >= 3).length;

  const handleLoadServerZones = () => {
    if (!productType) {
      onStatusChange({ state: "error", text: "Не найден тип изделия для загрузки FP zones" });
      return;
    }

    onStatusChange({ state: "loading", text: "Загрузка FP zones с сервера..." });
    void orchestratorApi
      .getFpZones(productType)
      .then((response) => {
        const loadedZones = response.zones.map((zone) => ({
          id: zone.id,
          camera_id: cameraId,
          note: zone.note ?? "Исключающая зона",
          points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ x: point.x, y: point.y })),
        }));
        onChange(loadedZones);
        onStatusChange({ state: "success", text: `Загружено FP zones: ${loadedZones.length}` });
      })
      .catch((error: unknown) => {
        onStatusChange({
          state: "error",
          text: error instanceof Error ? error.message : "Не удалось загрузить FP zones с сервера",
        });
      });
  };

  const handleSave = () => {
    if (!orchestratorWs.isOpen) {
      onStatusChange({ state: "error", text: "WebSocket не подключён" });
      return;
    }

    const fpZones = zones
      .filter((zone) => zone.points_norm_heatmap.length >= 3)
      .map((zone) => ({
        ...zone,
        camera_id: cameraId,
        points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ x: point.x, y: point.y })),
      }));

    try {
      onStatusChange({ state: "saving", text: "Сохранение FP zones..." });
      const messageId = orchestratorWs.sendFpZonesUpdate({
        heatmap_width: heatmapSize.width,
        heatmap_height: heatmapSize.height,
        fp_zones: fpZones,
      });

      waitForFpZonesAck(messageId)
        .then(() => {
          updateReferenceFpZones([cameraId], fpZones);
          onStatusChange({ state: "success", text: "FP zones обновлены" });
        })
        .catch((error: unknown) => {
          onStatusChange({
            state: "error",
            text: error instanceof Error ? error.message : "Не удалось обновить FP zones",
          });
        });
    } catch (error) {
      onStatusChange({
        state: "error",
        text: error instanceof Error ? error.message : "Не удалось отправить FP zones",
      });
    }
  };

  return (
    <section className="modal-fp-zones">
      <header className="modal-fp-zones__header">
        <div>
          <h3>FP zones во время инспекции</h3>
          <span>Зоны отправляются без перезадания эталона</span>
        </div>
        <div className="modal-fp-zones__actions">
          <button
            type="button"
            disabled={disabled || !productType}
            onClick={handleLoadServerZones}
          >
            {status.state === "loading" ? "Загрузка..." : "Загрузить зоны"}
          </button>
          <button
            type="button"
            disabled={disabled}
            onClick={handleSave}
          >
            {status.state === "saving" ? "Сохранение..." : "Обновить FP zones"}
          </button>
        </div>
      </header>
      <div className="modal-fp-zones__body">
        <FpZoneEditor
          disabled={disabled}
          imageUrl={imageUrl}
          roiPoints={roiPoints}
          zones={zones}
          onChange={onChange}
        />
        <div
          className="modal-fp-zones__status"
          data-state={status.state}
          aria-live="polite"
        >
          {status.text || `Готовых зон: ${validZoneCount}`}
        </div>
      </div>
    </section>
  );
}

function useGeometrySnapshot(
  isOpen: boolean,
  cameraId: number | undefined,
  frameId: string | undefined,
  geometryStatus: string | undefined,
  serverTsMs: number | undefined,
  testAnalyze: boolean | undefined,
): GeometrySnapshotState {
  const [state, setState] = useState<GeometrySnapshotState>({
    geometry: null,
    loading: false,
    error: null,
  });

  useEffect(() => {
    if (!isOpen || cameraId === undefined) {
      return;
    }

    const normalizedStatus = geometryStatus?.trim().toUpperCase();
    if (normalizedStatus === "SKIPPED" || normalizedStatus === "SKIP") {
      setState({
        geometry: null,
        loading: false,
        error: "Геометрия пропущена для этого кадра",
      });
      return;
    }

    const controller = new AbortController();
    window.queueMicrotask(() => {
      if (!controller.signal.aborted) {
        setState((current) => ({ ...current, loading: true, error: null }));
      }
    });

    void orchestratorApi
      .getGeometryLatestSnapshot(cameraId)
      .then((snapshot) => {
        if (controller.signal.aborted) {
          return;
        }
        // Avoid showing a stale geometry snapshot from another frame during test re-runs.
        if (frameId !== undefined && String(snapshot.frameId) !== String(frameId)) {
          setState({
            geometry: null,
            loading: false,
            error: testAnalyze ? "Нет свежего снимка геометрии для тестового кадра" : null,
          });
          return;
        }
        setState({
          geometry: snapshot.geometry ?? null,
          loading: false,
          error: null,
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        const message =
          error instanceof HttpError && error.status === 404
            ? "Нет снимка геометрии для камеры"
            : error instanceof Error
              ? error.message
              : "Не удалось загрузить геометрию";
        setState({
          geometry: null,
          loading: false,
          error: message,
        });
      });

    return () => controller.abort();
  }, [isOpen, cameraId, frameId, geometryStatus, serverTsMs, testAnalyze]);

  if (!isOpen || cameraId === undefined) {
    return EMPTY_GEOMETRY_SNAPSHOT;
  }

  return state;
}

function InspectionNavigation({
  title = "Инспекции",
  className,
  items,
  selectedFrameId,
  onSelect,
}: {
  title?: string;
  className?: string;
  items: InspectionNavigationItem[];
  selectedFrameId?: string;
  onSelect?: (frameId: string) => void;
}) {
  const tilesRef = useRef<HTMLDivElement>(null);
  const newestFrameId = items[0]?.frameId;

  useEffect(() => {
    tilesRef.current?.scrollTo({ left: 0 });
  }, [newestFrameId]);

  return (
    <section
      className={["modal-inspection-navigation", className].filter(Boolean).join(" ")}
      title={title}
      aria-label="Навигация по инспекциям"
    >
      <header>Инспекции</header>
      <div
        ref={tilesRef}
        className="modal-inspection-navigation__tiles"
      >
        {items.map((item) => (
          <button
            className="modal-inspection-navigation__tile"
            data-active={item.frameId === selectedFrameId}
            data-result={item.result}
            key={item.frameId}
            type="button"
            aria-pressed={item.frameId === selectedFrameId}
            onClick={() => onSelect?.(item.frameId)}
          >
            {item.inspectionId}
          </button>
        ))}
      </div>
    </section>
  );
}

function ImagePanel({
  label,
  imageUrl,
  roiPoints,
  jointRoiPoints,
  fpZones,
  fetchPriority = "high",
}: {
  label: string;
  imageUrl?: string;
  roiPoints?: InterestPointNorm[];
  jointRoiPoints?: InterestPointNorm[];
  fpZones?: FpZoneNorm[];
  fetchPriority?: "high" | "low" | "auto";
}) {
  const [imageSize, setImageSize] = useState({ width: 4, height: 3 });
  const svgPoints = roiPoints?.map((point) => `${point.x * imageSize.width},${point.y * imageSize.height}`).join(" ");
  const jointSvgPoints = jointRoiPoints
    ?.map((point) => `${point.x * imageSize.width},${point.y * imageSize.height}`)
    .join(" ");
  const fpZoneSvgPoints = fpZones
    ?.filter((zone) => zone.points_norm_heatmap.length >= 3)
    .map((zone, index) => ({
      key: zone.id ?? index,
      points: zone.points_norm_heatmap
        .map((point) => `${point.x * imageSize.width},${point.y * imageSize.height}`)
        .join(" "),
    }));
  const mediaStyle = {
    "--media-aspect": imageSize.width / imageSize.height,
  } as CSSProperties;

  return (
    <figure className="modal-image-panel">
      <figcaption>{label}</figcaption>
      <div className="modal-image-panel__image-wrap">
        <div
          className="modal-image-panel__media"
          style={mediaStyle}
        >
          <PreviewImage
            alt={label}
            className="modal-image-panel__image"
            decoding="async"
            fetchPriority={fetchPriority}
            placeholderClassName="modal-image-panel__placeholder"
            src={imageUrl}
            onLoad={(event) => {
              const { naturalWidth, naturalHeight } = event.currentTarget;
              if (naturalWidth > 0 && naturalHeight > 0) {
                setImageSize({ width: naturalWidth, height: naturalHeight });
              }
            }}
          />
          {imageUrl && svgPoints && roiPoints && roiPoints.length >= 3 && (
            <svg
              aria-hidden="true"
              className="modal-image-panel__roi-overlay"
              preserveAspectRatio="xMidYMid meet"
              viewBox={`0 0 ${imageSize.width} ${imageSize.height}`}
            >
              <polygon points={svgPoints} />
            </svg>
          )}
          {imageUrl && jointSvgPoints && jointRoiPoints && jointRoiPoints.length >= 3 && (
            <svg
              aria-hidden="true"
              className="modal-image-panel__roi-overlay modal-image-panel__roi-overlay--joint"
              preserveAspectRatio="xMidYMid meet"
              viewBox={`0 0 ${imageSize.width} ${imageSize.height}`}
            >
              <polygon points={jointSvgPoints} />
            </svg>
          )}
          {imageUrl && fpZoneSvgPoints && fpZoneSvgPoints.length > 0 && (
            <svg
              aria-hidden="true"
              className="modal-image-panel__roi-overlay modal-image-panel__roi-overlay--fp-zones"
              preserveAspectRatio="xMidYMid meet"
              viewBox={`0 0 ${imageSize.width} ${imageSize.height}`}
            >
              {fpZoneSvgPoints.map((zone) => (
                <polygon
                  key={zone.key}
                  points={zone.points}
                />
              ))}
            </svg>
          )}
        </div>
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
      <figcaption>Тепловая карта</figcaption>
      {cameraId !== undefined && matchingInspectResult?.heatmap ? (
        <HeatmapViewer
          cameraId={cameraId}
          heatmap={frozenHeatmap}
          backgroundImageUrl={cameraImageUrl}
        />
      ) : (
        <div className="modal-image-panel__image-wrap">
          <div className="modal-image-panel__placeholder">
            {matchingInspectResult ? "Тепловая карта готовится" : "Синхронизированного результата инспекции ещё нет"}
          </div>
        </div>
      )}
    </figure>
  );
}

function InspectResultPanel({
  inspectResult,
  geometry,
}: {
  inspectResult?: InspectResultPayload;
  geometry?: GeometryInspectResponse | null;
}) {
  return (
    <section
      className="modal-inspect-result"
      aria-label="Результат инспекции"
    >
      <header className="modal-inspect-result__header">
        <h3>Результат инспекции</h3>
        {inspectResult && (
          <span>
            {inspectResult.test_analyze || inspectResult.inspection_id === "тест"
              ? "тест"
              : `кадр ${inspectResult.frame_id}`}
          </span>
        )}
      </header>

      {inspectResult ? (
        <>
          <dl className="modal-inspect-result__summary">
            <InspectResultField
              label="камера"
              value={inspectResult.camera_id}
            />
            <InspectResultField
              label="состояние"
              value={inspectResult.session_state}
            />
            <InspectResultField
              label="изделие"
              value={inspectResult.detector.product_type}
            />
            <InspectResultField
              label="детектор"
              value={inspectResult.detector.detector_id}
            />
            <InspectResultField
              label="активный вид"
              value={inspectResult.active_reference_view_index}
            />
            <InspectResultField
              label="FP зоны"
              value={inspectResult.fp_zones.length}
            />
            <InspectResultField
              label="тепловая карта"
              value={inspectResult.heatmap ? `${inspectResult.heatmap.width}x${inspectResult.heatmap.height}` : "нет"}
            />
            <InspectResultField
              label="время сервера"
              value={formatServerTime(inspectResult.server_ts_ms)}
            />
            <InspectResultField
              label="радиус отклонения"
              value={
                geometry?.deviationRadiusMm !== undefined
                  ? `${Number(geometry.deviationRadiusMm).toFixed(3)} мм`
                  : undefined
              }
            />
          </dl>

          <div className="modal-inspect-result__decision">{formatInspectDecisionLine(inspectResult, geometry)}</div>

          <InspectResultRaw inspectResult={inspectResult} />
        </>
      ) : (
        <div className="modal-inspect-result__empty">Синхронизированного результата инспекции ещё нет</div>
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
      <summary>Исходный результат</summary>
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

function formatInspectDecisionLine(inspectResult: InspectResultPayload, geometry?: GeometryInspectResponse | null) {
  const deviation = geometry?.deviationRadiusMm !== undefined ? Number(geometry.deviationRadiusMm).toFixed(3) : "-";
  return [
    `общий результат: ${formatOptionalValue(inspectResult.overall_pass)}`,
    `действие: ${formatOptionalValue(inspectResult.action)}`,
    `оценка аномалии: ${formatOptionalValue(inspectResult.anomaly_score)}`,
    `статус Python: ${formatOptionalValue(inspectResult.python_status)}`,
    `статус геометрии: ${formatOptionalValue(inspectResult.geometry_status)}`,
    `радиус отклонения, мм: ${deviation}`,
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

function resolveFpZonesHeatmapSize(inspectResult: InspectResultPayload | undefined) {
  const width = inspectResult?.heatmap?.width ?? inspectResult?.current?.width ?? 1;
  const height = inspectResult?.heatmap?.height ?? inspectResult?.current?.height ?? 1;

  return {
    width: Math.max(1, Math.round(width)),
    height: Math.max(1, Math.round(height)),
  };
}

function waitForFpZonesAck(messageId: string) {
  return new Promise<void>((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
      unsubscribe();
      reject(new Error("Нет подтверждения обновления FP zones"));
    }, 5000);

    const unsubscribe = orchestratorWs.onMessage((message: ServerWsMessage) => {
      if (message.message_id !== messageId) {
        return;
      }

      if (message.type === "server.fp_zones_ack") {
        window.clearTimeout(timeoutId);
        unsubscribe();
        if (message.payload.ok) {
          resolve();
        } else {
          reject(new Error("Сервер отклонил FP zones"));
        }
        return;
      }

      if (message.type === "server.error") {
        window.clearTimeout(timeoutId);
        unsubscribe();
        reject(new Error(`${message.payload.code}: ${message.payload.message}`));
      }
    });
  });
}

function copyFpZones(zones: FpZoneNorm[]) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ x: point.x, y: point.y })),
  }));
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

  if (inspectResultImageUrl) {
    return {
      state: "loading" as const,
      label: `Кадр инспекции ${inspectResult.frame_id} получен, тепловая карта готовится`,
    };
  }

  return {
    state: "partial" as const,
    label: `Замороженные артефакты для кадра ${inspectResult.frame_id} неполные`,
  };
}
