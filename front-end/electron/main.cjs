const { app, BrowserWindow, crashReporter, ipcMain } = require("electron");
const fs = require("node:fs");
const path = require("node:path");

const rendererUrl = process.env.ELECTRON_RENDERER_URL;
const isDev = Boolean(rendererUrl);
const runtimeLogPath = path.resolve(__dirname, "..", "..", "logs", "electron-runtime.log");

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
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.on("unresponsive", () => writeRuntimeLog("window-unresponsive"));
  mainWindow.on("responsive", () => writeRuntimeLog("window-responsive"));
  mainWindow.on("closed", () => writeRuntimeLog("window-closed"));
  mainWindow.webContents.on("render-process-gone", (_event, details) => {
    writeRuntimeLog("render-process-gone", details);
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

app.whenReady().then(() => {
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

app.on("child-process-gone", (_event, details) => {
  writeRuntimeLog("child-process-gone", details);
});
app.on("before-quit", (_event, exitCode) => {
  writeRuntimeLog("before-quit", { exitCode });
});
app.on("will-quit", (_event, exitCode) => {
  writeRuntimeLog("will-quit", { exitCode });
});
