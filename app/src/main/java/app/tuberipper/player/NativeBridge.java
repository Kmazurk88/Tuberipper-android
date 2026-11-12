package app.tuberipper.player;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** What the web page calls (window.TRNative) to tell the service what's playing. */
public class NativeBridge {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    NativeBridge(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    @JavascriptInterface
    public void update(String json) {
        main.post(() -> {
            try {
                boolean playing = new JSONObject(json).optBoolean("playing");
                PlaybackService svc = PlaybackService.instance;
                if (svc != null) { svc.update(json); return; }
                if (!playing) return; // don't start a notification just because a saved queue was restored
                Intent i = new Intent(ctx, PlaybackService.class).setAction(PlaybackService.ACTION_UPDATE).putExtra("json", json);
                ctx.startForegroundService(i);
            } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface
    public void changeServer() {
        MainActivity a = MainActivity.current();
        if (a != null) a.showServerDialog(false);
    }

    // ----- Offline listening (see Offline.java). All of these are cheap and safe to call from the page. -----
    @JavascriptInterface public boolean offlineSupported() { return true; }
    @JavascriptInterface public String dlStart(String json) { return Offline.get(ctx).start(json); }
    @JavascriptInterface public String dlList() { return Offline.get(ctx).list(); }
    @JavascriptInterface public void dlRemove(String id) { Offline.get(ctx).remove(id); }
    @JavascriptInterface public void dlRemoveAll() { Offline.get(ctx).removeAll(); }
    @JavascriptInterface public String dlUrl(String id) { return Offline.get(ctx).url(id); }
    @JavascriptInterface public long dlUsage() { return Offline.get(ctx).usage(); }
    @JavascriptInterface public boolean dlCellular() { return Offline.get(ctx).cellular(); }
    @JavascriptInterface public void dlSetCellular(boolean on) { Offline.get(ctx).setCellular(on); }

    /** True when this phone's own Wi-Fi sits on the same /24 as the server address (i.e. really at home, not
     *  reaching the home address through Tailscale's subnet route from somewhere else). */
    @JavascriptInterface
    public boolean onHomeWifi(String host) {
        try {
            byte[] want = java.net.InetAddress.getByName(host).getAddress();
            if (want.length != 4) return false;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    byte[] a = la.getAddress().getAddress();
                    if (a.length == 4 && a[0] == want[0] && a[1] == want[1] && a[2] == want[2]) return true;
                }
            }
        } catch (Exception e) { /* unknown: treated as not home */ }
        return false;
    }

    /** A short haptic tick/confirm on the phone (no permission needed). */
    @JavascriptInterface
    public void haptic(String kind) {
        main.post(() -> {
            MainActivity a = MainActivity.current();
            if (a == null) return;
            int c = android.os.Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.KEYBOARD_TAP;
            a.getWindow().getDecorView().performHapticFeedback(c);
        });
    }

    /** Free space on the phone's storage, in bytes (for the downloads meter). */
    @JavascriptInterface
    public long freeBytes() {
        try { return new android.os.StatFs(ctx.getExternalFilesDir(null).getPath()).getAvailableBytes(); } catch (Exception e) { return 0; }
    }

    /** "Send diagnostics" in the menu. Answers later through window.TRNative_diag(ok, message) in the page. */
    @JavascriptInterface
    public void sendDiagnostics(String note) {
        MainActivity a = MainActivity.current();
        final String base = a == null ? null : a.currentAddress();
        Diagnostics.sendNow(base, note, (ok, message) -> {
            final android.webkit.WebView w = MainActivity.page();
            if (w == null) return;
            final String js = "window.TRNative_diag&&window.TRNative_diag(" + ok + ",'" + message.replace("\\", "").replace("'", "").replace("\n", " ") + "')";
            w.post(() -> w.evaluateJavascript(js, null));
        });
    }

    @JavascriptInterface
    public void clear() {
        main.post(() -> PlaybackService.stop(ctx));
    }
}
