package app.tuberipper.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Keeps the app alive while music plays and shows the media notification / lock-screen controls.
 *  It plays nothing itself: the web page owns the audio. Button presses are sent back to the page. */
public class PlaybackService extends Service {
    static final String ACTION_UPDATE = "app.tuberipper.UPDATE";
    static final String ACTION_CMD = "app.tuberipper.CMD";
    static final String ACTION_STOP = "app.tuberipper.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    static PlaybackService instance;
    static volatile boolean playing = false; // read by MainActivity so it doesn't switch address mid-song

    private MediaSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** After "paused" the service stays in the foreground for a few seconds. Between two songs the page reports a
     *  pause for a moment; dropping foreground status then lets Android freeze the app (screen off) before the next
     *  song has loaded, which is why songs didn't advance in the background. */
    private final Runnable demote = () -> {
        if (wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (this.foreground) { stopForeground(STOP_FOREGROUND_DETACH); this.foreground = false; }
        nm.notify(NOTIF_ID, buildNotification(false));
    };
    private boolean foreground = false;
    private JSONObject cur = new JSONObject();
    private String artUrl = "";
    private Bitmap art;

    static void stop(Context c) {
        PlaybackService s = instance;
        if (s != null) s.shutdown();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tuberipper:playback");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tuberipper:playback");
            wifi.setReferenceCounted(false);
        }
        session = new MediaSession(this, "tuberipper");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
            @Override public void onFastForward() { send("ffwd"); }
            @Override public void onRewind() { send("rew"); }
            @Override public void onSeekTo(long pos) { send("seek:" + pos); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        if (ACTION_CMD.equals(action)) { send(intent.getStringExtra("cmd")); return START_NOT_STICKY; }
        if (ACTION_UPDATE.equals(action)) update(intent.getStringExtra("json"));
        else if (!foreground) shutdown(); // started with nothing to show
        return START_NOT_STICKY;
    }

    /** Called with the page's JSON: {id,title,artist,album,art,playing,pos,dur}. */
    void update(String json) {
        try { cur = new JSONObject(json); } catch (Exception e) { return; }
        String newArt = cur.optString("art");
        if (!newArt.equals(artUrl)) { artUrl = newArt; art = null; loadArt(newArt); }
        boolean playing = cur.optBoolean("playing");
        PlaybackService.playing = playing;

        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, cur.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, cur.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, cur.optString("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, cur.optLong("dur"));
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, cur.optLong("pos"),
                        playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());

        Notification n = buildNotification(playing);
        handler.removeCallbacks(demote);
        if (playing) {
            if (!wake.isHeld()) wake.acquire();
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } else {
            nm.notify(NOTIF_ID, n);
            // Paused: keep everything held for a few seconds (a track change looks like a pause), then let go so
            // the notification becomes swipe-dismissable and the phone can sleep.
            if (foreground || wake.isHeld()) handler.postDelayed(demote, 15000);
        }
    }

    private Notification buildNotification(boolean playing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(cur.optString("title"))
                .setContentText(cur.optString("artist"))
                .setSubText(cur.optString("album"))
                .setLargeIcon(art)
                .setContentIntent(open)
                .setDeleteIntent(servicePending(ACTION_STOP, null, 9))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .addAction(action(android.R.drawable.ic_media_previous, "Previous", "prev", 1))
                .addAction(playing ? action(android.R.drawable.ic_media_pause, "Pause", "pause", 2)
                                   : action(android.R.drawable.ic_media_play, "Play", "play", 3))
                .addAction(action(android.R.drawable.ic_media_next, "Next", "next", 4))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private Notification.Action action(int icon, String title, String cmd, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, servicePending(ACTION_CMD, cmd, code)).build();
    }

    private PendingIntent servicePending(String action, String cmd, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        if (cmd != null) i.putExtra("cmd", cmd);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Sends a button press into the web page. */
    private void send(String cmd) {
        final android.webkit.WebView w = MainActivity.page();
        if (w == null || cmd == null) return;
        w.post(() -> w.evaluateJavascript("window.TRNative_cmd&&window.TRNative_cmd('" + cmd.replace("'", "") + "')", null));
    }

    private void loadArt(final String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                String cookie = android.webkit.CookieManager.getInstance().getCookie(url); // covers need the login session
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) new android.os.Handler(getMainLooper()).post(() -> {
                        if (url.equals(artUrl) && instance == this) { art = bmp; update(cur.toString()); }
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void shutdown() {
        handler.removeCallbacks(demote);
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        if (nm != null) nm.cancel(NOTIF_ID);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(demote);
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (session != null) { session.setActive(false); session.release(); }
        instance = null;
        playing = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
