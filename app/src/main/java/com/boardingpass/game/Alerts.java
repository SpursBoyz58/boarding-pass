package com.boardingpass.game;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Local notifications scheduled by the game (no server needed): the page works out when your plane
 * will reach each coin and hands the times over here; Android shows the alert even if the app is closed.
 */
public class Alerts {
    static final String CHANNEL = "coins";
    private static final String PREFS = "alerts";
    private final Context ctx;

    Alerts(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    static void createChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Coins ahead", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("A heads-up 2 minutes before your plane reaches coins on its route");
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    /** json: [{"id":123,"at":epochMillis,"title":"...","body":"..."}] — replaces anything scheduled before. */
    @JavascriptInterface
    public void schedule(String json) {
        cancelAll();
        try {
            JSONArray arr = new JSONArray(json);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Set<String> ids = new HashSet<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.getLong("at");
                if (at <= now) continue;
                int id = o.getInt("id");
                PendingIntent pi = pending(id, o.optString("title"), o.optString("body"));
                boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                ids.add(String.valueOf(id));
            }
            prefs().edit().putStringSet("ids", ids).apply();
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void cancelAll() {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (String s : prefs().getStringSet("ids", new HashSet<>())) {
            int id = Integer.parseInt(s);
            am.cancel(pending(id, "", ""));
        }
        prefs().edit().remove("ids").apply();
    }

    @JavascriptInterface
    public boolean available() { return true; }

    private PendingIntent pending(int id, String title, String body) {
        Intent i = new Intent(ctx, CoinAlarmReceiver.class);
        i.putExtra("id", id).putExtra("title", title).putExtra("body", body);
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void forget(Context ctx, int id) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> ids = new HashSet<>(p.getStringSet("ids", new HashSet<>()));
        ids.remove(String.valueOf(id));
        p.edit().putStringSet("ids", ids).apply();
    }
}
