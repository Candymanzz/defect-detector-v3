const { app, BrowserWindow, crashReporter, ipcMain } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

const rendererUrl = process.env.ELECTRON_RENDERER_URL;
const isDev = Boolean(rendererUrl);
const isKiosk = process.env.IML_KIOSK === "1";
const runtimeLogPath = process.env.IML_ELECTRON_LOG_PATH || path.resolve(__dirname, "..", "..", "logs", "electron-runtime.log");
const rendererRecoveryWindowMs = 60_000;
const rendererRecoveryLimit = 3;
const rendererRecoveryTimes = [];

function writeRuntimeLog(event, details = {}) {
  try {
    fs.mkdirSync(path.dirname(runtimeLogPath), { recursive: true });
    fs.appendFileSync(
      runtimeLogPath,
      `${JSON.stringify({ ts: new Date().toISOString(), event, pid: process.pid, ...details })}\n`,
      "utf8",
    );
  } catch {
    // Diagnostics must never make the UI fail.
  }
}

crashReporter.start({ uploadToServer: false });
process.on("uncaughtExceptionMonitor", (error, origin) => {
  writeRuntimeLog("uncaught-exception", { origin, error: error?.stack ?? String(error) });
});
process.on("unhandledRejection", (reason) => {
  writeRuntimeLog("unhandled-rejection", { reason: reason?.stack ?? String(reason) });
});

function createMainWindow() {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    title: "Defect Detector",
    backgroundColor: "#101317",
    kiosk: isKiosk,
    frame: !isKiosk,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      devTools: !isKiosk,
    },
  });

  if (isKiosk) {
    mainWindow.setMenu(null);
    mainWindow.on("close", (event) => {
      if (!app.isQuitting) event.preventDefault();
    });
    mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    mainWindow.webContents.on("will-navigate", (event) => event.preventDefault());
    mainWindow.webContents.on("before-input-event", (event, input) => {
      const key = input.key.toLowerCase();
      if (["f11", "f12"].includes(key) ||
          (input.alt && key === "f4") ||
          ((input.control || input.meta) && ["q", "w", "r", "l", "n", "t"].includes(key)) ||
          (input.control && input.shift && ["i", "j", "c"].includes(key))) {
        event.preventDefault();
      }
    });
  }

  mainWindow.on("unresponsive", () => writeRuntimeLog("window-unresponsive"));
  mainWindow.on("responsive", () => writeRuntimeLog("window-responsive"));
  mainWindow.on("closed", () => writeRuntimeLog("window-closed"));
  mainWindow.webContents.on("render-process-gone", (_event, details) => {
    writeRuntimeLog("render-process-gone", details);
    if (details?.reason === "clean-exit" || app.isQuitting) {
      return;
    }

    const now = Date.now();
    while (rendererRecoveryTimes.length > 0 && now - rendererRecoveryTimes[0] > rendererRecoveryWindowMs) {
      rendererRecoveryTimes.shift();
    }
    if (rendererRecoveryTimes.length >= rendererRecoveryLimit) {
      writeRuntimeLog("renderer-recovery-skipped", { reason: "rate-limit", attempts: rendererRecoveryTimes.length });
      return;
    }
    rendererRecoveryTimes.push(now);

    // A renderer OOM leaves an otherwise live BrowserWindow black. Drop Chromium's
    // decoded-resource cache and reload the UI in the same window.
    setTimeout(async () => {
      if (mainWindow.isDestroyed() || mainWindow.webContents.isDestroyed()) {
        return;
      }
      try {
        if (details?.reason === "oom") {
          await mainWindow.webContents.session.clearCache();
        }
        writeRuntimeLog("renderer-recovery", { reason: details?.reason ?? "unknown" });
        mainWindow.webContents.reloadIgnoringCache();
      } catch (error) {
        writeRuntimeLog("renderer-recovery-failed", { error: error?.stack ?? String(error) });
      }
    }, 500);
  });

  if (isDev) {
    mainWindow.loadURL(rendererUrl);
    return;
  }

  mainWindow.loadFile(path.join(__dirname, "..", "dist", "index.html"));
}

ipcMain.handle("app:getEnvironment", () => ({
  mode: isDev ? "development" : "production",
  platform: process.platform,
  versions: {
    chrome: process.versions.chrome,
    electron: process.versions.electron,
    node: process.versions.node,
  },
}));
app.disableHardwareAcceleration();
app.commandLine.appendSwitch("disable-gpu");
app.commandLine.appendSwitch("disable-gpu-compositing");
app.whenReady().then(() => {
// }));
//fix for electron
// app.whenReady().then(() => {
  if (process.platform === "win32") {
    app.setAppUserModelId("com.defect-detector.front-end");
  }

  createMainWindow();
  writeRuntimeLog("app-ready", { versions: process.versions });

  const memoryTimer = setInterval(async () => {
    try {
      const main = await process.getProcessMemoryInfo();
      const children = app.getAppMetrics().map((metric) => ({
        pid: metric.pid,
        type: metric.type,
        memory: metric.memory,
      }));
      writeRuntimeLog("memory", { main, children });
    } catch (error) {
      writeRuntimeLog("memory-error", { error: error?.stack ?? String(error) });
    }
  }, 60_000);
  memoryTimer.unref();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
// fix for electron
// app.on("child-process-gone", (_event, details) => {
//   writeRuntimeLog("child-process-gone", details);
// });

app.on("child-process-gone", (_event, details) => {
  writeRuntimeLog("child-process-gone", details);
  if (details?.type === "GPU") {
    for (const win of BrowserWindow.getAllWindows()) {
      win.webContents.reloadIgnoringCache();
    }
  }
});
app.on("before-quit", (_event, exitCode) => {
  app.isQuitting = true;
  writeRuntimeLog("before-quit", { exitCode });
});
app.on("will-quit", (_event, exitCode) => {
  writeRuntimeLog("will-quit", { exitCode });
});
