package app.tuberipper.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The native player (the "new player" setting). It owns the real audio: an ExoPlayer that is handed the song to play
 *  and the few songs that follow it, and moves on to the next one by itself, gaplessly, even when the page is asleep,
 *  frozen or gone. Android's own audio rules apply (another app taking over, headphones unplugged, calls). The page
 *  stays in charge of everything else (the queue, what plays next after those few songs, bookmarks, scrobbling) and
 *  hears about each change through window.TRPlayer_event(...).
 *
 *  Page -> engine:  TRNative.player(json), commands {c: ...}:
 *    load      {item, upcoming: [item...], pos, play}   play this song now, with these songs after it
 *    upcoming  {items: [item...]}                       the songs after the current one changed (queue edits)
 *    play, pause, stop
 *    seek      {pos}  (ms)                              speed {v}      gain {v} (0..1)
 *    sleep     {mode: off | end | timer, ms}            eq {on, gains: [5 dB values]}      visible {v}
 *  Engine -> page, {e: ...}:
 *    state {id, playing, state, pos, dur, rate}   time {id, pos, dur} (4/s while playing)
 *    transition {id, reason: auto | user}   the player moved on to the next song by itself (or the notification's Next)
 *    ended {id, sleep}   the last song ended (or, with the sleep timer set to "end", this one did)
 *    error {id, code, msg}   cmd {cmd: prev | next}   sleepDone {}
 *  item = {id, url, title, artist, album, art, dur (ms), gain (0..1), speed}
 *
 *  Everything here runs on the main thread (ExoPlayer's rule); the bridge posts to it. */
@OptIn(markerClass = UnstableApi.class)
final class Engine {
    static final int VERSION = 2;
    @SuppressLint("StaticFieldLeak")   // it holds the application context, which lives as long as the process
    private static Engine instance;

    static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    /** The engine if it was ever started (for shutdown), without creating one. */
    static synchronized Engine existing() { return instance; }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private ForwardingPlayer sessionPlayer;
    private DataSource.Factory dataSources;

    private final Map<String, PlayerPlan.Item> known = new HashMap<>();   // the current and upcoming songs by id: gain, speed
    private String currentId = "";
    private float speed = 1f;
    private float eqHeadroom = 1f;
    private float[] eqGains = null;
    private Equalizer equalizer;
    private int eqSession = -1;
    private float fade = 1f;                // sleep timer fade-out, 1 = full volume
    private Runnable sleepStart, sleepStep;
    private boolean visible = true;
    private volatile String stateJson = "{\"hasPlayer\":false}";

    private Engine(Context c) { ctx = c; }

    // ---------- what the bridge calls (any thread) ----------

    void command(final String json) { main.post(() -> handle(json)); }

    /** The latest state, for a page that just loaded and wants to see what is playing. At most a quarter second old. */
    String stateJson() { return stateJson; }

    /** The app is closing: stop the music and the notification. */
    static void shutdown() {
        final Engine e = existing();
        if (e != null) e.main.post(e::stop);
    }

    // ---------- the pieces Media3's session service needs (main thread) ----------

    /** The player the notification and lock screen control. Next and Previous are answered here, so they follow the page's queue. */
    Player sessionPlayer() {
        ensurePlayer();
        if (sessionPlayer == null) {
            sessionPlayer = new ForwardingPlayer(player) {
                @Override public Commands getAvailableCommands() {
                    return super.getAvailableCommands().buildUpon().addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build();
                }
                @Override public boolean isCommandAvailable(int command) {
                    return command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS
                            || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                            || super.isCommandAvailable(command);
                }
                @Override public boolean hasNextMediaItem() { return true; }
                @Override public boolean hasPreviousMediaItem() { return true; }
                @Override public void seekToNext() { userNext(); }
                @Override public void seekToNextMediaItem() { userNext(); }
                @Override public void seekToPrevious() { userPrev(); }
                @Override public void seekToPreviousMediaItem() { userPrev(); }
            };
        }
        return sessionPlayer;
    }

    /** Loads covers for the notification the same way the audio is loaded (with the sign-in cookie). */
    DataSource.Factory dataSources() {
        ensurePlayer();
        return dataSources;
    }

    // ---------- commands (main thread) ----------

    private void handle(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String c = o.optString("c");
            switch (c) {
                case "load": load(o); break;
                case "upcoming": if (player != null) setUpcoming(PlayerPlan.items(o.optJSONArray("items"))); break;
                case "play": if (player != null) { ensureService(); if (player.getPlaybackState() == Player.STATE_IDLE && player.getMediaItemCount() > 0) player.prepare(); player.play(); } break;
                case "pause": if (player != null) player.pause(); break;
                case "stop": stop(); break;
                case "seek": if (player != null) { player.seekTo(Math.max(0, o.optLong("pos"))); sendTime(); } break;
                case "speed": speed = PlayerPlan.clamp((float) o.optDouble("v", 1.0), 0.25f, 4f); if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed, 1f)); break;
                case "gain": { PlayerPlan.Item it = known.get(currentId); if (it != null) { known.put(currentId, withGain(it, (float) o.optDouble("v", 1.0))); applyVolume(); } break; }
                case "sleep": setSleep(o.optString("mode"), o.optLong("ms")); break;
                case "eq": setEq(o.optBoolean("on"), o.optJSONArray("gains")); break;
                case "visible": visible = o.optBoolean("v", true); break;
                default: Diagnostics.note("player: unknown command " + c);
            }
        } catch (Exception e) {
            Diagnostics.note("player command failed: " + e);
        }
    }

    private void load(JSONObject o) throws Exception {
        PlayerPlan.Item cur = PlayerPlan.Item.from(o.getJSONObject("item"));
        List<PlayerPlan.Item> up = PlayerPlan.items(o.optJSONArray("upcoming"));
        long pos = Math.max(0, o.optLong("pos"));
        boolean play = o.optBoolean("play", true);
        ensurePlayer();
        if (play) ensureService();
        known.clear();
        known.put(cur.id, cur);
        for (PlayerPlan.Item u : up) known.put(u.id, u);
        currentId = cur.id;
        speed = cur.speed;
        List<MediaItem> list = new ArrayList<>();
        list.add(mediaItem(cur));
        for (PlayerPlan.Item u : up) list.add(mediaItem(u));
        cancelFade();
        player.setMediaItems(list, 0, pos);
        player.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        applyVolume();
        player.prepare();
        player.setPlayWhenReady(play);
        sendState();
    }

    /** The songs after the current one changed: bring the player's list up to date without touching the playing song. */
    private void setUpcoming(List<PlayerPlan.Item> want) {
        if (player.getMediaItemCount() == 0) return;
        for (PlayerPlan.Item u : want) known.put(u.id, u);
        int first = player.getCurrentMediaItemIndex() + 1;
        List<String> have = new ArrayList<>();
        for (int i = first; i < player.getMediaItemCount(); i++) {
            MediaItem m = player.getMediaItemAt(i);
            have.add(m.mediaId + "|" + (m.localConfiguration == null ? "" : m.localConfiguration.uri));
        }
        PlayerPlan.Change ch = PlayerPlan.diff(have, want);
        if (ch.noop) return;
        int from = first + ch.keep;
        if (from < player.getMediaItemCount()) player.removeMediaItems(from, player.getMediaItemCount());
        List<MediaItem> add = new ArrayList<>();
        for (PlayerPlan.Item it : ch.add) add.add(mediaItem(it));
        if (!add.isEmpty()) player.addMediaItems(add);
    }

    private void stop() {
        cancelSleep();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentId = "";
        known.clear();
        try { ctx.stopService(new Intent(ctx, NativePlayerService.class)); } catch (RuntimeException ignored) { /* not running */ }
        stateJson = "{\"hasPlayer\":true,\"id\":\"\",\"playing\":false,\"state\":\"idle\",\"pos\":0,\"dur\":0}";
    }

    private void userNext() {
        if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();   // the transition event tells the page
        else emit(new JSONObject(), "cmd", "next");
    }

    private void userPrev() {
        if (MainActivity.page() != null) emit(new JSONObject(), "cmd", "prev");   // the page decides (restart or the previous song)
        else if (player != null) player.seekTo(0);
    }

    // ---------- the player ----------

    private void ensurePlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("TubeRipperApp/" + BuildConfig.VERSION_NAME).setConnectTimeoutMs(10000).setReadTimeoutMs(20000);
        // The internet address needs the signed-in session: add its cookie to each request (only to the host it belongs to).
        dataSources = new ResolvingDataSource.Factory(http, spec -> {
            String cookie = CookieManager.getInstance().getCookie(spec.uri.toString());
            if (cookie == null || cookie.isEmpty()) return spec;
            Map<String, String> headers = new HashMap<>(spec.httpRequestHeaders);
            headers.put("Cookie", cookie);
            return spec.withRequestHeaders(headers);
        });
        player = new ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSources))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)   // audio focus
                .setHandleAudioBecomingNoisy(true)                    // headphones unplugged: pause
                .setWakeMode(C.WAKE_MODE_NETWORK)                     // keep the CPU and Wi-Fi awake while playing
                .setSeekForwardIncrementMs(30000).setSeekBackIncrementMs(15000)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { transitioned(item, reason); }
            @Override public void onIsPlayingChanged(boolean isPlaying) { sendState(); }
            @Override public void onPlaybackStateChanged(int state) {
                sendState();
                if (state == Player.STATE_ENDED) emit(idObject(), "ended", null);
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // The sleep timer's "stop at the end of this song" (the only time the player pauses itself at a song's end).
                    player.setPauseAtEndOfMediaItems(false);
                    try { emit(idObject().put("sleep", true), "ended", null); } catch (Exception ignored) { /* cannot happen */ }
                }
                sendState();
            }
            @Override public void onPlayerError(PlaybackException error) {
                Diagnostics.note("player error " + error.getErrorCodeName());
                try { emit(idObject().put("code", error.getErrorCodeName()).put("msg", String.valueOf(error.getMessage())), "error", null); } catch (Exception ignored) { /* cannot happen */ }
            }
            @Override public void onAudioSessionIdChanged(int audioSessionId) { attachEq(audioSessionId); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) sendTime();
            }
        });
        main.postDelayed(ticker, 1000);
    }

    private void transitioned(@Nullable MediaItem item, int reason) {
        if (item == null || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return;   // our own load / queue edits
        currentId = item.mediaId;
        PlayerPlan.Item it = known.get(currentId);
        if (it != null) {
            speed = it.speed;
            player.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        }
        applyVolume();
        try {
            emit(idObject().put("reason", reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ? "auto" : "user"), "transition", null);
        } catch (Exception ignored) { /* cannot happen */ }
        // Songs already played are dead weight: drop them (after this callback returns).
        main.post(() -> {
            int idx = player.getCurrentMediaItemIndex();
            if (idx > 0) player.removeMediaItems(0, idx);
        });
        sendState();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null) {
                snapshot();
                if (player.isPlaying()) sendTime();
            }
            main.postDelayed(this, visible ? 250 : 1000);
        }
    };

    private MediaItem mediaItem(PlayerPlan.Item it) {
        MediaMetadata.Builder md = new MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album);
        if (it.durMs > 0) md.setDurationMs(it.durMs);
        if (!it.art.isEmpty()) md.setArtworkUri(Uri.parse(it.art));
        return new MediaItem.Builder().setMediaId(it.id).setUri(it.url).setMediaMetadata(md.build()).build();
    }

    private static PlayerPlan.Item withGain(PlayerPlan.Item it, float gain) {
        return new PlayerPlan.Item(it.id, it.url, it.title, it.artist, it.album, it.art, it.durMs, PlayerPlan.clamp(gain, 0f, 1f), it.speed);
    }

    private void applyVolume() {
        if (player == null) return;
        PlayerPlan.Item it = known.get(currentId);
        player.setVolume((it == null ? 1f : it.gain) * eqHeadroom * fade);
    }

    private void ensureService() {
        try {
            ctx.startService(new Intent(ctx, NativePlayerService.class));
        } catch (RuntimeException e) {
            Diagnostics.note("player service not started: " + e);   // the music still plays; there is just no notification
        }
    }

    // ---------- sleep timer ----------

    private void setSleep(String mode, long ms) {
        cancelSleep();
        if (player == null) return;
        if ("end".equals(mode)) {
            player.setPauseAtEndOfMediaItems(true);
        } else if ("timer".equals(mode) && ms > 0) {
            sleepStart = this::fadeOutAndPause;
            main.postDelayed(sleepStart, ms);
        }
    }

    private void cancelSleep() {
        if (sleepStart != null) main.removeCallbacks(sleepStart);
        sleepStart = null;
        cancelFade();
        if (player != null) player.setPauseAtEndOfMediaItems(false);
    }

    private void cancelFade() {
        if (sleepStep != null) main.removeCallbacks(sleepStep);
        sleepStep = null;
        fade = 1f;
        applyVolume();
    }

    /** Fades out over 5 seconds, then pauses. */
    private void fadeOutAndPause() {
        final long t0 = SystemClock.uptimeMillis();
        sleepStep = new Runnable() {
            @Override public void run() {
                float k = (SystemClock.uptimeMillis() - t0) / 5000f;
                if (k >= 1f || player == null || !player.isPlaying()) {
                    if (player != null) player.pause();
                    sleepStep = null;
                    fade = 1f;
                    applyVolume();
                    emit(new JSONObject(), "sleepDone", null);
                } else {
                    fade = 1f - k;
                    applyVolume();
                    main.postDelayed(this, 100);
                }
            }
        };
        sleepStep.run();
    }

    // ---------- equalizer ----------

    private void setEq(boolean on, @Nullable JSONArray gains) {
        float[] g = null;
        if (on && gains != null && gains.length() >= EqMap.PAGE_BANDS_HZ.length) {
            g = new float[EqMap.PAGE_BANDS_HZ.length];
            for (int i = 0; i < g.length; i++) g[i] = PlayerPlan.clamp((float) gains.optDouble(i, 0.0), -12f, 12f);
        }
        eqGains = g;
        eqHeadroom = g == null ? 1f : EqMap.headroom(g);
        applyVolume();
        if (player != null) attachEq(player.getAudioSessionId());
    }

    /** Applies the equalizer to the player's audio session (and again whenever Android gives the player a new one). */
    private void attachEq(int session) {
        try {
            if (equalizer != null && eqSession != session) { equalizer.release(); equalizer = null; }
            if (eqGains == null) { if (equalizer != null) equalizer.setEnabled(false); return; }
            if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return;
            if (equalizer == null) { equalizer = new Equalizer(0, session); eqSession = session; }
            short n = equalizer.getNumberOfBands();
            float[] centres = new float[n];
            for (short b = 0; b < n; b++) centres[b] = equalizer.getCenterFreq(b) / 1000f;    // milliHertz -> Hz
            short[] range = equalizer.getBandLevelRange();
            short[] levels = EqMap.levels(eqGains, centres, range[0], range[1]);
            for (short b = 0; b < n; b++) equalizer.setBandLevel(b, levels[b]);
            equalizer.setEnabled(true);
        } catch (RuntimeException e) {
            Diagnostics.note("equalizer failed: " + e);
            equalizer = null;
        }
    }

    // ---------- telling the page ----------

    private JSONObject idObject() {
        try { return new JSONObject().put("id", currentId); } catch (Exception e) { return new JSONObject(); }
    }

    private void sendState() { snapshot(); emit(stateObject(), "state", null); }

    private void sendTime() {
        try { emit(new JSONObject().put("id", currentId).put("pos", player.getCurrentPosition()).put("dur", durMs()), "time", null); } catch (Exception ignored) { /* cannot happen */ }
    }

    private long durMs() {
        long d = player.getDuration();
        if (d != C.TIME_UNSET && d > 0) return d;
        PlayerPlan.Item it = known.get(currentId);
        return it == null ? 0 : it.durMs;
    }

    private JSONObject stateObject() {
        JSONObject o = new JSONObject();
        try {
            int s = player.getPlaybackState();
            o.put("id", currentId).put("playing", player.isPlaying())
                    .put("state", s == Player.STATE_READY ? "ready" : s == Player.STATE_BUFFERING ? "buffering" : s == Player.STATE_ENDED ? "ended" : "idle")
                    .put("wantsPlay", player.getPlayWhenReady())
                    .put("pos", player.getCurrentPosition()).put("dur", durMs()).put("rate", speed);
        } catch (Exception ignored) { /* cannot happen */ }
        return o;
    }

    private void snapshot() {
        if (player == null) return;
        try { stateJson = stateObject().put("hasPlayer", true).toString(); } catch (Exception ignored) { /* keep the old one */ }
    }

    private void emit(JSONObject body, String type, @Nullable String cmd) {
        try {
            body.put("e", type);
            if (cmd != null) body.put("cmd", cmd);
        } catch (Exception ignored) { /* cannot happen */ }
        final WebView w = MainActivity.page();
        if (w == null) return;
        final String js = "window.TRPlayer_event&&window.TRPlayer_event(" + body + ")";
        w.post(() -> w.evaluateJavascript(js, null));
    }
}
