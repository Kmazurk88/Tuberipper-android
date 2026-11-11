package app.tuberipper.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** The 127.0.0.1 file server that plays downloaded songs: Range answers, and that nothing but a known id with
 *  the right token is ever served. */
public class LocalFileServerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String TOKEN = "0123456789abcdef";
    private static final byte[] DATA = "0123456789".getBytes(StandardCharsets.US_ASCII);
    private LocalFileServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        final File dir = tmp.getRoot();
        Files.write(new File(dir, "song1").toPath(), DATA);
        server = new LocalFileServer(TOKEN, new LocalFileServer.Resolver() {
            @Override public File file(String key) { return new File(dir, key.substring(2)); }
            @Override public String mime(String key) { return "audio/mpeg"; }
        });
        server.start();
        String base = server.base();
        port = Integer.parseInt(base.substring("http://127.0.0.1:".length(), base.indexOf('/', "http://127.0.0.1:".length())));
    }

    @After
    public void tearDown() { server.stop(); }

    /** status, headers (lower-case names) and body of one raw request. */
    private static final class Reply {
        int status;
        final Map<String, String> headers = new HashMap<>();
        byte[] body;
    }

    private Reply send(String method, String path, String range) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            String req = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (range == null ? "" : "Range: " + range + "\r\n") + "\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            byte[] raw = all.toByteArray();
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            String[] lines = text.substring(0, split).split("\r\n");
            Reply r = new Reply();
            r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
            }
            r.body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            return r;
        }
    }

    private String path(String key) { return "/" + TOKEN + "/" + key; }

    @Test
    public void servesTheWholeFile() throws Exception {
        Reply r = send("GET", path("a/song1"), null);
        assertEquals(200, r.status);
        assertArrayEquals(DATA, r.body);
        assertEquals("10", r.headers.get("content-length"));
        assertEquals("bytes", r.headers.get("accept-ranges"));
        assertEquals("audio/mpeg", r.headers.get("content-type"));
        assertEquals("no-store", r.headers.get("cache-control"));
    }

    @Test
    public void ignoresAQueryString() throws Exception {
        assertEquals(200, send("GET", path("a/song1") + "?t=123", null).status);
    }

    @Test
    public void answersARange() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=2-5");
        assertEquals(206, r.status);
        assertEquals("2345", new String(r.body, StandardCharsets.US_ASCII));
        assertEquals("bytes 2-5/10", r.headers.get("content-range"));
        assertEquals("4", r.headers.get("content-length"));
    }

    @Test
    public void answersAnOpenEndedRange() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=7-");
        assertEquals(206, r.status);
        assertEquals("789", new String(r.body, StandardCharsets.US_ASCII));
        assertEquals("bytes 7-9/10", r.headers.get("content-range"));
    }

    @Test
    public void answersASuffixRange() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=-3");
        assertEquals(206, r.status);
        assertEquals("789", new String(r.body, StandardCharsets.US_ASCII));
    }

    @Test
    public void clampsARangePastTheEnd() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=8-100");
        assertEquals(206, r.status);
        assertEquals("89", new String(r.body, StandardCharsets.US_ASCII));
        assertEquals("bytes 8-9/10", r.headers.get("content-range"));
    }

    @Test
    public void refusesARangeStartingPastTheEnd() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=10-");
        assertEquals(416, r.status);
        assertEquals("bytes */10", r.headers.get("content-range"));
    }

    @Test
    public void treatsAGarbledRangeAsTheWholeFile() throws Exception {
        Reply r = send("GET", path("a/song1"), "bytes=x-y");
        assertEquals(200, r.status);
        assertArrayEquals(DATA, r.body);
    }

    @Test
    public void headHasNoBody() throws Exception {
        Reply r = send("HEAD", path("a/song1"), null);
        assertEquals(200, r.status);
        assertEquals("10", r.headers.get("content-length"));
        assertEquals(0, r.body.length);
    }

    @Test
    public void refusesOtherMethods() throws Exception {
        assertEquals(405, send("POST", path("a/song1"), null).status);
    }

    @Test
    public void needsTheToken() throws Exception {
        assertEquals(404, send("GET", "/wrongtoken/a/song1", null).status);
        assertEquals(404, send("GET", "/a/song1", null).status);
    }

    @Test
    public void servesIdsOnlyNeverPaths() throws Exception {
        assertEquals(404, send("GET", path("a/../song1"), null).status);
        assertEquals(404, send("GET", path("a/x/song1"), null).status);
        assertEquals(404, send("GET", path("b/song1"), null).status);
        assertEquals(404, send("GET", path("a/%2e%2e"), null).status);
        assertEquals(404, send("GET", path("a/"), null).status);
    }

    @Test
    public void unknownIdIsNotFound() throws Exception {
        assertEquals(404, send("GET", path("a/nosuchsong"), null).status);
    }

    @Test
    public void listensOnLoopbackOnly() {
        assertEquals("http://127.0.0.1:" + port + "/" + TOKEN + "/", server.base());
    }
}
