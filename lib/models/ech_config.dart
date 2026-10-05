import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// A single ech tunnel node: just an IP:port pair as shown on the main screen.
class EchNode {
  final String id;
  String address; // IP or hostname
  int port;
  String remark; // optional label

  EchNode({
    required this.id,
    required this.address,
    required this.port,
    this.remark = '',
  });

  String get endpoint => '$address:$port';

  Map<String, dynamic> toJson() => {
        'id': id,
        'address': address,
        'port': port,
        'remark': remark,
      };

  factory EchNode.fromJson(Map<String, dynamic> json) => EchNode(
        id: json['id'] as String,
        address: json['address'] as String,
        port: json['port'] as int,
        remark: (json['remark'] as String?) ?? '',
      );
}

/// Settings for the ech engine (mirrors the reference settings page).
class EchSettings {
  String workerDomain; // service address, domain without port
  String token;
  String listenAddress;
  int localPort;
  String dohServer; // ECH DOH server
  String pubKeyDomain; // ECH public key query domain
  bool vpnGlobal; // VPN global takeover (off = local proxy only)
  bool perAppProxy; // per-app proxy
  String perAppMode; // 'allow' or 'exclude'
  List<String> allowedApps;
  List<String> excludedApps;

  /// Preset DOH servers (Cloudflare / AliDNS / Google).
  static const List<String> dohPresets = [
    'dns.alidns.com/dns-query',
    'cloudflare-dns.com/dns-query',
    'dns.google/dns-query',
    'doh.pub/dns-query',
  ];

  /// Preset ECH public key query domains.
  static const List<String> pubkeyPresets = [
    'cloudflare-ech.com',
    'ech.mozilla.org',
    'dns.aa.net.cn',
  ];

  EchSettings({
    this.workerDomain = '',
    this.token = '',
    this.listenAddress = '127.0.0.1',
    this.localPort = 30000,
    this.dohServer = 'dns.alidns.com/dns-query',
    this.pubKeyDomain = 'cloudflare-ech.com',
    this.vpnGlobal = true,
    this.perAppProxy = false,
    this.perAppMode = 'allow',
    List<String>? allowedApps,
    List<String>? excludedApps,
  })  : allowedApps = allowedApps ?? [],
        excludedApps = excludedApps ?? [];

  EchSettings copyWith({
    String? workerDomain,
    String? token,
    String? listenAddress,
    int? localPort,
    String? dohServer,
    String? pubKeyDomain,
    bool? vpnGlobal,
    bool? perAppProxy,
    String? perAppMode,
    List<String>? allowedApps,
    List<String>? excludedApps,
  }) {
    return EchSettings(
      workerDomain: workerDomain ?? this.workerDomain,
      token: token ?? this.token,
      listenAddress: listenAddress ?? this.listenAddress,
      localPort: localPort ?? this.localPort,
      dohServer: dohServer ?? this.dohServer,
      pubKeyDomain: pubKeyDomain ?? this.pubKeyDomain,
      vpnGlobal: vpnGlobal ?? this.vpnGlobal,
      perAppProxy: perAppProxy ?? this.perAppProxy,
      perAppMode: perAppMode ?? this.perAppMode,
      allowedApps: allowedApps ?? this.allowedApps,
      excludedApps: excludedApps ?? this.excludedApps,
    );
  }

  Map<String, dynamic> toJson() => {
        'workerDomain': workerDomain,
        'token': token,
        'listenAddress': listenAddress,
        'localPort': localPort,
        'dohServer': dohServer,
        'pubKeyDomain': pubKeyDomain,
        'vpnGlobal': vpnGlobal,
        'perAppProxy': perAppProxy,
        'perAppMode': perAppMode,
        'allowedApps': allowedApps,
        'excludedApps': excludedApps,
      };

  factory EchSettings.fromJson(Map<String, dynamic> json) => EchSettings(
        workerDomain: (json['workerDomain'] as String?) ?? '',
        token: (json['token'] as String?) ?? '',
        listenAddress: (json['listenAddress'] as String?) ?? '127.0.0.1',
        localPort: (json['localPort'] as int?) ?? 30000,
        dohServer: (json['dohServer'] as String?) ?? 'dns.alidns.com/dns-query',
        pubKeyDomain: (json['pubKeyDomain'] as String?) ?? 'cloudflare-ech.com',
        vpnGlobal: (json['vpnGlobal'] as bool?) ?? true,
        perAppProxy: (json['perAppProxy'] as bool?) ?? false,
        perAppMode: (json['perAppMode'] as String?) ?? 'allow',
        allowedApps: ((json['allowedApps'] as List?) ?? [])
            .map((e) => e.toString())
            .toList(),
        excludedApps: ((json['excludedApps'] as List?) ?? [])
            .map((e) => e.toString())
            .toList(),
      );
}

/// Persistence for ech nodes + settings via SharedPreferences.
class EchConfigStore {
  static const String _nodesKey = 'ech_nodes';
  static const String _settingsKey = 'ech_settings';
  static const String _selectedIdKey = 'ech_selected_id';

  static Future<List<EchNode>> loadNodes() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_nodesKey);
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list
          .map((e) => EchNode.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> saveNodes(List<EchNode> nodes) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _nodesKey,
      jsonEncode(nodes.map((n) => n.toJson()).toList()),
    );
  }

  static Future<EchSettings> loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_settingsKey);
    if (raw == null || raw.isEmpty) return EchSettings();
    try {
      return EchSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return EchSettings();
    }
  }

  static Future<void> saveSettings(EchSettings settings) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_settingsKey, jsonEncode(settings.toJson()));
  }

  static Future<String?> loadSelectedId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_selectedIdKey);
  }

  static Future<void> saveSelectedId(String? id) async {
    final prefs = await SharedPreferences.getInstance();
    if (id == null) {
      await prefs.remove(_selectedIdKey);
    } else {
      await prefs.setString(_selectedIdKey, id);
    }
  }
}
