import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { orchestratorApi } from "../../shared/api";
import type { GeometryRuntimeConfig } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { Button } from "../../shared/ui/Button";

const DEFAULT_MAX_SHIFT_MM = 0.5;
const DEFAULT_JOINT_SENSITIVITY = 0.5;

type Props = {
  selectedCameraId: number | null;
  testFrameId?: string;
  onSaveComplete?: () => Promise<void> | void;
  hideSaveAction?: boolean;
};
export type GeometryTestSettingsPanelHandle = { save: () => Promise<void> };

type Status = { kind: "loading" | "saving" | "success" | "error"; text: string };

export const GeometryTestSettingsPanel = forwardRef<GeometryTestSettingsPanelHandle, Props>(function GeometryTestSettingsPanel({
  selectedCameraId,
  testFrameId,
  onSaveComplete,
  hideSaveAction = false,
}, ref) {
  const [maxShiftMm, setMaxShiftMm] = useState(DEFAULT_MAX_SHIFT_MM);
  const [jointSensitivity, setJointSensitivity] = useState(DEFAULT_JOINT_SENSITIVITY);
  const [status, setStatus] = useState<Status>({ kind: "loading", text: "Загрузка геометрии…" });
  const hydratedRef = useRef(false);
  const userEditedRef = useRef(false);
  const previewRequestIdRef = useRef(0);
  const previewTimerRef = useRef<number | null>(null);

  useEffect(() => {
    let active = true;
    hydratedRef.current = false;
    userEditedRef.current = false;
    setStatus({ kind: "loading", text: "Загрузка геометрии…" });
    orchestratorApi
      .getGeometryRuntime(selectedCameraId)
      .then((runtime) => {
        if (!active) {
          return;
        }
        setMaxShiftMm(readMaxShiftMm(runtime));
        setJointSensitivity(readJointSensitivity(runtime));
        hydratedRef.current = true;
        setStatus({ kind: "success", text: "Параметры геометрии загружены" });
      })
      .catch((error) => {
        if (active) {
          setStatus({ kind: "error", text: errorMessage(error) });
        }
      });
    return () => {
      active = false;
    };
  }, [selectedCameraId]);

  useEffect(() => {
    if (!hydratedRef.current || selectedCameraId === null || !userEditedRef.current) {
      return;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
    }
    const requestId = ++previewRequestIdRef.current;
    const preview = Boolean(testFrameId);
    previewTimerRef.current = window.setTimeout(() => {
      setStatus({
        kind: "saving",
        text: preview ? `Сохранение геометрии и проверка кадра ${testFrameId}…` : "Сохранение геометрии…",
      });
      void persistGeometry(selectedCameraId, maxShiftMm, jointSensitivity)
        .then(() => (preview ? orchestratorApi.testAnalyzePinnedFrame(selectedCameraId, testFrameId!) : undefined))
        .then(() => {
          if (requestId === previewRequestIdRef.current) {
            setStatus({
              kind: "success",
              text: preview
                ? `Геометрия сохранена, кадр ${testFrameId} пересчитан`
                : "Геометрия сохранена",
            });
          }
        })
        .catch((error) => {
          if (requestId === previewRequestIdRef.current) {
            setStatus({ kind: "error", text: errorMessage(error) });
          }
        });
    }, 450);
    return () => {
      if (previewTimerRef.current !== null) {
        window.clearTimeout(previewTimerRef.current);
        previewTimerRef.current = null;
      }
    };
  }, [jointSensitivity, maxShiftMm, selectedCameraId, testFrameId]);

  const persist = async () => {
    if (selectedCameraId === null) {
      return;
    }
    if (!hydratedRef.current) {
      const error = new Error("Настройки геометрии ещё загружаются");
      setStatus({ kind: "error", text: error.message });
      throw error;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
      previewTimerRef.current = null;
    }
    previewRequestIdRef.current += 1;
    setStatus({ kind: "saving", text: "Сохранение геометрии…" });
    try {
      await persistGeometry(selectedCameraId, maxShiftMm, jointSensitivity);
      if (!hideSaveAction) {
        if (testFrameId) {
          await orchestratorApi.testAnalyzePinnedFrame(selectedCameraId, testFrameId);
          setStatus({ kind: "success", text: `Геометрия сохранена, кадр ${testFrameId} пересчитан` });
          return;
        }
        await onSaveComplete?.();
      }
      setStatus({ kind: "success", text: "Геометрия сохранена" });
    } catch (error) {
      setStatus({ kind: "error", text: errorMessage(error) });
      throw error;
    }
  };

  useImperativeHandle(ref, () => ({ save: persist }));

  const busy = status.kind === "loading" || status.kind === "saving";

  return (
    <div className="geometry-test-settings">
      <p className="geometry-test-settings__intro">
        Параметры java-geometry для текущего кадра (смещение и сегментация стыка).
      </p>
      <div className="geometry-test-settings__fields">
        <label className="geometry-test-settings__field">
          <span>
            <strong>Макс. смещение, мм</strong>
            <output>{maxShiftMm.toFixed(2)}</output>
          </span>
          <input
            type="range"
            min={0}
            max={100}
            step={0.01}
            value={maxShiftMm}
            disabled={busy || selectedCameraId === null}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              userEditedRef.current = true;
              setMaxShiftMm(clamp(Number(event.target.value), 0, 100));
            }}
          />
        </label>
        <label className="geometry-test-settings__field">
          <span>
            <strong>Чувствительность стыка</strong>
            <output>{jointSensitivity.toFixed(2)}</output>
          </span>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={jointSensitivity}
            disabled={busy || selectedCameraId === null}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              userEditedRef.current = true;
              setJointSensitivity(clamp(Number(event.target.value), 0, 1));
            }}
          />
        </label>
      </div>
      <div className="geometry-test-settings__footer">
        <span className="geometry-test-settings__status" data-kind={status.kind}>
          {status.text}
        </span>
        {!hideSaveAction && (
          <Button type="button" disabled={busy || selectedCameraId === null} onClick={() => void persist()}>
            Сохранить геометрию
          </Button>
        )}
      </div>
    </div>
  );
});

async function persistGeometry(cameraId: number, maxShiftMm: number, jointSensitivity: number) {
  await orchestratorApi.patchGeometryRuntime(
    {
      max_shift_mm: maxShiftMm,
      joint_seam_segmentation_enabled: true,
      joint_seam_segmentation_sensitivity: jointSensitivity,
    },
    cameraId,
  );
}

function readMaxShiftMm(runtime: GeometryRuntimeConfig) {
  return clamp(
    firstNumber(
      runtime.runtimeOverrides.max_shift_mm,
      runtime.runtimeOverrides.maxShiftMm,
      runtime.effectiveForNextGeometryInspect.max_shift_mm,
      runtime.effectiveForNextGeometryInspect.maxShiftMm,
      DEFAULT_MAX_SHIFT_MM,
    ),
    0,
    100,
  );
}

function readJointSensitivity(runtime: GeometryRuntimeConfig) {
  return clamp(
    firstNumber(
      runtime.runtimeOverrides.joint_seam_segmentation_sensitivity,
      runtime.runtimeOverrides.jointSeamSegmentationSensitivity,
      runtime.effectiveForNextGeometryInspect.joint_seam_segmentation_sensitivity,
      runtime.effectiveForNextGeometryInspect.jointSeamSegmentationSensitivity,
      DEFAULT_JOINT_SENSITIVITY,
    ),
    0,
    1,
  );
}

function firstNumber(...values: unknown[]) {
  for (const value of values) {
    const number = typeof value === "number" ? value : Number(value);
    if (Number.isFinite(number)) {
      return number;
    }
  }
  return 0;
}

function clamp(value: number, min: number, max: number) {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.min(max, Math.max(min, value));
}
