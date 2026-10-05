package dev.amirzr.flutter_v2ray_client.v2ray.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities;

/**
 * VpnService-based proxy engine for the Cloudflare Worker WebSocket tunnel
 * (ech.txt protocol).
 *
 * <p>Establishes a VPN interface, runs tun2socks to redirect all app traffic
 * to a local SOCKS5 server, and forwards every connection through the Go
 * kernel (x-tunnel, the EchOS engine) which dials the Worker over a WebSocket
 * tunnel using the CONNECT/DATA/CLOSE frame protocol. The Go kernel handles
 * TCP + UDP/DNS (DNS-over-TCP / DoH) natively, so this engine completely
 * bypasses the libv2ray core.
 */
public class WsProxyVpnService extends VpnService {

    private static final String TAG = "WsProxyVpnService";
    private static final int NOTIFICATION_ID = 1;
    /** Port the Go kernel exposes its SOCKS5 server on (tun2socks target). */
    private static final int KERNEL_SOCKS5_PORT = 10808;

    /** Latest running instance, for native probes that need socket protection. */
    private static volatile WsProxyVpnService sInstance;

    /** Returns the currently running service instance, or null. */
    public static WsProxyVpnService getInstance() {
        return sInstance;
    }

    private ParcelFileDescriptor mInterface;
    private Process tunProcess;
    private Process kernelProcess;
    private volatile boolean isRunning = true;

    private String wsUrl;
    private String token;
    private String preferredIp;
    private String dohServer;
    private String pubKeyDomain;
    private String remark;
    private ArrayList<String> blockedApps;
    private ArrayList<String> bypassSubnets;

    private CountDownTimer countDownTimer;
    private int seconds, minutes, hours;
    private long totalDownload, totalUpload;
    private String serviceDuration = "00:00:00";

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        isRunning = false;
        tunProcess = null;
        kernelProcess = null;
        mInterface = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = intent != null ? intent.getStringExtra("COMMAND") : null;
        if ("STOP_SERVICE".equals(command)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        wsUrl = intent.getStringExtra("WS_URL");
        token = intent.getStringExtra("WS_TOKEN");
        preferredIp = intent.getStringExtra("WS_PREFERRED_IP");
        dohServer = intent.getStringExtra("ECH_DOH_SERVER");
        pubKeyDomain = intent.getStringExtra("ECH_PUBKEY_DOMAIN");
        remark = intent.getStringExtra("REMARK");
        blockedApps = intent.getStringArrayListExtra("BLOCKED_APPS");
        bypassSubnets = intent.getStringArrayListExtra("BYPASS_SUBNETS");

        if (wsUrl == null || wsUrl.isEmpty()) {
            Log.e(TAG, "Missing WS_URL");
            stopSelf();
            return START_NOT_STICKY;
        }

        setup();
        return START_NOT_STICKY;
    }

    private void setup() {
        Intent prepareIntent = prepare(this);
        if (prepareIntent != null) {
            // Permission not granted; the plugin already handles this via
            // requestPermission() before starting the service.
            stopSelf();
            return;
        }

        Builder builder = new Builder();
        builder.setSession(remark != null ? remark : "ProxyCloud");
        builder.setMtu(1500);
        builder.addAddress("26.26.26.1", 30);
        builder.addRoute("0.0.0.0", 0);
        try {
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");
        } catch (Exception ignored) {
        }
        if (blockedApps != null) {
            for (String pkg : blockedApps) {
                try {
                    builder.addDisallowedApplication(pkg);
                } catch (Exception ignored) {
                }
            }
        }
        if (bypassSubnets != null) {
            for (String subnet : bypassSubnets) {
                try {
                    String[] parts = subnet.split("/");
                    if (parts.length == 2) {
                        builder.addRoute(parts[0], Integer.parseInt(parts[1]));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        try {
            mInterface = builder.establish();
            if (mInterface == null) {
                Log.e(TAG, "VPN establish returned null");
                stopSelf();
                return;
            }
            isRunning = true;
            AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;
            startForegroundCompat();
            startDurationTimer();
            startGoKernel();
            runTun2socks();
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN interface", e);
            stopAll();
        }
    }

    /**
     * Extracts the Go kernel (x-tunnel, EchOS engine) from assets and starts
     * it as a child process with the SOCKS5 listener the tunnel feeds into.
     */
    private void startGoKernel() {
        try {
            File kernel = extractKernel();
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(kernel.getAbsolutePath());
            // Local SOCKS5 listener: tun2socks connects here
            cmd.add("-l");
            cmd.add("socks5://127.0.0.1:" + KERNEL_SOCKS5_PORT);
            // Worker WS endpoint. wsUrl is ech://domain:port?ip=...&token=...
            // (or ws:// / wss://). Build the -f target from the URL's scheme,
            // host and port so the Host header / SNI carry the Worker domain,
            // while -ip points the TCP connection at the preferred IP.
            String scheme = "wss";
            String hostPort = wsUrl;
            if (wsUrl != null) {
                String u = wsUrl;
                int q = u.indexOf('?');
                if (q >= 0) u = u.substring(0, q);
                if (u.startsWith("ech://")) u = "wss://" + u.substring("ech://".length());
                if (u.startsWith("ws://")) {
                    scheme = "ws";
                } else if (u.startsWith("wss://")) {
                    scheme = "wss";
                } else if (u.indexOf("://") < 0) {
                    u = "wss://" + u;
                }
                hostPort = u;
            }
            cmd.add("-f");
            cmd.add(hostPort);
            cmd.add("-n");
            cmd.add("3");
            if (preferredIp != null && !preferredIp.isEmpty()) {
                cmd.add("-ip");
                cmd.add(preferredIp);
            }
            if (token != null && !token.isEmpty()) {
                cmd.add("-token");
                cmd.add(token);
            }
            // ECH public-key bootstrap: the Worker is served through Cloudflare,
            // which offers ECH (TLS Encrypted Client Hello). The kernel resolves
            // the ECH config via DoH (-dns) for the given domain (-ech), same
            // as the EchOS client. Falls back to the kernel defaults (doh.pub,
            // cloudflare-ech.com) when the user left the settings blank.
            cmd.add("-ech");
            cmd.add(pubKeyDomain != null && !pubKeyDomain.isEmpty()
                    ? pubKeyDomain : "cloudflare-ech.com");
            cmd.add("-dns");
            cmd.add(dohServer != null && !dohServer.isEmpty()
                    ? dohServer : "https://doh.pub/dns-query");
            // Block UDP 443 (QUIC) like EchOS does
            cmd.add("-block");
            cmd.add("443");
            cmd.add("-default");
            cmd.add("all");
            cmd.add("-loglevel");
            cmd.add("info");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            kernelProcess = pb.start();
            Log.d(TAG, "Go kernel started (pid=" + kernelProcess.pid() + "): " + hostPort
                    + (preferredIp != null ? " ip=" + preferredIp : ""));
            // Consume kernel logs so the pipe doesn't fill up
            Thread logThread = new Thread(() -> {
                try (InputStream is = kernelProcess.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (true) {
                            Log.d(TAG, "kernel: " + new String(buf, 0, n).trim());
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "ech-kernel-log");
            logThread.setDaemon(true);
            logThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Go kernel", e);
        }
    }

    /** Copies the packaged Go kernel binary out of assets and makes it executable. */
    private File extractKernel() throws IOException {
        File dir = new File(getFilesDir(), "ech");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File target = new File(dir, "xtun");
        // Asset names: xtun-arm64 (aarch64) / xtun-arm (armeabi-v7a) / xtun-x86_64.
        // (Android reports ABIs like "arm64-v8a" — map them to our asset names.)
        String abi = android.os.Build.SUPPORTED_ABIS != null && android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        String assetName;
        if (abi.startsWith("arm64")) {
            assetName = "xtun-arm64";
        } else if (abi.startsWith("armeabi")) {
            assetName = "xtun-arm";
        } else if (abi.startsWith("x86_64") || abi.startsWith("x86")) {
            assetName = "xtun-x86_64";
        } else {
            throw new IOException("unsupported ABI: " + abi);
        }
        boolean exists = target.exists() && target.length() > 1000000;
        if (!exists) {
            try (InputStream is = getAssets().open(assetName)) {
                try (OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
        if (!target.setExecutable(true, true)) {
            Log.w(TAG, "setExecutable failed for " + target);
        }
        return target;
    }

    private void runTun2socks() {
        ArrayList<String> cmd = new ArrayList<>();
        cmd.add(new File(getApplicationInfo().nativeLibraryDir, "libtun2socks.so").getAbsolutePath());
        cmd.add("--netif-ipaddr");
        cmd.add("26.26.26.2");
        cmd.add("--netif-netmask");
        cmd.add("255.255.255.252");
        cmd.add("--socks-server-addr");
        cmd.add("127.0.0.1:10808");
        cmd.add("--tunmtu");
        cmd.add("1500");
        cmd.add("--sock-path");
        cmd.add("sock_path");
        cmd.add("--enable-udprelay");
        cmd.add("--socks5-udp");
        cmd.add("--loglevel");
        cmd.add("none");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);
            tunProcess = processBuilder.directory(getApplicationContext().getFilesDir()).start();
            new Thread(() -> {
                try {
                    int exitCode = tunProcess.waitFor();
                    Log.d(TAG, "tun2socks exited: " + exitCode);
                    if (isRunning && exitCode != 0) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        runTun2socks();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Tun2socks_Thread").start();
            sendFileDescriptor();
        } catch (Exception e) {
            Log.e(TAG, "FAILED to start tun2socks", e);
            stopAll();
        }
    }

    private void sendFileDescriptor() {
        String localSocksFile = new File(getApplicationContext().getFilesDir(), "sock_path").getAbsolutePath();
        FileDescriptor tunFd = mInterface.getFileDescriptor();
        new Thread(() -> {
            int tries = 0;
            while (true) {
                try {
                    Thread.sleep(50L * tries);
                    LocalSocket clientLocalSocket = new LocalSocket();
                    clientLocalSocket.connect(new LocalSocketAddress(localSocksFile, LocalSocketAddress.Namespace.FILESYSTEM));
                    if (clientLocalSocket.isConnected()) {
                        OutputStream clientOutStream = clientLocalSocket.getOutputStream();
                        clientLocalSocket.setFileDescriptorsForSend(new FileDescriptor[]{tunFd});
                        clientOutStream.write(32);
                        clientLocalSocket.setFileDescriptorsForSend(null);
                        clientLocalSocket.shutdownOutput();
                        clientLocalSocket.close();
                    }
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "sendFd failed", e);
                    if (tries > 5) break;
                    tries += 1;
                }
            }
        }, "sendFd_Thread").start();
    }

    private void stopAll() {
        isRunning = false;
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {
        }
        sendDisconnectedBroadcast();
        stopKernel();
        if (tunProcess != null) {
            try {
                tunProcess.destroy();
                if (!tunProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    tunProcess.destroyForcibly();
                }
            } catch (Exception e) {
                try {
                    tunProcess.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            tunProcess = null;
        }
        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing VPN interface: " + e.getMessage());
            }
            mInterface = null;
        }
        stopSelf();
    }

    private void stopKernel() {
        if (kernelProcess != null) {
            try {
                kernelProcess.destroy();
                if (!kernelProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    kernelProcess.destroyForcibly();
                }
            } catch (Exception e) {
                try {
                    kernelProcess.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            kernelProcess = null;
        }
    }

    private void startForegroundCompat() {
        // Android 13+ requires POST_NOTIFICATIONS permission to show the notification.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Still start foreground with a minimal notification; Android allows
                // FGS without the permission (it just won't be visible).
            }
        }
        String channelId = "A_FLUTTER_V2RAY_SERVICE_CH_ID";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(channelId,
                        "ProxyCloud Background Service", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("ProxyCloud background service");
                channel.setLightColor(Color.DKGRAY);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                nm.createNotificationChannel(channel);
            }
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.setAction("FROM_DISCONNECT_BTN");
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, launchIntent, flags);

        Intent stopIntent = new Intent(this, WsProxyVpnService.class);
        stopIntent.putExtra("COMMAND", "STOP_SERVICE");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(AppConfigs.APPLICATION_ICON)
                .setContentTitle(remark != null ? remark : "ProxyCloud")
                .setContentText("WebSocket Tunnel")
                .addAction(0, "DISCONNECT", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .setSilent(true)
                .setOngoing(true)
                .setAutoCancel(false);
        try {
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed: " + e.getMessage());
        }
    }

    private void startDurationTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        seconds = 0;
        minutes = 0;
        hours = 0;
        totalDownload = 0;
        totalUpload = 0;
        serviceDuration = "00:00:00";
        countDownTimer = new CountDownTimer(7200, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (!isRunning) {
                    cancel();
                    return;
                }
                seconds++;
                if (seconds >= 60) {
                    minutes++;
                    seconds = seconds % 60;
                }
                if (minutes >= 60) {
                    minutes = 0;
                    hours++;
                }
                if (hours >= 24) {
                    hours = 0;
                }
                serviceDuration = Utilities.convertIntToTwoDigit(hours) + ":"
                        + Utilities.convertIntToTwoDigit(minutes) + ":"
                        + Utilities.convertIntToTwoDigit(seconds);
                sendStatusBroadcast();
            }

            @Override
            public void onFinish() {
                if (isRunning) {
                    startDurationTimer();
                }
            }
        }.start();
        sendStatusBroadcast();
    }

    private void sendStatusBroadcast() {
        try {
            Intent intent = new Intent("V2RAY_CONNECTION_INFO");
            intent.putExtra("STATE", AppConfigs.V2RAY_STATE);
            intent.putExtra("DURATION", serviceDuration);
            intent.putExtra("UPLOAD_SPEED", 0L);
            intent.putExtra("DOWNLOAD_SPEED", 0L);
            intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
            intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "sendStatusBroadcast failed: " + e.getMessage());
        }
    }

    private void sendDisconnectedBroadcast() {
        serviceDuration = "00:00:00";
        try {
            Intent intent = new Intent("V2RAY_CONNECTION_INFO");
            intent.putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED);
            intent.putExtra("DURATION", serviceDuration);
            intent.putExtra("UPLOAD_SPEED", 0L);
            intent.putExtra("DOWNLOAD_SPEED", 0L);
            intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
            intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "sendDisconnectedBroadcast failed: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        if (isRunning) {
            stopAll();
        }
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopAll();
    }
}
