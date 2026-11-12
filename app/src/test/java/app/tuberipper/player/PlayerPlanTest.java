package app.tuberipper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PlayerPlanTest {
    private static JSONObject json(String id, String url) throws Exception {
        return new JSONObject().put("id", id).put("url", url).put("title", "T " + id).put("artist", "A").put("album", "Al").put("art", "")
                .put("dur", 200000).put("gain", 0.7).put("speed", 1);
    }

    private static PlayerPlan.Item item(String id) throws Exception { return PlayerPlan.Item.from(json(id, "http://h/s/" + id)); }

    private static List<PlayerPlan.Item> items(String... ids) throws Exception {
        List<PlayerPlan.Item> l = new ArrayList<>();
        for (String id : ids) l.add(item(id));
        return l;
    }

    private static List<String> keys(String... ids) {
        List<String> k = new ArrayList<>();
        for (String id : ids) k.add(id + "|http://h/s/" + id);
        return k;
    }

    @Test
    public void anItemKeepsWhatThePageSent() throws Exception {
        PlayerPlan.Item i = PlayerPlan.Item.from(json("abc", "https://x/y"));
        assertEquals("abc|https://x/y", i.key());
        assertEquals("T abc", i.title);
        assertEquals(200000L, i.durMs);
        assertEquals(0.7f, i.gain, 0.001f);
    }

    @Test
    public void gainAndSpeedAreKeptInRange() throws Exception {
        PlayerPlan.Item i = PlayerPlan.Item.from(json("a", "http://h/a").put("gain", 7).put("speed", 99));
        assertEquals(1f, i.gain, 0f);
        assertEquals(4f, i.speed, 0f);
        PlayerPlan.Item j = PlayerPlan.Item.from(json("a", "http://h/a").put("gain", -1).put("speed", 0));
        assertEquals(0f, j.gain, 0f);
        assertEquals(0.25f, j.speed, 0f);
    }

    @Test(expected = org.json.JSONException.class)
    public void onlyWebAddressesArePlayed() throws Exception {
        PlayerPlan.Item.from(json("a", "file:///etc/passwd"));
    }

    @Test(expected = org.json.JSONException.class)
    public void idsAreChecked() throws Exception {
        PlayerPlan.Item.from(json("a b/../c", "http://h/a"));
    }

    @Test
    public void ytIdsAreAccepted() throws Exception {
        assertEquals("yt:dQw4w9WgXcQ", PlayerPlan.Item.from(json("yt:dQw4w9WgXcQ", "http://h/p")).id);
    }

    @Test
    public void listsAreCutAtTheLimitAndAtTheFirstBadItem() throws Exception {
        JSONArray many = new JSONArray();
        for (int i = 0; i < 9; i++) many.put(json("s" + i, "http://h/s/s" + i));
        assertEquals(PlayerPlan.MAX_UPCOMING, PlayerPlan.items(many).size());
        JSONArray bad = new JSONArray().put(json("a", "http://h/a")).put(new JSONObject().put("id", "b")).put(json("c", "http://h/c"));
        assertEquals(1, PlayerPlan.items(bad).size());      // c must not follow a: that would skip b
        assertEquals(0, PlayerPlan.items(null).size());
    }

    @Test
    public void nothingToDoWhenTheListsAgree() throws Exception {
        assertTrue(PlayerPlan.diff(keys("a", "b"), items("a", "b")).noop);
        assertTrue(PlayerPlan.diff(Collections.<String>emptyList(), items()).noop);
    }

    @Test
    public void appendingKeepsEverythingLoaded() throws Exception {
        PlayerPlan.Change c = PlayerPlan.diff(keys("a"), items("a", "b"));
        assertFalse(c.noop);
        assertEquals(1, c.keep);
        assertEquals(1, c.add.size());
        assertEquals("b", c.add.get(0).id);
    }

    @Test
    public void aChangeInTheMiddleReplacesOnlyTheTail() throws Exception {
        PlayerPlan.Change c = PlayerPlan.diff(keys("a", "b", "c"), items("a", "x", "c"));
        assertEquals(1, c.keep);
        assertEquals(Arrays.asList("x", "c"), Arrays.asList(c.add.get(0).id, c.add.get(1).id));
    }

    @Test
    public void clearingTheQueueRemovesEverything() throws Exception {
        PlayerPlan.Change c = PlayerPlan.diff(keys("a", "b"), items());
        assertFalse(c.noop);
        assertEquals(0, c.keep);
        assertEquals(0, c.add.size());
    }

    @Test
    public void theSameSongWithAnotherAddressIsADifferentItem() throws Exception {
        List<PlayerPlan.Item> want = Collections.singletonList(PlayerPlan.Item.from(json("a", "http://127.0.0.1:1/token/a/a")));   // now downloaded
        assertEquals(0, PlayerPlan.diff(keys("a"), want).keep);
    }
}
