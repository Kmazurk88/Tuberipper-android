package app.tuberipper.player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A tiny HTTP file server on 127.0.0.1 (this phone only) that plays downloaded files to the web page.
 *  An <audio> element needs real HTTP with Range support to seek, and a http://127.0.0.1 address works from
 *  both http:// and https:// pages. Every address starts with a random token, so other apps can't guess it.
 *  No Android classes are used here, so it can be tested on a plain JVM. */
final class LocalFileServer {
    interface Resolver {
        /** The file for a request key such as "a/abc123" (audio) or "c/abc123" (cover), or null. */
        File file(String key);
        String mime(String key);
    }

    private final String token;
    private final Resolver resolver;
    private ServerSocket socket;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "local-file"); t.setDaemon(true); return t; });

    LocalFileServer(String token, Resolver resolver) {
        this.token = token;
        this.resolver = resolver;
    }

    synchronized void start() throws IOException {
        if (socket != null) return;
        socket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket c = socket.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    return;
                }
            }
        }, "local-file-accept");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
    }

    /** e.g. http://127.0.0.1:41234/<token>/ */
    String base() { return "http://127.0.0.1:" + socket.getLocalPort() + "/" + token + "/"; }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            Map<String, String> headers = new HashMap<>();
            for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String[] parts = req.split(" ");
            if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) { reply(out, 405, "Method Not Allowed", null); return; }
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String prefix = "/" + token + "/";
            if (!path.startsWith(prefix)) { reply(out, 404, "Not Found", null); return; }
            String key = path.substring(prefix.length());
            File f = key.matches("[ac]/[A-Za-z0-9_-]{1,64}") ? resolver.file(key) : null; // ids only: no paths, no ".."
            if (f == null || !f.isFile()) { reply(out, 404, "Not Found", null); return; }
            long len = f.length();
            long start = 0, end = len - 1;
            boolean partial = false;
            String range = headers.get("range");
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) { start = Math.max(0, len - Long.parseLong(spec.substring(1))); }
                    else {
                        start = Long.parseLong(spec.substring(0, dash));
                        if (dash < spec.length() - 1) end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                    }
                    partial = true;
                } catch (NumberFormatException e) { partial = false; start = 0; end = len - 1; }
                if (partial && (start > end || start >= len)) {
                    Map<String, String> h = new HashMap<>();
                    h.put("Content-Range", "bytes */" + len);
                    reply(out, 416, "Range Not Satisfiable", h);
                    return;
                }
            }
            long count = end - start + 1;
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(resolver.mime(key)).append("\r\n");
            head.append("Content-Length: ").append(count).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
            head.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (parts[0].equals("GET")) {
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(start);
                    byte[] buf = new byte[65536];
                    long left = count;
                    while (left > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // the player closed the connection (seek, skip): normal
        }
    }

    private static void reply(OutputStream out, int code, String text, Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\nConnection: close\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
