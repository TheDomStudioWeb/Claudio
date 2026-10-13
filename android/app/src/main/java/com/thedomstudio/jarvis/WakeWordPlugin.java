package com.thedomstudio.jarvis;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Puente JS ↔ servicio de palabra de activación (Porcupine). */
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

    private File keywordFile() {
        return new File(getContext().getFilesDir(), "wake.ppn");
    }

    @PluginMethod
    public void saveKeyword(PluginCall call) {
        String b64 = call.getString("base64");
        if (b64 == null || b64.isEmpty()) {
            call.reject("Archivo vacío");
            return;
        }
        try (FileOutputStream out = new FileOutputStream(keywordFile())) {
            out.write(Base64.decode(b64, Base64.DEFAULT));
        } catch (IOException | IllegalArgumentException e) {
            call.reject(e.getMessage());
            return;
        }
        // Si ya estaba escuchando, reiniciar con la palabra nueva.
        WakeWordService s = WakeWordService.instance;
        if (s != null) s.reload();
        call.resolve();
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
        String key = call.getString("accessKey", "");
        Float sens = call.getFloat("sensitivity", 0.6f);
        if (key == null || key.isEmpty()) {
            call.reject("Falta la AccessKey de Picovoice");
            return;
        }
        if (!keywordFile().exists()) {
            call.reject("Falta cargar el archivo .ppn de la palabra de activación");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4711);
        }
        Intent i = new Intent(getContext(), WakeWordService.class);
        i.setAction(WakeWordService.ACTION_START);
        i.putExtra("accessKey", key);
        i.putExtra("sensitivity", sens == null ? 0.6f : sens);
        i.putExtra("keywordPath", keywordFile().getAbsolutePath());
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

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", WakeWordService.instance != null);
        o.put("hasKeyword", keywordFile().exists());
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
}
