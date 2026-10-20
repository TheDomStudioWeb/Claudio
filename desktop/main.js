// Claudio para Windows: círculo flotante siempre visible, panel desplegable,
// ícono junto al reloj, atajo de teclado global y arranque con Windows.
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain, screen, session, shell } = require('electron');
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 48713; // fijo: así se conservan la clave, la memoria y los ajustes entre sesiones
const MINI = 168;
const FULL = { width: 420, height: 720 };
const HOTKEY = 'CommandOrControl+Shift+Space';
const WWW = path.join(__dirname, 'www');
const STATE_FILE = path.join(app.getPath('userData'), 'claudio-desk.json');

let win = null;
let tray = null;
let mode = 'mini';
let state = { x: null, y: null, firstRun: true };

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
}

function loadState() {
  try { state = { ...state, ...JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) }; } catch {}
}
function saveState() {
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(state)); } catch {}
}

/* ---------- Servidor local para la app (micrófono y memoria necesitan un origen fijo) ---------- */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.gz': 'application/gzip', '.wasm': 'application/wasm', '.webmanifest': 'application/manifest+json'
};
function startServer() {
  return new Promise((resolve, reject) => {
    const srv = http.createServer((req, res) => {
      let p = decodeURIComponent((req.url || '/').split('?')[0]);
      if (p === '/') p = '/index.html';
      const file = path.normalize(path.join(WWW, p));
      if (!file.startsWith(WWW)) { res.writeHead(403); return res.end(); }
      fs.readFile(file, (err, data) => {
        if (err) { res.writeHead(404); return res.end(); }
        res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
        res.end(data);
      });
    });
    srv.on('error', reject);
    srv.listen(PORT, '127.0.0.1', () => resolve());
  });
}

/* ---------- Ventana ---------- */
function workArea() {
  return screen.getPrimaryDisplay().workArea;
}
function miniBounds() {
  const wa = workArea();
  let { x, y } = state;
  if (x == null || y == null) { x = wa.x + wa.width - MINI - 24; y = wa.y + wa.height - MINI - 24; }
  x = Math.min(Math.max(x, wa.x), wa.x + wa.width - MINI);
  y = Math.min(Math.max(y, wa.y), wa.y + wa.height - MINI);
  return { x, y, width: MINI, height: MINI };
}
function fullBounds() {
  // El panel se abre anclado a la esquina del círculo y siempre dentro de la pantalla.
  const wa = workArea();
  const m = miniBounds();
  let x = m.x + MINI - FULL.width;
  let y = m.y + MINI - FULL.height;
  x = Math.min(Math.max(x, wa.x), wa.x + wa.width - FULL.width);
  y = Math.min(Math.max(y, wa.y), wa.y + wa.height - FULL.height);
  return { x, y, ...FULL };
}
function setMode(m) {
  mode = m === 'full' ? 'full' : 'mini';
  if (!win) return;
  win.setBounds(mode === 'full' ? fullBounds() : miniBounds());
  win.webContents.send('desk:mode', mode);
  showWindow();
  rebuildTray();
}
function showWindow() {
  if (!win) return;
  win.show();
  win.setAlwaysOnTop(true, 'screen-saver');
}

function createWindow() {
  win = new BrowserWindow({
    ...miniBounds(),
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: false,
    show: false,
    title: 'Claudio',
    icon: path.join(__dirname, 'build', 'tray.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  });
  win.setAlwaysOnTop(true, 'screen-saver');
  win.setVisibleOnAllWorkspaces(true);

  win.on('moved', () => {
    if (mode !== 'mini') return;
    const b = win.getBounds();
    state.x = b.x; state.y = b.y; saveState();
  });
  win.on('close', e => {
    if (!app.isQuitting) { e.preventDefault(); win.hide(); }
  });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('http://127.0.0.1:' + PORT)) { e.preventDefault(); shell.openExternal(url); }
  });
  win.webContents.on('did-finish-load', () => win.webContents.send('desk:mode', mode));
  win.once('ready-to-show', () => showWindow());
  win.loadURL('http://127.0.0.1:' + PORT + '/index.html');
}

/* ---------- Ícono junto al reloj ---------- */
let wakeOn = true;
function rebuildTray() {
  if (!tray) return;
  const startup = app.getLoginItemSettings().openAtLogin;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: mode === 'full' ? 'Reducir a círculo' : 'Abrir panel', click: () => setMode(mode === 'full' ? 'mini' : 'full') },
    { label: 'Escuchar mi nombre', type: 'checkbox', checked: wakeOn, click: () => { if (win) win.webContents.send('desk:wake-toggle'); } },
    { label: 'Hablar (Ctrl + Mayús + Espacio)', click: () => { showWindow(); win.webContents.send('desk:hotkey'); } },
    { label: win && win.isVisible() ? 'Ocultar' : 'Mostrar', click: () => { if (win.isVisible()) win.hide(); else showWindow(); rebuildTray(); } },
    { type: 'separator' },
    { label: 'Iniciar con Windows', type: 'checkbox', checked: startup, click: i => { app.setLoginItemSettings({ openAtLogin: i.checked }); } },
    { type: 'separator' },
    { label: 'Salir de Claudio', click: () => { app.isQuitting = true; app.quit(); } }
  ]));
}
function createTray() {
  tray = new Tray(path.join(__dirname, 'build', 'tray.png'));
  tray.setToolTip('Claudio');
  tray.on('click', () => { if (win.isVisible()) win.hide(); else showWindow(); rebuildTray(); });
  rebuildTray();
}

/* ---------- Arranque ---------- */
app.whenReady().then(async () => {
  loadState();
  if (state.firstRun) {
    app.setLoginItemSettings({ openAtLogin: true });
    state.firstRun = false; saveState();
  }
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(permission === 'media' || permission === 'notifications'));
  session.defaultSession.setPermissionCheckHandler((wc, permission) => permission === 'media');

  try { await startServer(); } catch (e) { console.error('Servidor local:', e); }
  createWindow();
  createTray();

  ipcMain.on('desk:set-mode', (e, m) => setMode(m));
  ipcMain.on('desk:hide', () => { win.hide(); rebuildTray(); });
  ipcMain.on('desk:wake-state', (e, on) => { wakeOn = !!on; rebuildTray(); });

  globalShortcut.register(HOTKEY, () => { showWindow(); win.webContents.send('desk:hotkey'); });
  screen.on('display-metrics-changed', () => setMode(mode));
});

app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', e => e.preventDefault());
