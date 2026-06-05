import { useState } from "react";
import { MainOverview } from "../components/MainOverview";
import { SettingList } from "../components/SettingList";
import logo from "../shared/assets/images/savt_logo_white.png";
import "./App.css";

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);

  const handleSettingsCameraToggle = (cameraId: number) => {
    setSelectedSettingsCameraId((currentCameraId) => (currentCameraId === cameraId ? null : cameraId));
  };

  return (
    <main className="app-shell">
      <header className="app-header" >
        <div className="app-header-left">
        <img width={'30%'} src={logo} alt="Defect Detector" className="logo" />
        <h1 style={{ fontSize: '24px', fontWeight: 'bold' }}>Автоматизация контроля качества</h1>
        </div>
        <div className="app-header-right">
          <button className="app-header-button">Меню</button>
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
