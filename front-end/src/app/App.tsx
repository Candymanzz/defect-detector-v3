import { useEffect, useState } from "react";
import { MainOverview } from "../components/MainOverview";
import { SettingList } from "../components/SettingList";
import logo from "../shared/assets/images/savt_logo_white.png";
import { Button } from "../shared/ui/Button";
import "./App.css";

export function App() {
  const [selectedSettingsCameraId, setSelectedSettingsCameraId] = useState<number | null>(null);
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
            <strong>Backend: Offline</strong>
            <span>HTTP 502 Bad Gateway</span>
          </div>
          <Button variant="ghost">☰ Меню</Button>
        </div>
      </header>

      <div className="app-content">
        <MainOverview
          selectedSettingsCameraId={selectedSettingsCameraId}
          onSettingsCameraToggle={handleSettingsCameraToggle}
        />
        <SettingList selectedCameraId={selectedSettingsCameraId} />
      </div>

      <footer className="app-footer">
        <span>Система контроля качества</span>
        <time dateTime={currentDate.toISOString()}>{formatFooterDateTime(currentDate)}</time>
      </footer>
    </main>
  );
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
