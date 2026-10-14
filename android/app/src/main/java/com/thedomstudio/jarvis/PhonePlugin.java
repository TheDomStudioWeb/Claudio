package com.thedomstudio.jarvis;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.app.SearchManager;
import android.telephony.SmsManager;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Acciones del teléfono para Claudio: calendario, contactos, mensajes, llamadas y apps. */
@CapacitorPlugin(
    name = "Phone",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR }, alias = "calendar"),
        @Permission(strings = { Manifest.permission.READ_CONTACTS }, alias = "contacts"),
        @Permission(strings = { Manifest.permission.CALL_PHONE }, alias = "phone"),
        @Permission(strings = { Manifest.permission.SEND_SMS }, alias = "sms")
    }
)
public class PhonePlugin extends Plugin {

    /* ---------------- Estado y permisos ---------------- */

    private JSObject statusObj() {
        JSObject o = new JSObject();
        o.put("calendar", getPermissionState("calendar") == PermissionState.GRANTED);
        o.put("contacts", getPermissionState("contacts") == PermissionState.GRANTED);
        o.put("phone", getPermissionState("phone") == PermissionState.GRANTED);
        o.put("sms", getPermissionState("sms") == PermissionState.GRANTED);
        o.put("notifications", NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName()));
        o.put("locked", isLocked());
        return o;
    }

    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void setup(PluginCall call) {
        requestAllPermissions(call, "setupDone");
    }

    @PermissionCallback
    private void setupDone(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void openNotificationAccess(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    private boolean need(PluginCall call, String alias, String what) {
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            call.reject("Falta el permiso de " + what + ". Actívelo en Protocolos → Asistente del teléfono.");
            return false;
        }
        return true;
    }

    private boolean unlocked(PluginCall call) {
        if (isLocked()) {
            call.reject("El teléfono está bloqueado. Por seguridad, desbloquéelo para esta acción.");
            return false;
        }
        return true;
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getActivity() != null) getActivity().startActivity(i);
        else getContext().startActivity(i);
    }

    /* ---------------- Calendario ---------------- */

    @PluginMethod
    public void calendars(PluginCall call) {
        if (!need(call, "calendar", "calendario")) return;
        JSArray arr = new JSArray();
        String[] proj = {
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE
        };
        try (Cursor c = getContext().getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)) {
            while (c != null && c.moveToNext()) {
                JSObject o = new JSObject();
                o.put("id", c.getLong(0));
                o.put("nombre", c.getString(1));
                o.put("cuenta", c.getString(2));
                o.put("editable", c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR);
                o.put("principal", c.getInt(4) == 1);
                o.put("visible", c.getInt(5) == 1);
                arr.put(o);
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("calendarios", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void events(PluginCall call) {
        if (!need(call, "calendar", "calendario")) return;
        Long from = call.getLong("from");
        Long to = call.getLong("to");
        if (from == null || to == null) { call.reject("Faltan fechas"); return; }
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION
        };
        JSArray arr = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(b.build(), proj,
                CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 60) {
                JSObject o = new JSObject();
                o.put("id", c.getLong(0));
                o.put("titulo", c.getString(1));
                o.put("inicio", c.getLong(2));
                o.put("fin", c.getLong(3));
                o.put("lugar", c.getString(4));
                o.put("todoElDia", c.getInt(5) == 1);
                o.put("calendario", c.getString(6));
                String d = c.getString(7);
                if (d != null && d.length() > 200) d = d.substring(0, 200) + "…";
                o.put("descripcion", d);
                arr.put(o);
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("eventos", arr);
        call.resolve(r);
    }

    private long pickCalendar(String name) {
        String[] proj = {
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        };
        String sel = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            + " AND " + CalendarContract.Calendars.VISIBLE + "=1";
        long best = -1, primaryGoogle = -1, anyGoogle = -1;
        String want = Blocklist.norm(name);
        try (Cursor c = getContext().getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                String dn = Blocklist.norm(c.getString(1));
                String acc = Blocklist.norm(c.getString(2));
                boolean google = "com.google".equals(c.getString(3));
                if (!want.isEmpty() && (dn.contains(want) || acc.contains(want))) return id;
                if (google && c.getInt(4) == 1 && primaryGoogle < 0) primaryGoogle = id;
                if (google && anyGoogle < 0) anyGoogle = id;
                if (best < 0) best = id;
            }
        }
        if (primaryGoogle >= 0) return primaryGoogle;
        if (anyGoogle >= 0) return anyGoogle;
        return best;
    }

    @PluginMethod
    public void createEvent(PluginCall call) {
        if (!need(call, "calendar", "calendario") || !unlocked(call)) return;
        String title = call.getString("title");
        Long start = call.getLong("start");
        Long end = call.getLong("end");
        if (title == null || start == null) { call.reject("Faltan datos del evento"); return; }
        if (end == null || end <= start) end = start + 60L * 60L * 1000L;
        long calId = pickCalendar(call.getString("calendar", ""));
        if (calId < 0) { call.reject("No encontré un calendario editable"); return; }
        ContentResolver cr = getContext().getContentResolver();
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, title);
        v.put(CalendarContract.Events.DTSTART, start);
        v.put(CalendarContract.Events.DTEND, end);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        String loc = call.getString("location");
        if (loc != null) v.put(CalendarContract.Events.EVENT_LOCATION, loc);
        String desc = call.getString("description");
        if (desc != null) v.put(CalendarContract.Events.DESCRIPTION, desc);
        try {
            Uri uri = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (uri == null) { call.reject("No se pudo crear el evento"); return; }
            long eventId = Long.parseLong(uri.getLastPathSegment());
            Integer rem = call.getInt("reminder", 15);
            if (rem != null && rem >= 0) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, eventId);
                r.put(CalendarContract.Reminders.MINUTES, rem);
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
            JSObject o = new JSObject();
            o.put("id", eventId);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void updateEvent(PluginCall call) {
        if (!need(call, "calendar", "calendario") || !unlocked(call)) return;
        Long id = call.getLong("id");
        if (id == null) { call.reject("Falta el evento"); return; }
        ContentValues v = new ContentValues();
        String title = call.getString("title");
        if (title != null) v.put(CalendarContract.Events.TITLE, title);
        Long start = call.getLong("start");
        Long end = call.getLong("end");
        if (start != null) {
            v.put(CalendarContract.Events.DTSTART, start);
            if (end == null) {
                // conservar la duración original
                try (Cursor c = getContext().getContentResolver().query(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                        new String[] { CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND }, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(1)) end = start + (c.getLong(1) - c.getLong(0));
                }
            }
        }
        if (end != null) v.put(CalendarContract.Events.DTEND, end);
        String loc = call.getString("location");
        if (loc != null) v.put(CalendarContract.Events.EVENT_LOCATION, loc);
        if (v.size() == 0) { call.reject("No hay cambios"); return; }
        try {
            int n = getContext().getContentResolver().update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null);
            if (n == 0) call.reject("No encontré ese evento");
            else call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void deleteEvent(PluginCall call) {
        if (!need(call, "calendar", "calendario") || !unlocked(call)) return;
        Long id = call.getLong("id");
        if (id == null) { call.reject("Falta el evento"); return; }
        try {
            int n = getContext().getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null);
            if (n == 0) call.reject("No encontré ese evento");
            else call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /* ---------------- Contactos ---------------- */

    private String whatsappNumber(long contactId) {
        String[] proj = { ContactsContract.Data.DATA1 };
        String sel = ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?";
        try (Cursor c = getContext().getContentResolver().query(ContactsContract.Data.CONTENT_URI, proj, sel,
                new String[] { String.valueOf(contactId), "vnd.android.cursor.item/vnd.com.whatsapp.profile" }, null)) {
            if (c != null && c.moveToFirst()) {
                String jid = c.getString(0);
                if (jid != null && jid.contains("@")) return jid.substring(0, jid.indexOf('@'));
            }
        } catch (Exception ignored) {}
        return null;
    }

    @PluginMethod
    public void searchContacts(PluginCall call) {
        if (!need(call, "contacts", "contactos") || !unlocked(call)) return;
        String q = call.getString("query", "");
        if (q == null || q.trim().isEmpty()) { call.reject("Falta el nombre"); return; }
        JSArray arr = new JSArray();
        Set<String> seen = new HashSet<>();
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q.trim()));
        String[] proj = {
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        };
        try (Cursor c = getContext().getContentResolver().query(uri, proj, null, null, null)) {
            int n = 0;
            while (c != null && c.moveToNext() && n < 8) {
                String num = c.getString(2);
                String key = c.getLong(0) + "|" + (num == null ? "" : num.replaceAll("\\D", ""));
                if (!seen.add(key)) continue;
                n++;
                JSObject o = new JSObject();
                o.put("nombre", c.getString(1));
                o.put("telefono", num);
                o.put("tipo", String.valueOf(ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    getContext().getResources(), c.getInt(3), c.getString(4))));
                String wa = whatsappNumber(c.getLong(0));
                if (wa != null) o.put("whatsapp", wa);
                arr.put(o);
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
            return;
        }
        // correos
        JSArray mails = new JSArray();
        Uri euri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(q.trim()));
        try (Cursor c = getContext().getContentResolver().query(euri,
                new String[] { ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS },
                null, null, null)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 5) {
                JSObject o = new JSObject();
                o.put("nombre", c.getString(0));
                o.put("email", c.getString(1));
                mails.put(o);
            }
        } catch (Exception ignored) {}
        JSObject r = new JSObject();
        r.put("contactos", arr);
        r.put("correos", mails);
        call.resolve(r);
    }

    /* ---------------- Notificaciones y respuestas ---------------- */

    @PluginMethod
    public void notifications(PluginCall call) {
        if (!unlocked(call)) return;
        if (!NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName())) {
            call.reject("Falta permitir el acceso a notificaciones. Actívelo en Protocolos → Asistente del teléfono.");
            return;
        }
        String filter = Blocklist.norm(call.getString("app", ""));
        JSArray arr = new JSArray();
        List<NotifListener.Item> list = NotifListener.snapshot();
        for (int i = list.size() - 1; i >= 0; i--) {
            NotifListener.Item it = list.get(i);
            if (!filter.isEmpty() && !Blocklist.norm(it.app).contains(filter) && !Blocklist.norm(it.pkg).contains(filter)) continue;
            JSObject o = new JSObject();
            o.put("id", it.id.substring(it.id.lastIndexOf('#') + 1));
            o.put("app", it.app);
            o.put("de", it.title);
            o.put("texto", it.text);
            o.put("hora", it.time);
            o.put("sePuedeResponder", it.reply != null);
            arr.put(o);
            if (arr.length() >= 20) break;
        }
        JSObject r = new JSObject();
        r.put("notificaciones", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void reply(PluginCall call) {
        if (!unlocked(call)) return;
        String id = call.getString("id");
        String text = call.getString("text");
        if (id == null || text == null || text.isEmpty()) { call.reject("Faltan datos"); return; }
        NotifListener.Item it = NotifListener.find(id);
        if (it == null) { call.reject("Ese mensaje ya no está en las notificaciones"); return; }
        if (it.reply == null) { call.reject("Esa notificación no permite responder"); return; }
        if (Blocklist.isBlocked(getContext(), it.pkg)) { call.reject("App bloqueada"); return; }
        try {
            Intent fill = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput ri : it.inputs) results.putCharSequence(ri.getResultKey(), text);
            RemoteInput.addResultsToIntent(it.inputs, fill, results);
            it.reply.actionIntent.send(getContext(), 0, fill);
            call.resolve();
        } catch (PendingIntent.CanceledException e) {
            call.reject("La notificación ya no está disponible");
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /* ---------------- Mensajes, llamadas y correo ---------------- */

    private static String waDigits(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        boolean intl = phone != null && phone.trim().startsWith("+");
        if (!intl) {
            if (d.startsWith("00")) d = d.substring(2);
            else if (d.startsWith("0")) d = d.substring(1);
        }
        if (d.startsWith("54")) {
            if (!d.startsWith("549") && d.length() == 12) d = "549" + d.substring(2);
            return d;
        }
        if (intl) return d;
        // Argentina: código de área + número (10 dígitos). Se quita el "15" de celular si está.
        if (d.length() == 12) {
            for (int a = 2; a <= 4; a++) {
                if (d.substring(a, a + 2).equals("15")) { d = d.substring(0, a) + d.substring(a + 2); break; }
            }
        }
        if (d.length() == 10) return "549" + d;
        return d;
    }

    @PluginMethod
    public void whatsapp(PluginCall call) {
        if (!unlocked(call)) return;
        String phone = call.getString("phone");
        String text = call.getString("text", "");
        if (phone == null) { call.reject("Falta el número"); return; }
        String digits = waDigits(phone);
        Uri uri = Uri.parse("https://api.whatsapp.com/send?phone=" + digits + "&text=" + Uri.encode(text == null ? "" : text));
        for (String pkg : new String[] { "com.whatsapp", "com.whatsapp.w4b" }) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage(pkg);
                launch(i);
                call.resolve();
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        call.reject("WhatsApp no está instalado");
    }

    @PluginMethod
    public void sms(PluginCall call) {
        if (!need(call, "sms", "SMS") || !unlocked(call)) return;
        String phone = call.getString("phone");
        String text = call.getString("text");
        if (phone == null || text == null) { call.reject("Faltan datos"); return; }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getContext().getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(text);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void dial(PluginCall call) {
        if (!need(call, "phone", "llamadas") || !unlocked(call)) return;
        String phone = call.getString("phone");
        if (phone == null) { call.reject("Falta el número"); return; }
        try {
            launch(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(phone))));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void email(PluginCall call) {
        if (!unlocked(call)) return;
        String to = call.getString("to", "");
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        if (to != null && !to.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[] { to });
        i.putExtra(Intent.EXTRA_SUBJECT, call.getString("subject", ""));
        i.putExtra(Intent.EXTRA_TEXT, call.getString("body", ""));
        try {
            launch(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No hay una app de correo instalada");
        }
    }

    /* ---------------- Alarmas, apps, mapas y música ---------------- */

    @PluginMethod
    public void alarm(PluginCall call) {
        Integer h = call.getInt("hour");
        Integer m = call.getInt("minute", 0);
        if (h == null) { call.reject("Falta la hora"); return; }
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, h);
        i.putExtra(AlarmClock.EXTRA_MINUTES, m == null ? 0 : m);
        String label = call.getString("label");
        if (label != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        try {
            launch(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No hay una app de reloj compatible");
        }
    }

    @PluginMethod
    public void timer(PluginCall call) {
        Integer s = call.getInt("seconds");
        if (s == null || s <= 0) { call.reject("Falta la duración"); return; }
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
        i.putExtra(AlarmClock.EXTRA_LENGTH, s);
        String label = call.getString("label");
        if (label != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        try {
            launch(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No hay una app de reloj compatible");
        }
    }

    @PluginMethod
    public void openApp(PluginCall call) {
        if (!unlocked(call)) return;
        String want = Blocklist.norm(call.getString("name", ""));
        if (want.isEmpty()) { call.reject("Falta el nombre de la app"); return; }
        if (Blocklist.isBlockedLabel(want)) { call.reject("Por seguridad, no abro apps bancarias ni billeteras."); return; }
        PackageManager pm = getContext().getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
        ResolveInfo exact = null, partial = null;
        for (ResolveInfo ri : apps) {
            String label = Blocklist.norm(String.valueOf(ri.loadLabel(pm)));
            if (label.equals(want)) { exact = ri; break; }
            if (partial == null && (label.contains(want) || want.contains(label))) partial = ri;
        }
        ResolveInfo hit = exact != null ? exact : partial;
        if (hit == null) { call.reject("No encontré esa app"); return; }
        String pkg = hit.activityInfo.packageName;
        if (Blocklist.isBlocked(getContext(), pkg)) { call.reject("Por seguridad, no abro apps bancarias ni billeteras."); return; }
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { call.reject("No se puede abrir esa app"); return; }
        launch(i);
        JSObject o = new JSObject();
        o.put("app", String.valueOf(hit.loadLabel(pm)));
        call.resolve(o);
    }

    @PluginMethod
    public void navigate(PluginCall call) {
        if (!unlocked(call)) return;
        String dest = call.getString("destination");
        if (dest == null) { call.reject("Falta el destino"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)));
            i.setPackage("com.google.android.apps.maps");
            launch(i);
        } catch (ActivityNotFoundException e) {
            launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))));
        }
        call.resolve();
    }

    @PluginMethod
    public void music(PluginCall call) {
        String q = call.getString("query", "");
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.putExtra(SearchManager.QUERY, q);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        try {
            launch(i);
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No hay una app de música compatible");
        }
    }
}
