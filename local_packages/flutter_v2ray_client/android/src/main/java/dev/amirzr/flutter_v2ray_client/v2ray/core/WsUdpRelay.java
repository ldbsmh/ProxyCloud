package dev.amirzr.flutter_v2ray_client.v2ray.core;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP relay for the ech tunnel.
 *
 * <p>tun2socks (with {@code --socks5-udp}) sends UDP datagrams to our SOCKS5
 * server wrapped in the standard SOCKS5 UDP relay format (RFC 1928):
 * {@code RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT DATA}. We parse that header,
 * then forward each datagram to its destination through a dedicated
 * WebSocket tunnel session.
 *
 * <p>Cloudflare Workers' {@code connect()} is TCP-only, so a UDP datagram to
 * {@code host:port} is delivered as a TCP byte stream to the same host:port.
 * For DNS (port 53) the payload is framed with the standard 2-byte length
 * prefix (RFC 1035 §4.2.2 "TCP usage") — most public resolvers (8.8.8.8,
 * 1.1.1.1, 223.5.5.5, ...) accept DNS over TCP on port 53. Other UDP ports
 * are sent as raw byte streams (best effort).
 *
 * <p>One tunnel session is created per destination address and kept open,
 * keyed by "host:port", so a DNS client (usually a single resolver) reuses
 * the same tunnel.
 */
public class WsUdpRelay {

    private static final String TAG = "WsUdpRelay";

    private static final int SOCKS5_ATYP_IPV4 = 0x01;
    private static final int SOCKS5_ATYP_DOMAIN = 0x03;
    private static final int SOCKS5_ATYP_IPV6 = 0x04;

    private final WsTunnelPool pool;
    private final DatagramSocket udpSocket;
    private final Map<String, UdpSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread receiverThread;

    public WsUdpRelay(WsTunnelPool pool, DatagramSocket udpSocket) {
        this.pool = pool;
        this.udpSocket = udpSocket;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        receiverThread = new Thread(this::receiveLoop, "ech-udp-relay");
        receiverThread.setDaemon(true);
        receiverThread.start();
        Log.d(TAG, "UDP relay started on " + udpSocket.getLocalPort());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        for (UdpSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
        udpSocket.close();
    }

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        while (running.get()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udpSocket.receive(pkt);
                handleDatagram(buf, pkt.getLength(), pkt.getAddress(), pkt.getPort());
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "udp receive failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Parses a SOCKS5 UDP relay datagram and forwards the payload.
     *
     * @param data      full datagram bytes (header + payload)
     * @param length    valid length in {@code data}
     * @param srcAddr   source (the SOCKS client / tun2socks)
     * @param srcPort   source port
     */
    private void handleDatagram(byte[] data, int length, InetAddress srcAddr, int srcPort) {
        if (length < 4) {
            Log.w(TAG, "UDP relay packet too short: " + length);
            return;
        }
        int rsv = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int frag = data[2] & 0xFF;
        if (rsv != 0 || frag != 0) {
            // Fragmentation is not supported (RFC 1928 says FRAG != 0 must be dropped
            // unless the implementation reassembles; tun2socks does not fragment).
            Log.w(TAG, "drop UDP relay packet rsv=" + rsv + " frag=" + frag);
            return;
        }
        int atyp = data[3] & 0xFF;
        int idx = 4;
        String host;
        byte[] addrHeader;
        try {
            switch (atyp) {
                case SOCKS5_ATYP_IPV4: {
                    if (length < idx + 4) return;
                    host = InetAddress.getByAddress(
                            new byte[]{data[idx], data[idx + 1], data[idx + 2], data[idx + 3]})
                            .getHostAddress();
                    addrHeader = new byte[]{data[idx], data[idx + 1], data[idx + 2], data[idx + 3]};
                    idx += 4;
                    break;
                }
                case SOCKS5_ATYP_IPV6: {
                    if (length < idx + 16) return;
                    byte[] v6 = new byte[16];
                    System.arraycopy(data, idx, v6, 0, 16);
                    host = InetAddress.getByAddress(v6).getHostAddress();
                    addrHeader = v6;
                    idx += 16;
                    break;
                }
                case SOCKS5_ATYP_DOMAIN: {
                    int len = data[idx] & 0xFF;
                    idx += 1;
                    if (len == 0 || length < idx + len) return;
                    host = new String(data, idx, len, java.nio.charset.StandardCharsets.UTF_8);
                    addrHeader = new byte[len + 1];
                    addrHeader[0] = (byte) len;
                    System.arraycopy(data, idx, addrHeader, 1, len);
                    idx += len;
                    break;
                }
                default:
                    Log.w(TAG, "drop UDP relay packet with unknown ATYP " + atyp);
                    return;
            }
            if (length < idx + 2) return;
            int port = ((data[idx] & 0xFF) << 8) | (data[idx + 1] & 0xFF);
            idx += 2;
            if (length <= idx) return; // empty payload — nothing to forward
            byte[] payload = new byte[length - idx];
            System.arraycopy(data, idx, payload, 0, payload.length);

            forward(host, port, atyp, addrHeader, payload, srcAddr, srcPort);
        } catch (Exception e) {
            Log.w(TAG, "udp packet parse error: " + e.getMessage());
        }
    }

    private void forward(String host, int port, int atyp, byte[] addrHeader, byte[] payload,
                         InetAddress srcAddr, int srcPort) {
        String key = host + ":" + port;
        UdpSession session = sessions.get(key);
        if (session == null || !session.isAlive()) {
            try {
                session = new UdpSession(host, port, srcAddr, srcPort, addrHeader, atyp);
                sessions.put(key, session);
                session.start();
                Log.d(TAG, "new UDP tunnel session for " + key);
            } catch (IOException e) {
                Log.w(TAG, "failed to open UDP tunnel to " + key + ": " + e.getMessage());
                return;
            }
        }
        session.send(payload);
    }

    private void reply(InetAddress srcAddr, int srcPort, byte[] addrHeader, int atyp,
                       int dstPort, byte[] data) {
        try {
            // Build SOCKS5 UDP relay header: RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA
            int addrLen = (atyp == SOCKS5_ATYP_IPV4) ? 4 : (atyp == SOCKS5_ATYP_IPV6) ? 16
                    : (addrHeader != null ? addrHeader.length : 0);
            byte[] header = new byte[4 + addrLen + 2];
            header[3] = (byte) atyp;
            if (addrLen > 0 && addrHeader != null) {
                System.arraycopy(addrHeader, 0, header, 4, addrLen);
            }
            header[header.length - 2] = (byte) ((dstPort >> 8) & 0xFF);
            header[header.length - 1] = (byte) (dstPort & 0xFF);

            byte[] out = new byte[header.length + data.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(data, 0, out, header.length, data.length);
            udpSocket.send(new DatagramPacket(out, out.length, srcAddr, srcPort));
        } catch (Exception e) {
            Log.w(TAG, "udp reply failed: " + e.getMessage());
        }
    }

    /**
     * One tunnel session per destination: a WS tunnel whose local side is a
     * pipe. The pump thread reads datagrams queued via {@link #send} — for DNS
     * (port 53) each datagram is framed with the 2-byte TCP length prefix.
     * Tunnel bytes are read back, deframed, and returned to the SOCKS client
     * in SOCKS5 UDP relay format.
     */
    private class UdpSession {
        final String host;
        final int port;
        final InetAddress srcAddr;
        final int srcPort;
        // Original SOCKS5 UDP relay header address (ATYP + ADDR bytes) so the
        // reply echoes exactly what the client sent — no DNS resolution here
        // (resolving inside the VPN would loop back into the tunnel).
        final byte[] addrHeader;
        final int atyp;
        final PipedOutputStream toTunnel = new PipedOutputStream();
        final PipedInputStream toTunnelIn = new PipedInputStream(1 << 16);
        final PipedInputStream fromTunnelIn = new PipedInputStream(1 << 16);
        final PipedOutputStream fromTunnelOut = new PipedOutputStream();
        final WsTunnelClient client;
        final WsTunnelClient.Session session;
        final boolean dnsFramed;
        final AtomicBoolean alive = new AtomicBoolean(true);
        Thread readerThread;

        UdpSession(String host, int port, InetAddress srcAddr, int srcPort,
                   byte[] addrHeader, int atyp) throws IOException {
            this.host = host;
            this.port = port;
            this.srcAddr = srcAddr;
            this.srcPort = srcPort;
            this.addrHeader = addrHeader;
            this.atyp = atyp;
            this.dnsFramed = (port == 53);
            // toTunnel <-> toTunnelIn: SOCKS client -> tunnel (written by send(),
            // read by the session pump thread)
            toTunnel.connect(toTunnelIn);
            // fromTunnelIn <-> fromTunnelOut: tunnel -> SOCKS client
            fromTunnelIn.connect(fromTunnelOut);
            WsTunnelClient client = new WsTunnelClient(
                    pool.getWsUrl(), pool.getToken(), pool.getVpnService(), pool.getPreferredIp());
            this.client = client;
            this.session = client.connectPipeAndAwait(toTunnelIn, fromTunnelOut, host, port);
            this.session.start();
        }

        void start() {
            readerThread = new Thread(this::readLoop, "ech-udp-read");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        void send(byte[] payload) {
            try {
                if (dnsFramed) {
                    // DNS over TCP: 2-byte length prefix (RFC 1035)
                    int len = payload.length;
                    toTunnel.write((len >> 8) & 0xFF);
                    toTunnel.write(len & 0xFF);
                    toTunnel.write(payload);
                } else {
                    toTunnel.write(payload);
                }
                toTunnel.flush();
            } catch (IOException e) {
                close();
            }
        }

        void readLoop() {
            try {
                InputStream in = fromTunnelIn;
                if (dnsFramed) {
                    byte[] lenBuf = new byte[2];
                    while (alive.get()) {
                        int n = readFully(in, lenBuf);
                        if (n < 0) break;
                        int len = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
                        if (len <= 0 || len > 65535) break;
                        byte[] data = new byte[len];
                        if (readFully(in, data) < 0) break;
                        reply(srcAddr, srcPort, addrHeader, atyp, port, data);
                    }
                } else {
                    byte[] buf = new byte[8192];
                    int n;
                    while (alive.get() && (n = in.read(buf)) > 0) {
                        byte[] data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                        reply(srcAddr, srcPort, addrHeader, atyp, port, data);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "udp read loop end: " + e.getMessage());
            } finally {
                close();
            }
        }

        boolean isAlive() {
            return alive.get() && !client.isClosed();
        }

        void close() {
            if (!alive.compareAndSet(true, false)) return;
            sessions.remove(host + ":" + port);
            try {
                toTunnel.close();
            } catch (IOException ignored) {
            }
            try {
                toTunnelIn.close();
            } catch (IOException ignored) {
            }
            try {
                fromTunnelIn.close();
            } catch (IOException ignored) {
            }
            try {
                fromTunnelOut.close();
            } catch (IOException ignored) {
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
            try {
                if (session != null) session.kill();
            } catch (Exception ignored) {
            }
        }

        private int readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) return off == 0 ? -1 : off;
                off += n;
            }
            return off;
        }
    }
}
