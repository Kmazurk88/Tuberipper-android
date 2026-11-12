package app.tuberipper.player;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Problem reports without any third-party service: crashes are written to a small file as they happen
 *  (uncaught-exception handler), Android's own record of why it ended the app is read at the next launch
 *  (ApplicationExitInfo, Android 11+), and both are sent to the TubeRipper server (POST /api/app/diag), which keeps
 *  them and tells Discord. Reports go only to the server the app is already using, with its sign-in cookie.
 *  What is sent: the stack trace, the app and Android version, the phone model, which kind of address was in use,
 *  and the last lines of the app's own log. Never the address, the cookie or anything you played. */
final class Diagnostics {
    private Diagnostics() { }

    private static DiagLog log;
    private static Context appContext;
    private static volatile String where = "unknown";

    /** Once, from Application.onCreate: the crash handler and the log. */
    static synchronized void install(Context c) {
        if (log != null) return;
        appContext = c.getApplicationContext();
        log = new DiagLog(new java.io.File(appContext.getFilesDir(), "diag"));
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                log.saveCrash(System.currentTimeMillis(), thread.getName(), sw.toString(), PlaybackService.playing, where);
                log.note("CRASH on " + thread.getName() + ": " + error);
            } catch (Throwable ignored) { /* the original handler must still run */ }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        note("start " + BuildConfig.VERSION_NAME + " android " + Build.VERSION.SDK_INT);
    }

    /** One line in the app's log. Labels and numbers only: never addresses, cookies or titles. */
    static void note(String line) {
        DiagLog l = log;
        if (l != null) l.note(line);
    }

    /** Which kind of address the app is on now ("home", "tailscale", "public"), for the log and for crash reports. */
    static void setAddress(String base) {
        String w = DiagLog.whereOf(base);
        if (!w.equals(where)) note("address " + w);
        where = w;
    }

    interface Result { void done(boolean ok, String message); }

    /** On launch, after the first page loaded: sends crashes and abnormal exits found since last time, if there are any.
     *  Sends nothing when all is well. */
    static void sendIfAny(final String base) {
        run(base, null, false, null);
    }

    /** "Send diagnostics" in the menu: always sends (with the log), and says how it went. */
    static void sendNow(final String base, final String note, final Result result) {
        run(base, note == null ? "" : note, true, result);
    }

    private static void run(final String base, final String note, final boolean manual, final Result result) {
        final DiagLog l = log;
        if (l == null || base == null) {
            if (result != null) result.done(false, "Not ready yet. Try again in a moment.");
            return;
        }
        new Thread(() -> {
            String message = "Couldn’t send diagnostics.";
            boolean ok = false;
            try {
                DiagLog.Pending pending = l.pending();
                long[] newest = {0};
                JSONArray exits = abnormalExits(newest);
                if (!manual && pending.crashes.isEmpty() && exits.length() == 0) { remember(newest[0]); return; }
                String kind = manual ? "manual" : pending.crashes.isEmpty() ? "exit" : "crash";
                JSONObject body = DiagLog.payload(kind, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                        Build.MANUFACTURER + " " + Build.MODEL, note, pending.crashes, exits, l.ringTail(16000));
                String ref = post(base, body);
                l.clear(pending);
                remember(newest[0]);
                ok = true;
                message = "Diagnostics sent. Ref " + ref + ".";
            } catch (java.io.IOException e) {
                message = "Couldn’t reach the server (" + e.getMessage() + ").";
            } catch (Exception e) {
                message = "Couldn’t send diagnostics.";
            } finally {
                if (result != null) result.done(ok, message);
            }
        }, "diag-send").start();
    }

    /** POSTs the report; returns the server's reference (letters, digits, - and _ only). */
    private static String post(String base, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "api/app/diag").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Content-Type", "application/json");
        String cookie = CookieManager.getInstance().getCookie(base);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        int code = c.getResponseCode();
        if (code == 429) throw new java.io.IOException("too many reports, try later");
        if (code == 401 || code == 302) throw new java.io.IOException("not signed in");
        if (code != 200) throw new java.io.IOException("server answered " + code);
        String ref = new JSONObject(ServerPicker.readAll(c)).optString("ref");
        return ref.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static SharedPreferences prefs() { return appContext.getSharedPreferences("diag", Context.MODE_PRIVATE); }

    private static void remember(long newestExit) {
        if (newestExit > 0) prefs().edit().putLong("exit_ts", newestExit).apply();
    }

    /** Why Android ended this app's process since the last report, when that was not a normal exit. newest[0] gets the
     *  time of the newest exit seen (normal ones too), so each is looked at once. Android 11+; empty before that. */
    private static JSONArray abnormalExits(long[] newest) {
        JSONArray out = new JSONArray();
        if (Build.VERSION.SDK_INT < 30) return out;
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 10);
            long since = prefs().getLong("exit_ts", 0);
            for (ApplicationExitInfo i : list) {
                if (i.getTimestamp() <= since) continue;
                newest[0] = Math.max(newest[0], i.getTimestamp());
                String reason = reasonName(i.getReason());
                if (reason == null) continue;
                out.put(new JSONObject().put("ts", i.getTimestamp()).put("reason", reason).put("status", i.getStatus())
                        .put("importance", i.getImportance()).put("description", i.getDescription() == null ? "" : i.getDescription())
                        .put("rss_kb", i.getRss()));
            }
        } catch (Exception ignored) { /* best effort */ }
        return out;
    }

    /** null for the normal ways an app ends (the user closed it, it exited by itself, the user stopped it...). */
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case 14: return "FREEZER";   // REASON_FREEZER (Android 12+): the app was frozen in the background, then ended
            default: return null;
        }
    }
}
