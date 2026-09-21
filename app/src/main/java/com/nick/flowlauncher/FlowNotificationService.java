package com.nick.flowlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class FlowNotificationService extends NotificationListenerService {
    public static final int MEDIA_PREVIOUS = 0;
    public static final int MEDIA_PLAY_PAUSE = 1;
    public static final int MEDIA_NEXT = 2;

    private static final Object LOCK = new Object();
    private static final Map<String, FlowNotificationInfo> NOTIFICATIONS = new HashMap<>();
    private static TidalMediaState tidalState = TidalMediaState.empty();
    private static PendingIntent tidalContentIntent;
    private static PendingIntent tidalPrevious;
    private static PendingIntent tidalPlayPause;
    private static PendingIntent tidalNext;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        rebuild();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        rebuild();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        rebuild();
    }

    private void rebuild() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            return;
        }
        if (active == null) return;

        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, String> texts = new HashMap<>();

        TidalMediaState nextTidal = TidalMediaState.empty();
        PendingIntent nextContent = null;
        PendingIntent previous = null;
        PendingIntent playPause = null;
        PendingIntent next = null;

        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.getNotification() == null) continue;
            Notification n = sbn.getNotification();
            String pkg = sbn.getPackageName();
            Bundle extras = n.extras;
            String title = extras == null ? "" : stringValue(extras.get(Notification.EXTRA_TITLE));
            String text = extras == null ? "" : stringValue(extras.get(Notification.EXTRA_TEXT));

            if ("com.aspiro.tidal".equals(pkg)) {
                Bitmap art = null;
                try {
                    Icon icon = n.getLargeIcon();
                    if (icon != null) {
                        android.graphics.drawable.Drawable d = icon.loadDrawable(this);
                        if (d != null) {
                            int size = Math.max(1, Math.min(256, Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight())));
                            art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                            android.graphics.Canvas c = new android.graphics.Canvas(art);
                            d.setBounds(0, 0, size, size);
                            d.draw(c);
                        }
                    }
                } catch (Throwable ignored) {
                }

                boolean isPlaying = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                String artist = extras == null ? "" : stringValue(extras.get(Notification.EXTRA_SUB_TEXT));
                if (artist.isEmpty()) artist = text;

                nextTidal = new TidalMediaState(true, title, artist, isPlaying, art);
                nextContent = n.contentIntent;

                if (n.actions != null) {
                    for (Notification.Action action : n.actions) {
                        if (action == null || action.actionIntent == null) continue;
                        String actionTitle = action.title == null ? "" : action.title.toString().toLowerCase(Locale.ROOT);
                        if (actionTitle.contains("previous") || actionTitle.contains("vorige")) {
                            previous = action.actionIntent;
                        } else if (actionTitle.contains("next") || actionTitle.contains("volgende")) {
                            next = action.actionIntent;
                        } else if (actionTitle.contains("pause") || actionTitle.contains("play")
                                || actionTitle.contains("pauze") || actionTitle.contains("afspelen")) {
                            playPause = action.actionIntent;
                            if (actionTitle.contains("pause") || actionTitle.contains("pauze")) isPlaying = true;
                            if (actionTitle.contains("play") || actionTitle.contains("afspelen")) isPlaying = false;
                        }
                    }
                    nextTidal = new TidalMediaState(true, title, artist, isPlaying, art);
                }
                continue;
            }

            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;

            counts.put(pkg, counts.getOrDefault(pkg, 0) + 1);
            if (!title.isEmpty()) titles.put(pkg, title);
            if (!text.isEmpty()) texts.put(pkg, text);
        }

        synchronized (LOCK) {
            NOTIFICATIONS.clear();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String pkg = e.getKey();
                NOTIFICATIONS.put(pkg, new FlowNotificationInfo(
                        e.getValue(),
                        titles.getOrDefault(pkg, ""),
                        texts.getOrDefault(pkg, "")));
            }

            tidalState = nextTidal;
            tidalContentIntent = nextContent;
            tidalPrevious = previous;
            tidalPlayPause = playPause;
            tidalNext = next;
        }
    }

    public static Map<String, FlowNotificationInfo> notificationSnapshot() {
        synchronized (LOCK) {
            return new HashMap<>(NOTIFICATIONS);
        }
    }

    public static TidalMediaState tidalSnapshot() {
        synchronized (LOCK) {
            return tidalState == null ? TidalMediaState.empty() : tidalState;
        }
    }

    public static boolean sendTidalAction(int action) {
        PendingIntent target;
        synchronized (LOCK) {
            if (action == MEDIA_PREVIOUS) target = tidalPrevious;
            else if (action == MEDIA_NEXT) target = tidalNext;
            else target = tidalPlayPause;
        }
        if (target == null) return false;
        try {
            target.send();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean openTidal() {
        PendingIntent target;
        synchronized (LOCK) {
            target = tidalContentIntent;
        }
        if (target == null) return false;
        try {
            target.send();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
