package com.thedomstudio.jarvis;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Cámara de Claudio: dispara el obturador y graba video sin abrir la app de cámara.
 * Funciona con Claudio en primer plano (Android no deja usar la cámara desde segundo plano).
 * Siempre suena el obturador y Android muestra su indicador de cámara en uso.
 * Fotos y videos quedan en la galería, carpeta "Claudio".
 */
@CapacitorPlugin(
    name = "Camara",
    permissions = {
        @Permission(strings = { Manifest.permission.CAMERA }, alias = "camera"),
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic")
    }
)
public class CamaraPlugin extends Plugin {

    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private PluginCall stopCall;
    private String videoNombre;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable autoStop;
    private MediaActionSound sound;

    private void sonido(int que) {
        try {
            if (sound == null) sound = new MediaActionSound();
            sound.play(que);
        } catch (Exception ignored) {}
    }

    private boolean tengoCamara() {
        return getPermissionState("camera") == PermissionState.GRANTED;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("camera", tengoCamara());
        o.put("mic", getPermissionState("mic") == PermissionState.GRANTED);
        o.put("grabando", recording != null);
        call.resolve(o);
    }

    @PluginMethod
    public void pedirPermiso(PluginCall call) {
        requestAllPermissions(call, "permisoListo");
    }

    @PermissionCallback
    private void permisoListo(PluginCall call) {
        status(call);
    }

    /* ---------------- Foto ---------------- */

    @PluginMethod
    public void foto(PluginCall call) {
        if (!tengoCamara()) { call.reject("SIN_PERMISO"); return; }
        if (recording != null) { call.reject("GRABANDO: hay un video en curso; primero detenelo."); return; }
        final boolean frontal = "frontal".equals(call.getString("camara", "trasera"));
        final boolean flash = Boolean.TRUE.equals(call.getBoolean("flash", false));
        final Context ctx = getContext();
        final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(ctx);
        f.addListener(() -> {
            try {
                provider = f.get();
                provider.unbindAll();
                imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flash ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                    .build();
                provider.bindToLifecycle((LifecycleOwner) getActivity(), selector(frontal), imageCapture);
                // Sin vista previa la cámara necesita un instante para ajustar luz y enfoque.
                main.postDelayed(() -> disparar(call), 800);
            } catch (Exception e) {
                call.reject("No se pudo abrir la cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(ctx));
    }

    private CameraSelector selector(boolean frontal) {
        return frontal ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private String nombre(String pref) {
        return pref + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void disparar(PluginCall call) {
        final String nom = nombre("Claudio");
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, nom + ".jpg");
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Claudio");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(
            getContext().getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v).build();
        sonido(MediaActionSound.SHUTTER_CLICK);
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(getContext()), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) {}
                JSObject o = new JSObject();
                o.put("nombre", nom + ".jpg");
                o.put("carpeta", "Pictures/Claudio");
                Uri u = r.getSavedUri();
                if (u != null) o.put("uri", u.toString());
                call.resolve(o);
            }
            @Override public void onError(@NonNull ImageCaptureException e) {
                try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) {}
                call.reject("No se pudo sacar la foto: " + e.getMessage());
            }
        });
    }

    /* ---------------- Video ---------------- */

    @PluginMethod
    public void iniciarVideo(PluginCall call) {
        if (!tengoCamara()) { call.reject("SIN_PERMISO"); return; }
        if (recording != null) { call.reject("YA_GRABANDO"); return; }
        final boolean frontal = "frontal".equals(call.getString("camara", "trasera"));
        final Integer maxSeg = call.getInt("maxSegundos", 600);
        final Context ctx = getContext();
        final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(ctx);
        f.addListener(() -> {
            try {
                provider = f.get();
                provider.unbindAll();
                Recorder rec = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(
                        java.util.Arrays.asList(Quality.FHD, Quality.HD, Quality.SD)))
                    .build();
                videoCapture = VideoCapture.withOutput(rec);
                provider.bindToLifecycle((LifecycleOwner) getActivity(), selector(frontal), videoCapture);
                videoNombre = nombre("Claudio");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, videoNombre + ".mp4");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Claudio");
                MediaStoreOutputOptions out = new MediaStoreOutputOptions.Builder(
                    ctx.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).build();
                androidx.camera.video.PendingRecording pr = videoCapture.getOutput().prepareRecording(ctx, out);
                if (getPermissionState("mic") == PermissionState.GRANTED) pr = pr.withAudioEnabled();
                sonido(MediaActionSound.START_VIDEO_RECORDING);
                recording = pr.start(ContextCompat.getMainExecutor(ctx), this::eventoVideo);
                getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (maxSeg != null && maxSeg > 0) {
                    autoStop = () -> detener(null);
                    main.postDelayed(autoStop, maxSeg * 1000L);
                }
                JSObject o = new JSObject();
                o.put("grabando", true);
                o.put("nombre", videoNombre + ".mp4");
                call.resolve(o);
            } catch (Exception e) {
                recording = null;
                call.reject("No se pudo empezar a grabar: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(ctx));
    }

    private void eventoVideo(VideoRecordEvent ev) {
        if (!(ev instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) ev;
        recording = null;
        if (autoStop != null) { main.removeCallbacks(autoStop); autoStop = null; }
        try { getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception ignored) {}
        try { if (provider != null) provider.unbindAll(); } catch (Exception ignored) {}
        sonido(MediaActionSound.STOP_VIDEO_RECORDING);
        PluginCall c = stopCall; stopCall = null;
        if (c == null) return;
        if (fin.hasError() && fin.getError() != VideoRecordEvent.Finalize.ERROR_NONE
            && fin.getError() != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
            c.reject("El video terminó con error (" + fin.getError() + ")");
            return;
        }
        JSObject o = new JSObject();
        o.put("nombre", videoNombre + ".mp4");
        o.put("carpeta", "Movies/Claudio");
        o.put("segundos", Math.round(fin.getRecordingStats().getRecordedDurationNanos() / 1e9));
        c.resolve(o);
    }

    private void detener(PluginCall call) {
        if (recording == null) { if (call != null) call.reject("NO_GRABANDO"); return; }
        stopCall = call;
        recording.stop();
    }

    @PluginMethod
    public void detenerVideo(PluginCall call) {
        main.post(() -> detener(call));
    }

    @Override
    protected void handleOnDestroy() {
        try { if (recording != null) recording.stop(); } catch (Exception ignored) {}
        try { if (sound != null) sound.release(); } catch (Exception ignored) {}
    }
}
