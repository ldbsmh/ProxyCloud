package dev.amirzr.flutter_v2ray_client.v2ray.core;

import android.net.VpnService;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * One Cloudflare Worker WebSocket tunnel session (ech.txt protocol).
 *
 * <p>The Worker implementation serves <b>exactly one CONNECT per WebSocket</b>:
 * when the remote TCP stream ends it sends {@code CLOSE} and closes the whole
 * WebSocket. Therefore this class models one WebSocket = one target TCP stream.
 * A new instance is created for every SOCKS5 connection.
 *
 * <p>Wire protocol:
 * <pre>
 *   client -&gt; server: "CONNECT:&lt;host&gt;:&lt;port&gt;|&lt;firstFrameData&gt;"  (text)
 *                      binary frames = raw payload                     (binary)
 *   server -&gt; client: "CONNECTED" | "ERROR:&lt;msg&gt;" | "CLOSE"          (text)
 *                      binary frames = raw payload                     (binary)
 * </pre>
 *
 * <p>Because the tunnel is created through the VPN itself, the WebSocket socket
 * must be {@link VpnService#protect(Socket) protected} so its traffic bypasses
 * the tunnel (otherwise it would loop forever).
 */
public class WsTunnelClient extends WebSocketListener {

    private static final String TAG = "WsTunnelClient";

    public static final String PREFIX_CONNECT = "CONNECT:";
    public static final String CMD_CLOSE = "CLOSE";
    public static final String REPLY_CONNECTED = "CONNECTED";
    public static final String PREFIX_ERROR = "ERROR:";

    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    /**
     * Shared dispatcher (bounded thread pool) + connection pool across all
     * tunnel sessions.
     *
     * <p>Creating a fresh OkHttpClient per SOCKS5 connection used to spin up a
     * whole new Dispatcher (cached thread pool) + ConnectionPool each time.
     * ech opens one WebSocket per connection, so concurrent connections or
     * failed retries created a new thread each — eventually exhausting the
     * 256MB heap (OutOfMemoryError in RealCall$AsyncCall.run). Sharing the
     * dispatcher bounds the thread count process-wide.
     */
    private static final okhttp3.Dispatcher SHARED_DISPATCHER =
            new okhttp3.Dispatcher(java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "ech-okhttp");
                t.setDaemon(true);
                return t;
            }));

    private static final okhttp3.ConnectionPool SHARED_POOL =
            new okhttp3.ConnectionPool(4, 60, java.util.concurrent.TimeUnit.SECONDS);

    private final String wsUrl;
    private final String token;

    /** VpnService used to protect the WebSocket socket (bypass the tunnel). */
    private final VpnService vpnService;

    /** Per-session client: own socketFactory (protects via its VpnService)
     * but shares the process-wide dispatcher + connection pool. */
    private final OkHttpClient httpClient;

    private OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .dispatcher(SHARED_DISPATCHER)
                .connectionPool(SHARED_POOL);
        if (vpnService != null) {
            b.socketFactory(new ProtectedSocketFactory(vpnService));
        }
        return b.build();
    }

    private volatile WebSocket webSocket;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lock = new Object();
    private ConnectWaiter waiter;
    private Session session;
    private boolean wsOpened;

    /** Serializes writes to the WebSocket (server processes messages in order). */
    private final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ech-ws-writer");
                t.setDaemon(true);
                return t;
            });

    public WsTunnelClient(String wsUrl, String token, VpnService vpnService) {
        this.wsUrl = wsUrl;
        this.token = token == null ? "" : token;
        this.vpnService = vpnService;
        this.httpClient = buildClient();
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Opens the WebSocket, sends CONNECT and waits for CONNECTED.
     * On success returns a started {@link Session} bound to {@code localSocket}.
     * On failure throws {@link IOException} and cleans everything up.
     */
    public Session connectAndAwait(Socket localSocket, String host, int port)
            throws IOException {
        if (closed.get()) {
            throw new IOException("Tunnel already closed");
        }
        final ConnectWaiter w = new ConnectWaiter();
        final Session s = new Session(localSocket);
        synchronized (lock) {
            if (waiter != null || session != null) {
                throw new IOException("Session already in progress");
            }
            waiter = w;
            session = s;
            this.host = host;
            this.port = port;
        }
        try {
            open();
            Throwable error = w.await(CONNECT_TIMEOUT_MS);
            if (error != null) {
                synchronized (lock) {
                    if (waiter == w) waiter = null;
                    if (session == s) session = null;
                }
                s.kill();
                if (error instanceof IOException) {
                    throw (IOException) error;
                }
                throw new IOException("CONNECT failed: " + error.getMessage(), error);
            }
            return s;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private volatile String host;
    private volatile int port;

    private void open() {
        Request.Builder rb = new Request.Builder().url(wsUrl);
        if (!token.isEmpty()) {
            rb.header("Sec-WebSocket-Protocol", token);
        }
        webSocket = httpClient.newWebSocket(rb.build(), this);
    }

    /** Closes the WebSocket permanently. Idempotent. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            wsOpened = false;
            if (waiter != null) {
                waiter.fail(new IOException("Tunnel closed"));
                waiter = null;
            }
            Session s = session;
            session = null;
            if (s != null) {
                s.kill();
            }
        }
        writer.shutdownNow();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                // cancel() aborts the connection immediately, even mid-handshake,
                // releasing the dispatcher thread. close() alone waits for a
                // graceful close that never completes if the WS never opened.
                ws.cancel();
            } catch (Exception ignored) {
            }
        }
        // Note: the shared dispatcher/connection pool are intentionally NOT
        // shut down here — they are process-wide and reused by all sessions.
    }

    // ------------------------------------------------------------ WebSocket events

    @Override
    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
        synchronized (lock) {
            wsOpened = true;
            String h = host;
            int p = port;
            // Format: CONNECT:<host>:<port>|  (IPv6 hosts get brackets)
            String target = h.contains(":")
                    ? "[" + h + "]:" + p
                    : h + ":" + p;
            final String frame = PREFIX_CONNECT + target + "|";
            try {
                writer.submit(() -> {
                    WebSocket ws = this.webSocket;
                    if (ws != null && !closed.get()) {
                        ws.send(frame);
                    } else {
                        ConnectWaiter w;
                        synchronized (WsTunnelClient.this.lock) {
                            w = WsTunnelClient.this.waiter;
                        }
                        if (w != null) w.fail(new IOException("WebSocket closed before CONNECT"));
                    }
                });
            } catch (RejectedExecutionException e) {
                ConnectWaiter w;
                synchronized (lock) {
                    w = waiter;
                }
                if (w != null) w.fail(new IOException("Writer shut down", e));
            }
        }
        Log.d(TAG, "WebSocket opened: " + wsUrl);
    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
        if (closed.get()) return;
        if (text.equals(REPLY_CONNECTED)) {
            ConnectWaiter w;
            synchronized (lock) {
                w = waiter;
            }
            if (w != null) w.success();
            return;
        }
        if (text.startsWith(PREFIX_ERROR)) {
            String msg = text.substring(PREFIX_ERROR.length());
            ConnectWaiter w;
            synchronized (lock) {
                w = waiter;
            }
            if (w != null) w.fail(new IOException("Server error: " + msg));
            return;
        }
        if (text.equals(CMD_CLOSE)) {
            Session s;
            synchronized (lock) {
                s = session;
                session = null;
            }
            if (s != null) s.onRemoteClosed();
            return;
        }
        Log.w(TAG, "Unknown text frame: " + (text.length() > 64 ? text.substring(0, 64) : text));
    }

    @Override
    public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
        Session s;
        synchronized (lock) {
            s = session;
        }
        if (s != null) s.onRemoteData(bytes.toByteArray());
    }

    @Override
    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
        Log.w(TAG, "WebSocket failure: " + t.getMessage());
        closed.set(true);
        ConnectWaiter w;
        Session s;
        synchronized (lock) {
            w = waiter;
            waiter = null;
            s = session;
            session = null;
        }
        if (w != null) w.fail(t);
        if (s != null) s.onTransportDead();
    }

    @Override
    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
        Log.d(TAG, "WebSocket closed: " + code + " " + reason);
        closed.set(true);
        ConnectWaiter w;
        Session s;
        synchronized (lock) {
            w = waiter;
            waiter = null;
            s = session;
            session = null;
        }
        if (w != null) w.fail(new IOException("WebSocket closed (" + code + ")"));
        if (s != null) s.onTransportDead();
    }

    // ------------------------------------------------------------ Session

    /** One CONNECT/duplex cycle over this single-use WebSocket. */
    public class Session {
        private final Socket local;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final Object ioLock = new Object();
        private InputStream localIn;
        private OutputStream localOut;

        Session(Socket local) {
            this.local = local;
        }

        public Socket getLocal() {
            return local;
        }

        /** Attaches local IO and starts pumping local bytes to the tunnel. */
        public void start() throws IOException {
            localIn = local.getInputStream();
            localOut = local.getOutputStream();
            Thread pump = new Thread(this::pumpLocalToWs, "ech-local-pump");
            pump.setDaemon(true);
            pump.start();
        }

        private void pumpLocalToWs() {
            byte[] buf = new byte[8192];
            try {
                while (!done.get() && !closed.get()) {
                    int n = localIn.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    final byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    try {
                        writer.submit(() -> {
                            WebSocket ws = webSocket;
                            if (ws != null && !closed.get()) {
                                ws.send(ByteString.of(chunk));
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        break;
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "local read end: " + e.getMessage());
            } finally {
                finish(false);
            }
        }

        void onRemoteData(byte[] data) {
            if (done.get()) return;
            try {
                synchronized (ioLock) {
                    if (localOut != null) {
                        localOut.write(data);
                        localOut.flush();
                    }
                }
            } catch (IOException e) {
                finish(false);
            }
        }

        void onRemoteClosed() {
            finish(true);
        }

        void onTransportDead() {
            finish(true);
        }

        public void kill() {
            finish(false);
        }

        private void finish(boolean remoteClosed) {
            if (!done.compareAndSet(false, true)) return;
            if (!closed.get() && !remoteClosed) {
                // Gracefully tell the Worker to drop the target connection.
                try {
                    writer.submit(() -> {
                        WebSocket ws = webSocket;
                        if (ws != null && !closed.get()) {
                            ws.send(CMD_CLOSE);
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }
            synchronized (lock) {
                if (session == this) {
                    session = null;
                }
            }
            try {
                local.close();
            } catch (Exception ignored) {
            }
            close();
        }
    }

    /** Two-phase CONNECT handshake latch (wait for onOpen, then CONNECTED). */
    private static class ConnectWaiter {
        private final Object lock = new Object();
        private boolean done;
        private Throwable error;

        void success() {
            synchronized (lock) {
                done = true;
                lock.notifyAll();
            }
        }

        void fail(Throwable t) {
            synchronized (lock) {
                done = true;
                error = t;
                lock.notifyAll();
            }
        }

        /** Returns the error if the handshake failed, or null on success. */
        Throwable await(long timeoutMs) {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!done) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        error = new IOException("CONNECT handshake timeout");
                        return error;
                    }
                    try {
                        lock.wait(remain);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        error = new IOException("Interrupted", e);
                        return error;
                    }
                }
                return error;
            }
        }
    }

    /** SocketFactory that calls VpnService.protect() on every created socket. */
    private static class ProtectedSocketFactory extends SocketFactory {
        private final VpnService vpnService;

        ProtectedSocketFactory(VpnService vpnService) {
            this.vpnService = vpnService;
        }

        private Socket protect(Socket s) throws SocketException {
            vpnService.protect(s);
            return s;
        }

        @Override
        public Socket createSocket() throws IOException {
            return protect(new Socket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return protect(new Socket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return protect(new Socket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return protect(new Socket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return protect(new Socket(address, port, localAddress, localPort));
        }
    }
}
