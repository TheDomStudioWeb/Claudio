// Puente mínimo y seguro entre la app y Windows.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('claudioDesktop', {
  setMode: mode => ipcRenderer.send('desk:set-mode', mode),
  hide: () => ipcRenderer.send('desk:hide'),
  onMode: cb => ipcRenderer.on('desk:mode', (_e, mode) => cb(mode)),
  onHotkey: cb => ipcRenderer.on('desk:hotkey', () => cb())
});
