import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../services/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon_tile.dart';
import '../widgets/glass_card.dart';
import '../widgets/web_protection_controls.dart';

class BlocksScreen extends StatelessWidget {
  const BlocksScreen({
    super.key,
    required this.study,
    required this.webProtectionState,
    required this.accessibilityAvailable,
    required this.apps,
    required this.alwaysAllowedPackages,
    required this.usageByPackage,
    required this.nameForPackage,
    required this.onEditFocusApps,
    required this.onSetAppLimit,
    required this.onSetGlobalWebProtection,
    required this.onSetYoutubeShortsBlocking,
    required this.onSetInstagramReelsBlocking,
    required this.onEditGlobalDomains,
    required this.onEditFocusDomains,
    required this.onRefreshUsage,
  });

  final StudySettings study;
  final WebProtectionState webProtectionState;
  final bool accessibilityAvailable;
  final List<InstalledAppInfo> apps;
  final Set<String> alwaysAllowedPackages;
  final Map<String, int> usageByPackage;
  final String Function(String) nameForPackage;
  final VoidCallback onEditFocusApps;
  final Future<void> Function(String packageName, int? minutes) onSetAppLimit;
  final Future<void> Function(bool enabled) onSetGlobalWebProtection;
  final Future<void> Function(bool enabled) onSetYoutubeShortsBlocking;
  final Future<void> Function(bool enabled) onSetInstagramReelsBlocking;
  final VoidCallback onEditGlobalDomains;
  final VoidCallback onEditFocusDomains;
  final Future<void> Function() onRefreshUsage;

  @override
  Widget build(BuildContext context) {
    final limits = study.dailyAppLimits.entries.toList()
      ..sort((a, b) => nameForPackage(a.key).compareTo(nameForPackage(b.key)));
    final focusCount = study.policy == StudySettings.blocklist
        ? study.blockedPackages.length
        : study.allowedPackages.length;
    final focusPackages = (study.policy == StudySettings.blocklist
            ? study.blockedPackages
            : study.allowedPackages)
        .toList()
      ..sort((a, b) => nameForPackage(a).compareTo(nameForPackage(b)));
    final suggestions = _limitSuggestions(study.dailyAppLimits.keys.toSet());
    return RefreshIndicator(
      onRefresh: onRefreshUsage,
      color: AppColors.lime,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 126),
        children: [
          Row(
            children: [
              Expanded(
                child: Text('Blocks',
                    style: Theme.of(context).textTheme.headlineSmall),
              ),
              TextButton.icon(
                onPressed: () => _showHelp(context),
                icon: const Icon(Icons.chat_bubble_outline_rounded, size: 18),
                label: const Text('Help'),
              ),
            ],
          ),
          const SizedBox(height: 22),
          _SectionHeader(
            title: 'Focus apps',
            action: 'Edit',
            actionIcon: Icons.edit_outlined,
            onAction: onEditFocusApps,
          ),
          const SizedBox(height: 10),
          SolidCard(
            selected: study.enabled,
            onTap: onEditFocusApps,
            child: Row(
              children: [
                _FocusIconCluster(
                  packages: focusPackages,
                  nameForPackage: nameForPackage,
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        study.policy == StudySettings.blocklist
                            ? 'Blocked during Focus'
                            : 'Allowed during Focus',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 3),
                      Text(
                        focusCount == 1
                            ? '1 selected app'
                            : '$focusCount selected apps',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                const _ActionPill(label: 'Manage'),
              ],
            ),
          ),
          const SizedBox(height: 28),
          const _SectionHeader(title: 'Website Protection'),
          const SizedBox(height: 10),
          _WebsiteProtectionCard(
            enabled: study.globalWebProtectionEnabled,
            domainsLocked: study.enabled,
            focusActive: study.enabled,
            globalDomains: study.globalCustomBlockedDomains,
            focusDomains: study.customBlockedDomains,
            state: webProtectionState,
            onChanged: onSetGlobalWebProtection,
            onEditGlobalDomains: onEditGlobalDomains,
            onEditFocusDomains: onEditFocusDomains,
            onShowInfo: () => showWebProtectionInfoDialog(context),
          ),
          const SizedBox(height: 28),
          _SectionHeader(
            title: 'Daily App Limits',
            action: 'Add App',
            onAction: () => _addLimit(context),
          ),
          const SizedBox(height: 10),
          if (limits.isEmpty && suggestions.isEmpty)
            SolidCard(
              onTap: () => _addLimit(context),
              child: const Row(
                children: [
                  _LeadingIcon(icon: Icons.timer_outlined),
                  SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      'Choose an app and set its daily limit.',
                      style: TextStyle(color: AppColors.textSecondary),
                    ),
                  ),
                  _ActionPill(label: 'Add'),
                ],
              ),
            ),
          if (limits.isEmpty && suggestions.isNotEmpty)
            ...suggestions.map(
              (app) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _SuggestedLimitCard(
                  app: app,
                  onTap: () => _editLimit(context, app.packageName, 60),
                ),
              ),
            ),
          if (limits.isNotEmpty)
            ...limits.map(
              (entry) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _LimitCard(
                  packageName: entry.key,
                  appName: nameForPackage(entry.key),
                  limitMinutes: entry.value,
                  usedMs: usageByPackage[entry.key] ?? 0,
                  onTap: () => _editLimit(
                    context,
                    entry.key,
                    entry.value,
                  ),
                ),
              ),
            ),
          const SizedBox(height: 28),
          const _SectionHeader(title: 'Short-form content'),
          const SizedBox(height: 8),
          _ShortsProtectionCard(
            packageName: 'com.google.android.youtube',
            title: 'YouTube Shorts',
            enabled: study.youtubeShortsBlockingEnabled,
            accessibilityAvailable: accessibilityAvailable,
            onChanged: onSetYoutubeShortsBlocking,
          ),
          const SizedBox(height: 8),
          _ShortsProtectionCard(
            packageName: 'com.instagram.android',
            title: 'Instagram Reels',
            enabled: study.instagramReelsBlockingEnabled,
            accessibilityAvailable: accessibilityAvailable,
            onChanged: onSetInstagramReelsBlocking,
          ),
        ],
      ),
    );
  }

  List<InstalledAppInfo> _limitSuggestions(Set<String> configured) {
    const priority = [
      'com.google.android.youtube',
      'com.instagram.android',
      'com.whatsapp',
      'com.android.chrome',
      'com.facebook.katana',
    ];
    final eligible = apps
        .where(
          (app) =>
              app.packageName != 'com.trustissue.child' &&
              !alwaysAllowedPackages.contains(app.packageName) &&
              !configured.contains(app.packageName),
        )
        .toList();
    eligible.sort((a, b) {
      final aPriority = priority.indexOf(a.packageName);
      final bPriority = priority.indexOf(b.packageName);
      final aRank = aPriority < 0 ? priority.length : aPriority;
      final bRank = bPriority < 0 ? priority.length : bPriority;
      if (aRank != bRank) return aRank.compareTo(bRank);
      return a.appName.toLowerCase().compareTo(b.appName.toLowerCase());
    });
    return eligible.take(3).toList();
  }

  Future<void> _addLimit(BuildContext context) async {
    if (apps.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Installed apps are still loading. Try again.')),
      );
      return;
    }
    final packageName = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      barrierColor: Colors.black.withValues(alpha: 0.68),
      clipBehavior: Clip.antiAlias,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
        side: BorderSide(color: AppColors.surfaceBorder),
      ),
      builder: (_) => _LimitAppPicker(
        apps: apps
            .where(
              (app) =>
                  app.packageName != 'com.trustissue.child' &&
                  !alwaysAllowedPackages.contains(app.packageName),
            )
            .toList(),
        configured: study.dailyAppLimits.keys.toSet(),
      ),
    );
    if (packageName == null || !context.mounted) return;
    await _editLimit(context, packageName, 60);
  }

  Future<void> _editLimit(
    BuildContext context,
    String packageName,
    int currentMinutes,
  ) async {
    final selected = await showModalBottomSheet<int>(
      context: context,
      backgroundColor: AppColors.surface,
      barrierColor: Colors.black.withValues(alpha: 0.68),
      clipBehavior: Clip.antiAlias,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
        side: BorderSide(color: AppColors.surfaceBorder),
      ),
      builder: (_) => _LimitDurationSheet(
        appName: nameForPackage(packageName),
        currentMinutes: currentMinutes,
      ),
    );
    if (selected == null) return;
    await onSetAppLimit(packageName, selected <= 0 ? null : selected);
  }

  void _showHelp(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (_) => const AlertDialog(
        title: Text('How Blocks work'),
        content: Text(
          'Focus app rules apply during a session. Daily App Limits stay active '
          'throughout the day using package-only Usage Access. Global Website '
          'Protection covers adult and saved custom domains; active Focus rules '
          'are added during a session. Shorts and Reels Blocking use optional '
          'Accessibility and offer a one-item pass.',
        ),
      ),
    );
  }
}

class _ShortsProtectionCard extends StatelessWidget {
  const _ShortsProtectionCard({
    required this.packageName,
    required this.title,
    required this.enabled,
    required this.accessibilityAvailable,
    required this.onChanged,
  });

  final String packageName;
  final String title;
  final bool enabled;
  final bool accessibilityAvailable;
  final Future<void> Function(bool enabled) onChanged;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      decoration: BoxDecoration(
        color: enabled
            ? AppColors.lime.withValues(alpha: 0.09)
            : AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(
          color: enabled
              ? AppColors.lime.withValues(alpha: 0.34)
              : AppColors.surfaceBorder.withValues(alpha: 0.78),
        ),
        boxShadow: enabled
            ? [
                BoxShadow(
                  color: AppColors.lime.withValues(alpha: 0.30),
                  blurRadius: 24,
                  spreadRadius: -6,
                  offset: const Offset(0, 6),
                ),
              ]
            : const [],
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppRadius.md),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap:
              enabled && !accessibilityAvailable ? () => onChanged(true) : null,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            child: Row(
              children: [
                AppIconTile(
                  key: ValueKey(packageName),
                  appName: title,
                  packageName: packageName,
                  size: 40,
                  radius: 12,
                ),
                const SizedBox(width: 11),
                Expanded(
                  child: Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                  ),
                ),
                const SizedBox(width: 6),
                SizedBox(
                  width: 44,
                  height: 32,
                  child: FittedBox(
                    fit: BoxFit.contain,
                    child: Switch.adaptive(
                      value: enabled,
                      onChanged: onChanged,
                      activeTrackColor: AppColors.lime,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _WebsiteProtectionCard extends StatelessWidget {
  const _WebsiteProtectionCard({
    required this.enabled,
    required this.domainsLocked,
    required this.focusActive,
    required this.globalDomains,
    required this.focusDomains,
    required this.state,
    required this.onChanged,
    required this.onEditGlobalDomains,
    required this.onEditFocusDomains,
    required this.onShowInfo,
  });

  final bool enabled;
  final bool domainsLocked;
  final bool focusActive;
  final Set<String> globalDomains;
  final Set<String> focusDomains;
  final WebProtectionState state;
  final Future<void> Function(bool enabled) onChanged;
  final VoidCallback onEditGlobalDomains;
  final VoidCallback onEditFocusDomains;
  final VoidCallback onShowInfo;

  @override
  Widget build(BuildContext context) {
    final customDomainCount = globalDomains.length;
    final locked = domainsLocked;
    final configuredStatus = globalDomains.isEmpty
        ? 'Adult protection active'
        : 'Adult protection · $customDomainCount custom';
    final String status;
    if (state.stopping) {
      status = 'Disconnecting';
    } else if (!enabled) {
      status = globalDomains.isEmpty ? 'Off' : 'Off · $customDomainCount saved';
    } else if (state.usingExternalVpn) {
      status = 'Paused · current VPN kept';
    } else if (state.degraded) {
      status = 'Protection unavailable';
    } else if (state.starting || state.reconnecting) {
      status = 'Securing connection';
    } else if (state.active) {
      status = configuredStatus;
    } else {
      status = 'Ready to connect';
    }
    return SolidCard(
      radius: 10,
      color: AppColors.surface,
      borderColor: AppColors.surfaceBorder,
      selected: enabled,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const _LeadingIcon(icon: Icons.language_rounded),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Websites',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      locked
                          ? '$status · domain edits locked during Focus'
                          : status,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: 'How Website Protection works',
                onPressed: onShowInfo,
                icon: const Icon(Icons.info_outline_rounded, size: 20),
              ),
              Switch.adaptive(
                value: enabled,
                onChanged: onChanged,
                activeTrackColor: AppColors.lime,
              ),
            ],
          ),
          const Divider(height: 22, color: AppColors.surfaceDivider),
          _ProtectionStateLine(
            icon: Icons.public_rounded,
            label: 'Global',
            value: state.stopping ? 'Disconnecting' : (enabled ? 'On' : 'Off'),
            active: enabled,
          ),
          const SizedBox(height: 8),
          _ProtectionStateLine(
            icon: Icons.center_focus_strong_rounded,
            label: 'During Focus',
            value: state.stopping
                ? 'Disconnecting'
                : focusActive
                    ? state.usingExternalVpn
                        ? 'Paused by current VPN'
                        : state.degraded
                            ? 'Unavailable'
                            : 'Active'
                    : 'Starts with Focus',
            active: focusActive &&
                !state.stopping &&
                !state.degraded &&
                !state.usingExternalVpn,
          ),
          const SizedBox(height: 14),
          _DomainPreview(
            label: 'Global domains',
            domains: globalDomains,
            locked: domainsLocked,
            onManage: onEditGlobalDomains,
          ),
          const SizedBox(height: 10),
          _DomainPreview(
            label: 'Focus domains',
            domains: focusDomains,
            locked: domainsLocked,
            onManage: onEditFocusDomains,
          ),
          if (state.active && state.activeRuleCount > 0) ...[
            const SizedBox(height: 12),
            Text(
              '${state.activeRuleCount} active rules · '
              '${state.protectedBrowserCount} protected '
              'browser${state.protectedBrowserCount == 1 ? '' : 's'}',
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ProtectionStateLine extends StatelessWidget {
  const _ProtectionStateLine({
    required this.icon,
    required this.label,
    required this.value,
    required this.active,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          icon,
          size: 17,
          color: active ? AppColors.emerald : AppColors.textMuted,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            label,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            color: active ? AppColors.emerald : AppColors.textMuted,
            fontSize: 12.5,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}

class _DomainPreview extends StatelessWidget {
  const _DomainPreview({
    required this.label,
    required this.domains,
    required this.locked,
    required this.onManage,
  });

  final String label;
  final Set<String> domains;
  final bool locked;
  final VoidCallback onManage;

  @override
  Widget build(BuildContext context) {
    final sorted = domains.toList()..sort();
    final summary = sorted.isEmpty ? 'None added' : sorted.join('  ·  ');
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: locked ? null : onManage,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.language_rounded,
              size: 17,
              color: AppColors.textMuted,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    locked && sorted.isEmpty
                        ? 'None added · edit after Focus'
                        : summary,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: sorted.isEmpty
                          ? AppColors.textMuted
                          : AppColors.textPrimary,
                      fontSize: 12,
                      height: 1.35,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: sorted.isEmpty ? 'Add $label' : 'Manage $label',
              visualDensity: VisualDensity.compact,
              constraints: const BoxConstraints.tightFor(width: 34, height: 34),
              onPressed: locked ? null : onManage,
              icon: Icon(
                sorted.isEmpty ? Icons.add_rounded : Icons.edit_outlined,
                size: 18,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    this.title = '',
    this.action,
    this.actionIcon = Icons.add_rounded,
    this.onAction,
  });
  final String title;
  final String? action;
  final IconData actionIcon;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(title, style: Theme.of(context).textTheme.titleLarge),
        ),
        if (action != null)
          TextButton.icon(
            onPressed: onAction,
            icon: Icon(actionIcon, size: 19),
            label: Text(action!),
          ),
      ],
    );
  }
}

class _FocusIconCluster extends StatelessWidget {
  const _FocusIconCluster({
    required this.packages,
    required this.nameForPackage,
  });

  final List<String> packages;
  final String Function(String) nameForPackage;

  @override
  Widget build(BuildContext context) {
    if (packages.isEmpty) {
      return const _LeadingIcon(icon: Icons.apps_rounded);
    }
    final visible = packages.take(3).toList();
    return SizedBox(
      width: 48 + (visible.length - 1) * 25,
      height: 48,
      child: Stack(
        children: [
          for (var index = 0; index < visible.length; index++)
            Positioned(
              key: ValueKey(visible[index]),
              left: index * 25,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: AppColors.surface, width: 2),
                ),
                child: AppIconTile(
                  appName: nameForPackage(visible[index]),
                  packageName: visible[index],
                  size: 44,
                  radius: 12,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _ActionPill extends StatelessWidget {
  const _ActionPill({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.surfaceRaised,
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: AppColors.textPrimary,
          fontSize: 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _LeadingIcon extends StatelessWidget {
  const _LeadingIcon({required this.icon});
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        color: AppColors.surfaceRaised,
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      child: Icon(icon, color: AppColors.textSecondary),
    );
  }
}

class _LimitCard extends StatelessWidget {
  const _LimitCard({
    required this.packageName,
    required this.appName,
    required this.limitMinutes,
    required this.usedMs,
    required this.onTap,
  });
  final String packageName;
  final String appName;
  final int limitMinutes;
  final int usedMs;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final usedMinutes = usedMs ~/ 60000;
    final progress = (usedMinutes / limitMinutes).clamp(0.0, 1.0).toDouble();
    return SolidCard(
      onTap: onTap,
      child: Row(
        children: [
          AppIconTile(
            appName: appName,
            packageName: packageName,
            size: 48,
            radius: 15,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(appName, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  '${_minutesLabel(usedMinutes)} used · ${_minutesLabel(limitMinutes)} limit',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: progress,
                    minHeight: 5,
                    backgroundColor: AppColors.surfaceRaised,
                    color: progress >= 1 ? AppColors.danger : AppColors.lime,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          const _ActionPill(label: 'Edit'),
        ],
      ),
    );
  }
}

class _SuggestedLimitCard extends StatelessWidget {
  const _SuggestedLimitCard({required this.app, required this.onTap});

  final InstalledAppInfo app;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      onTap: onTap,
      child: Row(
        children: [
          AppIconTile(
            key: ValueKey(app.packageName),
            appName: app.appName,
            packageName: app.packageName,
            size: 48,
            radius: 14,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(app.appName,
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 3),
                Text('No daily limit',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
          const _ActionPill(label: '+  Add'),
        ],
      ),
    );
  }
}

class _LimitAppPicker extends StatefulWidget {
  const _LimitAppPicker({required this.apps, required this.configured});
  final List<InstalledAppInfo> apps;
  final Set<String> configured;

  @override
  State<_LimitAppPicker> createState() => _LimitAppPickerState();
}

class _LimitAppPickerState extends State<_LimitAppPicker> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final filtered = widget.apps.where((app) {
      final text = '${app.appName} ${app.packageName}'.toLowerCase();
      return !widget.configured.contains(app.packageName) &&
          text.contains(_query.toLowerCase());
    }).toList()
      ..sort((a, b) => a.appName.compareTo(b.appName));
    return Container(
      height: MediaQuery.sizeOf(context).height * 0.78,
      decoration: const BoxDecoration(
        color: Color(0xFF111513),
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppRadius.lg),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
      child: Column(
        children: [
          Container(width: 56, height: 5, color: AppColors.textMuted),
          const SizedBox(height: 20),
          TextField(
            onChanged: (value) => setState(() => _query = value),
            decoration: const InputDecoration(
              prefixIcon: Icon(Icons.search_rounded),
              hintText: 'Search apps',
              filled: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.all(
                  Radius.circular(AppRadius.md),
                ),
                borderSide: BorderSide.none,
              ),
            ),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: ListView.builder(
              itemCount: filtered.length,
              itemBuilder: (context, index) {
                final app = filtered[index];
                return ListTile(
                  contentPadding: const EdgeInsets.symmetric(vertical: 4),
                  leading: AppIconTile(
                    appName: app.appName,
                    packageName: app.packageName,
                    size: 44,
                    radius: 13,
                  ),
                  title: Text(app.appName),
                  trailing: const Icon(Icons.add_circle_outline_rounded,
                      color: AppColors.lime),
                  onTap: () => Navigator.of(context).pop(app.packageName),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _LimitDurationSheet extends StatefulWidget {
  const _LimitDurationSheet(
      {required this.appName, required this.currentMinutes});
  final String appName;
  final int currentMinutes;

  @override
  State<_LimitDurationSheet> createState() => _LimitDurationSheetState();
}

class _LimitDurationSheetState extends State<_LimitDurationSheet> {
  static const _choices = [15, 30, 45, 60, 90, 120, 180, 240];
  late int _minutes;

  @override
  void initState() {
    super.initState();
    _minutes =
        _choices.contains(widget.currentMinutes) ? widget.currentMinutes : 60;
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFF111513),
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppRadius.lg),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.appName, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            const Text('Choose the daily usage limit.'),
            const SizedBox(height: 18),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _choices.map((minutes) {
                return ChoiceChip(
                  label: Text(_minutesLabel(minutes)),
                  selected: minutes == _minutes,
                  onSelected: (_) => setState(() => _minutes = minutes),
                );
              }).toList(),
            ),
            const SizedBox(height: 20),
            Row(
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(0),
                  child: const Text('Remove limit'),
                ),
                const Spacer(),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(_minutes),
                  child: const Text('Save limit'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

String _minutesLabel(int minutes) {
  if (minutes < 60) return '$minutes min';
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  return remainder == 0 ? '$hours hr' : '$hours hr $remainder min';
}
