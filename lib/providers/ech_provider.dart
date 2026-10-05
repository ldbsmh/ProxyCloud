import 'dart:async';

import 'package:flutter/material.dart';

import '../models/ech_config.dart';
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
  String _statusText = '';
  Timer? _statusTimer;

  List<EchNode> get nodes => List.unmodifiable(_nodes);
  EchNode? get selected => _selected;
  EchSettings get settings => _settings;
  bool get connecting => _connecting;
  bool get connected => _connected;
  String get statusText => _statusText;

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
  bool get perAppEnabled => _settings.perAppProxy;
  bool get perAppMode => _settings.perAppMode == 'allow';
  List<String> get selectedApps => _settings.perAppMode == 'allow'
      ? _settings.allowedApps
      : _settings.excludedApps;

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

  void setPerAppEnabled(bool v) {
    _settings = _settings.copyWith(perAppProxy: v);
    _persistSettings();
  }

  void setPerAppMode(bool v) {
    _settings = _settings.copyWith(perAppMode: v ? 'allow' : 'exclude');
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
    // ECHURL parses `ech://host:port` and the engine config is built from
    // node + settings.
    final remark = node.remark.isNotEmpty ? node.remark : 'ECH ${node.endpoint}';
    final config = V2RayConfig(
      id: node.id,
      remark: remark,
      address: node.address,
      port: node.port,
      configType: 'ech',
      fullConfig: 'ech://${node.endpoint}',
    );

    final ok = await _v2rayService.connect(config, false);
    _connecting = false;
    _connected = ok;
    _statusText = ok ? 'connected' : 'failed';
    notifyListeners();
    return ok;
  }

  Future<void> disconnect() async {
    await _v2rayService.disconnect();
    _connected = false;
    _statusText = 'disconnected';
    notifyListeners();
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }
}
