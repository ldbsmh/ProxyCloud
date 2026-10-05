import 'dart:async';

import 'package:flutter/material.dart';

import '../models/ech_config.dart';
import '../models/v2ray_config.dart';
import '../services/v2ray_service.dart';

/// Manages ech tunnel state: node list, selection, settings, connection lifecycle.
/// Independent from the v2ray provider — ech runs its own engine.
class EchProvider extends ChangeNotifier {
  final V2RayService _v2rayService = V2RayService();

  List<EchNode> _nodes = [];
  EchNode? _selected;
  EchSettings _settings = EchSettings();
  bool _initialized = false;

  // connection state
  bool _connecting = false;
  bool _connected = false;
  bool _initializing = true;
  String _statusText = '';
  String? _lastError;
  Timer? _statusTimer;

  List<EchNode> get nodes => List.unmodifiable(_nodes);
  EchNode? get selected => _selected;
  EchSettings get settings => _settings;
  bool get connecting => _connecting;
  bool get connected => _connected;
  bool get initializing => _initializing;
  String get statusText => _statusText;
  String? get lastError => _lastError;

  /// Load persisted nodes/settings. Call once at app start.
  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    _nodes = await EchConfigStore.loadNodes();
    _settings = await EchConfigStore.loadSettings();
    final selectedId = await EchConfigStore.loadSelectedId();
    if (selectedId != null) {
      for (final n in _nodes) {
        if (n.id == selectedId) {
          _selected = n;
          break;
        }
      }
    }
    _connected = await _v2rayService.isActuallyConnected();
    _statusText = _connected ? 'connected' : 'disconnected';
    _initializing = false;
    notifyListeners();
  }

  Future<void> refreshConnectionState() async {
    _connected = await _v2rayService.isActuallyConnected();
    _statusText = _connected ? 'connected' : 'disconnected';
    notifyListeners();
  }

  // ---------- node management ----------

  Future<void> addNode(EchNode node) async {
    _nodes = [..._nodes, node];
    await EchConfigStore.saveNodes(_nodes);
    notifyListeners();
  }

  Future<void> updateNode(EchNode node) async {
    final i = _nodes.indexWhere((n) => n.id == node.id);
    if (i < 0) return;
    _nodes = [..._nodes];
    _nodes[i] = node;
    if (_selected?.id == node.id) _selected = node;
    await EchConfigStore.saveNodes(_nodes);
    notifyListeners();
  }

  Future<void> removeNode(String id) async {
    _nodes = _nodes.where((n) => n.id != id).toList();
    if (_selected?.id == id) {
      _selected = null;
      await EchConfigStore.saveSelectedId(null);
    }
    await EchConfigStore.saveNodes(_nodes);
    notifyListeners();
  }

  Future<void> selectNode(EchNode node) async {
    if (_connected) return; // cannot switch while connected
    _selected = node;
    await EchConfigStore.saveSelectedId(node.id);
    notifyListeners();
  }

  Future<void> updateSettings(EchSettings s) async {
    _settings = s;
    await EchConfigStore.saveSettings(s);
    notifyListeners();
  }

  // ---------- settings page bindings ----------

  late final TextEditingController serviceAddressController =
      TextEditingController(text: _settings.workerDomain);
  late final TextEditingController tokenController =
      TextEditingController(text: _settings.token);
  late final TextEditingController listenPortController =
      TextEditingController(text: _settings.localPort.toString());

  String get dohServer => _settings.dohServer;
  String get pubkeyDomain => _settings.pubKeyDomain;
  bool get vpnTakeover => _settings.vpnGlobal;

  void setDohServer(String v) {
    _settings = _settings.copyWith(dohServer: v);
    _persistSettings();
  }

  void setPubkeyDomain(String v) {
    _settings = _settings.copyWith(pubKeyDomain: v);
    _persistSettings();
  }

  void setVpnTakeover(bool v) {
    _settings = _settings.copyWith(vpnGlobal: v);
    _persistSettings();
  }

  void _persistSettings() {
    EchConfigStore.saveSettings(_settings);
    notifyListeners();
  }

  /// Reads controller values into settings and persists.
  Future<void> saveSettings() async {
    final s = _settings.copyWith(
      workerDomain: serviceAddressController.text.trim(),
      token: tokenController.text.trim(),
      localPort: int.tryParse(listenPortController.text.trim()) ??
          _settings.localPort,
    );
    _settings = s;
    await EchConfigStore.saveSettings(s);
    notifyListeners();
  }

  // ---------- connection ----------

  Future<bool> connect() async {
    final node = _selected;
    if (node == null) return false;
    if (_connecting) return false;

    _connecting = true;
    _statusText = 'connecting';
    notifyListeners();

    // Build a V2RayConfig whose fullConfig is an ech:// URL.
    // The URL HOST must be the Worker DOMAIN (so the WS request carries the
    // right Host header / TLS SNI — a middle server like a 优选IP reverse
    // proxy routes by Host). The node's IP (优选IP / 中转) is passed as the
    // `ip` query param; ECHURL reads it and the native engine connects to that
    // IP while keeping the domain in Host/SNI.
    final domain = _settings.workerDomain
        .trim()
        .replaceFirst(RegExp(r'^https?://'), '')
        .replaceAll(RegExp(r'/.*$'), '')
        .trim();
    final host = domain.isNotEmpty ? domain : node.address;
    final remark = node.remark.isNotEmpty ? node.remark : 'ECH ${node.endpoint}';
    final token = _settings.token.trim();
    final tokenParam = token.isNotEmpty ? '&token=$token' : '';
    final dohParam = '&doh=${Uri.encodeQueryComponent(_settings.dohServer.trim())}';
    final echParam = '&ech=${Uri.encodeQueryComponent(_settings.pubKeyDomain.trim())}';
    final config = V2RayConfig(
      id: node.id,
      remark: remark,
      address: node.address,
      port: node.port,
      configType: 'ech',
      fullConfig:
          'ech://$host:${node.port}?ip=${node.address}$tokenParam$dohParam$echParam',
    );

    final ok = await _v2rayService.connect(config, false);
    _connecting = false;
    _connected = ok;
    _statusText = ok ? 'connected' : 'failed';
    _lastError = ok ? null : _v2rayService.lastConnectError;
    notifyListeners();
    return ok;
  }

  Future<void> disconnect() async {
    await _v2rayService.disconnect();
    _connected = false;
    _statusText = 'disconnected';
    _lastError = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }
}
