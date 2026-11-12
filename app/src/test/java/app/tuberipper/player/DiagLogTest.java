package app.tuberipper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** The app's small log and unsent-crash files, and the report it builds from them. */
public class DiagLogTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private DiagLog log;
    private File dir;

    @Before
    public void setUp() {
        dir = new File(tmp.getRoot(), "diag");
        log = new DiagLog(dir);
    }

    @Test
    public void notesAreTimestampedLinesInOrder() {
        log.note("first");
        log.note("second\nwith a newline");
        String[] lines = log.ringTail(10000).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} first"));
        assertTrue(lines[1].endsWith("second with a newline"));   // one note stays one line
    }

    @Test
    public void theLogKeepsOnlyItsEndAndStartsOnALine() throws Exception {
        String filler = new String(new char[200]).replace('\0', 'x');
        for (int i = 0; i < 600; i++) log.note("line " + i + " " + filler);   // ~130 KB written
        long size = new File(dir, "ring.log").length();
        assertTrue("log stays small: " + size, size <= 2L * DiagLog.RING_KEEP + 300);
        String all = new String(Files.readAllBytes(new File(dir, "ring.log").toPath()), StandardCharsets.UTF_8);
        assertTrue(all.substring(0, 12).matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3}"));   // starts on a whole line, not cut mid-line
        assertTrue(all.contains("line 599 "));
        assertFalse(all.contains("line 0 "));
    }

    @Test
    public void ringTailIsCutToAskedSizeAtALineStart() {
        for (int i = 0; i < 50; i++) log.note("entry " + i);
        String tail = log.ringTail(200);
        assertTrue(tail.length() <= 200);
        assertTrue(tail.matches("(?s)\\d\\d:\\d\\d:\\d\\d\\.\\d{3} entry \\d+\n.*"));
        assertTrue(tail.endsWith("entry 49\n"));
    }

    @Test
    public void crashesAreKeptUntilClearedNewestFiveOnly() throws Exception {
        for (int i = 1; i <= 7; i++) log.saveCrash(1000L + i, "main", "java.lang.Boom" + i, i % 2 == 0, "home");
        DiagLog.Pending p = log.pending();
        assertEquals(DiagLog.MAX_CRASHES, p.crashes.size());
        assertEquals("java.lang.Boom3", p.crashes.get(0).getString("stack"));   // the two oldest are gone
        assertEquals("java.lang.Boom7", p.crashes.get(4).getString("stack"));
        assertEquals("home", p.crashes.get(0).getString("where"));
        log.clear(p);
        assertEquals(0, log.pending().crashes.size());
    }

    @Test
    public void aLongStackIsCut() throws Exception {
        log.saveCrash(1L, "t", new String(new char[20000]).replace('\0', 's'), true, "public");
        JSONObject c = log.pending().crashes.get(0);
        assertEquals(DiagLog.STACK_MAX, c.getString("stack").length());
        assertTrue(c.getBoolean("playing"));
    }

    @Test
    public void anUnreadableCrashFileIsDroppedNotFatal() throws Exception {
        Files.write(new File(dir, "crash-0000000000001-0.json").toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        log.saveCrash(5L, "main", "ok", false, "home");
        assertEquals(1, log.pending().crashes.size());
        assertFalse(new File(dir, "crash-0000000000001-0.json").exists());
    }

    @Test
    public void thePayloadHasTheShapeTheServerExpects() throws Exception {
        JSONArray exits = new JSONArray().put(new JSONObject().put("reason", "LOW_MEMORY"));
        JSONObject p = DiagLog.payload("crash", "1.9", 10, 34, "Google Pixel 8", "my note",
                Collections.singletonList(new JSONObject().put("stack", "s")), exits, "log text");
        assertEquals("crash", p.getString("kind"));
        assertEquals("1.9", p.getJSONObject("app").getString("name"));
        assertEquals(10, p.getJSONObject("app").getInt("code"));
        assertEquals(34, p.getJSONObject("device").getInt("android"));
        assertEquals("Google Pixel 8", p.getJSONObject("device").getString("model"));
        assertEquals(1, p.getJSONArray("crashes").length());
        assertEquals("LOW_MEMORY", p.getJSONArray("exits").getJSONObject(0).getString("reason"));
        assertEquals("log text", p.getString("log"));
        JSONObject bare = DiagLog.payload("manual", "1.9", 10, 34, "m", null, Collections.<JSONObject>emptyList(), null, null);
        assertEquals("", bare.getString("note"));
        assertEquals(0, bare.getJSONArray("exits").length());
    }

    @Test
    public void whichKindOfAddressWithoutWritingTheAddress() {
        assertEquals("tailscale", DiagLog.whereOf("http://100.64.1.2:8765/"));
        assertEquals("home", DiagLog.whereOf("http://192.168.1.5:8765/"));
        assertEquals("home", DiagLog.whereOf("http://10.0.0.7:8765/"));
        assertEquals("home", DiagLog.whereOf("http://172.20.0.3:8765/"));
        assertEquals("public", DiagLog.whereOf("https://music.example.net/"));
        assertEquals("public", DiagLog.whereOf("http://172.32.0.1/"));   // 172.32+ is not private
        assertEquals("unknown", DiagLog.whereOf("not a url"));
        assertEquals("unknown", DiagLog.whereOf(null));
    }
}
