const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronAPI", {
  getEnvironment: () => ipcRenderer.invoke("app:getEnvironment"),
});
