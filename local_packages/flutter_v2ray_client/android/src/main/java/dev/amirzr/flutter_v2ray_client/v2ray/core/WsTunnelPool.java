package dev.amirzr.flutter_v2ray_client.v2ray.core;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;

/**
 * Factory for single-use Cloudflare Worker WebSocket tunnel sessions.
 *
 * <p>The Worker (ech.txt) serves exactly one CONNECT per WebSocket and closes
 * the WebSocket when the remote stream ends, so we create a fresh
 * {@link WsTunnelClient} per SOCKS5 connection instead of maintaining a pool.
 */
public class WsTunnelPool {

    private static final String TAG = "WsTunnelPool";

    private final String wsUrl;
    private final String token;
    private final VpnService vpnService;
    private volatile boolean closed;

    public WsTunnelPool(String wsUrl, String token, VpnService vpnService) {
        this.wsUrl = wsUrl;
        this.token = token;
        this.vpnService = vpnService;
    }

    /**
     * Opens a new WebSocket, performs the CONNECT handshake for
     * {@code host:port} and returns the started session bound to
     * {@code localSocket}.
     */
    public WsTunnelClient.Session connectTarget(Socket localSocket, String host, int port,
                                                int timeoutMs) throws IOException {
        if (closed) {
            throw new IOException("Tunnel pool closed");
        }
        WsTunnelClient client = new WsTunnelClient(wsUrl, token, vpnService);
        try {
            WsTunnelClient.Session session = client.connectAndAwait(localSocket, host, port);
            return session;
        } catch (IOException e) {
            Log.w(TAG, "connectTarget failed: " + e.getMessage());
            client.close();
            throw e;
        }
    }

    /** Marks the pool closed. Idempotent. */
    public void close() {
        closed = true;
    }
}
