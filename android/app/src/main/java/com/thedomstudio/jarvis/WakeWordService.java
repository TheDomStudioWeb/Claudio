package com.thedomstudio.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;

/** Servicio en primer plano que escucha la palabra de activación sin conexión. */
public class WakeWordService extends Service {

    static final String ACTION_START = "com.thedomstudio.jarvis.WAKE_START";
    private static final String CHANNEL = "wakeword";
    private static final String CHANNEL_ALERT = "wakeword_alert";
    private static final int NOTIF_ID = 7001;
    private static final int NOTIF_WAKE_ID = 7002;

    static volatile WakeWordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PorcupineManager manager;
    private boolean paused = false;
    private String accessKey;
    private String keywordPath;
    private float sensitivity = 0.6f;
    private String name = "Claudio";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            accessKey = intent.getStringExtra("accessKey");
            keywordPath = intent.getStringExtra("keywordPath");
            sensitivity = intent.getFloatExtra("sensitivity", 0.6f);
            String nm = intent.getStringExtra("name");
            if (nm != null && !nm.isEmpty()) name = nm;
            NotificationManager nmgr = getSystemService(NotificationManager.class);
            if (nmgr != null) nmgr.notify(NOTIF_ID, buildNotification());
            paused = false;
            init();
        }
        return START_NOT_STICKY;
    }

    /** Recarga el modelo (por ejemplo, tras cargar un .ppn nuevo). */
    void reload() {
        main.post(this::init);
    }

    private void init() {
        release();
        if (accessKey == null || keywordPath == null) return;
        try {
            manager = new PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordPath)
                .setModelPath("porcupine_params_es.pv")
                .setSensitivity(sensitivity)
                .setErrorCallback(e -> WakeWordPlugin.emitError(e.getMessage()))
                .build(getApplicationContext(), index -> main.post(this::onWake));
            if (!paused) manager.start();
        } catch (PorcupineException e) {
            WakeWordPlugin.emitError(friendly(e));
            manager = null;
            stopSelf();
        }
    }

    private String friendly(PorcupineException e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("ActivationLimit")) return "La AccessKey llegó a su límite de dispositivos";
        if (name.contains("ActivationRefused")) return "Picovoice rechazó la AccessKey";
        if (name.contains("ActivationThrottled")) return "AccessKey limitada temporalmente; probá más tarde";
        if (name.contains("Activation")) return "No se pudo validar la AccessKey (¿hay internet?)";
        if (name.contains("InvalidArgument")) return "El archivo .ppn no es válido para Android/Español o para esta versión";
        return "Error al iniciar: " + e.getMessage();
    }

    private void onWake() {
        if (paused) return;
        pauseDetection();
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 160);
            main.postDelayed(tg::release, 400);
        } catch (RuntimeException ignored) {}

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        boolean canOpen = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (canOpen) {
            try { startActivity(open); } catch (Exception ignored) {}
        } else {
            // Sin permiso para abrirse sola: aviso tocable.
            PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(name + " lo escuchó")
                .setContentText("Toque para hablar")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_WAKE_ID, n);
            // Si nadie atiende, vuelve a escuchar en unos segundos.
            main.postDelayed(() -> { if (WakeWordPlugin.pendingWake) { WakeWordPlugin.pendingWake = false; resumeDetection(); } }, 8000);
        }
        WakeWordPlugin.emitWake();
    }

    void pauseDetection() {
        main.post(() -> {
            paused = true;
            if (manager != null) {
                try { manager.stop(); } catch (PorcupineException ignored) {}
            }
        });
    }

    void resumeDetection() {
        main.post(() -> {
            paused = false;
            if (manager != null) {
                try { manager.start(); } catch (PorcupineException e) { WakeWordPlugin.emitError(friendly(e)); }
            }
        });
    }

    private void release() {
        if (manager != null) {
            try { manager.stop(); } catch (PorcupineException ignored) {}
            manager.delete();
            manager = null;
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel c = new NotificationChannel(CHANNEL, "Escucha de activación", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "Llamadas por voz", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(name + " está atento")
            .setContentText("Diga su nombre para hablarle")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
