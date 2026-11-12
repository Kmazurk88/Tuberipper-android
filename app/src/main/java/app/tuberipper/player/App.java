package app.tuberipper.player;

import android.app.Application;

/** Starts the crash recorder before anything else (the activity, the playback service) can fail. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diagnostics.install(this);
    }
}
