package com.thedomstudio.jarvis;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Apps a las que Claudio nunca accede: bancos, billeteras y medios de pago.
 * Se compara por nombre visible y por nombre de paquete, sin tildes ni mayúsculas.
 */
final class Blocklist {

    /** Palabras que, si aparecen en el nombre de la app, la bloquean. */
    private static final String[] LABEL_WORDS = {
        "banco", "bank", "macro", "galicia", "mercado pago", "mercadopago", "brubank", "prex",
        "pago facil", "pagofacil", "banco del sol", "billetera", "wallet", "uala", "naranja x",
        "santander", "bbva", "cuenta dni", "personal pay", "lemon cash", "icbc", "hsbc",
        "supervielle", "credicoop", "comafi", "hipotecario", "openbank", "rapipago", "binance"
    };

    /** Nombres exactos (palabra completa) para marcas cortas. */
    private static final String[] LABEL_EXACT = { "modo", "belo", "bna", "bna+", "dni" };

    /** Fragmentos dentro del nombre de paquete. */
    private static final String[] PACKAGE_PARTS = {
        "bank", "banco", "macro", "galicia", "mercadopago", "brubank", "prex", "pagofacil",
        "bancodelsol", "naranjax", "santander", "bbva", "wallet", "supervielle", "credicoop",
        "comafi", "hipotecario", "lemoncash", "rapipago", "binance", "uala"
    };

    /** Segmentos exactos del paquete (entre puntos) para marcas cortas. */
    private static final String[] PACKAGE_TOKENS = { "modo", "belo", "bna", "icbc", "hsbc", "prex" };

    private Blocklist() {}

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    static boolean isBlockedPackage(String pkg) {
        String p = norm(pkg);
        if (p.isEmpty()) return false;
        for (String part : PACKAGE_PARTS) if (p.contains(part)) return true;
        for (String tok : p.split("\\.")) for (String t : PACKAGE_TOKENS) if (tok.equals(t)) return true;
        return false;
    }

    static boolean isBlockedLabel(String label) {
        String l = norm(label);
        if (l.isEmpty()) return false;
        for (String w : LABEL_WORDS) if (l.contains(w)) return true;
        for (String w : l.split("[^a-z0-9+]+")) for (String e : LABEL_EXACT) if (w.equals(e)) return true;
        return false;
    }

    static boolean isBlocked(Context ctx, String pkg) {
        if (isBlockedPackage(pkg)) return true;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return isBlockedLabel(String.valueOf(pm.getApplicationLabel(ai)));
        } catch (Exception e) {
            return false;
        }
    }
}
