package com.thedomstudio.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Servicio en primer plano que escucha el nombre del asistente sin conexión (Vosk, español). */
public class WakeWordService extends Service implements RecognitionListener {

    static final String ACTION_START = "com.thedomstudio.jarvis.WAKE_START";
    private static final String CHANNEL = "wakeword";
    private static final String CHANNEL_ALERT = "wakeword_alert";
    private static final int NOTIF_ID = 7001;
    private static final int NOTIF_WAKE_ID = 7002;
    private static final String MODEL_ASSET = "vosk-es";
    private static final float SAMPLE_RATE = 16000f;

    static volatile WakeWordService instance;
    /** Modo reunión: mientras sea verdadero, el micrófono queda libre y Claudio no escucha su nombre. */
    static volatile boolean reunion = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private boolean paused = false;
    private String name = "Claudio";
    private String key = "claudio";
    private float minConf = 0.6f;
    private float sens = 0.6f;
    private static final long AUTO_RESUME_MS = 60000L;
    private final Runnable autoResume = () -> resumeDetection();

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("claudio_wake", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sp = prefs(this);
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            sp.edit().putString("name", intent.getStringExtra("name"))
                .putFloat("sens", intent.getFloatExtra("sensitivity", 0.6f))
                .putBoolean("enabled", true).apply();
        }
        {
            String nm = sp.getString("name", "Claudio");
            if (nm != null && !nm.trim().isEmpty()) name = nm.trim();
            String[] parts = name.toLowerCase(new Locale("es")).split("\\s+");
            key = parts[parts.length - 1];
            sens = sp.getFloat("sens", 0.6f);
            // Sensibilidad alta = acepta coincidencias con menos confianza.
            minConf = Math.max(0.2f, Math.min(0.95f, 1.2f - sens));
            paused = false;
        }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android no permite abrir el micrófono desde segundo plano: se reactivará al abrir la app.
            stopSelf();
            return START_NOT_STICKY;
        }
        init();
        return START_STICKY;
    }

    void reload() {
        init();
    }

    private void init() {
        final String grammar = new JSONArray().put(key).put("[unk]").toString();
        io.execute(() -> {
            try {
                if (model == null) model = new Model(ensureModel());
                Recognizer r = new Recognizer(model, SAMPLE_RATE, grammar);
                r.setWords(true);
                main.post(() -> {
                    stopListening();
                    if (recognizer != null) recognizer.close();
                    recognizer = r;
                    if (!paused) startListening();
                });
            } catch (Exception e) {
                WakeWordPlugin.emitError("No se pudo cargar el modelo de voz: " + e.getMessage());
                main.post(this::stopSelf);
            }
        });
    }

    /** Copia el modelo desde la APK al almacenamiento interno (solo la primera vez o tras actualizar). */
    private String ensureModel() throws Exception {
        File dir = new File(getFilesDir(), MODEL_ASSET);
        long stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        File ok = new File(dir, ".ok-" + stamp);
        if (!ok.exists()) {
            deleteRecursive(dir);
            copyAssets(MODEL_ASSET, dir);
            if (!ok.createNewFile()) throw new IOException("no se pudo marcar el modelo");
        }
        return dir.getAbsolutePath();
    }

    private void copyAssets(String path, File out) throws IOException {
        String[] list = getAssets().list(path);
        if (list == null || list.length == 0) {
            try (InputStream in = getAssets().open(path); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (FileNotFoundException emptyDir) {
                out.mkdirs();
            }
            return;
        }
        out.mkdirs();
        for (String child : list) copyAssets(path + "/" + child, new File(out, child));
    }

    private void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void startListening() {
        if (reunion || recognizer == null || speech != null) return;
        try {
            recognizer.reset();
            speech = new SpeechService(recognizer, SAMPLE_RATE);
            speech.startListening(this);
        } catch (IOException e) {
            speech = null;
            WakeWordPlugin.emitError("No pude abrir el micrófono: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
    }

    void pauseDetection() {
        main.post(() -> {
            paused = true;
            stopListening();
            // Red de seguridad: si nadie la reactiva, vuelve a escuchar sola.
            main.removeCallbacks(autoResume);
            main.postDelayed(autoResume, AUTO_RESUME_MS);
            updateNotification();
        });
    }

    void resumeDetection() {
        main.post(() -> {
            if (reunion) return;
            main.removeCallbacks(autoResume);
            paused = false;
            startListening();
            updateNotification();
        });
    }

    /** Reanuda tras una pausa breve (por ejemplo, cuando el usuario sale de la app). */
    void resumeSoon(long delayMs) {
        main.post(() -> {
            if (reunion) return;
            main.removeCallbacks(autoResume);
            main.postDelayed(autoResume, delayMs);
        });
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private void check(String hypothesis) {
        if (paused || hypothesis == null) return;
        try {
            JSONArray res = new JSONObject(hypothesis).optJSONArray("result");
            if (res == null) return;
            for (int i = 0; i < res.length(); i++) {
                JSONObject w = res.getJSONObject(i);
                if (key.equals(w.optString("word")) && w.optDouble("conf", 0) >= minConf) {
                    onWake();
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    @Override public void onPartialResult(String hypothesis) {}
    @Override public void onResult(String hypothesis) { check(hypothesis); }
    @Override public void onFinalResult(String hypothesis) { check(hypothesis); }

    @Override
    public void onError(Exception e) {
        stopListening();
        main.postDelayed(() -> { if (!paused) startListening(); }, 2000);
    }

    @Override
    public void onTimeout() {
        stopListening();
        if (!paused) startListening();
    }

    private void onWake() {
        if (paused) return;
        paused = true;
        stopListening();
        main.removeCallbacks(autoResume);
        main.postDelayed(autoResume, AUTO_RESUME_MS);
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
            main.postDelayed(() -> {
                if (WakeWordPlugin.pendingWake) { WakeWordPlugin.pendingWake = false; resumeDetection(); }
            }, 8000);
        }
        WakeWordPlugin.emitWake();
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
            .setContentTitle(reunion ? name + " en modo reunión" : paused ? name + " está conversando" : name + " está atento")
            .setContentText(reunion ? "No escucha hasta que termine la grabación" : paused ? "Vuelve a escuchar su nombre al terminar" : "Diga \"" + name + "\" para hablarle")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopListening();
        io.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
