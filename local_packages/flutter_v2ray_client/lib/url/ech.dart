import 'dart:convert';

import 'package:flutter_v2ray_client/url/url.dart';

/// URL parser for the Cloudflare Worker WebSocket proxy (ech.txt server).
///
/// The server exposes a raw WebSocket endpoint that tunnels TCP using a
/// custom text-frame protocol:
///   client -> server: "CONNECT:<host>|<port>", "DATA:<payload>", "CLOSE"
///   server -> client: "CONNECTED", "CLOSE", "ERROR:<message>"
///
/// Supported link formats:
///   ws://<worker-host>/<path>?token=<WS_TOKEN>#<remark>
///   wss://<worker-host>/<path>?token=<WS_TOKEN>#<remark>
///   ech://<worker-host>/<path>?token=<WS_TOKEN>#<remark>  (alias, treated as wss)
///
/// The link may also carry a port for wss:// (the Worker is normally served
/// on 443, but the user might have a custom domain).
class ECHURL extends V2RayURL {
  /// Creates an ECHURL by parsing the provided WebSocket proxy link.
  ///
  /// Throws [ArgumentError] if the url does not start with `ech://`, `ws://`
  /// or `wss://`, or cannot be decoded into a valid URI.
  ECHURL({required super.url}) {
    final lower = url.toLowerCase();
    if (!lower.startsWith('ech://') &&
        !lower.startsWith('ws://') &&
        !lower.startsWith('wss://')) {
      throw ArgumentError('url is invalid');
    }

    var raw = url;
    if (lower.startsWith('ech://')) {
      // ech:// is just a friendly alias for wss://
      raw = 'wss://${url.substring(6)}';
    }

    final temp = Uri.tryParse(raw);
    if (temp == null || temp.host.isEmpty) {
      throw ArgumentError('url is invalid');
    }
    uri = temp;
  }

  /// The parsed URI object.
  late final Uri uri;

  /// Worker host, e.g. `my-worker.my-domain.workers.dev`.
  @override
  String get address => uri.host;

  /// WebSocket port (defaults to 443 for wss / 80 for ws).
  @override
  int get port => uri.hasPort ? uri.port : (uri.scheme == 'ws' ? 80 : 443);

  /// Human-readable remark decoded from the URI fragment.
  @override
  String get remark {
    final fragment = uri.fragment;
    if (fragment.isEmpty) return address;
    return Uri.decodeFull(fragment.replaceAll('+', '%20'));
  }

  /// The full ws(s):// URL that the client engine will connect to.
  String get wsUrl {
    final scheme = uri.scheme == 'ws' ? 'ws' : 'wss';
    final portPart = uri.hasPort ? ':${uri.port}' : '';
    final path = uri.path.isEmpty ? '/' : uri.path;
    final query = uri.hasQuery ? '?${uri.query}' : '';
    return '$scheme://${uri.host}$portPart$path$query';
  }

  /// The WS_TOKEN to send via Sec-WebSocket-Protocol, empty if none.
  String get token => uri.queryParameters['token'] ?? '';

  /// Fallback IPs for the `CF_FALLBACK_IPS`-style candidates, empty if none.
  String get fallbackIps => uri.queryParameters['fallback'] ?? '';

  /// Preferred connect IP (优选IP/中转IP) for the Worker domain, empty if none.
  /// The native engine connects to this IP but keeps the domain in Host/SNI.
  String get preferredIp => uri.queryParameters['ip'] ?? '';

  /// The ws(s):// URL without any query parameters.
  String get bareWsUrl {
    final scheme = uri.scheme == 'ws' ? 'ws' : 'wss';
    final portPart = uri.hasPort ? ':${uri.port}' : '';
    final path = uri.path.isEmpty ? '/' : uri.path;
    return '$scheme://${uri.host}$portPart$path';
  }

  /// Full JSON configuration consumed by the native ech engine.
  /// Kept symmetric with the v2ray parsers so `getFullConfiguration()` works
  /// everywhere the app calls it (ping, connectivity test, etc.).
  Map<String, dynamic> get echEngineConfig => {
        'engine': 'ech',
        'remark': remark,
        'address': address,
        'port': port,
        'wsUrl': wsUrl,
        'token': token,
        'fallbackIps': fallbackIps,
        'preferredIp': preferredIp,
      };

  @override
  Map<String, dynamic> get fullConfiguration => {
        'log': log,
        'inbounds': [inbound],
        'outbounds': [outbound1, outbound2, outbound3],
        'dns': dns,
        'routing': routing,
      };

  /// Outbound placeholder so the v2ray core never actually receives this
  /// config; the native side detects `echEngineConfig` first.
  @override
  Map<String, dynamic> get outbound1 => {
        'tag': 'proxy',
        'protocol': 'freedom',
        'settings': <String, dynamic>{},
        'streamSettings': null,
        'proxySettings': null,
        'sendThrough': null,
        'mux': null,
      };

  /// Serialize the full engine config to JSON for the native side.
  String getFullEchConfig() => jsonEncode(removeNulls(echEngineConfig));
}
