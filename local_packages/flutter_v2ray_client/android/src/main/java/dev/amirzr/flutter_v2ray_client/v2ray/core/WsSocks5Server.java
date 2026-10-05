package dev.amirzr.flutter_v2ray_client.v2ray.core;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal SOCKS5 server (no auth) that hands every accepted connection to the
 * {@link WsTunnelPool} as a CONNECT session. tun2socks points all VPN traffic
 * at 127.0.0.1:{@value #PORT}.
 *
 * <p>Only CONNECT (0x01) is supported; UDP ASSOCIATE and BIND return the
 * standard "command not supported" reply.
 */
public class WsSocks5Server {

    private static final String TAG = "WsSocks5Server";

    public static final int PORT = 10808;
    private static final int SOCKS5_VERSION = 0x05;
    private static final int CMD_CONNECT = 0x01;
    private static final int CMD_UDP_ASSOCIATE = 0x03;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REP_SUCCESS = 0x00;
    private static final int REP_FAILURE = 0x01;
    private static final int REP_NOT_ALLOWED = 0x02;
    private static final int REP_CMD_NOT_SUPPORTED = 0x07;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int UDP_RELAY_PORT = 10809;

    private final WsTunnelPool pool;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private WsUdpRelay udpRelay;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ech-socks-accept");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ech-socks-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WsSocks5Server(WsTunnelPool pool) {
        this.pool = pool;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        acceptExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
                Log.d(TAG, "SOCKS5 listening on 127.0.0.1:" + PORT);
                // UDP relay socket (SOCKS5 UDP ASSOCIATE target)
                try {
                    udpSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", UDP_RELAY_PORT));
                    udpRelay = new WsUdpRelay(pool, udpSocket);
                    udpRelay.start();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to bind UDP relay on " + UDP_RELAY_PORT, e);
                }
                while (running.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        workerPool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.w(TAG, "accept failed: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to bind SOCKS5 server", e);
            }
        });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (udpRelay != null) {
            udpRelay.stop();
        } else if (udpSocket != null) {
            udpSocket.close();
        }
        workerPool.shutdownNow();
        acceptExecutor.shutdownNow();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // ---- greeting: VER NMETHODS METHODS
            int version = in.read();
            if (version != SOCKS5_VERSION) {
                closeQuietly(client);
                return;
            }
            int nMethods = in.read();
            if (nMethods <= 0) {
                closeQuietly(client);
                return;
            }
            byte[] methods = new byte[nMethods];
            readFully(in, methods);
            // Reply: no auth (0x00)
            out.write(new byte[]{SOCKS5_VERSION, 0x00});
            out.flush();

            // ---- request: VER CMD RSV ATYP ...
            int reqVersion = in.read();
            int cmd = in.read();
            in.read(); // RSV
            int atyp = in.read();
            if (reqVersion != SOCKS5_VERSION) {
                sendReply(out, REP_FAILURE, null);
                closeQuietly(client);
                return;
            }
            if (cmd == CMD_UDP_ASSOCIATE) {
                // Read (and ignore) the requested address/port (usually 0.0.0.0:0),
                // reply with our UDP relay address, then keep the TCP control
                // connection open until the client closes it.
                try {
                    skipAddressAndPort(in, atyp);
                } catch (IOException e) {
                    sendReply(out, REP_FAILURE, null);
                    closeQuietly(client);
                    return;
                }
                if (udpSocket == null) {
                    sendReply(out, REP_CMD_NOT_SUPPORTED, null);
                    closeQuietly(client);
                    return;
                }
                sendUdpAssociateReply(out);
                // Block until the client closes the control connection.
                try {
                    while (in.read() != -1) {
                        // drain
                    }
                } catch (IOException ignored) {
                }
                closeQuietly(client);
                return;
            }
            if (cmd != CMD_CONNECT) {
                sendReply(out, REP_CMD_NOT_SUPPORTED, null);
                closeQuietly(client);
                return;
            }

            String host;
            switch (atyp) {
                case ATYP_IPV4: {
                    byte[] addr = new byte[4];
                    readFully(in, addr);
                    host = InetAddress.getByAddress(addr).getHostAddress();
                    break;
                }
                case ATYP_IPV6: {
                    byte[] addr = new byte[16];
                    readFully(in, addr);
                    host = InetAddress.getByAddress(addr).getHostAddress();
                    break;
                }
                case ATYP_DOMAIN: {
                    int len = in.read();
                    if (len <= 0 || len > 255) {
                        sendReply(out, REP_FAILURE, null);
                        closeQuietly(client);
                        return;
                    }
                    byte[] domain = new byte[len];
                    readFully(in, domain);
                    host = new String(domain, StandardCharsets.UTF_8);
                    break;
                }
                default:
                    sendReply(out, REP_FAILURE, null);
                    closeQuietly(client);
                    return;
            }
            int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // ---- hand off to the tunnel pool
            try {
                WsTunnelClient.Session session =
                        pool.connectTarget(client, host, port, CONNECT_TIMEOUT_MS);
                sendReply(out, REP_SUCCESS, client.getLocalAddress());
                session.start();
            } catch (IOException e) {
                Log.w(TAG, "CONNECT to " + host + ":" + port + " failed: " + e.getMessage());
                sendReply(out, REP_FAILURE, null);
                closeQuietly(client);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleClient error: " + e.getMessage());
            closeQuietly(client);
        }
    }

    private void sendUdpAssociateReply(OutputStream out) throws IOException {
        // Reply with BND.ADDR=127.0.0.1 BND.PORT=UDP_RELAY_PORT
        byte[] reply = new byte[10];
        reply[0] = SOCKS5_VERSION;
        reply[1] = REP_SUCCESS;
        reply[2] = 0x00;
        reply[3] = ATYP_IPV4;
        byte[] addr = new byte[]{127, 0, 0, 1};
        System.arraycopy(addr, 0, reply, 4, 4);
        reply[8] = (byte) ((UDP_RELAY_PORT >> 8) & 0xFF);
        reply[9] = (byte) (UDP_RELAY_PORT & 0xFF);
        out.write(reply);
        out.flush();
    }

    private static void skipAddressAndPort(InputStream in, int atyp) throws IOException {
        switch (atyp) {
            case ATYP_IPV4:
                readFully(in, new byte[4]);
                break;
            case ATYP_IPV6:
                readFully(in, new byte[16]);
                break;
            case ATYP_DOMAIN: {
                int len = in.read();
                if (len <= 0 || len > 255) {
                    throw new IOException("bad domain length");
                }
                readFully(in, new byte[len]);
                break;
            }
            default:
                throw new IOException("bad atyp " + atyp);
        }
        readFully(in, new byte[2]); // port
    }

    private void sendReply(OutputStream out, int rep, InetAddress bindAddr) throws IOException {
        byte[] reply = new byte[10];
        reply[0] = SOCKS5_VERSION;
        reply[1] = (byte) rep;
        reply[2] = 0x00;
        if (rep == REP_SUCCESS && bindAddr != null) {
            byte[] addr = bindAddr.getAddress();
            if (addr.length == 4) {
                reply[3] = ATYP_IPV4;
                System.arraycopy(addr, 0, reply, 4, 4);
            } else {
                reply[3] = ATYP_IPV6;
                System.arraycopy(addr, 0, reply, 4, 16);
                // For IPv6 the reply needs 22 bytes total; resize.
                byte[] ipv6Reply = new byte[22];
                System.arraycopy(reply, 0, ipv6Reply, 0, 4);
                System.arraycopy(addr, 0, ipv6Reply, 4, 16);
                ipv6Reply[20] = 0;
                ipv6Reply[21] = 0;
                out.write(ipv6Reply);
                out.flush();
                return;
            }
        } else {
            reply[3] = ATYP_IPV4;
        }
        out.write(reply);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Unexpected EOF");
            off += n;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
