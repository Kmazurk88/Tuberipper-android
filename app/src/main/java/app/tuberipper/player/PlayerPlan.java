package app.tuberipper.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** What the page tells the native player: the song to play and the songs that follow it, and how to bring the
 *  player's list of upcoming songs up to date without touching the song that is playing. No Android classes, so it
 *  can be tested on a plain JVM. */
final class PlayerPlan {
    private PlayerPlan() { }

    /** How many songs after the current one the player holds (it advances through them by itself, gaplessly). */
    static final int MAX_UPCOMING = 5;

    /** One song. gain: 0..1 (volume levelling); speed: playback rate (1 for music). */
    static final class Item {
        final String id, url, title, artist, album, art;
        final long durMs;
        final float gain, speed;

        Item(String id, String url, String title, String artist, String album, String art, long durMs, float gain, float speed) {
            this.id = id; this.url = url; this.title = title; this.artist = artist; this.album = album; this.art = art;
            this.durMs = durMs; this.gain = gain; this.speed = speed;
        }

        /** Two items with the same key are the same media: the player keeps what it has loaded for them. */
        String key() { return id + "|" + url; }

        static Item from(JSONObject o) throws JSONException {
            String id = o.getString("id");
            if (!id.matches("[A-Za-z0-9_:.-]{1,80}")) throw new JSONException("bad id");
            String url = o.getString("url");
            if (!(url.startsWith("http://") || url.startsWith("https://"))) throw new JSONException("bad url");
            return new Item(id, url, o.optString("title"), o.optString("artist"), o.optString("album"), o.optString("art"),
                    o.optLong("dur"), clamp((float) o.optDouble("gain", 1.0), 0f, 1f), clamp((float) o.optDouble("speed", 1.0), 0.25f, 4f));
        }
    }

    /** The items of a JSON array, at most MAX_UPCOMING. An item that can't be read ends the list there: the songs
     *  must stay consecutive, or the player would skip one. */
    static List<Item> items(JSONArray a) {
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length() && out.size() < MAX_UPCOMING; i++) {
            try { out.add(Item.from(a.getJSONObject(i))); } catch (JSONException e) { break; }
        }
        return out;
    }

    /** What to do to the player's upcoming list: keep the first `keep` entries, remove the rest, append `add`. */
    static final class Change {
        final int keep;
        final List<Item> add;
        final boolean noop;

        Change(int keep, List<Item> add, boolean noop) { this.keep = keep; this.add = add; this.noop = noop; }
    }

    /** have: keys of the songs queued after the current one; want: where the page wants it. Only what differs is
     *  touched, so a song that is already loaded (buffering the next one!) is never thrown away. */
    static Change diff(List<String> have, List<Item> want) {
        int keep = 0;
        while (keep < have.size() && keep < want.size() && have.get(keep).equals(want.get(keep).key())) keep++;
        if (keep == have.size() && keep == want.size()) return new Change(keep, new ArrayList<Item>(), true);
        return new Change(keep, new ArrayList<>(want.subList(keep, want.size())), false);
    }

    static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }
}
