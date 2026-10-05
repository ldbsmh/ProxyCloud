import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/ech_config.dart';
import '../providers/ech_provider.dart';
import '../theme/app_theme.dart';
import '../utils/app_localizations.dart';
import '../widgets/background_gradient.dart';

/// Main ech tab: shows node list (IP:port) + connect/disconnect,
/// mirroring the reference EchOS style. Top-right settings gear opens
/// the ech settings page.
class EchScreen extends StatelessWidget {
  const EchScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final ech = context.watch<EchProvider>();

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: Text(t.tr('ech.title')),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: t.tr('ech.settings'),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => EchSettingsScreen()),
              );
            },
          ),
        ],
      ),
      body: BackgroundGradient(
        child: ech.initializing
            ? const Center(child: CircularProgressIndicator())
            : _buildBody(context, t, ech),
      ),
    );
  }

  Widget _buildBody(BuildContext context, AppLocalizations t, EchProvider ech) {
    final nodes = ech.nodes;

    if (nodes.isEmpty) {
      return _EmptyState(onAdd: () => _showAddNode(context));
    }

    return Column(
      children: [
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 100, 16, 8),
            itemCount: nodes.length + 1,
            itemBuilder: (context, i) {
              if (i == 0) return _header(context, t, ech);
              final node = nodes[i - 1];
              return _nodeCard(context, t, ech, node);
            },
          ),
        ),
        _bottomBar(context, t, ech),
      ],
    );
  }

  Widget _header(BuildContext context, AppLocalizations t, EchProvider ech) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            t.tr('ech.nodes_title'),
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            t.tr('ech.nodes_subtitle'),
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _nodeCard(BuildContext context, AppLocalizations t, EchProvider ech,
      EchNode node) {
    final theme = Theme.of(context);
    final selected = ech.selected?.id == node.id;
    final isConnected = ech.connected && selected;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: selected
            ? theme.colorScheme.primary.withValues(alpha: 0.12)
            : theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: ech.connected ? null : () => ech.selectNode(node),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: selected
                    ? theme.colorScheme.primary
                    : Colors.transparent,
                width: 1.6,
              ),
            ),
            child: Row(
              children: [
                Icon(
                  selected
                      ? Icons.check_circle
                      : Icons.dns_outlined,
                  color: selected
                      ? theme.colorScheme.primary
                      : theme.colorScheme.onSurfaceVariant,
                  size: 22,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (node.remark.isNotEmpty)
                        Text(
                          node.remark,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      Text(
                        node.endpoint,
                        style: theme.textTheme.bodyLarge?.copyWith(
                          fontFamily: 'monospace',
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
                if (isConnected)
                  Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: AppTheme.connectedGreen.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      t.tr('ech.in_use'),
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: AppTheme.connectedGreen,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert, size: 20),
                  onSelected: (v) {
                    if (v == 'edit') _showAddNode(context, node: node);
                    if (v == 'delete') _confirmDelete(context, ech, node);
                  },
                  itemBuilder: (_) => [
                    PopupMenuItem(
                      value: 'edit',
                      child: Text(t.tr('ech.edit')),
                    ),
                    PopupMenuItem(
                      value: 'delete',
                      child: Text(t.tr('ech.delete')),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _bottomBar(BuildContext context, AppLocalizations t, EchProvider ech) {
    final theme = Theme.of(context);
    final selected = ech.selected;
    final connecting = ech.connecting;
    final connected = ech.connected;

    final statusText = connected
        ? t.tr('home.connected')
        : connecting
            ? t.tr('home.connecting')
            : selected == null
                ? t.tr('ech.select_hint')
                : t.tr('home.disconnected');
    final statusColor = connected
        ? AppTheme.connectedGreen
        : connecting
            ? theme.colorScheme.primary
            : theme.colorScheme.onSurfaceVariant;

    return Container(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 12,
      ),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 12,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                connected ? Icons.check_circle : Icons.circle,
                size: 10,
                color: statusColor,
              ),
              const SizedBox(width: 6),
              Text(statusText, style: theme.textTheme.bodyMedium),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: connected
                    ? FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: AppTheme.disconnectedRed,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 14),
                        ),
                        icon: const Icon(Icons.stop),
                        label: Text(t.tr('home.disconnect')),
                        onPressed: () => ech.disconnect(),
                      )
                    : FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: theme.colorScheme.primary,
                          foregroundColor: theme.colorScheme.onPrimary,
                          padding: const EdgeInsets.symmetric(vertical: 14),
                        ),
                        icon: const Icon(Icons.power_settings_new),
                        label: Text(
                          connecting
                              ? t.tr('home.connecting')
                              : t.tr('home.connect'),
                        ),
                        onPressed: connecting || selected == null
                            ? null
                            : () => ech.connect(),
                      ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: () => _showAddNode(context),
            icon: const Icon(Icons.add),
            label: Text(t.tr('ech.add_node')),
          ),
        ],
      ),
    );
  }

  void _showAddNode(BuildContext context, {EchNode? node}) {
    final t = AppLocalizations.of(context);
    final ech = context.read<EchProvider>();
    showDialog<void>(
      context: context,
      builder: (ctx) => _AddNodeDialog(ech: ech, node: node, t: t),
    );
  }

  Future<void> _confirmDelete(
      BuildContext context, EchProvider ech, EchNode node) async {
    final t = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.tr('ech.delete_title')),
        content: Text('${t.tr('ech.delete_confirm')}\n${node.endpoint}'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(t.tr('common.cancel')),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.disconnectedRed,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(t.tr('ech.delete')),
          ),
        ],
      ),
    );
    if (ok == true) {
      await ech.removeNode(node.id);
    }
  }
}

class _EmptyState extends StatelessWidget {
  final VoidCallback onAdd;
  const _EmptyState({required this.onAdd});

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.hub_outlined,
              size: 72, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(height: 16),
          Text(t.tr('ech.no_nodes'),
              style: theme.textTheme.titleMedium),
          const SizedBox(height: 6),
          Text(
            t.tr('ech.no_nodes_subtitle'),
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: onAdd,
            icon: const Icon(Icons.add),
            label: Text(t.tr('ech.add_node')),
          ),
        ],
      ),
    );
  }
}

class _AddNodeDialog extends StatefulWidget {
  final EchProvider ech;
  final EchNode? node;
  final AppLocalizations t;
  const _AddNodeDialog({required this.ech, this.node, required this.t});

  @override
  State<_AddNodeDialog> createState() => _AddNodeDialogState();
}

class _AddNodeDialogState extends State<_AddNodeDialog> {
  late final TextEditingController _address;
  late final TextEditingController _port;
  late final TextEditingController _remark;
  String? _error;

  @override
  void initState() {
    super.initState();
    final n = widget.node;
    _address = TextEditingController(text: n?.address ?? '');
    _port = TextEditingController(text: n?.port.toString() ?? '');
    _remark = TextEditingController(text: n?.remark ?? '');
  }

  @override
  void dispose() {
    _address.dispose();
    _port.dispose();
    _remark.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = widget.t;
    final isEdit = widget.node != null;
    return AlertDialog(
      title: Text(isEdit ? t.tr('ech.edit_node') : t.tr('ech.add_node')),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _address,
              decoration: InputDecoration(
                labelText: t.tr('ech.ip'),
                hintText: '1.2.3.4',
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _port,
              decoration: InputDecoration(
                labelText: t.tr('ech.port'),
                hintText: '443',
                border: const OutlineInputBorder(),
              ),
              keyboardType: TextInputType.number,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _remark,
              decoration: InputDecoration(
                labelText: t.tr('ech.remark'),
                hintText: t.tr('ech.remark_hint'),
                border: const OutlineInputBorder(),
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(_error!,
                    style: TextStyle(color: AppTheme.disconnectedRed)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(t.tr('common.cancel')),
        ),
        FilledButton(
          onPressed: _save,
          child: Text(t.tr('common.save')),
        ),
      ],
    );
  }

  void _save() {
    final address = _address.text.trim();
    final portText = _port.text.trim();
    if (address.isEmpty) {
      setState(() => _error = widget.t.tr('ech.ip_required'));
      return;
    }
    final port = int.tryParse(portText);
    if (port == null || port < 1 || port > 65535) {
      setState(() => _error = widget.t.tr('ech.port_invalid'));
      return;
    }
    final ech = widget.ech;
    if (widget.node != null) {
      final updated = EchNode(
        id: widget.node!.id,
        address: address,
        port: port,
        remark: _remark.text.trim(),
      );
      ech.updateNode(updated);
    } else {
      final id = DateTime.now().microsecondsSinceEpoch.toString();
      ech.addNode(EchNode(
        id: id,
        address: address,
        port: port,
        remark: _remark.text.trim(),
      ));
    }
    Navigator.pop(context);
  }
}
