package com.gorev.asistani;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Web arayüzünden gelen hatırlatma listesini telefonun AlarmManager'ına kurar. */
final class Scheduler {
    static final String PREFS = "reminders";
    static final String KEY_LIST = "list";
    static final String KEY_IDS = "ids";
    static final String CHANNEL = "reminders";

    private Scheduler() {}

    static void sync(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, json).apply();
        scheduleAll(c);
    }

    /** Kayıtlı listeden tüm alarmları yeniden kurar (uygulama açılışı, güncelleme ve yeniden başlatma sonrası). */
    static void scheduleAll(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);

        String old = sp.getString(KEY_IDS, "");
        if (!old.isEmpty()) {
            for (String s : old.split(",")) {
                try { cancel(c, am, Integer.parseInt(s.trim())); } catch (Exception ignored) {}
            }
        }

        StringBuilder ids = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_LIST, "[]"));
            long now = System.currentTimeMillis();
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            for (int i = 0; i < arr.length() && i < 200; i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.getLong("at");
                if (at <= now) continue;
                int id = o.getInt("id");

                Intent in = new Intent(c, ReminderReceiver.class);
                in.putExtra("id", id);
                in.putExtra("title", o.optString("title"));
                in.putExtra("body", o.optString("body"));
                PendingIntent pi = PendingIntent.getBroadcast(
                        c, id, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                try {
                    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } catch (SecurityException e) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
        } catch (Exception ignored) {}
        sp.edit().putString(KEY_IDS, ids.toString()).apply();
    }

    private static void cancel(Context c, AlarmManager am, int id) {
        PendingIntent pi = PendingIntent.getBroadcast(
                c, id, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    /** Tanılama: N saniye sonra tek seferlik alarm kurar (uygulama kapalıyken gelip gelmediğini denemek için). */
    static void scheduleTest(Context c, int seconds) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent in = new Intent(c, ReminderReceiver.class);
        in.putExtra("id", 2);
        in.putExtra("title", "Test: uygulama kapalıyken geldi ✅");
        in.putExtra("body", "Telefonun alarm sistemi çalışıyor, hatırlatmalar gelecek.");
        PendingIntent pi = PendingIntent.getBroadcast(c, 2, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = System.currentTimeMillis() + Math.max(5, seconds) * 1000L;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Planlanan alarm sayısı ve sıradaki zaman (JSON). */
    static String info(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ids = sp.getString(KEY_IDS, "");
        int count = ids.isEmpty() ? 0 : ids.split(",").length;
        long next = 0;
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_LIST, "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                long at = arr.getJSONObject(i).getLong("at");
                if (at > now && (next == 0 || at < next)) next = at;
            }
        } catch (Exception ignored) {}
        return "{\"count\":" + count + ",\"next\":" + next + "}";
    }

    static void notifyNow(Context c, int id, String title, String body) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Görev hatırlatmaları", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (body == null) body = "";
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
