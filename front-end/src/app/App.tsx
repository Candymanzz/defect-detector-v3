import { useCallback, useEffect, useState } from "react";
import { MainOverview } from "../components/MainOverview";
import type { BackendStatus } from "../components/MainOverview/type";
import { SettingList } from "../components/SettingList";
import logo from "../shared/assets/images/savt_logo_white.png";
import "./App.css";

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);
  const [isPreviewPaused, setIsPreviewPaused] = useState(false);
  const [backendStatus, setBackendStatus] = useState<BackendStatus>({
    state: "loading",
    text: "checking",
  });
  const [currentDate, setCurrentDate] = useState(() => new Date());

  useEffect(() => {
    const timerId = window.setInterval(() => {
      setCurrentDate(new Date());
    }, 1000);

    return () => window.clearInterval(timerId);
  }, []);

  const handleSettingsCameraToggle = (cameraId: number) => {
    setSelectedSettingsCameraId((currentCameraId) => (currentCameraId === cameraId ? null : cameraId));
  };
  const handleBackendStatusChange = useCallback((nextBackendStatus: BackendStatus) => {
    setBackendStatus(nextBackendStatus);
  }, []);

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="app-header-left">
          <img
            src={logo}
            alt="SAVT"
            className="app-header__logo"
          />
          <h1>Автоматизация контроля качества</h1>
        </div>
        <div className="app-header-right">
          <div className="app-header__status">
            <strong>Backend: {formatBackendState(backendStatus)}</strong>
            <span>{backendStatus.text}</span>
          </div>
        </div>
      </header>

      <div className="app-content">
        <MainOverview
          isPreviewPaused={isPreviewPaused}
          selectedSettingsCameraId={selectedSettingsCameraId}
          onSettingsCameraToggle={handleSettingsCameraToggle}
          onBackendStatusChange={handleBackendStatusChange}
        />
        <SettingList
          selectedCameraId={selectedSettingsCameraId}
          onPreviewPauseChange={setIsPreviewPaused}
        />
      </div>

      <footer className="app-footer">
        <span>Система контроля качества</span>
        <time dateTime={currentDate.toISOString()}>{formatFooterDateTime(currentDate)}</time>
      </footer>
    </main>
  );
}

function formatBackendState(status: BackendStatus) {
  if (status.state === "ready") {
    return "Online";
  }

  if (status.state === "error") {
    return "Offline";
  }

  return "Checking";
}

function formatFooterDateTime(date: Date) {
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    hour: "2-digit",
    hour12: false,
    minute: "2-digit",
    month: "2-digit",
    second: "2-digit",
    year: "numeric",
  }).format(date);
}
