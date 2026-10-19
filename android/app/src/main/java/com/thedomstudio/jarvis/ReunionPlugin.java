package com.thedomstudio.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Puente JS ↔ grabación de reuniones (micrófono o audio del sistema, en segundo plano). */
@CapacitorPlugin(
    name = "Reunion",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone") }
)
public class ReunionPlugin extends Plugin {

    static volatile ReunionPlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    /** Avisa a la pantalla: tipo = parte | fin | error. */
    static void emitir(String tipo, String texto, int num, String uri) {
        ReunionPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("tipo", tipo);
        if (texto != null) o.put("texto", texto);
        o.put("num", num);
        if (uri != null) o.put("uri", uri);
        p.notifyListeners("reunion", o);
    }

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (ReunionService.activa) {
            call.reject("Ya hay una reunión en curso.");
            return;
        }
        final String fuente = "sistema".equals(call.getString("fuente", "mic")) ? "sistema" : "mic";
        if ("sistema".equals(fuente) && Build.VERSION.SDK_INT < 29) {
            call.reject("El audio del sistema requiere Android 10 o superior.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4712);
        }
        if ("mic".equals(fuente) && getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermCallback");
            return;
        }
        arrancar(call, fuente);
    }

    @PermissionCallback
    private void micPermCallback(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) arrancar(call, "mic");
        else call.reject("Sin permiso de micrófono");
    }

    private void arrancar(PluginCall call, String fuente) {
        if ("sistema".equals(fuente)) {
            MediaProjectionManager mpm = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                call.reject("Este teléfono no permite capturar el audio del sistema.");
                return;
            }
            // Android pide confirmar en pantalla; no se graba video, solo el audio.
            startActivityForResult(call, mpm.createScreenCaptureIntent(), "proyeccionResult");
            return;
        }
        lanzar(call, "mic", 0, null);
    }

    @ActivityCallback
    private void proyeccionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("No se dio el permiso para capturar el audio.");
            return;
        }
        lanzar(call, "sistema", result.getResultCode(), result.getData());
    }

    private void lanzar(PluginCall call, String fuente, int resultCode, Intent data) {
        Integer chunk = call.getInt("chunkMin", 25);
        Intent i = new Intent(getContext(), ReunionService.class);
        i.setAction(ReunionService.ACTION_START);
        i.putExtra("fuente", fuente);
        i.putExtra("chunkMin", chunk == null ? 25 : chunk);
        if (data != null) {
            i.putExtra("resultCode", resultCode);
            i.putExtra("data", data);
        }
        try {
            // Se marca ya como activa para que la pantalla no la vea apagada mientras Android arranca el servicio.
            ReunionService.activa = true;
            ReunionService.inicioMs = System.currentTimeMillis();
            ReunionService.fuenteActual = fuente;
            ReunionService.parteActual = 0;
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve(estadoObj());
        } catch (Exception e) {
            ReunionService.activa = false;
            call.reject("No se pudo iniciar la grabación: " + e.getMessage());
        }
    }

    @PluginMethod
    public void terminar(PluginCall call) {
        ReunionService s = ReunionService.instance;
        if (s != null && ReunionService.activa) {
            s.requestStop();
        } else {
            // El servicio no está: solo se libera el bloqueo de Claudio por si quedó pegado.
            ReunionService.activa = false;
            WakeWordService.reunion = false;
            WakeWordService w = WakeWordService.instance;
            if (w != null) w.resumeDetection();
        }
        call.resolve(estadoObj());
    }

    @PluginMethod
    public void estado(PluginCall call) {
        call.resolve(estadoObj());
    }

    private JSObject estadoObj() {
        JSObject o = new JSObject();
        o.put("activa", ReunionService.activa);
        o.put("fuente", ReunionService.fuenteActual);
        o.put("inicioMs", ReunionService.inicioMs);
        o.put("parte", ReunionService.parteActual);
        o.put("nivel", ReunionService.nivel);
        JSArray arr = new JSArray();
        try {
            JSONArray ja = new JSONArray(ReunionService.prefs(getContext()).getString("ultima", "[]"));
            for (int k = 0; k < ja.length(); k++) {
                JSONObject it = ja.getJSONObject(k);
                JSObject x = new JSObject();
                x.put("nombre", it.optString("nombre"));
                x.put("uri", it.optString("uri"));
                x.put("seg", it.optInt("seg"));
                arr.put(x);
            }
        } catch (Exception ignored) {}
        o.put("partes", arr);
        return o;
    }

    /** Abre el menú de compartir con los archivos de audio de la última reunión (para Gemini, Drive, WhatsApp…). */
    @PluginMethod
    public void compartir(PluginCall call) {
        try {
            JSONArray ja = new JSONArray(ReunionService.prefs(getContext()).getString("ultima", "[]"));
            if (ja.length() == 0) { call.reject("No hay grabaciones para compartir."); return; }
            ArrayList<Uri> uris = new ArrayList<>();
            for (int k = 0; k < ja.length(); k++) {
                String u = ja.getJSONObject(k).optString("uri");
                if (u.startsWith("content://")) uris.add(Uri.parse(u));
                else if (u.startsWith("file://")) {
                    uris.add(androidx.core.content.FileProvider.getUriForFile(getContext(),
                        getContext().getPackageName() + ".fileprovider", new java.io.File(Uri.parse(u).getPath())));
                }
            }
            if (uris.isEmpty()) { call.reject("No encontré los archivos."); return; }
            Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            send.setType("audio/*");
            if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Compartir audio de la reunión");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("No pude compartir: " + e.getMessage());
        }
    }
}
