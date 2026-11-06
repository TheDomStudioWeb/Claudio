package com.thedomstudio.jarvis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clasificador de fotos (X5): recorre la galería (cámara, WhatsApp, ediciones...), descarta las repetidas
 * (idénticas y casi idénticas), y va entregando las fotos de a una, achicadas, para que la IA las mire.
 * Las que sirven se COPIAN a Imágenes / X5-Historia (Entregas, Planes, Revisar_logo_X5). Los originales no se tocan.
 */
@CapacitorPlugin(name = "Fotos")
public class FotosPlugin extends Plugin {

    static final String DESTINO = "X5-Historia";
    static final int MIN_KB = 30;           // por debajo suele ser sticker, miniatura o ícono

    static class Foto {
        File f; long size, mod; long dh; boolean tieneDh; int w, h;
        Foto(File f) { this.f = f; this.size = f.length(); this.mod = f.lastModified(); }
    }

    private static final List<Foto> cola = new ArrayList<>();   // únicas, listas para clasificar
    private static int cursor = 0;
    private static File destino;
    private static final Object LOCK = new Object();

    /* ---------------- Permiso (el mismo "Acceso a todos los archivos") ---------------- */

    private boolean tienePermiso() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return getContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            && getContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private File carpetaImagenes() { return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES); }

    /* ---------------- Recorrido ---------------- */

    private static boolean esImagen(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp") || s.endsWith(".heic");
    }

    private void recorrer(File dir, List<Foto> out, File excluir) {
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".") || f.equals(excluir)) continue;
                if (n.equalsIgnoreCase("Stickers") || n.equalsIgnoreCase("WhatsApp Stickers") || n.equalsIgnoreCase("WhatsApp Profile Photos")) continue;
                String ruta = f.getAbsolutePath();
                if (ruta.endsWith("/Android/data") || ruta.endsWith("/Android/obb") || ruta.endsWith("/_papelera_claudio")) continue;
                recorrer(f, out, excluir);
            } else if (!n.startsWith(".") && esImagen(n) && f.length() >= MIN_KB * 1024L) {
                out.add(new Foto(f));
            }
        }
    }

    private static String origen(File f) {
        String p = f.getAbsolutePath().replace(Environment.getExternalStorageDirectory().getAbsolutePath() + "/", "");
        String[] t = p.split("/");
        if (p.toLowerCase(Locale.ROOT).contains("whatsapp")) return "WhatsApp";
        if (t.length >= 2 && t[0].equals("DCIM")) return "Cámara/DCIM (" + t[1] + ")";
        return t.length >= 2 ? t[0] + "/" + t[1] : t[0];
    }

    /* ---------------- Huella visual (dHash 64 bits) ---------------- */

    private static void huella(Foto ft) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(ft.f.getAbsolutePath(), o);
            ft.w = o.outWidth; ft.h = o.outHeight;
            if (o.outWidth <= 0 || o.outHeight <= 0) return;
            int lado = Math.max(o.outWidth, o.outHeight), s = 1;
            while (lado / (s * 2) >= 96) s *= 2;
            o.inJustDecodeBounds = false; o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(ft.f.getAbsolutePath(), o);
            if (b == null) return;
            Bitmap c = Bitmap.createScaledBitmap(b, 9, 8, true);
            long h = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int p1 = c.getPixel(x, y), p2 = c.getPixel(x + 1, y);
                    int g1 = ((p1 >> 16 & 255) * 3 + (p1 >> 8 & 255) * 6 + (p1 & 255)) / 10;
                    int g2 = ((p2 >> 16 & 255) * 3 + (p2 >> 8 & 255) * 6 + (p2 & 255)) / 10;
                    h = (h << 1) | (g1 > g2 ? 1 : 0);
                }
            }
            ft.dh = h; ft.tieneDh = true;
            if (c != b) c.recycle();
            b.recycle();
        } catch (Throwable ignored) { }
    }

    /* ---------------- Fecha de la foto ---------------- */

    private static final Pattern FECHA_NOMBRE = Pattern.compile("(20\\d{2})[-_]?(\\d{2})[-_]?(\\d{2})");

    private static long fechaDe(Foto ft) {
        try {
            ExifInterface ex = new ExifInterface(ft.f.getAbsolutePath());
            String d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (d == null) d = ex.getAttribute(ExifInterface.TAG_DATETIME);
            if (d != null) {
                Date dt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d.trim());
                if (dt != null && dt.getTime() > 946684800000L) return dt.getTime();
            }
        } catch (Throwable ignored) { }
        Matcher m = FECHA_NOMBRE.matcher(ft.f.getName());
        if (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1)), me = Integer.parseInt(m.group(2)), di = Integer.parseInt(m.group(3));
                if (me >= 1 && me <= 12 && di >= 1 && di <= 31) {
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    c.clear(); c.set(a, me - 1, di, 12, 0, 0);
                    return c.getTimeInMillis();
                }
            } catch (Throwable ignored) { }
        }
        return ft.mod;
    }

    private static String sha(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /* ---------------- 1) Inventario y descarte de repetidas (no toca nada) ---------------- */

    @PluginMethod
    public void inventario(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        try {
            int muestra = call.getInt("muestra", 0);
            destino = new File(carpetaImagenes(), DESTINO);
            List<Foto> todas = new ArrayList<>();
            recorrer(Environment.getExternalStorageDirectory(), todas, destino);

            Map<String, Integer> porOrigen = new LinkedHashMap<>();
            for (Foto f : todas) { String o = origen(f.f); porOrigen.put(o, porOrigen.containsKey(o) ? porOrigen.get(o) + 1 : 1); }

            // 1) idénticas: solo se calcula el hash donde coincide el tamaño
            Map<Long, List<Foto>> porTam = new HashMap<>();
            for (Foto f : todas) { List<Foto> l = porTam.get(f.size); if (l == null) { l = new ArrayList<>(); porTam.put(f.size, l); } l.add(f); }
            List<Foto> unicas = new ArrayList<>();
            int identicas = 0;
            for (List<Foto> g : porTam.values()) {
                if (g.size() == 1) { unicas.add(g.get(0)); continue; }
                Map<String, Foto> vistos = new LinkedHashMap<>();
                for (Foto f : g) {
                    String h;
                    try { h = sha(f.f); } catch (Exception e) { h = "?" + f.f.getAbsolutePath(); }
                    Foto otra = vistos.get(h);
                    if (otra == null) vistos.put(h, f);
                    else { identicas++; if (f.mod < otra.mod) vistos.put(h, f); }   // queda la más antigua (la original)
                }
                unicas.addAll(vistos.values());
            }

            // 2) casi idénticas (recomprimidas por WhatsApp, recortes leves): huella visual
            int hecho = 0;
            for (Foto f : unicas) {
                huella(f);
                if (++hecho % 100 == 0) {
                    JSObject p = new JSObject(); p.put("hechas", hecho); p.put("total", unicas.size());
                    notifyListeners("fotosProgreso", p);
                }
            }
            Collections.sort(unicas, (a, b) -> Long.compare(b.size, a.size));   // la más pesada gana
            List<Foto> finales = new ArrayList<>();
            int casi = 0;
            for (Foto f : unicas) {
                boolean rep = false;
                if (f.tieneDh) {
                    double ar = f.h == 0 ? 1 : (double) f.w / f.h;
                    for (Foto k : finales) {
                        if (!k.tieneDh) continue;
                        double ak = k.h == 0 ? 1 : (double) k.w / k.h;
                        if (Math.abs(ar - ak) / Math.max(ar, ak) > 0.04) continue;
                        if (Long.bitCount(f.dh ^ k.dh) <= 4) { rep = true; break; }
                    }
                }
                if (rep) casi++; else finales.add(f);
            }

            // 3) cola de trabajo
            Collections.shuffle(finales, new Random(7));
            List<Foto> trabajo = muestra > 0 && finales.size() > muestra ? new ArrayList<>(finales.subList(0, muestra)) : finales;
            synchronized (LOCK) {
                cola.clear(); cola.addAll(trabajo); cursor = 0;
            }

            JSObject res = new JSObject();
            res.put("encontradas", todas.size());
            res.put("identicas", identicas);
            res.put("casiIguales", casi);
            res.put("unicas", finales.size());
            res.put("aClasificar", trabajo.size());
            JSArray orig = new JSArray();
            List<Map.Entry<String, Integer>> es = new ArrayList<>(porOrigen.entrySet());
            Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < es.size() && i < 8; i++) { JSObject o = new JSObject(); o.put("lugar", es.get(i).getKey()); o.put("fotos", es.get(i).getValue()); orig.put(o); }
            res.put("origenes", orig);
            res.put("destino", destino.getAbsolutePath());
            call.resolve(res);
        } catch (Throwable e) {
            call.reject("No se pudo recorrer la galería: " + e.getMessage());
        }
    }

    /* ---------------- 2) Entrega de a lotes ---------------- */

    @PluginMethod
    public void lote(PluginCall call) {
        int n = Math.max(1, Math.min(20, call.getInt("n", 3)));
        JSArray arr = new JSArray();
        synchronized (LOCK) {
            int fin = Math.min(cola.size(), cursor + n);
            for (int i = cursor; i < fin; i++) {
                Foto f = cola.get(i);
                JSObject o = new JSObject();
                o.put("id", i);
                o.put("nombre", f.f.getName());
                o.put("origen", origen(f.f));
                o.put("fecha", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(fechaDe(f))));
                arr.put(o);
            }
            cursor = fin;
        }
        JSObject res = new JSObject();
        res.put("fotos", arr);
        res.put("quedan", Math.max(0, cola.size() - cursor));
        res.put("total", cola.size());
        call.resolve(res);
    }

    /** Foto achicada (lado largo 1024) y derecha, en base64 JPEG, para mandarla a la IA. */
    @PluginMethod
    public void miniatura(PluginCall call) {
        int id = call.getInt("id", -1);
        Foto ft;
        synchronized (LOCK) { ft = id >= 0 && id < cola.size() ? cola.get(id) : null; }
        if (ft == null) { call.reject("Foto inexistente."); return; }
        try {
            String ruta = ft.f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(ruta, o);
            int lado = Math.max(o.outWidth, o.outHeight), s = 1;
            while (lado / (s * 2) >= 1024) s *= 2;
            o.inJustDecodeBounds = false; o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(ruta, o);
            if (b == null) { call.reject("No se pudo abrir la imagen."); return; }
            int rot = 0;
            try {
                int ori = new ExifInterface(ruta).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90; else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180; else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Throwable ignored) { }
            int mayor = Math.max(b.getWidth(), b.getHeight());
            float esc = mayor > 1024 ? 1024f / mayor : 1f;
            if (rot != 0 || esc != 1f) {
                Matrix m = new Matrix();
                if (rot != 0) m.postRotate(rot);
                if (esc != 1f) m.postScale(esc, esc);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) { b.recycle(); b = r; }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            b.recycle();
            JSObject res = new JSObject();
            res.put("b64", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
            call.resolve(res);
        } catch (Throwable e) {
            call.reject("No se pudo achicar la foto: " + e.getMessage());
        }
    }

    /* ---------------- 3) Copiar a la carpeta que corresponde (los originales no se tocan) ---------------- */

    private static String carpetaDe(String cat) {
        if ("entrega".equals(cat)) return "Entregas";
        if ("plan".equals(cat)) return "Planes";
        return "Revisar_logo_X5";
    }

    private static File libre(File dir, String nombre) {
        File f = new File(dir, nombre);
        if (!f.exists()) return f;
        int dot = nombre.lastIndexOf('.');
        String base = dot > 0 ? nombre.substring(0, dot) : nombre, ext = dot > 0 ? nombre.substring(dot) : "";
        for (int i = 2; i < 1000; i++) { File g = new File(dir, base + " (" + i + ")" + ext); if (!g.exists()) return g; }
        return new File(dir, System.currentTimeMillis() + ext);
    }

    private static String csv(String s) { return "\"" + (s == null ? "" : s.replace("\"", "'").replace("\n", " ")) + "\""; }

    @PluginMethod
    public void guardar(PluginCall call) {
        if (!tienePermiso()) { call.reject("SIN_PERMISO"); return; }
        int id = call.getInt("id", -1);
        String cat = call.getString("categoria", "revisar");
        String motivo = call.getString("motivo", "");
        Foto ft;
        synchronized (LOCK) { ft = id >= 0 && id < cola.size() ? cola.get(id) : null; }
        if (ft == null || destino == null) { call.reject("Foto inexistente."); return; }
        try {
            synchronized (LOCK) {
                File dir = new File(destino, carpetaDe(cat));
                if (!dir.exists() && !dir.mkdirs()) { call.reject("No pude crear la carpeta " + dir.getAbsolutePath()); return; }
                long t = fechaDe(ft);
                String fecha = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
                File dst = libre(dir, fecha + "_" + ft.f.getName());
                try (InputStream in = new FileInputStream(ft.f); OutputStream out = new FileOutputStream(dst)) {
                    byte[] b = new byte[64 * 1024]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (dst.length() != ft.size) { dst.delete(); call.reject("La copia no coincide."); return; }
                dst.setLastModified(t);
                MediaScannerConnection.scanFile(getContext(), new String[]{dst.getAbsolutePath()}, null, null);
                File idx = new File(destino, "indice.csv");
                boolean nuevo = !idx.exists();
                try (OutputStream out = new FileOutputStream(idx, true)) {
                    String l = (nuevo ? "fecha,categoria,archivo_copiado,origen,motivo\n" : "")
                        + fecha + "," + cat + "," + csv(dst.getName()) + "," + csv(ft.f.getAbsolutePath()) + "," + csv(motivo) + "\n";
                    out.write(l.getBytes("UTF-8"));
                }
                JSObject res = new JSObject();
                res.put("carpeta", dir.getAbsolutePath());
                call.resolve(res);
            }
        } catch (Throwable e) {
            call.reject("No se pudo copiar: " + e.getMessage());
        }
    }
}
