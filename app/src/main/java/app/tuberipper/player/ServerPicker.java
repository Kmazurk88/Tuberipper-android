package app.tuberipper.player;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Chooses which address to use: the home-network one, the Tailscale one, or the public one (in that
 *  order). A home/Tailscale address is only trusted after it proves it is this server: the app sends a
 *  random challenge and checks the answer, which can only be computed with a secret id the app learned
 *  over the signed-in public address. So a different device with the same IP on another network is ignored. */
final class ServerPicker {
    private ServerPicker() { }

    static String readAll(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
            return out.toString("utf-8");
        }
    }

    static String hmacHex(String key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("utf-8"), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(msg.getBytes("utf-8"))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** True if `base` answers the challenge correctly, i.e. it is our server. A phone's first packet through
     *  Tailscale (waking the tunnel, or going via a relay) can take a few seconds, so it gets two tries. */
    static boolean isOurServer(String base, String id) {
        return proveOnce(base, id, 3500) || proveOnce(base, id, 3500);
    }

    private static boolean proveOnce(String base, String id, int timeoutMs) {
        try {
            byte[] raw = new byte[12];
            new SecureRandom().nextBytes(raw);
            StringBuilder nonce = new StringBuilder();
            for (byte b : raw) nonce.append(String.format("%02x", b));
            HttpURLConnection c = (HttpURLConnection) new URL(base + "api/instance?nonce=" + nonce).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return false;
            String proof = new JSONObject(readAll(c)).optString("proof");
            return proof.equals(hmacHex(id, nonce.toString()));
        } catch (Exception e) {
            return false;
        }
    }

    /** True if anything answers at `base` (used to see whether the current address still works). */
    static boolean answers(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "login").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            return c.getResponseCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** The best verified address, or `publicUrl` when none of the alternatives proves itself. */
    static String pick(String publicUrl, String id, List<String> alts) {
        if (id == null || alts.isEmpty()) return publicUrl;
        ExecutorService pool = Executors.newFixedThreadPool(alts.size());
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String alt : alts) results.add(pool.submit(() -> isOurServer(alt, id)));
            long deadline = System.currentTimeMillis() + 15000;   // the probes run side by side; a dead home address must not hide a slow Tailscale one
            for (int i = 0; i < alts.size(); i++) {
                try {
                    long left = Math.max(1, deadline - System.currentTimeMillis());
                    if (results.get(i).get(left, TimeUnit.MILLISECONDS)) return alts.get(i); // alts are in preference order
                } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
        return publicUrl;
    }

    /** Learns the server's id and alternative addresses from the signed-in public address. null on any problem. */
    static JSONObject learn(String publicUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(publicUrl + "api/instance").openConnection();
            String cookie = CookieManager.getInstance().getCookie(publicUrl);
            if (cookie == null) return null; // not signed in yet
            c.setRequestProperty("Cookie", cookie);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            JSONObject o = new JSONObject(readAll(c));
            return o.optString("id").isEmpty() ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> parseUrls(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                String u = a.getString(i).trim();
                if (u.startsWith("http://") || u.startsWith("https://")) out.add(u.endsWith("/") ? u : u + "/");
            }
        } catch (Exception ignored) { }
        return out;
    }
}
