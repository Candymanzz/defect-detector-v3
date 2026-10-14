const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronAPI", {
  logDiagnostic: entry => ipcRenderer.send("app:diagnostic", entry),
  getEnvironment: () => ipcRenderer.invoke("app:getEnvironment"),
});
