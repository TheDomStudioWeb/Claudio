package com.thedomstudio.jarvis;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee las notificaciones entrantes (excepto bancos y billeteras) para que Claudio
 * pueda contarlas y, cuando la app lo permite, responderlas desde la propia notificación.
 */
public class NotifListener extends NotificationListenerService {

    static final class Item {
        String id;
        String pkg;
        String app;
        String title;
        String text;
        long time;
        Notification.Action reply;
        RemoteInput[] inputs;
    }

    private static final int MAX = 40;
    private static final List<Item> items = new ArrayList<>();
    private static int seq = 0;
    static volatile boolean connected = false;

    @Override
    public void onListenerConnected() {
        connected = true;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) add(sbn);
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        add(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        synchronized (items) {
            String key = sbn.getKey();
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).id.startsWith(key + "#")) items.remove(i);
            }
        }
    }

    private void add(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName())) return;
            if (Blocklist.isBlocked(this, pkg)) return; // bancos y billeteras: nunca se leen
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            Bundle ex = n.extras;
            if (ex == null) return;
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) return;

            Item it = new Item();
            it.id = sbn.getKey() + "#" + (++seq);
            it.pkg = pkg;
            it.app = appLabel(pkg);
            it.title = title == null ? "" : title.toString();
            it.text = text == null ? "" : text.toString();
            if (it.text.length() > 500) it.text = it.text.substring(0, 500) + "…";
            it.time = sbn.getPostTime();
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    RemoteInput[] ri = a.getRemoteInputs();
                    if (ri != null && ri.length > 0) {
                        it.reply = a;
                        it.inputs = ri;
                        break;
                    }
                }
            }
            synchronized (items) {
                // Una sola entrada por conversación: la más reciente reemplaza a la anterior.
                String key = sbn.getKey() + "#";
                for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).id.startsWith(key)) items.remove(i);
                items.add(it);
                while (items.size() > MAX) items.remove(0);
            }
        } catch (Exception ignored) {}
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    static List<Item> snapshot() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    static Item find(String id) {
        synchronized (items) {
            for (Item it : items) if (it.id.equals(id) || it.id.endsWith("#" + id)) return it;
        }
        return null;
    }
}
