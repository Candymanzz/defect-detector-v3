const { app, BrowserWindow, ipcMain } = require("electron");
const path = require("node:path");

const rendererUrl = process.env.ELECTRON_RENDERER_URL;
const isDev = Boolean(rendererUrl);

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
