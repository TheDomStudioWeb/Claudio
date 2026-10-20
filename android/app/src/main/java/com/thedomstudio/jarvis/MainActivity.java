package com.thedomstudio.jarvis;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WakeWordPlugin.class);
        registerPlugin(PhonePlugin.class);
        registerPlugin(ReunionPlugin.class);
        registerPlugin(ArchivosPlugin.class);
        super.onCreate(savedInstanceState);
        // Permite que la app aparezca sobre la pantalla de bloqueo al ser llamada por voz.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Si la escucha estaba activada pero Android cerró el servicio, se vuelve a encender al abrir la app.
        if (WakeWordService.instance == null && WakeWordService.prefs(this).getBoolean("enabled", false)) {
            try {
                Intent i = new Intent(this, WakeWordService.class);
                ContextCompat.startForegroundService(this, i);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Al salir de la app, la escucha del nombre se reactiva aunque la conversación haya quedado a medias.
        WakeWordService s = WakeWordService.instance;
        if (s != null) s.resumeSoon(1500);
    }
}
