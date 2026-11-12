package app.tuberipper.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The app's own small record of what happened, kept in a folder of its own: a rolling text log (the last ~24 KB)
 *  and one JSON file per crash that has not been sent yet. Used by Diagnostics, which sends them to the server. No
 *  Android classes are used here, so it can be tested on a plain JVM. Everything is best-effort: a problem writing
 *  the log must never become a problem for the app. */
final class DiagLog {
    static final int RING_KEEP = 24 * 1024;     // bytes of log kept
    static final int MAX_CRASHES = 5;           // unsent crash files kept (the newest)
    static final int STACK_MAX = 6000;          // characters of one stack trace (the server cuts at the same size)

    private final File dir;
    private final File ring;
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private int crashCounter = 0;

    DiagLog(File dir) {
        this.dir = dir;
        this.ring = new File(dir, "ring.log");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Adds one line. When the file has grown to twice RING_KEEP, the oldest half goes (cut at a line start). */
    synchronized void note(String line) {
        try {
            String text = stamp.format(new Date()) + " " + line.replace('\n', ' ').replace('\r', ' ') + "\n";
            try (FileOutputStream out = new FileOutputStream(ring, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (ring.length() > 2L * RING_KEEP) trim();
        } catch (IOException | RuntimeException ignored) { /* best effort */ }
    }

    private void trim() throws IOException {
        byte[] all = readAll(ring);
        int from = Math.max(0, all.length - RING_KEEP);
        if (from > 0) while (from < all.length && all[from - 1] != '\n') from++;   // start on a line
        try (FileOutputStream out = new FileOutputStream(ring, false)) {
            out.write(all, from, all.length - from);
        }
    }

    /** The end of the log, at most maxChars characters, starting on a line. */
    synchronized String ringTail(int maxChars) {
        try {
            String all = new String(readAll(ring), StandardCharsets.UTF_8);
            if (all.length() <= maxChars) return all;
            String cut = all.substring(all.length() - maxChars);
            int nl = cut.indexOf('\n');
            return nl >= 0 ? cut.substring(nl + 1) : cut;
        } catch (IOException e) {
            return "";
        }
    }

    /** Saves one crash: {ts, thread, stack, playing, where}. Written straight away (the process is about to die). */
    synchronized void saveCrash(long ts, String thread, String stack, boolean playing, String where) {
        try {
            JSONObject o = new JSONObject().put("ts", ts).put("thread", thread).put("playing", playing).put("where", where)
                    .put("stack", stack.length() > STACK_MAX ? stack.substring(0, STACK_MAX) : stack);
            File f = new File(dir, String.format(Locale.US, "crash-%013d-%d.json", ts, crashCounter++));
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            File[] all = crashFiles();
            for (int i = 0; i < all.length - MAX_CRASHES; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        } catch (IOException | JSONException | RuntimeException ignored) { /* best effort */ }
    }

    private File[] crashFiles() {
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash-") && name.endsWith(".json"));
        if (files == null) return new File[0];
        Arrays.sort(files);   // the names start with the time: oldest first
        return files;
    }

    /** The unsent crashes (oldest first) and their files, so they can be deleted once the server has them. */
    static final class Pending {
        final List<JSONObject> crashes = new ArrayList<>();
        final List<File> files = new ArrayList<>();
    }

    synchronized Pending pending() {
        Pending p = new Pending();
        for (File f : crashFiles()) {
            try {
                p.crashes.add(new JSONObject(new String(readAll(f), StandardCharsets.UTF_8)));
                p.files.add(f);
            } catch (IOException | JSONException e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();   // unreadable: not worth keeping
            }
        }
        return p;
    }

    synchronized void clear(Pending p) {
        for (File f : p.files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The body of POST /api/app/diag. kind: crash | exit | manual. */
    static JSONObject payload(String kind, String appName, int appCode, int android, String model, String note,
                              List<JSONObject> crashes, JSONArray exits, String log) throws JSONException {
        JSONArray c = new JSONArray();
        for (JSONObject o : crashes) c.put(o);
        return new JSONObject().put("kind", kind)
                .put("app", new JSONObject().put("name", appName).put("code", appCode))
                .put("device", new JSONObject().put("android", android).put("model", model))
                .put("note", note == null ? "" : note)
                .put("crashes", c).put("exits", exits == null ? new JSONArray() : exits).put("log", log == null ? "" : log);
    }

    /** "home", "tailscale" or "public": which kind of address the app was on (a label only; the address itself is
     *  never written down). */
    static String whereOf(String base) {
        try {
            String host = new URI(base).getHost();
            if (host == null) return "unknown";
            if (host.startsWith("100.")) return "tailscale";
            if (host.startsWith("192.168.") || host.startsWith("10.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")) return "home";
            return "public";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static byte[] readAll(File f) throws IOException {
        if (!f.isFile()) return new byte[0];
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
