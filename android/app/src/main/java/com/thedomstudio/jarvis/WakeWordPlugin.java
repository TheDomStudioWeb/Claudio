package com.thedomstudio.jarvis;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;


/** Puente JS ↔ servicio de palabra de activación (Vosk). */
@CapacitorPlugin(
    name = "WakeWord",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone") }
)
public class WakeWordPlugin extends Plugin {

    static volatile WakeWordPlugin instance;
    static volatile boolean pendingWake = false;

    @Override
    public void load() {
        instance = this;
    }

    static void emitWake() {
        WakeWordPlugin p = instance;
        if (p != null && p.hasListeners("wake")) {
            pendingWake = false;
            p.notifyListeners("wake", new JSObject());
        } else {
            pendingWake = true;
        }
    }

    static void emitError(String message) {
        WakeWordPlugin p = instance;
        if (p != null) {
            JSObject o = new JSObject();
            o.put("message", message);
            p.notifyListeners("error", o);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermCallback");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void micPermCallback(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) doStart(call);
        else call.reject("Sin permiso de micrófono");
    }

    private void doStart(PluginCall call) {
        Float sens = call.getFloat("sensitivity", 0.6f);
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4711);
        }
        Intent i = new Intent(getContext(), WakeWordService.class);
        i.setAction(WakeWordService.ACTION_START);
        i.putExtra("sensitivity", sens == null ? 0.6f : sens);
        i.putExtra("name", call.getString("name", "Claudio"));
        try {
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo iniciar el servicio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), WakeWordService.class));
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        WakeWordService s = WakeWordService.instance;
        if (s != null) s.pauseDetection();
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        WakeWordService s = WakeWordService.instance;
        if (s != null) s.resumeDetection();
        call.resolve();
    }

    /** Tono corto que indica "ya puede hablar". */
    @PluginMethod
    public void beep(PluginCall call) {
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tg::release, 300);
        } catch (RuntimeException ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", WakeWordService.instance != null);
        o.put("canOverlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(o);
    }

    @PluginMethod
    public void consumeWake(PluginCall call) {
        JSObject o = new JSObject();
        o.put("wake", pendingWake);
        pendingWake = false;
        call.resolve(o);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /* ---------- Reconocimiento de voz propio (con reintentos y eventos de error) ---------- */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private int sttSession = 0;

    private void emitStt(String type, String text, int code) {
        JSObject o = new JSObject();
        o.put("type", type);
        if (text != null) o.put("text", text);
        if (code != 0) o.put("code", code);
        notifyListeners("stt", o);
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("Sin permiso de micrófono");
            return;
        }
        final String lang = call.getString("language", "es-AR");
        final boolean beep = Boolean.TRUE.equals(call.getBoolean("beep", true));
        final int session = ++sttSession;
        ui.post(() -> startRecognizer(lang, beep, session, 0));
        call.resolve();
    }

    private void destroyRecognizer() {
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) {}
            try { sr.destroy(); } catch (Exception ignored) {}
            sr = null;
        }
    }

    private void startRecognizer(final String lang, final boolean beep, final int session, final int attempt) {
        if (session != sttSession) return;
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            emitStt("error", "El reconocimiento de voz no está disponible en este teléfono", -1);
            return;
        }
        destroyRecognizer();
        sr = SpeechRecognizer.createSpeechRecognizer(getContext());
        final long startedAt = SystemClock.elapsedRealtime();
        final boolean[] spoke = { false };
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (session != sttSession) return;
                if (beep && attempt == 0) playBeep();
                emitStt("ready", null, 0);
            }
            @Override public void onBeginningOfSpeech() { spoke[0] = true; if (session == sttSession) emitStt("begin", null, 0); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { if (session == sttSession) emitStt("end", null, 0); }
            @Override public void onEvent(int eventType, Bundle params) {}
            @Override public void onPartialResults(Bundle b) {
                if (session != sttSession) return;
                ArrayList<String> m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty() && !m.get(0).isEmpty()) emitStt("partial", m.get(0), 0);
            }
            @Override public void onResults(Bundle b) {
                if (session != sttSession) return;
                ArrayList<String> m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                emitStt("final", m != null && !m.isEmpty() ? m.get(0) : "", 0);
            }
            @Override public void onError(int error) {
                if (session != sttSession) return;
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                boolean transient_ = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_AUDIO
                    || ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && elapsed < 1500);
                if (transient_ && !spoke[0] && attempt < 3) {
                    // Falla típica justo después de que habló el sintetizador: reintentar.
                    ui.postDelayed(() -> startRecognizer(lang, beep, session, attempt + 1), 350L * (attempt + 1));
                    return;
                }
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                emitStt(silence ? "silence" : "error", silence ? null : "código " + error, error);
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
        try {
            sr.startListening(intent);
        } catch (Exception e) {
            emitStt("error", e.getMessage(), -2);
        }
    }

    @PluginMethod
    public void stopListen(PluginCall call) {
        ui.post(() -> { if (sr != null) try { sr.stopListening(); } catch (Exception ignored) {} });
        call.resolve();
    }

    @PluginMethod
    public void cancelListen(PluginCall call) {
        sttSession++;
        ui.post(this::destroyRecognizer);
        call.resolve();
    }

    private void playBeep() {
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
            ui.postDelayed(tg::release, 300);
        } catch (RuntimeException ignored) {}
    }

    @Override
    protected void handleOnDestroy() {
        sttSession++;
        destroyRecognizer();
        super.handleOnDestroy();
    }
}
