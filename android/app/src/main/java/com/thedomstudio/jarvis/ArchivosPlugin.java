package com.thedomstudio.jarvis;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Juntar archivos en una sola carpeta: buscar por nombre y fecha, quedarse con la última versión,
 * copiar y verificar, y recién después (con confirmación) mandar los originales a una papelera de 7 días.
 * Nada se borra de forma definitiva salvo la papelera vencida.
 */
@CapacitorPlugin(name = "Archivos")
public class ArchivosPlugin extends Plugin {

    static final String PAPELERA = "_papelera_claudio";
    static final long DIAS_PAPELERA = 7L * 24 * 3600 * 1000;

    /** Un archivo encontrado. */
    static class Arch {
        File f; String hash; long size, mod;
        String rol = "conservar";   // conservar | repetido | anterior
        String familia;
        boolean dudoso;
        File copia;
        Arch(File f) { this.f = f; this.size = f.length(); this.mod = f.lastModified(); }
    }

    // Estado del plan actual (se rehace con cada búsqueda).
    private static final List<Arch> plan = new ArrayList<>();
    private static File destino;
    private static boolean copiaVerificada = false;

    /* ---------------- Permiso ---------------- */

    private boolean tienePermiso() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return getContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            && getContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("permiso", tienePermiso());
        call.resolve(o);
    }

    /** Abre la pantalla de Android donde se da "Acceso a todos los archivos". */
    @PluginMethod
    public void pedirPermiso(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } else {
                getActivity().requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4711);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /* ---------------- Utilidades ---------------- */

    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static final Pattern[] SUFIJOS = {
        Pattern.compile("\\s*\\(\\d+\\)$"),
        Pattern.compile("[ _-]*(copia|copy)( \\d+)?$"),
        Pattern.compile("[ _-]*v\\d+(\\.\\d+)?$"),
        Pattern.compile("[ _-]*(final|nuevo|nueva|definitivo|definitiva|ultimo|ultima)$"),
        Pattern.compile("[ _-]+\\d$"),
    };

    /** Nombre sin extensión ni marcas de versión: "GUIA_v2 (3).pdf" y "GUIA_final.pdf" caen en la misma familia. */
    static String familia(String nombre) {
        String n = norm(nombre);
        int dot = n.lastIndexOf('.');
        String ext = dot > 0 ? n.substring(dot) : "";
        String base = dot > 0 ? n.substring(0, dot) : n;
        base = base.replaceFirst("^(copia de|copy of)\\s+", "");
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            for (Pattern p : SUFIJOS) {
                String r = p.matcher(base).replaceFirst("");
                if (!r.equals(base) && r.length() > 0) { base = r; cambio = true; }
            }
        }
        return base.trim() + ext;
    }

    private File raiz() { return Environment.getExternalStorageDirectory(); }

    private File carpetaDocumentos() { return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS); }

    private void recorrer(File dir, List<String> claves, long desde, List<Arch> out, File excluir1, File excluir2) {
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".")) continue;
                if (f.equals(excluir1) || f.equals(excluir2)) continue;
                String ruta = f.getAbsolutePath();
                if (ruta.endsWith("/Android/data") || ruta.endsWith("/Android/obb")) continue;
                recorrer(f, claves, desde, out, excluir1, excluir2);
            } else {
                if (n.startsWith(".") || n.endsWith(".tmp") || n.endsWith(".crdownload") || n.endsWith(".part") || n.endsWith(".nomedia")) continue;
                if (f.lastModified() < desde) continue;
                String nn = norm(n);
                for (String k : claves) { if (nn.contains(k)) { out.add(new Arch(f)); break; } }
                if (out.size() > 3000) return;
            }
        }
    }

    private static String relativa(File f, File raiz) {
        String p = f.getParent() == null ? "" : f.getParent();
        String r = raiz.getAbsolutePath();
        return p.startsWith(r) ? p.substring(r.length()).replaceFirst("^/", "") : p;
    }

    /* ---------------- 1) Buscar y armar el plan (no toca nada) ---------------- */

    @PluginMethod
    public void buscar(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        try {
            JSArray arr = call.getArray("palabras");
            List<String> claves = new ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) { String k = norm(arr.getString(i)).trim(); if (k.length() >= 3) claves.add(k); }
            if (claves.isEmpty()) { call.reject("Faltan palabras para buscar."); return; }
            int dias = call.getInt("dias", 21);
            String nombreCarpeta = call.getString("carpeta", "FINANCO-DOMINO").replaceAll("[\\\\/:*?\"<>|]", "").trim();
            if (nombreCarpeta.isEmpty()) nombreCarpeta = "FINANCO-DOMINO";

            destino = new File(carpetaDocumentos(), nombreCarpeta);
            File papelera = new File(carpetaDocumentos(), PAPELERA);
            purgarPapelera(papelera);

            long desde = System.currentTimeMillis() - dias * 24L * 3600 * 1000;
            List<Arch> hallados = new ArrayList<>();
            recorrer(raiz(), claves, desde, hallados, destino, papelera);

            // Hash y agrupación
            for (Arch a : hallados) { try { a.hash = sha256(a.f); } catch (Exception e) { a.hash = "?" + a.f.getAbsolutePath(); } }
            Map<String, List<Arch>> porHash = new LinkedHashMap<>();
            for (Arch a : hallados) porHash.computeIfAbsent(a.hash, k -> new ArrayList<>()).add(a);
            List<Arch> unicos = new ArrayList<>();
            for (List<Arch> g : porHash.values()) {
                Collections.sort(g, (x, y) -> {
                    if (x.mod != y.mod) return Long.compare(y.mod, x.mod);
                    return Integer.compare(x.f.getName().length(), y.f.getName().length());
                });
                unicos.add(g.get(0));
                for (int i = 1; i < g.size(); i++) g.get(i).rol = "repetido";
            }
            // Versiones: misma familia con contenido distinto → queda la más nueva
            Map<String, List<Arch>> porFam = new LinkedHashMap<>();
            for (Arch a : unicos) { a.familia = familia(a.f.getName()); porFam.computeIfAbsent(a.familia, k -> new ArrayList<>()).add(a); }
            for (List<Arch> g : porFam.values()) {
                if (g.size() < 2) continue;
                Collections.sort(g, (x, y) -> Long.compare(y.mod, x.mod));
                Arch nuevo = g.get(0);
                for (int i = 1; i < g.size(); i++) {
                    Arch viejo = g.get(i);
                    double r = nuevo.size == 0 || viejo.size == 0 ? 1 : (double) Math.max(nuevo.size, viejo.size) / Math.min(nuevo.size, viejo.size);
                    if (r > 2.5) { viejo.dudoso = true; nuevo.dudoso = true; }   // muy distintos: no se decide solo
                    else viejo.rol = "anterior";
                }
            }
            plan.clear();
            plan.addAll(hallados);
            copiaVerificada = false;

            int cons = 0, rep = 0, ant = 0, dud = 0;
            JSArray conservar = new JSArray(), sobran = new JSArray(), dudosos = new JSArray();
            for (Arch a : hallados) {
                JSObject o = new JSObject();
                o.put("nombre", a.f.getName());
                o.put("carpeta", relativa(a.f, raiz()));
                o.put("kb", a.size / 1024);
                o.put("fecha", new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(a.mod)));
                if (a.rol.equals("conservar")) { cons++; if (a.dudoso) { dud++; dudosos.put(o); } else conservar.put(o); }
                else { if (a.rol.equals("repetido")) rep++; else ant++; o.put("motivo", a.rol); sobran.put(o); }
            }
            JSObject res = new JSObject();
            res.put("encontrados", hallados.size());
            res.put("conservar", cons);
            res.put("repetidos", rep);
            res.put("versionesAnteriores", ant);
            res.put("dudosos", dud);
            res.put("destino", destino.getAbsolutePath());
            res.put("lista", cortar(conservar, 40));
            res.put("sobran", cortar(sobran, 25));
            res.put("dudososLista", cortar(dudosos, 15));
            call.resolve(res);
        } catch (Exception e) {
            call.reject("No se pudo buscar: " + e.getMessage());
        }
    }

    private static JSArray cortar(JSArray a, int max) {
        if (a.length() <= max) return a;
        JSArray r = new JSArray();
        for (int i = 0; i < max; i++) { try { r.put(a.get(i)); } catch (Exception ignored) {} }
        return r;
    }

    /* ---------------- 2) Copiar y verificar (los originales no se tocan) ---------------- */

    @PluginMethod
    public void copiar(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        if (plan.isEmpty() || destino == null) { call.reject("Primero hay que buscar los archivos."); return; }
        try {
            if (!destino.exists() && !destino.mkdirs()) { call.reject("No pude crear la carpeta " + destino.getAbsolutePath()); return; }
            int ok = 0, fallas = 0, total = 0;
            JSArray errores = new JSArray();
            for (Arch a : plan) {
                if (!a.rol.equals("conservar")) continue;
                total++;
                try {
                    File dst = nombreLibre(destino, a.f.getName());
                    try (InputStream in = new FileInputStream(a.f); OutputStream out = new FileOutputStream(dst)) {
                        byte[] b = new byte[64 * 1024]; int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    dst.setLastModified(a.mod);
                    if (dst.length() == a.size && sha256(dst).equals(a.hash)) { a.copia = dst; ok++; }
                    else { dst.delete(); a.copia = null; fallas++; errores.put(a.f.getName()); }
                } catch (Exception e) { a.copia = null; fallas++; errores.put(a.f.getName()); }
            }
            copiaVerificada = fallas == 0 && total > 0;
            JSObject res = new JSObject();
            res.put("total", total);
            res.put("verificados", ok);
            res.put("fallas", fallas);
            res.put("errores", errores);
            res.put("listoParaLimpiar", copiaVerificada);
            res.put("destino", destino.getAbsolutePath());
            call.resolve(res);
        } catch (Exception e) {
            call.reject("No se pudo copiar: " + e.getMessage());
        }
    }

    private static File nombreLibre(File dir, String nombre) {
        File f = new File(dir, nombre);
        if (!f.exists()) return f;
        int dot = nombre.lastIndexOf('.');
        String base = dot > 0 ? nombre.substring(0, dot) : nombre, ext = dot > 0 ? nombre.substring(dot) : "";
        for (int i = 2; i < 1000; i++) { File g = new File(dir, base + " (" + i + ")" + ext); if (!g.exists()) return g; }
        return new File(dir, System.currentTimeMillis() + ext);
    }

    /* ---------------- 3) Originales → papelera de 7 días (solo si la copia quedó verificada) ---------------- */

    @PluginMethod
    public void limpiar(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        if (!copiaVerificada) { call.reject("No se puede: la copia todavía no está verificada al 100 %."); return; }
        try {
            // Antes de mover, se vuelve a comprobar que cada copia sigue intacta.
            for (Arch a : plan) {
                if (a.rol.equals("conservar") && (a.copia == null || !a.copia.exists() || a.copia.length() != a.size)) {
                    call.reject("Una copia ya no coincide (" + a.f.getName() + "). No se movió nada."); return;
                }
            }
            String dia = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
            File lote = new File(new File(carpetaDocumentos(), PAPELERA), dia);
            if (!lote.mkdirs() && !lote.exists()) { call.reject("No pude crear la papelera."); return; }
            JSONArray manifiesto = new JSONArray();
            int movidos = 0, fallas = 0;
            for (Arch a : plan) {
                if (!a.f.exists()) continue;
                File dst = nombreLibre(lote, a.f.getName());
                boolean ok = a.f.renameTo(dst);
                if (!ok) {
                    try {
                        try (InputStream in = new FileInputStream(a.f); OutputStream out = new FileOutputStream(dst)) {
                            byte[] b = new byte[64 * 1024]; int n;
                            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        }
                        if (dst.length() == a.size) ok = a.f.delete();
                    } catch (Exception ignored) {}
                }
                if (ok) {
                    movidos++;
                    JSONObject m = new JSONObject();
                    m.put("original", a.f.getAbsolutePath()); m.put("papelera", dst.getAbsolutePath());
                    manifiesto.put(m);
                } else fallas++;
            }
            try (OutputStream out = new FileOutputStream(new File(lote, "_manifiesto.json"))) {
                out.write(manifiesto.toString(1).getBytes("UTF-8"));
            }
            copiaVerificada = false;
            plan.clear();
            JSObject res = new JSObject();
            res.put("movidos", movidos);
            res.put("fallas", fallas);
            res.put("papelera", lote.getAbsolutePath());
            res.put("diasPapelera", 7);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("No se pudo limpiar: " + e.getMessage());
        }
    }

    /* ---------------- Deshacer: devuelve el último lote de la papelera a su lugar ---------------- */

    @PluginMethod
    public void restaurar(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        try {
            File pap = new File(carpetaDocumentos(), PAPELERA);
            File[] lotes = pap.listFiles(File::isDirectory);
            if (lotes == null || lotes.length == 0) { call.reject("La papelera está vacía."); return; }
            java.util.Arrays.sort(lotes, (x, y) -> y.getName().compareTo(x.getName()));
            File lote = lotes[0];
            File man = new File(lote, "_manifiesto.json");
            if (!man.exists()) { call.reject("Ese lote no tiene manifiesto."); return; }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = new FileInputStream(man)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8")); }
            JSONArray arr = new JSONArray(sb.toString());
            int vueltos = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                File src = new File(m.getString("papelera")), dst = new File(m.getString("original"));
                if (!src.exists()) continue;
                File dir = dst.getParentFile();
                if (dir != null) dir.mkdirs();
                if (dst.exists()) dst = nombreLibre(dir, dst.getName());
                if (src.renameTo(dst)) vueltos++;
            }
            man.delete(); lote.delete();
            JSObject res = new JSObject();
            res.put("restaurados", vueltos);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("No se pudo restaurar: " + e.getMessage());
        }
    }

    /** Borra definitivamente los lotes de la papelera con más de 7 días. */
    private void purgarPapelera(File pap) {
        File[] lotes = pap.listFiles(File::isDirectory);
        if (lotes == null) return;
        long limite = System.currentTimeMillis() - DIAS_PAPELERA;
        for (File l : lotes) {
            if (l.lastModified() < limite) {
                File[] hijos = l.listFiles();
                if (hijos != null) for (File h : hijos) h.delete();
                l.delete();
            }
        }
    }
}
