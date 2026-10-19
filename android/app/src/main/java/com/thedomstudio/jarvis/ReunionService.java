package com.thedomstudio.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Modo reunión: graba el micrófono o el audio del sistema en segundo plano, sin video,
 * y lo guarda en archivos M4A (AAC) de unos 25 minutos cada uno, en Música/Claudio Reuniones.
 * Mientras graba, Claudio no escucha ni habla (la detección del nombre queda bloqueada).
 */
public class ReunionService extends Service {

    static final String ACTION_START = "com.thedomstudio.jarvis.REUNION_START";
    static final String ACTION_STOP = "com.thedomstudio.jarvis.REUNION_STOP";
    private static final String CHANNEL = "reunion";
    private static final int NOTIF_ID = 7101;
    private static final String CARPETA = "Claudio Reuniones";
    private static final int[] RATES = { 16000, 44100 };
    private static final int BITRATE = 48000;

    static volatile ReunionService instance;
    /** Verdadero desde que se pide iniciar hasta que termina de guardarse todo. */
    static volatile boolean activa = false;
    static volatile int nivel = 0;        // 0..100, para mostrar que está entrando audio
    static volatile long inicioMs = 0;
    static volatile int parteActual = 0;
    static volatile String fuenteActual = "mic";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopRequested = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private MediaProjection projection;

    private String fuente = "mic";
    private long chunkUs = 25L * 60L * 1_000_000L;
    private String baseName;

    // Estado de la grabación (solo lo toca el hilo de trabajo)
    private int rate = 16000;
    private AudioRecord rec;
    private MediaCodec codec;
    private MediaFormat outFormat;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private int track = -1;
    private Uri parteUri;
    private File parteFile;
    private String parteNombre;
    private int parteNum = 0;
    private long parteInicioUs = 0;
    private long parteUltimoUs = 0;
    private boolean parteConDatos = false;
    private long samples = 0;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
    private final JSONArray partes = new JSONArray();

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("claudio_reunion", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || worker != null) return START_NOT_STICKY;

        fuente = "sistema".equals(intent.getStringExtra("fuente")) ? "sistema" : "mic";
        int min = intent.getIntExtra("chunkMin", 25);
        chunkUs = Math.max(1, Math.min(120, min)) * 60L * 1_000_000L;
        baseName = "Reunion_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());

        activa = true;
        stopRequested = false;
        inicioMs = System.currentTimeMillis();
        parteActual = 0;
        nivel = 0;
        fuenteActual = fuente;
        prefs(this).edit().putString("ultima", "[]").apply();

        // Claudio deja de escuchar su nombre mientras dura la reunión.
        WakeWordService.reunion = true;
        WakeWordService ws = WakeWordService.instance;
        if (ws != null) ws.pauseDetection();

        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = "sistema".equals(fuente)
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIF_ID, n, type);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            fallo("Android no permitió iniciar la grabación: " + e.getMessage());
            return START_NOT_STICKY;
        }

        if ("sistema".equals(fuente)) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            if (data == null) {
                fallo("Falta el permiso para capturar el audio del sistema.");
                return START_NOT_STICKY;
            }
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(resultCode, data);
                if (projection == null) throw new IllegalStateException("sin proyección");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { requestStop(); }
                }, main);
            } catch (Exception e) {
                fallo("No pude capturar el audio del sistema: " + e.getMessage());
                return START_NOT_STICKY;
            }
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudio:reunion");
            wakeLock.acquire(8L * 60L * 60L * 1000L);
        }

        worker = new Thread(this::grabar, "claudio-reunion");
        worker.start();
        return START_NOT_STICKY;
    }

    void requestStop() {
        stopRequested = true;
        // Si el hilo todavía no arrancó, cerrar el servicio directamente.
        if (worker == null) terminar();
    }

    /* ------------------------------------------------------------------ */
    /*  Grabación                                                         */
    /* ------------------------------------------------------------------ */

    private void grabar() {
        String error = null;
        try {
            // Deja que la escucha del nombre suelte el micrófono antes de abrirlo.
            if ("mic".equals(fuente)) Thread.sleep(700);
            abrirEntrada();
            abrirCodec();
            rec.startRecording();

            byte[] buf = new byte[4096];
            long ultimoAviso = 0;
            boolean finSecuencia = false;
            while (!finSecuencia) {
                boolean stop = stopRequested;
                int n = 0;
                if (!stop) {
                    n = rec.read(buf, 0, buf.length);
                    if (n < 0) throw new IOException("Falló la lectura del audio (" + n + ").");
                    medirNivel(buf, n);
                }
                alimentar(buf, n, stop);
                finSecuencia = drenar();
                if (stop) break;
                long ahora = System.currentTimeMillis();
                if (ahora - ultimoAviso > 30000) { ultimoAviso = ahora; main.post(this::actualizarNotificacion); }
            }
            // Vaciar lo que quedó dentro del codificador (ya se envió el fin de secuencia).
            long limite = System.currentTimeMillis() + 5000;
            while (!finSecuencia && System.currentTimeMillis() < limite) {
                finSecuencia = drenar();
                if (!finSecuencia) Thread.sleep(20);
            }
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            liberarEntrada();
            try { cerrarParte(); } catch (Exception ignored) {}
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
        final String err = error;
        main.post(() -> {
            if (err != null) ReunionPlugin.emitir("error", err, 0, null);
            terminar();
        });
    }

    private void abrirEntrada() throws Exception {
        Exception ultimo = null;
        for (int r : RATES) {
            try {
                AudioRecord ar = crearAudioRecord(r);
                if (ar != null && ar.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = ar;
                    rate = r;
                    return;
                }
                if (ar != null) ar.release();
            } catch (Exception e) {
                ultimo = e;
            }
        }
        if ("sistema".equals(fuente)) {
            throw new IOException("No pude abrir la captura del audio del sistema"
                + (ultimo != null ? ": " + ultimo.getMessage() : "") + ".");
        }
        throw new IOException("No pude abrir el micrófono" + (ultimo != null ? ": " + ultimo.getMessage() : "") + ".");
    }

    private AudioRecord crearAudioRecord(int r) {
        int min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(Math.max(min, 4096) * 4, 65536);
        if ("sistema".equals(fuente)) {
            if (Build.VERSION.SDK_INT < 29 || projection == null) {
                throw new IllegalStateException("el audio del sistema requiere Android 10 o superior");
            }
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            return new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(r)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        }
        return new AudioRecord(MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
    }

    private void abrirCodec() throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
    }

    private void liberarEntrada() {
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
            rec = null;
        }
    }

    private void medirNivel(byte[] b, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            if (v < 0) v = -v;
            if (v > peak) peak = v;
        }
        nivel = Math.min(100, (int) (peak * 100L / 12000L));
    }

    /** Entrega audio al codificador; con eos=true cierra la secuencia. */
    private void alimentar(byte[] buf, int n, boolean eos) throws IOException {
        int off = 0;
        while (off < n || eos) {
            int idx = codec.dequeueInputBuffer(10_000);
            if (idx < 0) { drenar(); continue; }
            ByteBuffer ib = codec.getInputBuffer(idx);
            ib.clear();
            int len = Math.min(n - off, ib.remaining());
            if (len > 0) ib.put(buf, off, len);
            long pts = samples * 1_000_000L / rate;
            off += len;
            samples += len / 2;
            boolean last = eos && off >= n;
            codec.queueInputBuffer(idx, 0, len, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (last) return;
            drenar();
        }
    }

    /** Saca lo codificado y lo escribe en el archivo. Devuelve true al llegar al final de la secuencia. */
    private boolean drenar() throws IOException {
        while (true) {
            int o = codec.dequeueOutputBuffer(info, 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = codec.getOutputFormat();
                if (muxer == null) abrirParte(0);
                continue;
            }
            if (o < 0) continue;
            ByteBuffer ob = codec.getOutputBuffer(o);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && ob != null && outFormat != null) {
                if (muxer != null && info.presentationTimeUs - parteInicioUs >= chunkUs) {
                    cerrarParte();
                    abrirParte(info.presentationTimeUs);
                }
                if (muxer == null) abrirParte(info.presentationTimeUs);
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                long rel = Math.max(0, info.presentationTimeUs - parteInicioUs);
                outInfo.set(0, info.size, rel, info.flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                muxer.writeSampleData(track, ob, outInfo);
                parteConDatos = true;
                parteUltimoUs = info.presentationTimeUs;
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(o, false);
            if (eos) return true;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Archivos                                                          */
    /* ------------------------------------------------------------------ */

    private void abrirParte(long inicioUs) throws IOException {
        parteNum++;
        parteActual = parteNum;
        parteInicioUs = inicioUs;
        parteUltimoUs = inicioUs;
        parteConDatos = false;
        parteNombre = baseName + "_parte" + String.format(Locale.US, "%02d", parteNum) + ".m4a";
        parteUri = null;
        parteFile = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, parteNombre);
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + CARPETA);
            parteUri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (parteUri == null) throw new IOException("No pude crear el archivo en Música.");
            pfd = getContentResolver().openFileDescriptor(parteUri, "rw");
            if (pfd == null) throw new IOException("No pude abrir el archivo de la reunión.");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), CARPETA);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            parteFile = new File(dir, parteNombre);
            muxer = new MediaMuxer(parteFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        track = muxer.addTrack(outFormat);
        muxer.start();
    }

    private void cerrarParte() throws IOException {
        if (muxer == null) return;
        boolean ok = false;
        try {
            if (parteConDatos) { muxer.stop(); ok = true; }
        } catch (Exception ignored) {
        } finally {
            try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
            if (pfd != null) { try { pfd.close(); } catch (IOException ignored) {} pfd = null; }
        }
        if (!ok) {
            // Parte vacía: se borra para no dejar archivos rotos.
            if (parteUri != null) { try { getContentResolver().delete(parteUri, null, null); } catch (Exception ignored) {} }
            if (parteFile != null) { //noinspection ResultOfMethodCallIgnored
                parteFile.delete(); }
            parteNum--;
            return;
        }
        int seg = (int) Math.max(1, (parteUltimoUs - parteInicioUs) / 1_000_000L);
        String uri = parteUri != null ? parteUri.toString() : Uri.fromFile(parteFile).toString();
        try {
            partes.put(new JSONObject().put("nombre", parteNombre).put("uri", uri).put("seg", seg));
            prefs(this).edit().putString("ultima", partes.toString()).apply();
        } catch (Exception ignored) {}
        final String nombre = parteNombre;
        final int num = parteNum;
        main.post(() -> ReunionPlugin.emitir("parte", nombre, num, uri));
    }

    /* ------------------------------------------------------------------ */
    /*  Cierre                                                            */
    /* ------------------------------------------------------------------ */

    private void fallo(String mensaje) {
        ReunionPlugin.emitir("error", mensaje, 0, null);
        terminar();
    }

    private boolean cerrado = false;

    private void terminar() {
        if (cerrado) return;
        cerrado = true;
        activa = false;
        nivel = 0;
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        wakeLock = null;
        worker = null;
        // Claudio vuelve a escuchar su nombre.
        WakeWordService.reunion = false;
        WakeWordService ws = WakeWordService.instance;
        if (ws != null) ws.resumeDetection();
        ReunionPlugin.emitir("fin", partes.length() + " parte(s)", partes.length(), null);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        main.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Notificación                                                      */
    /* ------------------------------------------------------------------ */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel c = new NotificationChannel(CHANNEL, "Grabación de reuniones", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    private void actualizarNotificacion() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && activa) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ReunionService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 11, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Grabando reunión")
            .setContentText(("sistema".equals(fuente) ? "Audio del sistema" : "Micrófono") + " · Parte " + Math.max(1, parteActual))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(inicioMs > 0 ? inicioMs : System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Terminar", psi)
            .build();
    }
}
