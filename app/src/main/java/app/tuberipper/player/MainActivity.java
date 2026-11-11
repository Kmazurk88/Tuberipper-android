package app.tuberipper.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Full-screen WebView around the TubeRipper web app. The page does the real work; this adds the
 *  things a browser tab can't: a foreground service for background audio (PlaybackService), a native
 *  media notification, the file picker for the Spotify CSV import, and a saved server address. */
public class MainActivity extends Activity {
    /** The running activity, for the service (lock-screen / notification buttons go into its page) and the bridge.
     *  A weak reference, so a finished activity and its WebView can be collected. */
    private static WeakReference<MainActivity> currentRef = new WeakReference<>(null);
    static MainActivity current() { return currentRef.get(); }
    /** The page of the running activity, or null. */
    static WebView page() { MainActivity a = current(); return a == null ? null : a.web; }

    private WebView web;
    private final List<Long> rendererDeaths = new ArrayList<>();

    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private static final int PICK_FILE = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String currentBase;          // the address currently loaded (home, Tailscale or public)
    private boolean errorRetried = false;
    private boolean needRepick = false;
    private long lastPick = 0;
    private boolean learnedThisRun = false;
    private int pickGeneration = 0;

    /** The public (internet) address the user typed. */
    private String serverUrl() { return prefs.getString("server", null); }

    private List<String> altUrls() { return ServerPicker.parseUrls(prefs.getString("alt_urls", "[]")); }

    private boolean isKnownHost(Uri u) {
        if (u.getHost() == null) return false;
        List<String> all = new ArrayList<>(altUrls());
        if (serverUrl() != null) all.add(serverUrl());
        for (String base : all) if (u.getHost().equals(Uri.parse(base).getHost())) return true;
        return false;
    }

    /** Picks the best address in the background (home, then Tailscale, then public) and loads it. If the
     *  app already shows a working address it only switches when a better one is available and nothing is playing. */
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private void pickAndLoad(final boolean force) {
        final String pub = serverUrl();
        if (pub == null) return;
        if (!online()) {
            // No network at all: show the app from the WebView's saved copy so downloaded music still opens.
            web.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            String base = currentBase != null ? currentBase : prefs.getString("last_base", pub);
            currentBase = base;
            web.loadUrl(base);
            return;
        }
        web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        final int gen = ++pickGeneration;
        final String id = prefs.getString("instance_id", null);
        final List<String> alts = altUrls();
        new Thread(() -> {
            String best = ServerPicker.pick(pub, id, alts);
            main.post(() -> {
                if (gen != pickGeneration || web == null) return;
                lastPick = System.currentTimeMillis();
                needRepick = false;
                if (!force && best.equals(currentBase)) { scheduleUpgradeCheck(best.equals(pub), alts); return; }
                if (!force && currentBase != null && !currentBase.equals(pub) && best.equals(pub)) {
                    // The probes found nothing better than the internet address, but we are on a home/Tailscale one.
                    // One slow answer must not drop a connection that works: only leave it if it really is gone.
                    final String keep = currentBase;
                    new Thread(() -> {
                        boolean works = ServerPicker.isOurServer(keep, id);
                        main.post(() -> {
                            if (gen != pickGeneration || web == null) return;
                            if (works) return;
                            if (PlaybackService.playing && ServerPicker.answers(keep)) return;
                            currentBase = best; web.loadUrl(best); scheduleUpgradeCheck(true, alts);
                        });
                    }).start();
                    return;
                }
                if (!force && currentBase != null && PlaybackService.playing) {
                    // Don't cut off a song for a nicer route unless the current one has stopped working.
                    new Thread(() -> {
                        boolean works = ServerPicker.answers(currentBase);
                        main.post(() -> { if (!works && gen == pickGeneration && web != null) { currentBase = best; web.loadUrl(best); } });
                    }).start();
                    return;
                }
                currentBase = best;
                web.loadUrl(best);
                scheduleUpgradeCheck(best.equals(pub), alts);
            });
        }).start();
    }

    /** While on the internet address although home/Tailscale addresses exist, look again every 30 s for a few minutes
     *  (Tailscale may simply not have been ready yet), so the app moves over by itself. */
    private void scheduleUpgradeCheck(boolean onPublic, List<String> alts) {
        main.removeCallbacks(upgradeCheck);
        if (!onPublic || alts.isEmpty()) { upgradeTries = 0; return; }
        if (upgradeTries++ < 10) main.postDelayed(upgradeCheck, 30000);
    }
    private int upgradeTries;
    private final Runnable upgradeCheck = () -> { if (web != null && serverUrl() != null) pickAndLoad(false); };

    /** After a successful load of the public address: remember the server's id and its home/Tailscale addresses. */
    private void learnServer() {
        final String pub = serverUrl();
        if (pub == null || learnedThisRun || currentBase == null || !currentBase.equals(pub)) return;
        learnedThisRun = true;
        new Thread(() -> {
            JSONObject o = ServerPicker.learn(pub);
            if (o == null) { learnedThisRun = false; return; } // not signed in yet: try again after the next page
            JSONArray urls = o.optJSONArray("urls");
            prefs.edit().putString("instance_id", o.optString("id")).putString("alt_urls", urls == null ? "[]" : urls.toString()).apply();
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentRef = new WeakReference<>(this);
        prefs = getSharedPreferences("tr", MODE_PRIVATE);
        CookieManager.getInstance().setAcceptCookie(true); // the login session lives in a cookie
        web = buildWebView();
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        registerNetworkWatcher();
        if (serverUrl() == null) showServerDialog(true);
        else pickAndLoad(true);
    }

    /** The WebView with everything the app needs: settings, the TRNative bridge, link/file/error handling. */
    private WebView buildWebView() {
        final WebView web = new WebView(this);
        web.setBackgroundColor(0xFF121212);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " TubeRipperApp/" + BuildConfig.VERSION_NAME);
        // Keep the page's renderer at full priority while the app is in the background, or the next
        // song can stall when the screen is off.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new NativeBridge(this), "TRNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("tr".equals(u.getScheme())) {
                    if ("retry".equals(u.getHost())) { errorRetried = false; pickAndLoad(true); } else showServerDialog(false);
                    return true;
                }
                if ("about".equals(u.getScheme()) || "data".equals(u.getScheme())) return false;
                if (isKnownHost(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true; // links to other sites (e.g. Wikipedia) open in the browser
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("http")) {
                    errorRetried = false;
                    if (currentBase != null) prefs.edit().putString("last_base", currentBase).apply();
                    learnServer();
                }
            }

            /** Android killed the page's renderer (low memory, or it crashed). Without this the app would close
             *  (the default) or show a dead page. Build a new WebView and load the same address; the page restores
             *  the queue from its saved copy. Gives up after 3 deaths within a minute (a page that always crashes). */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recoverRenderer(view);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                if (!errorRetried) { errorRetried = true; pickAndLoad(true); return; } // the network may have changed: look again once
                String html = "<body style='background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding:26vh 24px 0'>"
                        + "<h2>Can’t reach your server</h2><p style='color:#b3b3b3'>Check your internet connection.</p>"
                        + "<p><a href='tr://retry' style='display:inline-block;background:#fff;color:#000;"
                        + "padding:12px 26px;border-radius:999px;text-decoration:none;font-weight:700'>Try again</a></p>"
                        + "<p><a href='tr://change' style='color:#b3b3b3'>Change server address</a></p></body>";
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        return web;
    }

    private void recoverRenderer(WebView dead) {
        if (dead != web || isFinishing() || isDestroyed()) { if (dead != web) dead.destroy(); return; }
        long now = System.currentTimeMillis();
        rendererDeaths.add(now);
        while (!rendererDeaths.isEmpty() && now - rendererDeaths.get(0) > 60000) rendererDeaths.remove(0);
        PlaybackService.playing = false; // the page that was playing is gone; the new page reports again
        if (dead.getParent() instanceof ViewGroup) ((ViewGroup) dead.getParent()).removeView(dead);
        dead.destroy();
        web = buildWebView();
        setContentView(web);
        if (rendererDeaths.size() > 3) {
            web.loadDataWithBaseURL(null, "<body style='background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding:26vh 24px 0'>"
                    + "<h2>The app keeps stopping</h2><p style='color:#b3b3b3'>Close it and open it again.</p>"
                    + "<p><a href='tr://retry' style='display:inline-block;background:#fff;color:#000;padding:12px 26px;"
                    + "border-radius:999px;text-decoration:none;font-weight:700'>Try again</a></p></body>", "text/html", "utf-8", null);
            return;
        }
        String base = currentBase != null ? currentBase : prefs.getString("last_base", serverUrl());
        if (base != null) web.loadUrl(base);
    }

    /** When the phone's network changes (left home Wi-Fi, turned Tailscale on/off...), look for a better address. */
    private void registerNetworkWatcher() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            final Runnable repick = () -> pickAndLoad(false);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    needRepick = true; upgradeTries = 0;
                    main.post(() -> { if (web != null) web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT); }); // back online: stop preferring the saved copy
                    main.removeCallbacks(repick); main.postDelayed(repick, 2000);
                }
                @Override public void onLost(Network n) { needRepick = true; }
            });
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        upgradeTries = 0;
        if (serverUrl() != null && currentBase != null && (needRepick || System.currentTimeMillis() - lastPick > 5 * 60 * 1000)) pickAndLoad(false);
    }

    /** Asks for the server address (first launch, or "Change server address"). */
    void showServerDialog(final boolean first) {
        runOnUiThread(() -> {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setHint("https://music.example.ts.net");
            input.setSingleLine(true);
            if (serverUrl() != null) input.setText(serverUrl());
            FrameLayout box = new FrameLayout(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            lp.setMargins(pad, pad / 2, pad, 0);
            box.addView(input, lp);
            AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Server address")
                    .setMessage("The web address of your tuberipper server.")
                    .setView(box)
                    .setCancelable(!first)
                    .setPositiveButton("Connect", (d, w) -> {
                        String url = normalize(input.getText().toString());
                        if (url == null) {
                            Toast.makeText(this, "That doesn’t look like an address.", Toast.LENGTH_LONG).show();
                            showServerDialog(first);
                            return;
                        }
                        prefs.edit().putString("server", url).remove("instance_id").remove("alt_urls").apply(); // learned again after sign-in
                        learnedThisRun = false;
                        pickAndLoad(true);
                    });
            if (first) b.setNegativeButton("Quit", (d, w) -> finish());
            else b.setNegativeButton("Cancel", null);
            b.show();
        });
    }

    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        Uri u = Uri.parse(s);
        if (u.getHost() == null || !u.getHost().contains(".")) return null;
        return s.endsWith("/") ? s : s + "/";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush(); // write the login session to disk
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // The web app keeps its screens in browser history, so "back" closes the top screen; at the
        // home screen, leave the app running (music keeps playing) instead of quitting.
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            PlaybackService.stop(this);
            web.destroy();
            web = null;
            if (currentRef.get() == this) currentRef = new WeakReference<>(null);
        }
        super.onDestroy();
    }
}
