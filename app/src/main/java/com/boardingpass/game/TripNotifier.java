package com.boardingpass.game;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A pinned "current flight" notification, like a ride-share trip card: it sits on the lock screen and in the
 * shade from booking until landing, with a live countdown and a progress bar. The game hands over the trip;
 * an alarm refreshes it every couple of minutes so it stays current with the app closed.
 */
public class TripNotifier {
    static final String CHANNEL = "trip";
    static final int ID = 7001;
    private static final String PREFS = "trip";
    private final Context ctx;

    TripNotifier(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    static void createChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Current flight", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Your flight's status on the lock screen, from booking until landing");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    /** json: {key,id,airline,from,to,fromCity,toCity,dep,arr,gate,seat,type,fromTz,toTz,phase?,alt?} */
    @JavascriptInterface
    public void show(String json) {
        try {
            JSONObject o = new JSONObject(json);
            prefs(ctx).edit().putString("trip", json).apply();
            post(ctx, o);
            scheduleNext(ctx);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void clear() { clearAll(ctx); }

    static void clearAll(Context ctx) {
        prefs(ctx).edit().remove("trip").apply();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(refreshIntent(ctx));
    }

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static String clock(long ms, String tz) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (tz != null && !tz.isEmpty()) f.setTimeZone(TimeZone.getTimeZone(tz));
        return f.format(new Date(ms));
    }

    static void post(Context ctx, JSONObject o) {
        long now = System.currentTimeMillis(), dep = o.optLong("dep"), arr = o.optLong("arr");
        String id = o.optString("id"), from = o.optString("from"), to = o.optString("to");
        String fromCity = o.optString("fromCity"), toCity = o.optString("toCity");
        String gate = o.optString("gate"), seat = o.optString("seat");
        String depT = clock(dep, o.optString("fromTz")), arrT = clock(arr, o.optString("toTz"));
        String route = fromCity + " → " + toCity;

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setColor(0xFF1E7BE6)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx));

        boolean landed = now >= arr;
        if (landed) {
            // the trip is over: a normal notification the player can swipe away
            b.setOngoing(false).setAutoCancel(true)
             .setContentTitle("Landed in " + toCity)
             .setContentText(id + " · Open Boarding Pass to collect your stamp")
             .setSubText(from + " → " + to);
        } else if (now < dep) {
            long mins = (dep - now) / 60000;
            String status = mins > 40 ? "Boarding opens " + clock(dep - 40 * 60000L, o.optString("fromTz"))
                    : mins > 10 ? "Boarding now · gate closes " + clock(dep - 10 * 60000L, o.optString("fromTz"))
                    : "Doors closing";
            b.setOngoing(true)
             .setContentTitle(id + " · " + route)
             .setContentText(status + " · Gate " + gate + " · Seat " + seat)
             .setStyle(new Notification.BigTextStyle().bigText(
                     status + "\nGate " + gate + " · Seat " + seat + "\nDeparts " + depT + " · Arrives " + arrT + " local"))
             .setSubText("Departs in")
             .setWhen(dep).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        } else {
            int pct = (int) Math.max(1, Math.min(99, 100 * (now - dep) / Math.max(1, arr - dep)));
            String phase = o.optString("phase", "In the air");
            if (o.optLong("at") < now - 4 * 60000L) phase = "In the air"; // older than the app's last update
            int alt = o.optInt("alt", 0);
            String line = phase + (alt > 0 && phase.startsWith("Cruis") ? " at " + String.format(Locale.US, "%,d", alt) + " ft" : "");
            b.setOngoing(true)
             .setContentTitle(id + " · " + route)
             .setContentText(line + " · Lands " + arrT)
             .setProgress(100, pct, false)
             .setSubText("Lands in")
             .setWhen(arr).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, b.build());
    }

    static void scheduleNext(Context ctx) {
        String json = prefs(ctx).getString("trip", null);
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            long now = System.currentTimeMillis(), dep = o.optLong("dep"), arr = o.optLong("arr");
            if (now >= arr) { prefs(ctx).edit().remove("trip").apply(); return; }
            long next = now + 2 * 60000L;
            if (now < dep && next > dep) next = dep + 1000;      // flip to "in the air" right at take-off
            if (now < arr && next > arr) next = arr + 1000;      // and to "landed" right at landing
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, refreshIntent(ctx));
        } catch (Exception ignored) { }
    }

    private static PendingIntent refreshIntent(Context ctx) {
        Intent i = new Intent(ctx, Refresh.class);
        return PendingIntent.getBroadcast(ctx, ID, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context ctx) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Alarm target: redraw the trip card from the saved trip, then book the next refresh. */
    public static class Refresh extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String json = prefs(ctx).getString("trip", null);
            if (json == null) return;
            try { post(ctx, new JSONObject(json)); } catch (Exception ignored) { }
            scheduleNext(ctx);
        }
    }
}
