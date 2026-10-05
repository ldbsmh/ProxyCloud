import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/ech_config.dart';
import '../providers/ech_provider.dart';
import '../theme/app_theme.dart';
import '../utils/app_localizations.dart';
import '../widgets/background_gradient.dart';

/// ech settings page, mirroring the reference screenshot:
/// service address, TOKEN, local listen port, DOH server, public key
/// query domain, VPN takeover toggle, per-app proxy mode and app list.
class EchSettingsScreen extends StatelessWidget {
  const EchSettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final ech = context.watch<EchProvider>();

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: Text(t.tr('ech.settings')),
        actions: [
          TextButton.icon(
            onPressed: () => ech.saveSettings(),
            icon: const Icon(Icons.save_outlined),
            label: Text(t.tr('common.save')),
          ),
        ],
      ),
      body: BackgroundGradient(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 100, 16, 32),
          children: [
            _SectionCard(
              title: t.tr('ech.settings_server'),
              children: [
                _TextFieldRow(
                  label: t.tr('ech.service_address'),
                  hint: 'ech.example.workers.dev',
                  controller: ech.serviceAddressController,
                ),
                _TextFieldRow(
                  label: 'TOKEN',
                  hint: t.tr('ech.token_hint'),
                  controller: ech.tokenController,
                  obscure: true,
                ),
              ],
            ),
            const SizedBox(height: 14),
            _SectionCard(
              title: t.tr('ech.settings_local'),
              children: [
                _TextFieldRow(
                  label: t.tr('ech.listen_port'),
                  hint: '10808',
                  controller: ech.listenPortController,
                  keyboardType: TextInputType.number,
                ),
                _InfoRow(
                  icon: Icons.info_outline,
                  text: t.tr('ech.listen_hint'),
                ),
              ],
            ),
            const SizedBox(height: 14),
            _SectionCard(
              title: t.tr('ech.settings_doh'),
              children: [
                _DropdownRow(
                  label: t.tr('ech.doh_server'),
                  value: ech.dohServer,
                  options: EchSettings.dohPresets,
                  onChanged: ech.setDohServer,
                ),
                _DropdownRow(
                  label: t.tr('ech.pubkey_domain'),
                  value: ech.pubkeyDomain,
                  options: EchSettings.pubkeyPresets,
                  onChanged: ech.setPubkeyDomain,
                ),
              ],
            ),
            const SizedBox(height: 14),
            _SectionCard(
              title: t.tr('ech.settings_mode'),
              children: [
                SwitchListTile(
                  title: Text(t.tr('ech.vpn_takeover')),
                  subtitle: Text(t.tr('ech.vpn_takeover_sub')),
                  value: ech.vpnTakeover,
                  onChanged: ech.setVpnTakeover,
                ),
                _InfoRow(
                  icon: Icons.block,
                  text: t.tr('ech.blocked_apps_hint'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              onPressed: () => ech.saveSettings(),
              icon: const Icon(Icons.save_outlined),
              label: Text(t.tr('common.save')),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  final String title;
  final List<Widget> children;
  const _SectionCard({required this.title, required this.children});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: theme.colorScheme.outlineVariant.withValues(alpha: 0.3),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
            child: Text(
              title,
              style: theme.textTheme.titleSmall?.copyWith(
                color: theme.colorScheme.primary,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          ...children,
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

class _TextFieldRow extends StatelessWidget {
  final String label;
  final String hint;
  final TextEditingController controller;
  final bool obscure;
  final TextInputType? keyboardType;
  const _TextFieldRow({
    required this.label,
    required this.hint,
    required this.controller,
    this.obscure = false,
    this.keyboardType,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: TextField(
        controller: controller,
        obscureText: obscure,
        keyboardType: keyboardType,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurface,
          fontFamily: obscure ? null : 'monospace',
        ),
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          isDense: true,
          border: const UnderlineInputBorder(),
        ),
      ),
    );
  }
}

class _DropdownRow extends StatelessWidget {
  final String label;
  final String value;
  final List<String> options;
  final ValueChanged<String> onChanged;
  const _DropdownRow({
    required this.label,
    required this.value,
    required this.options,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Row(
        children: [
          Expanded(
            child: Text(label, style: theme.textTheme.bodyMedium),
          ),
          DropdownButton<String>(
            value: options.contains(value) ? value : options.first,
            isDense: true,
            underline: const SizedBox.shrink(),
            items: [
              for (final o in options)
                DropdownMenuItem(value: o, child: Text(o)),
            ],
            onChanged: (v) {
              if (v != null) onChanged(v);
            },
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String text;
  const _InfoRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 16, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
