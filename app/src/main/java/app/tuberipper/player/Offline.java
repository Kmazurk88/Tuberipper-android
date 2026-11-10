package app.tuberipper.player;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

/** Offline listening: downloads songs/episodes to this phone with Android's DownloadManager (background,
 *  resumable, shows progress in the notification shade) and plays them back through LocalFileServer, so the
 *  web page just sees a normal audio URL. Files and the list of what's downloaded live in the app's own
 *  storage and don't depend on which server address (home / Tailscale / internet) the app is using. */
final class Offline {
    private static Offline instance;

    static synchronized Offline get(Context c) {
        if (instance == null) instance = new Offline(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final DownloadManager dm;
    private final File dir;
    private LocalFileServer server;
    private JSONObject items;

    private Offline(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("offline", Context.MODE_PRIVATE);
        dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        File d = c.getExternalFilesDir("offline");
        dir = d != null ? d : new File(c.getFilesDir(), "offline");
        dir.mkdirs();
        try { items = new JSONObject(prefs.getString("items", "{}")); } catch (Exception e) { items = new JSONObject(); }
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format("%02x", b));
        server = new LocalFileServer(token.toString(), new LocalFileServer.Resolver() {
            @Override public File file(String key) { return new File(dir, key.startsWith("c/") ? key.substring(2) + ".jpg" : key.substring(2)); }
            @Override public String mime(String key) { return key.startsWith("c/") ? "image/jpeg" : mimeFor(key.substring(2)); }
        });
        try { server.start(); } catch (Exception e) { server = null; }
    }

    private synchronized void save() { prefs.edit().putString("items", items.toString()).apply(); }

    private String mimeFor(String id) {
        String suffix = "";
        try { suffix = items.getJSONObject(id).getJSONObject("song").optString("suffix", ""); } catch (Exception ignored) { }
        switch (suffix.toLowerCase()) {
            case "m4a": case "mp4": case "aac": case "m4b": return "audio/mp4";
            case "flac": return "audio/flac";
            case "ogg": case "opus": return "audio/ogg";
            case "wav": return "audio/wav";
            default: return "audio/mpeg";
        }
    }

    boolean cellular() { return prefs.getBoolean("cellular", false); }
    void setCellular(boolean on) { prefs.edit().putBoolean("cellular", on).apply(); }

    /** json: {id, url, coverUrl, song:{...}}. Returns "ok", "exists" or an error text. */
    synchronized String start(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String id = o.getString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) return "bad id";
            if (items.has(id) && !"failed".equals(statusOf(id).optString("status"))) return "exists";
            if (items.has(id)) removeFiles(id, items.getJSONObject(id).optLong("dlId", -1));
            String url = o.getString("url");
            JSONObject song = o.getJSONObject("song");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(song.optString("title", "Download"))
                    .setDescription(song.optString("artist", ""))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedNetworkTypes(cellular() ? (DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE) : DownloadManager.Request.NETWORK_WIFI)
                    .setDestinationInExternalFilesDir(ctx, "offline", id);
            String cookie = CookieManager.getInstance().getCookie(url); // the signed-in session, for the internet address
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            long dlId = dm.enqueue(req);
            JSONObject meta = new JSONObject().put("dlId", dlId).put("song", song).put("added", System.currentTimeMillis());
            items.put(id, meta);
            save();
            final String cover = o.optString("coverUrl", "");
            if (!cover.isEmpty()) fetchCover(id, cover);
            return "ok";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private void fetchCover(final String id, final String url) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(new File(dir, id + ".jpg"))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    /** {status: queued|running|waiting|done|failed, pct, bytes, total} for one item. */
    private JSONObject statusOf(String id) {
        JSONObject r = new JSONObject();
        try {
            long dlId = items.getJSONObject(id).optLong("dlId", -1);
            String status = "failed";
            long bytes = 0, total = 0;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(dlId))) {
                if (c != null && c.moveToFirst()) {
                    bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    switch (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        case DownloadManager.STATUS_SUCCESSFUL: status = "done"; break;
                        case DownloadManager.STATUS_RUNNING: status = "running"; break;
                        case DownloadManager.STATUS_PENDING: status = "queued"; break;
                        case DownloadManager.STATUS_PAUSED: status = "waiting"; break; // e.g. waiting for Wi-Fi
                        default: status = "failed";
                    }
                } else if (new File(dir, id).isFile()) {
                    status = "done"; // the download record was cleaned up but the file is there
                }
            }
            File f = new File(dir, id);
            if ("done".equals(status)) {
                if (!f.isFile() || f.length() == 0) status = "failed";
                else { bytes = total = f.length(); }
            }
            r.put("status", status).put("bytes", bytes).put("total", total).put("pct", total > 0 ? Math.min(100, bytes * 100 / total) : 0);
        } catch (Exception e) {
            try { r.put("status", "failed").put("pct", 0).put("bytes", 0).put("total", 0); } catch (Exception ignored) { }
        }
        return r;
    }

    synchronized String list() {
        JSONArray out = new JSONArray();
        java.util.Iterator<String> it = items.keys();
        while (it.hasNext()) {
            String id = it.next();
            try {
                JSONObject s = statusOf(id).put("id", id).put("song", items.getJSONObject(id).getJSONObject("song"))
                        .put("added", items.getJSONObject(id).optLong("added"));
                s.put("cover", new File(dir, id + ".jpg").isFile() && server != null ? server.base() + "c/" + id : "");
                out.put(s);
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    synchronized String url(String id) {
        if (server == null || !items.has(id) || !"done".equals(statusOf(id).optString("status"))) return "";
        return server.base() + "a/" + id;
    }

    private void removeFiles(String id, long dlId) {
        try { if (dlId >= 0) dm.remove(dlId); } catch (Exception ignored) { }
        new File(dir, id).delete();
        new File(dir, id + ".jpg").delete();
    }

    synchronized void remove(String id) {
        try {
            if (items.has(id)) removeFiles(id, items.getJSONObject(id).optLong("dlId", -1));
            items.remove(id);
            save();
        } catch (Exception ignored) { }
    }

    synchronized void removeAll() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Iterator<String> it = items.keys();
        while (it.hasNext()) ids.add(it.next());
        for (String id : ids) remove(id);
    }

    synchronized long usage() {
        long sum = 0;
        java.util.Iterator<String> it = items.keys();
        while (it.hasNext()) { String id = it.next(); sum += new File(dir, id).length() + new File(dir, id + ".jpg").length(); }
        return sum;
    }
}
