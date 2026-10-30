package com.thedomstudio.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Síntesis de voz propia: usa cualquier motor de texto a voz instalado (no solo el predeterminado),
 * lista todas sus voces indicando cuáles están realmente instaladas y habla con la elegida.
 */
@CapacitorPlugin(name = "Voz")
public class VozPlugin extends Plugin {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Eng> engines = new HashMap<>();
    private final Map<String, PluginCall> pending = new ConcurrentHashMap<>();
    private int seq = 0;

    private interface EngCb { void run(Eng e); }

    private static class Eng {
        TextToSpeech tts;
        boolean ready = false;
        boolean failed = false;
        final List<Runnable> waiting = new ArrayList<>();
    }

    /* ---------- Motores ---------- */
    private List<ResolveInfo> installedEngines() {
        PackageManager pm = getContext().getPackageManager();
        Intent i = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        List<ResolveInfo> l = pm.queryIntentServices(i, 0);
        return l == null ? new ArrayList<ResolveInfo>() : l;
    }

    private String defaultEngine() {
        try {
            return Settings.Secure.getString(getContext().getContentResolver(), "tts_default_synth");
        } catch (Exception e) {
            return null;
        }
    }

    @PluginMethod
    public void engines(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        String def = defaultEngine();
        JSArray arr = new JSArray();
        for (ResolveInfo ri : installedEngines()) {
            if (ri.serviceInfo == null) continue;
            JSObject o = new JSObject();
            o.put("package", ri.serviceInfo.packageName);
            o.put("label", String.valueOf(ri.loadLabel(pm)));
            o.put("default", ri.serviceInfo.packageName.equals(def));
            arr.put(o);
        }
        JSObject ret = new JSObject();
        ret.put("engines", arr);
        call.resolve(ret);
    }

    private void flush(Eng e) {
        List<Runnable> l = new ArrayList<>(e.waiting);
        e.waiting.clear();
        for (Runnable r : l) r.run();
    }

    /** Arranca (una sola vez) el motor indicado y avisa cuando está listo o falló. Siempre en el hilo principal. */
    private void withEngine(final String pkg, final EngCb cb) {
        final String key = pkg == null ? "" : pkg;
        Eng found = engines.get(key);
        if (found == null) {
            final Eng ne = new Eng();
            found = ne;
            engines.put(key, ne);
            try {
                ne.tts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
                    @Override public void onInit(final int status) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (ne.ready || ne.failed) return;
                                ne.ready = status == TextToSpeech.SUCCESS;
                                ne.failed = !ne.ready;
                                flush(ne);
                            }
                        });
                    }
                }, key.isEmpty() ? null : key);
                ne.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        PluginCall c = pending.remove(id);
                        if (c != null) c.resolve();
                    }
                    @Override public void onError(String id) {
                        PluginCall c = pending.remove(id);
                        if (c != null) c.reject("Error al hablar");
                    }
                    @Override public void onStop(String id, boolean interrupted) {
                        PluginCall c = pending.remove(id);
                        if (c != null) c.resolve();
                    }
                });
            } catch (Exception ex) {
                ne.failed = true;
            }
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!ne.ready && !ne.failed) { ne.failed = true; flush(ne); }
                }
            }, 6000);
        }
        final Eng e = found;
        if (e.ready || e.failed) cb.run(e);
        else e.waiting.add(new Runnable() { @Override public void run() { cb.run(e); } });
    }

    /* ---------- Voces ---------- */
    @PluginMethod
    public void voices(final PluginCall call) {
        final String language = call.getString("language", "");
        ui.post(new Runnable() {
            @Override public void run() {
                final PackageManager pm = getContext().getPackageManager();
                final String def = defaultEngine();
                final List<ResolveInfo> list = new ArrayList<>();
                for (ResolveInfo x : installedEngines()) if (x.serviceInfo != null) list.add(x);
                final JSArray out = new JSArray();
                final int[] left = { list.size() };
                if (list.isEmpty()) {
                    JSObject r = new JSObject(); r.put("voices", out); call.resolve(r);
                    return;
                }
                for (final ResolveInfo ri : list) {
                    final String pkg = ri.serviceInfo.packageName;
                    final String label = String.valueOf(ri.loadLabel(pm));
                    withEngine(pkg, new EngCb() {
                        @Override public void run(Eng e) {
                            try {
                                if (e.ready && e.tts != null) {
                                    Set<Voice> vs = e.tts.getVoices();
                                    if (vs != null) {
                                        for (Voice v : vs) {
                                            Locale loc = v.getLocale();
                                            if (!language.isEmpty() && !language.equalsIgnoreCase(loc.getLanguage())) continue;
                                            Set<String> feats = v.getFeatures();
                                            boolean installed = feats == null || !feats.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                                            JSObject o = new JSObject();
                                            o.put("engine", pkg);
                                            o.put("engineLabel", label);
                                            o.put("engineDefault", pkg.equals(def));
                                            o.put("name", v.getName());
                                            o.put("lang", loc.toLanguageTag());
                                            o.put("display", (loc.getDisplayLanguage() + " " + loc.getDisplayCountry()).trim());
                                            o.put("quality", v.getQuality());
                                            o.put("network", v.isNetworkConnectionRequired());
                                            o.put("installed", installed);
                                            out.put(o);
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                            if (--left[0] == 0) {
                                JSObject r = new JSObject();
                                r.put("voices", out);
                                call.resolve(r);
                            }
                        }
                    });
                }
            }
        });
    }

    /* ---------- Hablar ---------- */
    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        final String engine = call.getString("engine", "");
        final String voiceName = call.getString("voice", "");
        final String lang = call.getString("lang", "es-AR");
        final float rate = call.getFloat("rate", 1.0f);
        final float pitch = call.getFloat("pitch", 1.0f);
        final float volume = call.getFloat("volume", 1.0f);
        final int queue = call.getInt("queue", 0) == 1 ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
        ui.post(new Runnable() {
            @Override public void run() {
                withEngine(engine == null || engine.isEmpty() ? null : engine, new EngCb() {
                    @Override public void run(Eng e) {
                        if (!e.ready || e.tts == null) { call.reject("Motor de voz no disponible"); return; }
                        try {
                            if (queue != TextToSpeech.QUEUE_ADD) e.tts.stop();
                            Voice target = null;
                            if (voiceName != null && !voiceName.isEmpty()) {
                                Set<Voice> vs = e.tts.getVoices();
                                if (vs != null) for (Voice v : vs) if (voiceName.equals(v.getName())) { target = v; break; }
                            }
                            if (target != null) {
                                if (e.tts.setVoice(target) != TextToSpeech.SUCCESS) { call.reject("La voz elegida no está instalada"); return; }
                            } else {
                                e.tts.setLanguage(Locale.forLanguageTag(lang));
                            }
                            e.tts.setSpeechRate(rate);
                            e.tts.setPitch(pitch);
                            String id = "v" + (++seq);
                            pending.put(id, call);
                            Bundle b = new Bundle();
                            b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
                            int res = e.tts.speak(text, queue, b, id);
                            if (res != TextToSpeech.SUCCESS) { pending.remove(id); call.reject("No se pudo hablar"); }
                        } catch (Exception ex) {
                            call.reject(String.valueOf(ex.getMessage()));
                        }
                    }
                });
            }
        });
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        ui.post(new Runnable() {
            @Override public void run() {
                for (Eng e : engines.values()) {
                    try { if (e.ready && e.tts != null) e.tts.stop(); } catch (Exception ignored) {}
                }
                for (String id : new ArrayList<>(pending.keySet())) {
                    PluginCall c = pending.remove(id);
                    if (c != null) c.resolve();
                }
                call.resolve();
            }
        });
    }

    /** Abre los ajustes de texto a voz de Android (motor, voces y descarga de datos de voz). */
    @PluginMethod
    public void ajustes(PluginCall call) {
        try {
            Intent i = new Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                call.resolve();
            } catch (Exception e2) {
                call.reject(String.valueOf(e2.getMessage()));
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        for (Eng e : engines.values()) {
            try { if (e.tts != null) { e.tts.stop(); e.tts.shutdown(); } } catch (Exception ignored) {}
        }
        engines.clear();
        super.handleOnDestroy();
    }
}
