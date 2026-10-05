import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_v2ray_platform_interface.dart';
import 'model/v2ray_status.dart' show V2RayStatus;

/// An implementation of [FlutterV2rayPlatform] that uses method channels.
class MethodChannelFlutterV2ray extends FlutterV2rayPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_v2ray_client');

  /// The event channel used to receive status updates from the native platform.
  final eventChannel = const EventChannel('flutter_v2ray_client/status');

  @override
  Future<void> initializeV2Ray({
    required void Function(V2RayStatus status) onStatusChanged,
    required String notificationIconResourceType,
    required String notificationIconResourceName,
  }) async {
    eventChannel.receiveBroadcastStream().distinct().cast().listen((event) {
      if (event != null) {
        onStatusChanged.call(V2RayStatus(
          duration: event[0],
          uploadSpeed: int.parse(event[1]),
          downloadSpeed: int.parse(event[2]),
          upload: int.parse(event[3]),
          download: int.parse(event[4]),
          state: event[5],
        ));
      }
    });
    await methodChannel.invokeMethod(
      'initializeV2Ray',
      {
        'notificationIconResourceType': notificationIconResourceType,
        'notificationIconResourceName': notificationIconResourceName,
      },
    );
  }

  @override
  Future<void> startV2Ray({
    required String remark,
    required String config,
    required String notificationDisconnectButtonName,
    List<String>? blockedApps,
    List<String>? bypassSubnets,
    bool proxyOnly = false,
  }) async {
    await methodChannel.invokeMethod('startV2Ray', {
      'remark': remark,
      'config': config,
      'blocked_apps': blockedApps,
      'bypass_subnets': bypassSubnets,
      'proxy_only': proxyOnly,
      'notificationDisconnectButtonName': notificationDisconnectButtonName,
    });
  }

  /// Starts the Cloudflare Worker WebSocket tunnel engine (ech.txt protocol).
  /// [config] is the JSON blob from [ECHURL.getFullEchConfig].
  Future<void> startEchProxy({
    required String remark,
    required String config,
    String dohServer = '',
    String pubKeyDomain = '',
    List<String>? blockedApps,
    List<String>? bypassSubnets,
  }) async {
    await methodChannel.invokeMethod('startEchProxy', {
      'remark': remark,
      'config': config,
      'doh_server': dohServer,
      'pubkey_domain': pubKeyDomain,
      'blocked_apps': blockedApps,
      'bypass_subnets': bypassSubnets,
    });
  }

  /// Stops the WebSocket tunnel engine.
  Future<void> stopEchProxy() async {
    await methodChannel.invokeMethod('stopEchProxy');
  }

  /// Probes the ech tunnel end-to-end: opens a WebSocket to the Worker and
  /// performs a real CONNECT handshake (target 8.8.8.8:53). Returns a map
  /// with `ok` (bool), `ws` (bool) and `error` (string?).
  Future<Map<String, dynamic>> echReachability({
    required String wsUrl,
    String? token,
  }) async {
    final res = await methodChannel.invokeMethod('echReachability', {
      'ws_url': wsUrl,
      'token': token ?? '',
    });
    if (res is Map) {
      return Map<String, dynamic>.from(res);
    }
    return {'ok': false, 'ws': false, 'error': 'Unexpected native response'};
  }

  /// HTTP generate_204 probe THROUGH the ech tunnel. The hostname is resolved
  /// by the Worker (server-side), so this works even when client-side UDP DNS
  /// through the tunnel is broken. Returns a map with `ok` and `error`.
  Future<Map<String, dynamic>> echHttpProbe({
    required String wsUrl,
    String? token,
    required String host,
    int port = 80,
  }) async {
    final res = await methodChannel.invokeMethod('echHttpProbe', {
      'ws_url': wsUrl,
      'token': token ?? '',
      'host': host,
      'port': port,
    });
    if (res is Map) {
      return Map<String, dynamic>.from(res);
    }
    return {'ok': false, 'error': 'Unexpected native response'};
  }

  @override
  Future<void> stopV2Ray() async {
    await methodChannel.invokeMethod('stopV2Ray');
  }

  @override
  Future<int> getServerDelay({
    required String config,
    required String url,
  }) async {
    return await methodChannel.invokeMethod('getServerDelay', {
      'config': config,
      'url': url,
    });
  }

  @override
  Future<int> getConnectedServerDelay(String url) async {
    return await methodChannel
        .invokeMethod('getConnectedServerDelay', {'url': url});
  }

  @override
  Future<bool> requestPermission() async {
    return (await methodChannel.invokeMethod('requestPermission')) ?? false;
  }

  @override
  Future<String> getCoreVersion() async {
    return await methodChannel.invokeMethod('getCoreVersion');
  }
  
  @override
  Future<String> getConnectionState() async {
    return await methodChannel.invokeMethod('getConnectionState');
  }
}