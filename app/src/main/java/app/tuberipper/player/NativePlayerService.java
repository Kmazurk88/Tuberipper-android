package app.tuberipper.player;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.datasource.DataSourceBitmapLoader;

/** Gives the native player (Engine) its notification, lock-screen controls and Bluetooth/headset buttons, and keeps
 *  the app alive while music plays. It plays nothing itself: the Engine owns the player, this only wraps it in a
 *  media session. Media3 turns the service into a foreground service by itself when playback starts. */
@OptIn(markerClass = UnstableApi.class)
public class NativePlayerService extends MediaSessionService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        Engine engine = Engine.get(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, engine.sessionPlayer())
                .setSessionActivity(open)
                // covers come from the server like the audio does: with its sign-in cookie
                .setBitmapLoader(new DataSourceBitmapLoader.Builder(this).setDataSourceFactory(engine.dataSources()).build())
                .build();
        DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback").setChannelName(R.string.channel_name).build();
        provider.setSmallIcon(R.drawable.ic_stat_note);
        setMediaNotificationProvider(provider);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();   // the player itself belongs to the Engine and lives on
            session = null;
        }
        super.onDestroy();
    }
}
