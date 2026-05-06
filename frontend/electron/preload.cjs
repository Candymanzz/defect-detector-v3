const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("defectDetectorDesktop", {
  getConfig: () => ipcRenderer.invoke("desktop-config:get"),
  setConfig: (config) => ipcRenderer.invoke("desktop-config:set", config),
  writeLog: (entry) => ipcRenderer.invoke("desktop-log:write", entry),
});
