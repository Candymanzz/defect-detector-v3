import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { MainOverview } from "../components/MainOverview";
import { PlcPanel } from "../components/PlcPanel";
import { SettingList } from "../components/SettingList";
import { orchestratorApi } from "../shared/api/orchestratorApi";
import type { PlcTimeoutState } from "../shared/api/types";
import logo from "../shared/assets/images/savt_logo_white.png";
import { errorMessage as formatErrorMessage } from "../shared/lib/errors";
import { Button } from "../shared/ui/Button";
import type { InspectionStats } from "../components/MainOverview/type";
import { useBackendStatus } from "./useBackendStatus";
import "./App.css";

const EMPTY_INSPECTION_STATS: InspectionStats = {
  total: 0,
  passed: 0,
  failed: 0,
  groups: [],
  referenceFrameId: undefined,
  referenceSetAtMs: undefined,
  inspectionStartedAtMs: undefined,
  inspectionStoppedAtMs: undefined,
};

/** PLC DM D4405: 0 = сталь, 1 = пластик. */
const HANDLE_MATERIAL_MODE_KEY = "handle_material_mode";
const HANDLE_MATERIAL_ADDRESS = "D4405";

function findHandleModeEntry(timeouts: PlcTimeoutState[] | undefined): PlcTimeoutState | undefined {
  return (timeouts ?? []).find(
    (item) => item.name === HANDLE_MATERIAL_MODE_KEY || item.address === HANDLE_MATERIAL_ADDRESS,
  );
}

function handleModeUnits(entry: PlcTimeoutState | undefined): number {
  if (!entry) {
    return 0;
  }
  const raw = entry as PlcTimeoutState & { value_units?: number };
  const units = raw.valueUnits ?? raw.value_units ?? raw.rawWord;
  return Number.isFinite(units) ? Number(units) : 0;
}

function isPlasticFromTimeoutUnits(units: number | undefined): boolean {
  return (units ?? 0) !== 0;
}

function handleModeErrorMessage(error: unknown): string {
  const message = formatErrorMessage(error).trim();
  return message || "Не удалось записать режим ручки в ПЛК";
}

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);
  const [isPlcPanelOpen, setIsPlcPanelOpen] = useState(false);
  const [isPlasticHandleMode, setIsPlasticHandleMode] = useState(false);
  const [handleModeBusy, setHandleModeBusy] = useState(false);
  const [handleModeError, setHandleModeError] = useState<string | null>(null);
  const [inspectionStats, setInspectionStats] = useState<InspectionStats>(EMPTY_INSPECTION_STATS);
  const [inspectionResetVersion, setInspectionResetVersion] = useState(0);
  const [settingsMaxHeightPx, setSettingsMaxHeightPx] = useState<number | undefined>(undefined);
  const cameraOverviewsRef = useRef<HTMLDivElement | null>(null);
  const backendStatus = useBackendStatus();

  const handleSettingsCameraToggle = (cameraId: number) => {
    setSelectedSettingsCameraId((currentCameraId) => (currentCameraId === cameraId ? null : cameraId));
  };

  const handleAnalysisSettingsOpen = async (cameraId: number) => {
    await orchestratorApi.setTestMode(true);
    const inspectionState = await orchestratorApi.getInspectionStatus();
    window.dispatchEvent(new CustomEvent("inspection-control-changed", { detail: inspectionState }));
    setSelectedSettingsCameraId(cameraId);
  };

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const response = await orchestratorApi.getPlcTimeouts();
        if (cancelled) {
          return;
        }
        const entry = findHandleModeEntry(response.timeouts);
        if (entry) {
          setIsPlasticHandleMode(isPlasticFromTimeoutUnits(handleModeUnits(entry)));
        }
        setHandleModeError(null);
      } catch (error) {
        if (!cancelled) {
          setHandleModeError(handleModeErrorMessage(error));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleHandleModeChange = async (nextPlastic: boolean) => {
    if (handleModeBusy) {
      return;
    }
    const previous = isPlasticHandleMode;
    const value = nextPlastic ? 1 : 0;
    setIsPlasticHandleMode(nextPlastic);
    setHandleModeBusy(true);
    setHandleModeError(null);
    try {
      // Пишем и по имени, и по адресу — FINS DM D4405 raw 0/1.
      const response = await orchestratorApi.putPlcTimeouts({
        [HANDLE_MATERIAL_MODE_KEY]: value,
        [HANDLE_MATERIAL_ADDRESS]: value,
      });
      const entry = findHandleModeEntry(response.timeouts);
      if (entry) {
        setIsPlasticHandleMode(isPlasticFromTimeoutUnits(handleModeUnits(entry)));
      }
    } catch (error) {
      setIsPlasticHandleMode(previous);
      setHandleModeError(handleModeErrorMessage(error));
    } finally {
      setHandleModeBusy(false);
    }
  };

  useLayoutEffect(() => {
    const cameraOverviewsElement = cameraOverviewsRef.current;
    if (!cameraOverviewsElement) {
      return;
    }

    const updateSettingsHeight = () => {
      setSettingsMaxHeightPx(cameraOverviewsElement.getBoundingClientRect().height);
    };

    updateSettingsHeight();

    const resizeObserver = new ResizeObserver(updateSettingsHeight);
    resizeObserver.observe(cameraOverviewsElement);
    window.addEventListener("resize", updateSettingsHeight);

    return () => {
      resizeObserver.disconnect();
      window.removeEventListener("resize", updateSettingsHeight);
    };
  }, []);

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="app-header-left">
          <img
            src={logo}
            alt="Детектор дефектов"
            className="logo"
          />
          <h1 style={{ fontSize: "24px", fontWeight: "bold" }}>Автоматизация контроля качества</h1>
        </div>
        <div className="app-header-right">
          <button
            type="button"
            className="app-header-handle-mode"
            title={handleModeError ?? "Режим ручки → PLC D4405 (0=сталь, 1=пластик)"}
            data-error={handleModeError ? "true" : undefined}
            data-busy={handleModeBusy ? "true" : undefined}
            data-plastic={isPlasticHandleMode ? "true" : undefined}
            aria-pressed={isPlasticHandleMode}
            aria-label="Режим типа ручки"
            disabled={handleModeBusy}
            onClick={() => {
              void handleHandleModeChange(!isPlasticHandleMode);
            }}
          >
            <span data-active={!isPlasticHandleMode}>Стальная ручка</span>
            <span className="app-header-handle-switch" aria-hidden="true">
              <span />
            </span>
            <span data-active={isPlasticHandleMode}>Пластиковая ручка</span>
          </button>
          {handleModeError ? <span className="app-header-handle-error">{handleModeError}</span> : null}
          <Button
            type="button"
            className="app-header-plc-button"
            onClick={() => setIsPlcPanelOpen(true)}
          >
            ПЛК
          </Button>
          <div className="app-header-status">
            <span>Статус</span>
            <strong data-status={backendStatus.state}>{backendStatus.text}</strong>
          </div>
        </div>
      </header>
      <div className="app-content">
        <MainOverview
          rootRef={cameraOverviewsRef}
          inspectionResetVersion={inspectionResetVersion}
          selectedSettingsCameraId={selectedSettingsCameraId}
          onSettingsCameraToggle={handleSettingsCameraToggle}
          onAnalysisSettingsOpen={handleAnalysisSettingsOpen}
          onInspectionStatsChange={setInspectionStats}
        />
        <SettingList
          selectedCameraId={selectedSettingsCameraId}
          inspectionStats={inspectionStats}
          maxHeightPx={settingsMaxHeightPx}
          onInspectionReset={() => {
            setInspectionResetVersion((version) => version + 1);
          }}
        />
      </div>
      <PlcPanel
        isOpen={isPlcPanelOpen}
        onClose={() => setIsPlcPanelOpen(false)}
      />
    </main>
  );
}
