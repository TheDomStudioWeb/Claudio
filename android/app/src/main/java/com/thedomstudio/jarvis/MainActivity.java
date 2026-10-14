package com.thedomstudio.jarvis;

import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WakeWordPlugin.class);
        registerPlugin(PhonePlugin.class);
        super.onCreate(savedInstanceState);
        // Permite que la app aparezca sobre la pantalla de bloqueo al ser llamada por voz.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }
}
