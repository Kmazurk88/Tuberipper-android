package app.tuberipper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Choosing between the home, Tailscale and public addresses: only a server that proves it knows the instance
 *  id is trusted, preference order is kept, and anything else falls back to the public address. */
public class ServerPickerTest {
    private static final String ID = "instance-id-123";
    private static final String PUBLIC = "https://music.example.net/";
    private final List<ServerSocket> servers = new ArrayList<>();

    @After
    public void tearDown() throws Exception { for (ServerSocket s : servers) s.close(); }

    /** A fake TubeRipper that answers /api/instance?nonce=X with HMAC(id, X), like auth.instance on the server.
     *  (A plain socket: the JDK's HttpServer is not on the Android compile classpath.) */
    private String fakeServer(final String id, final int status, final long delayMs) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        servers.add(ss);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket c = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line = in.readLine();
                    for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) { /* skip headers */ }
                    if (delayMs > 0) Thread.sleep(delayMs);
                    String target = line == null ? "" : line.split(" ")[1];
                    int q = target.indexOf("nonce=");
                    String nonce = q >= 0 ? target.substring(q + 6) : "";
                    byte[] body = ("{\"proof\":\"" + ServerPicker.hmacHex(id, nonce) + "\"}").getBytes(StandardCharsets.UTF_8);
                    String head = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                            + (status == 302 ? "\r\nLocation: /login" : "") + "\r\nConnection: close\r\n\r\n";
                    OutputStream o = c.getOutputStream();
                    o.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    o.write(body);
                    o.flush();
                } catch (Exception e) {
                    if (ss.isClosed()) return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + ss.getLocalPort() + "/";
    }

    private static String deadAddress() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return "http://127.0.0.1:" + s.getLocalPort() + "/"; }
    }

    @Test
    public void hmacMatchesTheServer() throws Exception {
        // python: hmac.new(b"instance-id-123", b"abc123nonce", hashlib.sha256).hexdigest()
        assertEquals("d0dc5aad416a55f24bb769c5c5cdaa2c9491052cf701da5560d29110cc538039", ServerPicker.hmacHex(ID, "abc123nonce"));
    }

    @Test
    public void trustsOnlyAServerThatKnowsTheId() throws Exception {
        assertTrue(ServerPicker.isOurServer(fakeServer(ID, 200, 0), ID));
        assertFalse(ServerPicker.isOurServer(fakeServer("someone-else", 200, 0), ID));
        assertFalse(ServerPicker.isOurServer(fakeServer(ID, 500, 0), ID));
        assertFalse(ServerPicker.isOurServer(fakeServer(ID, 302, 0), ID));
        assertFalse(ServerPicker.isOurServer(deadAddress(), ID));
    }

    @Test
    public void picksTheFirstAddressThatProvesItself() throws Exception {
        String home = fakeServer(ID, 200, 0), tailscale = fakeServer(ID, 200, 0);
        assertEquals(home, ServerPicker.pick(PUBLIC, ID, Arrays.asList(home, tailscale)));
    }

    @Test
    public void skipsADeadOrForeignHomeAddress() throws Exception {
        String tailscale = fakeServer(ID, 200, 0);
        assertEquals(tailscale, ServerPicker.pick(PUBLIC, ID, Arrays.asList(deadAddress(), tailscale)));
        assertEquals(tailscale, ServerPicker.pick(PUBLIC, ID, Arrays.asList(fakeServer("impostor", 200, 0), tailscale)));
    }

    @Test
    public void waitsForASlowTailscaleAnswer() throws Exception {
        // First packet through Tailscale can take seconds: 2.5 s is inside one 3.5 s probe.
        String slow = fakeServer(ID, 200, 2500);
        assertEquals(slow, ServerPicker.pick(PUBLIC, ID, Arrays.asList(deadAddress(), slow)));
    }

    @Test
    public void fallsBackToThePublicAddress() throws Exception {
        assertEquals(PUBLIC, ServerPicker.pick(PUBLIC, ID, Arrays.asList(deadAddress(), fakeServer("impostor", 200, 0))));
        assertEquals(PUBLIC, ServerPicker.pick(PUBLIC, ID, Collections.emptyList()));
        assertEquals(PUBLIC, ServerPicker.pick(PUBLIC, null, Collections.singletonList(fakeServer(ID, 200, 0))));
    }

    @Test
    public void answersMeansAnythingBelow500() throws Exception {
        assertTrue(ServerPicker.answers(fakeServer(ID, 200, 0)));
        assertTrue(ServerPicker.answers(fakeServer(ID, 302, 0)));
        assertFalse(ServerPicker.answers(fakeServer(ID, 503, 0)));
        assertFalse(ServerPicker.answers(deadAddress()));
    }

    @Test
    public void parsesOnlyWebAddresses() {
        assertEquals(Arrays.asList("http://192.0.2.5:8765/", "https://x.example/"),
                ServerPicker.parseUrls("[\"http://192.0.2.5:8765\", \"ftp://nope\", \" https://x.example/ \"]"));
        assertEquals(Collections.emptyList(), ServerPicker.parseUrls("not json"));
        assertEquals(Collections.emptyList(), ServerPicker.parseUrls(null));
    }
}
