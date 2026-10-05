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
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import dev.amirzr.flutter_v2ray_client.v2ray.core.WsTunnelPool;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities;

/**
 * VpnService-based proxy engine for the Cloudflare Worker WebSocket tunnel
 * (ech.txt protocol).
 *
 * <p>Establishes a VPN interface, runs tun2socks to redirect all app traffic
 * to a local SOCKS5 server ({@link WsSocks5Server}), and forwards every SOCKS5
 * connection through a {@link WsTunnelPool} to the Worker using the custom
 * CONNECT/DATA/CLOSE WebSocket frame protocol. This engine completely bypasses
 * the libv2ray core.
 */
public class WsProxyVpnService extends VpnService {

    private static final String TAG = "WsProxyVpnService";
    private static final int NOTIFICATION_ID = 1;

    private ParcelFileDescriptor mInterface;
    private Process tunProcess;
    private WsSocks5Server socksServer;
    private WsTunnelPool pool;
    private volatile boolean isRunning = true;

    private String wsUrl;
    private String token;
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
        isRunning = false;
        tunProcess = null;
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
            if (pool == null) {
                pool = new WsTunnelPool(wsUrl, token, this);
            }
            startSocksServer();
            runTun2socks();
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN interface", e);
            stopAll();
        }
    }

    private void startSocksServer() {
        socksServer = new WsSocks5Server(pool);
        socksServer.start();
        Log.d(TAG, "SOCKS5 server started on 127.0.0.1:" + WsSocks5Server.PORT);
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
        if (pool != null) {
            pool.close();
            pool = null;
        }
        if (socksServer != null) {
            socksServer.stop();
            socksServer = null;
        }
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

    private void startForegroundCompat() {
        // Android 13+ requires POST_NOTIFICATIONS permission to show the notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopAll();
    }
}
