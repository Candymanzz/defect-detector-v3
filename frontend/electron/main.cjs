const { app, BrowserWindow, ipcMain } = require("electron");
const fs = require("fs");
const path = require("path");

function getDefaultConfig() {
  return {
    apiBaseUrl: app.isPackaged ? "" : "http://localhost:8000",
  };
}

function getConfigPath() {
  return path.join(app.getPath("userData"), "config.json");
}

function getLogPath(date = new Date()) {
  const fileDate = date.toISOString().slice(0, 10);
  return path.join(app.getPath("userData"), "logs", `desktop-${fileDate}.jsonl`);
}

function writeDesktopLog(entry) {
  const now = new Date();
  const logPath = getLogPath(now);
  const { level = "info", event = "desktop.log", ...fields } = entry && typeof entry === "object" ? entry : {};
  const normalized = {
    ts: now.toISOString(),
    level,
    event,
    ...fields,
  };

  try {
    fs.mkdirSync(path.dirname(logPath), { recursive: true });
    fs.appendFileSync(logPath, `${JSON.stringify(normalized)}\n`, "utf8");
  } catch (error) {
    console.error("Could not write desktop log:", error);
  }

  return { logPath };
}

function normalizeConfig(config) {
  const defaultConfig = getDefaultConfig();
  const apiBaseUrl =
    typeof config?.apiBaseUrl === "string" ? config.apiBaseUrl.trim().replace(/\/+$/, "") : defaultConfig.apiBaseUrl;

  return {
    apiBaseUrl,
  };
}

function readConfig() {
  const configPath = getConfigPath();
  const defaultConfig = getDefaultConfig();

  try {
    if (!fs.existsSync(configPath)) {
      fs.mkdirSync(path.dirname(configPath), { recursive: true });
      fs.writeFileSync(configPath, `${JSON.stringify(defaultConfig, null, 2)}\n`, "utf8");
      return { ...defaultConfig, configPath };
    }

    const parsed = JSON.parse(fs.readFileSync(configPath, "utf8"));
    return { ...normalizeConfig(parsed), configPath };
  } catch (error) {
    console.error("Could not read desktop config:", error);
    return { ...defaultConfig, configPath };
  }
}

function writeConfig(nextConfig) {
  const configPath = getConfigPath();
  const currentConfig = readConfig();
  const normalized = normalizeConfig({ ...currentConfig, ...nextConfig });

  fs.mkdirSync(path.dirname(configPath), { recursive: true });
  fs.writeFileSync(configPath, `${JSON.stringify(normalized, null, 2)}\n`, "utf8");
  writeDesktopLog({
    level: "info",
    event: "backend_url.selected",
    apiBaseUrl: normalized.apiBaseUrl,
    configPath,
  });

  return { ...normalized, configPath };
}

ipcMain.handle("desktop-config:get", () => readConfig());
ipcMain.handle("desktop-config:set", (_event, nextConfig) => writeConfig(nextConfig));
ipcMain.handle("desktop-log:write", (_event, entry) => writeDesktopLog(entry));

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.cjs"),
    },
  });

  win.webContents.on("did-fail-load", (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    writeDesktopLog({
      level: "error",
      event: "frontend.load.failed",
      errorCode,
      errorDescription,
      url: validatedURL,
      isMainFrame,
    });
  });

  win.webContents.on("render-process-gone", (_event, details) => {
    writeDesktopLog({
      level: "error",
      event: "frontend.render_process.gone",
      reason: details.reason,
      exitCode: details.exitCode,
    });
  });

  win.on("unresponsive", () => {
    writeDesktopLog({
      level: "error",
      event: "frontend.unresponsive",
    });
  });

  if (!app.isPackaged) {
    win.loadURL("http://localhost:5173");
  } else {
    win.loadFile(path.join(__dirname, "../dist/index.html"));
  }
}

app.whenReady().then(() => {
  const config = readConfig();
  writeDesktopLog({
    level: "info",
    event: "app.started",
    apiBaseUrl: config.apiBaseUrl,
    configPath: config.configPath,
  });
  createWindow();
});
