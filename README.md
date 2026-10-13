# Claudio — asistente personal de voz

Asistente de voz con IA (Claude) inspirado en J.A.R.V.I.S., el asistente de Tony Stark.
El nombre es configurable desde Protocolos.
Un mismo código, dos formas de usarlo:

- **App Android (APK)** — usa el reconocimiento y la voz nativos de Android.
- **Web / PWA** — para la PC o cualquier navegador (Chrome o Edge).

## Descargar la app
Cada cambio en `main` compila la APK automáticamente (GitHub Actions) y la publica en
**Releases → "Claudio — última versión"**. Descargá `Claudio.apk` desde el teléfono,
permití "instalar apps de origen desconocido" e instalala.

## Configuración inicial
⚙ Protocolos → pegá tu clave de API de [console.anthropic.com](https://console.anthropic.com/settings/keys) → Guardar.
Ahí también elegís voz, velocidad, tono, modelo y tu contexto personal.

## Activación por voz ("Claudio")
Solo en la app Android. Escucha el nombre del asistente sin conexión con [Vosk](https://alphacephei.com/vosk/)
(open source, modelo pequeño en español). No requiere cuentas ni claves.
1. ⚙ Protocolos → Activación por voz → activar → Guardar (pide permiso de micrófono y notificaciones).
2. Tocá **"Permitir abrir sobre otras apps"** para que se abra sola desde cualquier pantalla.
3. Ajustá la **sensibilidad** si no te escucha (subir) o si se activa sola (bajar).

Mientras está atento, Android muestra una notificación fija. Al detectar el nombre suena un tono y empieza a escucharte.
El modelo se descarga en la compilación (GitHub Actions); no está versionado en el repo.

## Estructura
| Carpeta | Qué es |
|---|---|
| `www/` | La app (HTML/CSS/JS). Es lo que se publica en Pages y lo que va dentro de la APK. |
| `android/` | Proyecto Android generado por Capacitor 8. |
| `assets/` | Ícono y splash fuente (regenerar con `npm run assets`). |
| `.github/workflows/` | Compilación de la APK y publicación web. |

## Desarrollo local (opcional)
```bash
npm install
npx cap sync android
npx cap open android   # abre Android Studio
```

## Seguridad
La clave de API se guarda solo en el dispositivo. Es una app de uso personal.
Para distribuirla a terceros, mover la llamada a la API a un backend (ej. Cloudflare Worker).
