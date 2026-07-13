import { useState } from "react";
import { MainOverview } from "../components/MainOverview";
import { SettingList } from "../components/SettingList";
import logo from "../shared/assets/images/savt_logo_white.png";
import { lineDirectionLabel, useLineDirection } from "../shared/hooks/useLineDirection";
import { useBackendStatus } from "./useBackendStatus";
import "./App.css";

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);
  const backendStatus = useBackendStatus();
  const lineDirection = useLineDirection();

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
            alt="Defect Detector"
            className="logo"
          />
          <h1 style={{ fontSize: "24px", fontWeight: "bold" }}>Автоматизация контроля качества</h1>
        </div>
        <div className="app-header-right">
          <div className="app-header-status">
            <span>Направление</span>
            <strong data-direction={lineDirection}>{lineDirectionLabel(lineDirection)}</strong>
          </div>
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
        />
        <SettingList selectedCameraId={selectedSettingsCameraId} />
      </div>
    </main>
  );
}
