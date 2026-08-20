import { useEffect, useState, useSyncExternalStore } from "react";
import type { CSSProperties, MouseEvent } from "react";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { RoiContourEditor } from "../RoiContourEditor";
import { orchestratorApi } from "../../shared/api";
import type { LearnedNormalCase } from "../../shared/api/types";
import {
  deleteArchivedReferenceGroup,
  detachLearnedCaseFromReferences,
  getArchivedReferenceGroups,
  getReferenceImage,
  subscribeReferenceImages,
} from "../../shared/referenceImages";
import type { ArchivedReferenceGroup } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import { useReferenceSetupController } from "./ReferenceController";

type ReferenceSetupProps = {
  initialCameraId: number | null;
  onClose: () => void;
};

export function ReferenceSetup({ onClose, initialCameraId }: ReferenceSetupProps) {
  const {
    message,
    cameraSlots,
    cameraGroups,
    activeGroupIndex,
    setActiveGroupIndex,
    jointCameraId,
    hasJointRoi,
    canSendAllReferences,
    hasAnyStoredReferenceForActiveGroup,
    isNewReferenceMode,
    referenceSubmission,
    handleCaptureNewReferenceFrames,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
    selectedCameraId,
    selectedRoiMode,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    setJointRoiPolygon,
    setRoiPolygonForCamera,
    fpZonesByCameraId,
  } = useReferenceSetupController(onClose, initialCameraId);
  const [selectedArchiveId, setSelectedArchiveId] = useState<string | null>(null);
  const [selectedLearnedCaseId, setSelectedLearnedCaseId] = useState<string | null>(null);
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
  const editorKey = `${selectedRoiMode}-${selectedCameraId}`;
  const selectedEditorPoints =
    selectedRoiMode === "joint" ? jointRoiPolygon : (roiPolygonsByCameraId[selectedCameraId] ?? []);
  const archivedReferences = useSyncExternalStore(subscribeReferenceImages, getArchivedReferenceGroups, () => []);
  const activeCameraIds = cameraSlots.map((slot) => slot.cameraId);
  const activeGroupKey = createCameraGroupKey(activeCameraIds);
  const activeGroupArchivedReferences = archivedReferences.filter(
    (archive) => createCameraGroupKey(archive.cameraIds) === activeGroupKey,
  );
  const activeReferenceKey = useSyncExternalStore(
    subscribeReferenceImages,
    () => createActiveReferenceKey(activeCameraIds),
    () => "",
  );
  const selectedArchive =
    activeGroupArchivedReferences.find((referenceGroup) => referenceGroup.id === selectedArchiveId) ??
    activeGroupArchivedReferences[0];
  const activeArchive = activeGroupArchivedReferences.find(
    (archive) => createArchiveReferenceKey(archive) === activeReferenceKey,
  );
  const selectedProductType = selectedSlot?.frame?.detector.product_type;
  const learnedNormals = useLearnedNormals(
    selectedSlot?.cameraId,
    selectedProductType,
    selectedSlot ? activeArchive?.learnedCaseIdsByCameraId[selectedSlot.cameraId] ?? [] : [],
  );
  const readyCameraCount = cameraSlots.filter(
    (slot) => Boolean(slot.frame) && (roiPolygonsByCameraId[slot.cameraId]?.length ?? 0) >= 3,
  ).length;
  const hasSetupError = /не получен|не задан|не отправлен|ошиб|отклон/i.test(message);
  const shouldStartNewReference = hasAnyStoredReferenceForActiveGroup && !isNewReferenceMode;
  const primaryReferenceLabel = shouldStartNewReference
    ? "Задать новый эталон"
    : isNewReferenceMode
      ? "Подтвердить новые эталоны →"
      : "Задать и использовать эталон →";

  useEffect(() => {
    const previousBodyOverflow = document.body.style.overflow;
    const previousHtmlOverflow = document.documentElement.style.overflow;
    document.body.style.overflow = "hidden";
    document.documentElement.style.overflow = "hidden";

    return () => {
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overflow = previousHtmlOverflow;
    };
  }, []);

  useEffect(() => {
    if (!selectedLearnedCaseId) return;

    const handleGalleryKeyDown = (event: KeyboardEvent) => {
      const selectedIndex = learnedNormals.cases.findIndex((item) => item.id === selectedLearnedCaseId);
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setSelectedLearnedCaseId(null);
      } else if (event.key === "ArrowLeft" && selectedIndex >= 0) {
        event.preventDefault();
        const previousIndex = (selectedIndex - 1 + learnedNormals.cases.length) % learnedNormals.cases.length;
        setSelectedLearnedCaseId(learnedNormals.cases[previousIndex]?.id ?? null);
      } else if (event.key === "ArrowRight" && selectedIndex >= 0) {
        event.preventDefault();
        const nextIndex = (selectedIndex + 1) % learnedNormals.cases.length;
        setSelectedLearnedCaseId(learnedNormals.cases[nextIndex]?.id ?? null);
      }
    };

    window.addEventListener("keydown", handleGalleryKeyDown, { capture: true });
    return () => window.removeEventListener("keydown", handleGalleryKeyDown, { capture: true });
  }, [learnedNormals.cases, selectedLearnedCaseId]);

  return (
    <div
      className="modal-backdrop"
      onMouseDown={onClose}
    >
      <section
        aria-label="Задание эталона"
        aria-modal="true"
        className="modal reference-setup"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Задание эталона</h2>
          <button
            aria-label="Закрыть"
            className="modal__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        <div className="reference-setup__body">
          {referenceSubmission && (
            <section
              className="reference-setup__submission"
              data-state={referenceSubmission.state}
              aria-live="polite"
            >
              <div className="reference-setup__submission-heading">
                <strong>
                  {referenceSubmission.state === "pending"
                    ? "Эталон отправлен — ожидается подтверждение"
                    : referenceSubmission.state === "confirmed"
                      ? "Эталон подтверждён сервером"
                      : "Сервер отклонил эталон. Проверьте кадры и ROI контроля"}
                </strong>
                <time>{new Date(referenceSubmission.submittedAtMs).toLocaleTimeString()}</time>
              </div>
              <div className="reference-setup__submission-frames">
                {referenceSubmission.cameraIds.map((cameraId) => (
                  <span key={cameraId}>
                    Камера {cameraId}: кадр {referenceSubmission.frameIdsByCameraId[cameraId] ?? "—"}
                  </span>
                ))}
              </div>
            </section>
          )}

          <div className="reference-setup__layout">
            <aside className="reference-setup__sidebar reference-setup__sidebar--cameras">
              <h3>Группа камер</h3>
              <div
                className="reference-setup__group-switch"
                role="tablist"
                aria-label="Группы камер"
              >
                {cameraGroups.map((groupCameraIds, groupIndex) => (
                  <button
                    key={groupCameraIds.join("-")}
                    aria-selected={groupIndex === activeGroupIndex}
                    className={
                      groupIndex === activeGroupIndex
                        ? "reference-setup__group-tab reference-setup__group-tab--active"
                        : "reference-setup__group-tab"
                    }
                    role="tab"
                    type="button"
                    onClick={() => setActiveGroupIndex(groupIndex)}
                  >
                    Группа {groupIndex + 1}
                    <span>Камеры {groupCameraIds.join(", ")}</span>
                  </button>
                ))}
              </div>

              <h3>Камеры группы {activeGroupIndex + 1}</h3>
              <div className="reference-setup__camera-list">
                {cameraSlots.map((slot) => {
                  const hasFrame = Boolean(slot.frame);
                  const hasRoi = (roiPolygonsByCameraId[slot.cameraId]?.length ?? 0) >= 3;
                  return (
                    <button
                      key={slot.cameraId}
                      className={
                        slot.cameraId === selectedCameraId && selectedRoiMode === "interest"
                          ? "reference-setup__slot reference-setup__slot--active"
                          : "reference-setup__slot"
                      }
                      data-ready={hasFrame && hasRoi}
                      type="button"
                      onClick={() => {
                        handleSelectCamera(slot.cameraId);
                      }}
                    >
                      <span
                        className="reference-setup__camera-icon"
                        aria-hidden="true"
                      >
                        ▣
                      </span>
                      <span className="reference-setup__camera-copy">
                        <strong>Камера {slot.cameraId}</strong>
                        <small>{hasFrame ? "Кадр получен" : "Кадр не получен"}</small>
                        <small data-state={hasRoi ? "ready" : "missing"}>{hasRoi ? "ROI задан" : "ROI не задан"}</small>
                        <small>
                          Доп. кадров: {slot.cameraId === selectedSlot?.cameraId ? learnedNormals.cases.length : "—"}
                        </small>
                      </span>
                      <span
                        className="reference-setup__camera-state"
                        aria-hidden="true"
                      >
                        {hasFrame && hasRoi ? "✓" : "!"}
                      </span>
                    </button>
                  );
                })}
              </div>

              <Button
                className="reference-setup__button reference-setup__refresh"
                onClick={handleCaptureNewReferenceFrames}
              >
                {hasAnyStoredReferenceForActiveGroup ? "＋ Добавить новый кадр" : "↻ Обновить кадры"}
              </Button>
              <div className="reference-setup__legend">
                <span>
                  <i data-state="missing" /> Кадр не получен
                </span>
                <span>
                  <i data-state="pending" /> Не настроено
                </span>
                <span>
                  <i data-state="ready" /> Готово
                </span>
              </div>
            </aside>

            <main className="reference-setup__workspace">
              <header className="reference-setup__workspace-header">
                <div>
                  <h3>Камера {selectedSlot?.cameraId ?? "—"}</h3>
                  <span data-ready={Boolean(selectedSlot?.frame)}>
                    {selectedSlot?.frame ? "Кадр получен" : "Ожидание кадра"}
                  </span>
                </div>
                {selectedSlot && (
                  <button
                    className={
                      selectedSlot.cameraId === jointCameraId && selectedRoiMode === "joint"
                        ? "reference-setup__joint-trigger reference-setup__joint-trigger--active"
                        : "reference-setup__joint-trigger"
                    }
                    type="button"
                    onClick={() => {
                      handleSelectJointRoi(selectedSlot.cameraId);
                    }}
                  >
                    ☆ Назначить камерой шва
                  </button>
                )}
              </header>

              <div className="reference-setup__editor">
                {selectedSlot?.imageUrl ? (
                  <RoiContourEditor
                    key={editorKey}
                    imageUrl={selectedSlot.imageUrl}
                    points={selectedEditorPoints}
                    exclusionZones={fpZonesByCameraId[selectedSlot.cameraId] ?? []}
                    shapeMode={selectedRoiMode === "joint" ? "oriented-rect" : "polygon"}
                    allowRadiusMode={selectedRoiMode !== "joint"}
                    onChange={(points) => {
                      if (selectedRoiMode === "joint") {
                        setJointRoiPolygon(points);
                        return;
                      }

                      setRoiPolygonForCamera(selectedSlot.cameraId, points);
                    }}
                  />
                ) : (
                  <div className="reference-setup__editor-empty">Кадр камеры ещё не получен</div>
                )}
              </div>

              {(activeReferenceKey || isNewReferenceMode) && (
                <div
                  className="reference-setup__active-reference"
                  data-source={isNewReferenceMode ? "new" : activeArchive ? "archive" : "current"}
                >
                  <strong>{isNewReferenceMode ? "Новый эталон" : "В работе"}</strong>
                  <span>
                    {isNewReferenceMode
                      ? "Свежие кадры — контуры нужно задать заново"
                      : activeArchive
                        ? `Архив от ${formatArchiveTime(activeArchive.createdAtMs)}`
                        : "Текущий эталон"}
                  </span>
                </div>
              )}
            </main>

            <div className="reference-setup__objects-column">
              <aside className="reference-setup__sidebar reference-setup__sidebar--objects">
              <h3>Объекты на изображении</h3>
              <section className="reference-setup__object-section">
                <header>
                  <span>ROI контроля</span>
                  <i data-kind="roi" />
                </header>
                <button
                  className={
                    selectedRoiMode === "interest"
                      ? "reference-setup__object-row reference-setup__object-row--active"
                      : "reference-setup__object-row"
                  }
                  type="button"
                  onClick={() => {
                    if (selectedSlot) handleSelectCamera(selectedSlot.cameraId);
                  }}
                >
                  <i data-kind="roi" /> ROI {selectedSlot?.cameraId ?? ""}
                  <span>{selectedEditorPoints.length >= 3 ? "задан" : "не задан"}</span>
                </button>
              </section>
              <section className="reference-setup__object-section">
                <header>
                  <span>Доп. кадры анализа</span>
                  <i data-kind="fp" />
                </header>
                {learnedNormals.loading && <div className="reference-setup__learned-empty">Загрузка…</div>}
                {learnedNormals.error && (
                  <div className="reference-setup__learned-empty" role="alert">{learnedNormals.error}</div>
                )}
                {!learnedNormals.loading && !learnedNormals.error && learnedNormals.cases.length === 0 && (
                  <div className="reference-setup__learned-empty">Для камеры пока нет добавленных кадров</div>
                )}
                <div className="reference-setup__learned-grid">
                  {learnedNormals.cases.map((item, index) => (
                    <figure key={item.id} className="reference-setup__learned-card">
                      <button
                        className="reference-setup__learned-delete"
                        type="button"
                        aria-label={`Удалить дополнительный фрагмент ${index + 1}`}
                        title="Удалить из анализа"
                        disabled={learnedNormals.deletingId === item.id}
                        onClick={() => void learnedNormals.remove(item.id)}
                      >
                        {learnedNormals.deletingId === item.id ? "…" : "×"}
                      </button>
                      <button
                        className="reference-setup__learned-open"
                        type="button"
                        aria-label={`Увеличить дополнительный фрагмент ${index + 1}`}
                        onClick={() => setSelectedLearnedCaseId(item.id)}
                      >
                        <img
                          src={orchestratorApi.learnedNormalImageUrl(item.id)}
                          alt={`Дополнительный фрагмент ${index + 1}`}
                        />
                      </button>
                      <figcaption>{item.note || `Фрагмент ${index + 1}`}</figcaption>
                    </figure>
                  ))}
                </div>
              </section>
              <section className="reference-setup__object-section">
                <header>
                  <span>Шов этикетки</span>
                  <i data-kind="joint" />
                </header>
                <div className="reference-setup__object-row">
                  <i data-kind="joint" /> Камера {jointCameraId}
                  <span>{hasJointRoi ? "ROI задан" : "не настроен"}</span>
                </div>
              </section>
              <p className="reference-setup__hint">
                <strong>Что такое ROI?</strong> Область изображения, в которой выполняется контроль. Всё за её пределами
                не учитывается при проверке.
              </p>
              </aside>
              <div className="reference-setup__footer">
                <div className="reference-setup__readiness">
                  <strong>Готовность группы {activeGroupIndex + 1}</strong>
                  <span>
                    {readyCameraCount} из {cameraSlots.length} камер готовы
                  </span>
                  <progress
                    max={Math.max(cameraSlots.length, 1)}
                    value={readyCameraCount}
                  />
                </div>
                <p
                  className="reference-setup__status"
                  data-error={hasSetupError}
                  title={message}
                  role={hasSetupError ? "alert" : undefined}
                >
                  {shouldStartNewReference
                    ? "Можно задать новые эталоны для любого количества камер. Остальные камеры сохранят старые эталоны."
                    : message}
                </p>
                <div className="reference-setup__footer-actions">
                  <button
                    className="reference-setup__cancel"
                    type="button"
                    onClick={onClose}
                  >
                    Отмена
                  </button>
                  <Button
                    className="reference-setup__button reference-setup__save"
                    disabled={!shouldStartNewReference && !canSendAllReferences}
                    onClick={shouldStartNewReference ? handleCaptureNewReferenceFrames : handleSendAllReferences}
                  >
                    {primaryReferenceLabel}
                  </Button>
                </div>
              </div>
            </div>
          </div>

          <ReferenceArchive
            archivedReferences={activeGroupArchivedReferences}
            activeArchiveId={activeArchive?.id}
            selectedArchive={selectedArchive}
            onDelete={async (archiveId) => {
              try {
                await deleteArchivedReferenceGroup(archiveId);
                if (selectedArchiveId === archiveId) {
                  setSelectedArchiveId(null);
                }
              } catch (error) {
                window.alert(error instanceof Error ? error.message : "Не удалось удалить эталон и его кадры анализа");
              }
            }}
            onSelect={setSelectedArchiveId}
            onUse={handleUseArchivedReference}
          />

          {selectedLearnedCaseId && (
            <LearnedFramesGallery
              cases={learnedNormals.cases}
              selectedId={selectedLearnedCaseId}
              onClose={() => setSelectedLearnedCaseId(null)}
              onSelect={setSelectedLearnedCaseId}
            />
          )}

        </div>
      </section>
    </div>
  );
}

function LearnedFramesGallery({
  cases,
  selectedId,
  onClose,
  onSelect,
}: {
  cases: LearnedNormalCase[];
  selectedId: string;
  onClose: () => void;
  onSelect: (caseId: string) => void;
}) {
  const selectedIndex = Math.max(0, cases.findIndex((item) => item.id === selectedId));
  const selectedCase = cases[selectedIndex];
  if (!selectedCase) return null;

  const selectOffset = (offset: number) => {
    const nextIndex = (selectedIndex + offset + cases.length) % cases.length;
    onSelect(cases[nextIndex].id);
  };

  return (
    <div
      className="reference-setup__gallery-backdrop"
      role="presentation"
      onMouseDown={onClose}
    >
      <section
        className="reference-setup__gallery"
        role="dialog"
        aria-modal="true"
        aria-label="Просмотр дополнительных кадров анализа"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <strong>{selectedCase.note || `Фрагмент ${selectedIndex + 1}`}</strong>
          <span>{selectedIndex + 1} / {cases.length}</span>
          <button type="button" aria-label="Закрыть" onClick={onClose}>x</button>
        </header>
        <div className="reference-setup__gallery-stage">
          {cases.length > 1 && (
            <button type="button" aria-label="Предыдущий кадр" onClick={() => selectOffset(-1)}>‹</button>
          )}
          <img
            src={orchestratorApi.learnedNormalImageUrl(selectedCase.id)}
            alt={selectedCase.note || `Дополнительный фрагмент ${selectedIndex + 1}`}
          />
          {cases.length > 1 && (
            <button type="button" aria-label="Следующий кадр" onClick={() => selectOffset(1)}>›</button>
          )}
        </div>
        <nav className="reference-setup__gallery-strip" aria-label="Дополнительные кадры">
          {cases.map((item, index) => (
            <button
              key={item.id}
              type="button"
              aria-label={`Открыть кадр ${index + 1}`}
              aria-current={item.id === selectedCase.id ? "true" : undefined}
              onClick={() => onSelect(item.id)}
            >
              <img src={orchestratorApi.learnedNormalImageUrl(item.id)} alt="" />
              <span>{index + 1}</span>
            </button>
          ))}
        </nav>
      </section>
    </div>
  );
}

function ReferenceArchive({
  archivedReferences,
  activeArchiveId,
  selectedArchive,
  onDelete,
  onSelect,
  onUse,
}: {
  archivedReferences: ArchivedReferenceGroup[];
  activeArchiveId?: string;
  selectedArchive?: ArchivedReferenceGroup;
  onDelete: (archiveId: string) => Promise<void>;
  onSelect: (archiveId: string) => void;
  onUse: (archiveId: string) => void;
}) {
  if (archivedReferences.length === 0) {
    return null;
  }

  return (
    <section
      className="reference-setup__archive"
      aria-label="Старые эталоны"
    >
      <header className="reference-setup__archive-header">
        <h3>Старые эталоны</h3>
        {selectedArchive && (
          <Button
            className="reference-setup__button"
            onClick={() => onUse(selectedArchive.id)}
          >
            Использовать выбранный
          </Button>
        )}
      </header>

      <div className="reference-setup__archive-layout">
        <div className="reference-setup__archive-tiles">
          {archivedReferences.map((archive) => (
            <article
              key={archive.id}
              className="reference-setup__archive-tile"
              data-active={archive.id === selectedArchive?.id}
              data-in-use={archive.id === activeArchiveId}
              role="button"
              tabIndex={0}
              onClick={() => onSelect(archive.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelect(archive.id);
                }
              }}
            >
              <img
                src={archive.images[0]?.imageUrl}
                alt={`Эталон камер ${archive.cameraIds.join(", ")}`}
              />
              <span>{formatArchiveTime(archive.createdAtMs)}</span>
              <strong>Камеры {archive.cameraIds.join(", ")}</strong>
              {archive.id === activeArchiveId && <em>В работе</em>}
              <button
                className="reference-setup__archive-delete"
                type="button"
                aria-label="Удалить старый эталон"
                onClick={(event: MouseEvent<HTMLButtonElement>) => {
                  event.stopPropagation();
                  void onDelete(archive.id);
                }}
              >
                x
              </button>
            </article>
          ))}
        </div>

        {selectedArchive && (
          <div className="reference-setup__archive-preview">
            {selectedArchive.images.map((image) => (
              <ArchiveImage
                key={image.cameraId}
                image={image}
              />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function ArchiveImage({ image }: { image: ArchivedReferenceGroup["images"][number] }) {
  const roiPoints = image.roiPoints.map((point) => `${point.x},${point.y}`).join(" ");
  const jointRoiPoints = image.jointRoiPoints?.map((point) => `${point.x},${point.y}`).join(" ");
  const fpZonePoints = image.fpZones
    ?.filter((zone) => zone.points_norm_heatmap.length >= 3)
    .map((zone, index) => ({
      key: zone.id ?? index,
      points: zone.points_norm_heatmap.map((point) => `${point.x},${point.y}`).join(" "),
    }));
  const mediaStyle = {
    "--archive-aspect": image.frame.width / image.frame.height,
  } as CSSProperties;

  return (
    <figure className="reference-setup__archive-frame">
      <figcaption>Камера {image.cameraId}</figcaption>
      <div
        className="reference-setup__archive-frame-media"
        style={mediaStyle}
      >
        <img
          src={image.imageUrl}
          alt={`Камера ${image.cameraId}`}
        />
        <div className="reference-setup__archive-frame-empty">Кадр не загружен</div>
        <svg
          aria-hidden="true"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
        >
          {image.roiPoints.length >= 3 && <polygon points={roiPoints} />}
          {image.jointRoiPoints && image.jointRoiPoints.length >= 3 && (
            <polygon
              className="reference-setup__archive-joint"
              points={jointRoiPoints}
            />
          )}
          {fpZonePoints?.map((zone) => (
            <polygon
              key={zone.key}
              className="reference-setup__archive-fp-zone"
              points={zone.points}
            />
          ))}
        </svg>
      </div>
    </figure>
  );
}

function formatArchiveTime(createdAtMs: number) {
  return new Date(createdAtMs).toLocaleTimeString();
}

function useLearnedNormals(cameraId?: number, productType?: string, allowedCaseIds: string[] = []) {
  const allowedCaseKey = [...allowedCaseIds].sort().join(",");
  const requestKey = cameraId !== undefined && productType ? `${cameraId}:${productType}:${allowedCaseKey}` : "";
  const [result, setResult] = useState<{
    key: string;
    cases: LearnedNormalCase[];
    error: string | null;
  }>({ key: "", cases: [], error: null });
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    if (cameraId === undefined || !productType) return;
    let active = true;
    orchestratorApi
      .getLearnedNormals(productType, cameraId)
      .then((payload) => {
        if (active) {
          const allowed = new Set(allowedCaseKey ? allowedCaseKey.split(",") : []);
          setResult({
            key: requestKey,
            cases: (payload.cases ?? []).filter((item) => allowed.has(item.id)),
            error: null,
          });
        }
      })
      .catch((error) => {
        if (active) {
          setResult({
            key: requestKey,
            cases: [],
            error: error instanceof Error ? error.message : "Не удалось загрузить дополнительные кадры",
          });
        }
      });
    return () => {
      active = false;
    };
  }, [allowedCaseKey, cameraId, productType, requestKey]);

  const remove = async (caseId: string) => {
    setDeletingId(caseId);
    try {
      await orchestratorApi.deleteLearnedNormal(caseId);
      detachLearnedCaseFromReferences(caseId);
      setResult((current) => ({
        ...current,
        cases: current.cases.filter((item) => item.id !== caseId),
        error: null,
      }));
    } catch (error) {
      setResult((current) => ({
        ...current,
        error: error instanceof Error ? error.message : "Не удалось удалить дополнительный кадр",
      }));
    } finally {
      setDeletingId(null);
    }
  };

  if (!requestKey) return { cases: [], error: null, loading: false, deletingId, remove };
  return {
    cases: result.key === requestKey ? result.cases : [],
    error: result.key === requestKey ? result.error : null,
    loading: result.key !== requestKey,
    deletingId,
    remove,
  };
}

function createActiveReferenceKey(cameraIds: number[]) {
  return cameraIds
    .map((cameraId) => {
      const referenceImage = getReferenceImage(cameraId);
      return referenceImage ? createReferenceImageKey(cameraId, referenceImage) : "";
    })
    .filter(Boolean)
    .join("|");
}

function createCameraGroupKey(cameraIds: number[]) {
  return [...cameraIds].sort((left, right) => left - right).join(",");
}

function createArchiveReferenceKey(archive: ArchivedReferenceGroup) {
  return archive.images.map((image) => createReferenceImageKey(image.cameraId, image)).join("|");
}

function createReferenceImageKey(
  cameraId: number,
  referenceImage: { frame: { frame_id: string | number }; imageUrl: string },
) {
  return `${cameraId}:${referenceImage.frame.frame_id}:${referenceImage.imageUrl}`;
}
