import { useState } from "react";
import { MainOverview } from "../components/MainOverview";
import { PlcPanel } from "../components/PlcPanel";
import { SettingList } from "../components/SettingList";
import logo from "../shared/assets/images/savt_logo_white.png";
import { Button } from "../shared/ui/Button";
import type { InspectionStats } from "../components/MainOverview/type";
import { useBackendStatus } from "./useBackendStatus";
import "./App.css";

const EMPTY_INSPECTION_STATS: InspectionStats = {
  total: 0,
  passed: 0,
  failed: 0,
  referenceFrameId: undefined,
  referenceSetAtMs: undefined,
  inspectionStartedAtMs: undefined,
  inspectionStoppedAtMs: undefined,
};

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);
  const [isPlcPanelOpen, setIsPlcPanelOpen] = useState(false);
  const [inspectionStats, setInspectionStats] = useState<InspectionStats>(EMPTY_INSPECTION_STATS);
  const backendStatus = useBackendStatus();

  const handleSettingsCameraToggle = (cameraId: number) => {
    setSelectedSettingsCameraId((currentCameraId) => (currentCameraId === cameraId ? null : cameraId));
  };

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="app-header-left">
          <img
            width={"30%"}
            src={logo}
            alt="Детектор дефектов"
            className="logo"
          />
          <h1 style={{ fontSize: "24px", fontWeight: "bold" }}>Автоматизация контроля качества</h1>
        </div>
        <div className="app-header-right">
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
          selectedSettingsCameraId={selectedSettingsCameraId}
          onSettingsCameraToggle={handleSettingsCameraToggle}
          onInspectionStatsChange={setInspectionStats}
        />
        <SettingList
          selectedCameraId={selectedSettingsCameraId}
          inspectionStats={inspectionStats}
        />
      </div>
      <PlcPanel isOpen={isPlcPanelOpen} onClose={() => setIsPlcPanelOpen(false)} />
    </main>
  );
}
