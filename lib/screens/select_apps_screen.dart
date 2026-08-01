import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon_tile.dart';
import '../widgets/glass_card.dart';
import '../widgets/primary_button.dart';
import '../widgets/web_protection_controls.dart';

class AppSelectionResult {
  const AppSelectionResult({
    required this.selectedPackages,
    required this.customBlockedDomains,
  });

  final Set<String> selectedPackages;
  final Set<String> customBlockedDomains;
}

Future<Set<String>?> showWebsiteRulesSheet(
  BuildContext context, {
  required Set<String> initialDomains,
  String title = 'Custom domains',
  String description =
      'Adult and common bypass domains are already covered. Add only your own extra rule.',
  String saveLabel = 'Save domains',
}) {
  return showModalBottomSheet<Set<String>>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    barrierColor: Colors.black.withValues(alpha: 0.68),
    clipBehavior: Clip.antiAlias,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(10)),
      side: BorderSide(color: AppColors.surfaceBorder),
    ),
    builder: (_) => _DomainRulesSheet(
      initialDomains: initialDomains,
      title: title,
      description: description,
      saveLabel: saveLabel,
    ),
  );
}

/// App picker used to build the allowlist / blocklist. Browser protection is
/// configured here so the Home screen stays focused on the session itself.
class SelectAppsScreen extends StatefulWidget {
  const SelectAppsScreen({
    super.key,
    required this.apps,
    required this.selected,
    required this.allowMode,
    this.alwaysAllowedPackages = const [],
    this.browserPackages = const [],
    this.customBlockedDomains = const <String>{},
    this.onCustomDomainsChanged,
  });

  final List<InstalledAppInfo> apps;
  final Set<String> selected;
  final bool allowMode;

  /// Packages the blocker always allows (phone, messages, settings, maps).
  /// Shown read-only so the child knows they stay reachable for safety.
  final List<String> alwaysAllowedPackages;
  final List<String> browserPackages;
  final Set<String> customBlockedDomains;
  final Future<void> Function(Set<String> domains)? onCustomDomainsChanged;

  @override
  State<SelectAppsScreen> createState() => _SelectAppsScreenState();
}

class _SelectAppsScreenState extends State<SelectAppsScreen> {
  final _searchController = TextEditingController();
  late Set<String> _selected;
  late final Set<String> _alwaysAllowed;
  late final Set<String> _browserPackages;
  late Set<String> _customDomains;
  String _query = '';

  // Popular packages surfaced first as "recommended", plus a rough category.
  static const Map<String, String> _categories = {
    'com.whatsapp': 'Communication',
    'com.whatsapp.w4b': 'Communication',
    'com.spotify.music': 'Music & Audio',
    'com.notion.id': 'Productivity',
    'com.google.android.apps.docs': 'Productivity',
    'com.instagram.android': 'Social',
    'com.snapchat.android': 'Social',
    'com.google.android.youtube': 'Video & Entertainment',
    'com.android.chrome': 'Utilities',
    'org.telegram.messenger': 'Communication',
    'com.google.android.gm': 'Communication',
    'com.microsoft.office.outlook': 'Communication',
    'com.google.android.apps.messaging': 'Communication',
    'com.samsung.android.messaging': 'Communication',
  };

  @override
  void initState() {
    super.initState();
    _selected = {...widget.selected};
    _alwaysAllowed = widget.alwaysAllowedPackages.toSet();
    _browserPackages = widget.browserPackages.toSet();
    _customDomains = {...widget.customBlockedDomains};
    _searchController.addListener(() {
      setState(() => _query = _searchController.text.trim().toLowerCase());
    });
  }

  bool _webProtected(String packageName) {
    if (!_browserPackages.contains(packageName)) return false;
    return widget.allowMode
        ? _selected.contains(packageName)
        : !_selected.contains(packageName);
  }

  Future<void> _editDomains() async {
    final updated = await showWebsiteRulesSheet(
      context,
      initialDomains: _customDomains,
      title: 'Focus blocked domains',
      description:
          'These domains are blocked only while a Focus session is active. Global domains are managed from Blocks.',
      saveLabel: 'Save Focus domains',
    );
    if (updated == null || !mounted) return;
    setState(() => _customDomains = updated);
    final persist = widget.onCustomDomainsChanged;
    if (persist == null) return;
    try {
      await persist({...updated});
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Could not save Focus domains. Please try again.'),
        ),
      );
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<InstalledAppInfo> get _filtered {
    final apps = widget.apps.where((app) {
      // Always-allowed apps are shown in their own read-only section, never as
      // selectable rows -- they can't be blocked, so toggling them is a no-op.
      if (_alwaysAllowed.contains(app.packageName)) return false;
      final text = '${app.appName} ${app.packageName}'.toLowerCase();
      return _query.isEmpty || text.contains(_query);
    }).toList();
    // Recommended (known) apps first, then alphabetical.
    apps.sort((a, b) {
      final aKnown = _categories.containsKey(a.packageName) ? 0 : 1;
      final bKnown = _categories.containsKey(b.packageName) ? 0 : 1;
      if (aKnown != bKnown) return aKnown - bKnown;
      return a.appName.toLowerCase().compareTo(b.appName.toLowerCase());
    });
    return apps;
  }

  void _toggle(String packageName) {
    setState(() {
      if (_selected.contains(packageName)) {
        _selected.remove(packageName);
      } else {
        _selected.add(packageName);
      }
    });
  }

  void _selectAllVisible() {
    final visible = _filtered.map((a) => a.packageName).toList();
    final allSelected = visible.every(_selected.contains);
    setState(() {
      if (allSelected) {
        _selected.removeAll(visible);
      } else {
        _selected.addAll(visible);
      }
    });
  }

  Widget _sectionHeader(String title) {
    return SliverToBoxAdapter(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 14, 20, 8),
        child: Text(
          title,
          style: const TextStyle(
            color: AppColors.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
    );
  }

  Widget _appSliverList(List<InstalledAppInfo> apps) {
    return SliverPadding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
      sliver: SliverList.separated(
        itemCount: apps.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          final app = apps[index];
          return _AppRow(
            key: ValueKey(app.packageName),
            app: app,
            category: _categories[app.packageName] ?? 'App',
            selected: _selected.contains(app.packageName),
            webProtected: _webProtected(app.packageName),
            onTap: () => _toggle(app.packageName),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _filtered;
    final target = widget.allowMode ? 'Allowlist' : 'Blocklist';
    final allVisibleSelected = filtered.isNotEmpty &&
        filtered.map((a) => a.packageName).every(_selected.contains);
    final recommended =
        filtered.where((a) => _categories.containsKey(a.packageName)).toList();
    final others =
        filtered.where((a) => !_categories.containsKey(a.packageName)).toList();
    final selectedApps = widget.apps
        .where((app) => _selected.contains(app.packageName))
        .toList()
      ..sort(
        (a, b) => a.appName.toLowerCase().compareTo(b.appName.toLowerCase()),
      );
    final alwaysAllowedApps = _query.isEmpty
        ? (widget.apps
            .where((a) => _alwaysAllowed.contains(a.packageName))
            .toList()
          ..sort((a, b) =>
              a.appName.toLowerCase().compareTo(b.appName.toLowerCase())))
        : <InstalledAppInfo>[];

    return Scaffold(
      backgroundColor: AppColors.page,
      body: ColoredBox(
        color: AppColors.page,
        child: SafeArea(
          bottom: false,
          child: Column(
            children: [
              _HeaderBar(target: target),
              Expanded(
                child: CustomScrollView(
                  slivers: [
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                        child: _SearchField(controller: _searchController),
                      ),
                    ),
                    if (selectedApps.isNotEmpty)
                      SliverToBoxAdapter(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(20, 6, 20, 12),
                          child: _SelectedAppsStrip(
                            apps: selectedApps,
                            onRemove: _toggle,
                          ),
                        ),
                      ),
                    if (alwaysAllowedApps.isNotEmpty)
                      SliverToBoxAdapter(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(20, 4, 20, 4),
                          child: _AlwaysAllowedCard(apps: alwaysAllowedApps),
                        ),
                      ),
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 6, 20, 10),
                        child: SolidCard(
                          padding: const EdgeInsets.all(12),
                          radius: 10,
                          color: AppColors.surface,
                          borderColor: AppColors.surfaceBorder,
                          child: WebProtectionInlineControls(
                            customDomains: _customDomains,
                            onAddDomain: _editDomains,
                            onShowInfo: () =>
                                showWebProtectionInfoDialog(context),
                          ),
                        ),
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 4, 20, 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                'Tap an app to add or remove it',
                                style: const TextStyle(
                                  color: AppColors.textSecondary,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                            GestureDetector(
                              onTap: _selectAllVisible,
                              child: Text(
                                allVisibleSelected ? 'Clear All' : 'Select All',
                                style: const TextStyle(
                                  color: AppColors.lime,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    if (filtered.isEmpty)
                      const SliverToBoxAdapter(
                        child: Padding(
                          padding: EdgeInsets.all(40),
                          child: Center(
                            child: Text('No apps found.',
                                style: TextStyle(color: AppColors.textMuted)),
                          ),
                        ),
                      )
                    else ...[
                      if (recommended.isNotEmpty) ...[
                        _sectionHeader('Recommended'),
                        _appSliverList(recommended),
                      ],
                      if (others.isNotEmpty) ...[
                        _sectionHeader(
                            recommended.isEmpty ? 'All apps' : 'More apps'),
                        _appSliverList(others),
                      ],
                      const SliverToBoxAdapter(child: SizedBox(height: 120)),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      bottomNavigationBar: Container(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        decoration: const BoxDecoration(
          color: AppColors.page,
          border: Border(
            top: BorderSide(color: AppColors.surfaceBorder),
          ),
        ),
        child: PrimaryButton(
          label: _selected.isEmpty
              ? 'Select apps to add'
              : 'Save ${_selected.length} App${_selected.length == 1 ? '' : 's'}',
          showIcon: _selected.isNotEmpty,
          trailingBadge: _selected.isEmpty ? null : _selected.length.toString(),
          onPressed: () => Navigator.of(context).pop(
            AppSelectionResult(
              selectedPackages: _selected,
              customBlockedDomains: _customDomains,
            ),
          ),
        ),
      ),
    );
  }
}

class _SelectedAppsStrip extends StatelessWidget {
  const _SelectedAppsStrip({
    required this.apps,
    required this.onRemove,
  });

  final List<InstalledAppInfo> apps;
  final ValueChanged<String> onRemove;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              'Selected apps',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(width: 7),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
              decoration: BoxDecoration(
                color: AppColors.surfaceRaised,
                borderRadius: BorderRadius.circular(AppRadius.pill),
              ),
              child: Text(
                '${apps.length}',
                style: const TextStyle(
                  color: AppColors.emerald,
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        SizedBox(
          height: 74,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: apps.length,
            separatorBuilder: (_, __) => const SizedBox(width: 12),
            itemBuilder: (context, index) {
              final app = apps[index];
              return Tooltip(
                key: ValueKey('selected_${app.packageName}'),
                message: 'Remove ${app.appName}',
                child: InkWell(
                  borderRadius: BorderRadius.circular(AppRadius.sm),
                  onTap: () => onRemove(app.packageName),
                  child: SizedBox(
                    width: 58,
                    child: Column(
                      children: [
                        Stack(
                          clipBehavior: Clip.none,
                          children: [
                            AppIconTile(
                              key: ValueKey('selected_icon_${app.packageName}'),
                              appName: app.appName,
                              packageName: app.packageName,
                              size: 48,
                              radius: 24,
                            ),
                            Positioned(
                              right: -3,
                              top: 0,
                              child: Container(
                                width: 18,
                                height: 18,
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  color: AppColors.surfaceRaised,
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: AppColors.surfaceBorder,
                                  ),
                                ),
                                child: const Icon(
                                  Icons.close_rounded,
                                  size: 11,
                                  color: AppColors.textSecondary,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 5),
                        Text(
                          app.appName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            color: AppColors.textSecondary,
                            fontSize: 10.5,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _DomainRulesSheet extends StatefulWidget {
  const _DomainRulesSheet({
    required this.initialDomains,
    required this.title,
    required this.description,
    required this.saveLabel,
  });

  final Set<String> initialDomains;
  final String title;
  final String description;
  final String saveLabel;

  @override
  State<_DomainRulesSheet> createState() => _DomainRulesSheetState();
}

class _DomainRulesSheetState extends State<_DomainRulesSheet> {
  final _controller = TextEditingController();
  late final Set<String> _domains;
  String _error = '';
  String? _editingDomain;

  @override
  void initState() {
    super.initState();
    _domains = {...widget.initialDomains};
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool _commitPendingDomain({bool allowEmpty = false}) {
    final raw = _controller.text.trim();
    if (raw.isEmpty && allowEmpty && _editingDomain == null) return true;
    final normalized = _normalizeDomain(_controller.text);
    if (normalized == null) {
      setState(() => _error = 'Enter a domain like example.com');
      return false;
    }
    setState(() {
      final editing = _editingDomain;
      if (editing != null && editing != normalized) {
        _domains.remove(editing);
      }
      _domains.add(normalized);
      _controller.clear();
      _error = '';
      _editingDomain = null;
    });
    return true;
  }

  void _addDomain() {
    _commitPendingDomain();
  }

  void _finish() {
    if (!_commitPendingDomain(allowEmpty: true)) return;
    Navigator.of(context).pop({..._domains});
  }

  void _beginEdit(String domain) {
    setState(() {
      _editingDomain = domain;
      _controller
        ..text = domain
        ..selection = TextSelection.collapsed(offset: domain.length);
      _error = '';
    });
  }

  void _cancelEdit() {
    setState(() {
      _editingDomain = null;
      _controller.clear();
      _error = '';
    });
  }

  @override
  Widget build(BuildContext context) {
    final sortedDomains = _domains.toList()..sort();
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: SafeArea(
        top: false,
        child: SolidCard(
          radius: 10,
          color: AppColors.surface,
          borderColor: AppColors.surface,
          padding: const EdgeInsets.fromLTRB(18, 10, 18, 18),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * 0.68,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          widget.title,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      IconButton(
                        tooltip: 'Close',
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                  Text(
                    widget.description,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 12.5,
                      height: 1.4,
                    ),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    controller: _controller,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.done,
                    autocorrect: false,
                    onSubmitted: (_) => _addDomain(),
                    decoration: InputDecoration(
                      hintText: _editingDomain == null
                          ? 'example.com'
                          : 'Edit domain',
                      errorText: _error.isEmpty ? null : _error,
                      filled: true,
                      fillColor: AppColors.surfaceRaised,
                      prefixIcon: const Icon(
                        Icons.language_rounded,
                        size: 19,
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(10),
                        borderSide:
                            const BorderSide(color: AppColors.surfaceBorder),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(10),
                        borderSide: const BorderSide(color: AppColors.emerald),
                      ),
                      suffixIcon: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_editingDomain != null)
                            IconButton(
                              tooltip: 'Cancel editing',
                              onPressed: _cancelEdit,
                              icon: const Icon(Icons.close_rounded),
                            ),
                          IconButton(
                            tooltip: _editingDomain == null
                                ? 'Add domain'
                                : 'Save domain',
                            onPressed: _addDomain,
                            icon: Icon(
                              _editingDomain == null
                                  ? Icons.add_rounded
                                  : Icons.check_rounded,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (sortedDomains.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Text(
                      'Custom rules',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const SizedBox(height: 10),
                    Column(
                      children: [
                        for (final domain in sortedDomains)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Container(
                              padding: const EdgeInsets.fromLTRB(12, 6, 4, 6),
                              decoration: BoxDecoration(
                                color: AppColors.surfaceRaised,
                                borderRadius: BorderRadius.circular(10),
                                border: Border.all(
                                  color: _editingDomain == domain
                                      ? AppColors.emerald
                                      : AppColors.surfaceBorder,
                                ),
                              ),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.language_rounded,
                                    size: 18,
                                    color: AppColors.emerald,
                                  ),
                                  const SizedBox(width: 9),
                                  Expanded(
                                    child: Text(
                                      domain,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        color: AppColors.textPrimary,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                  IconButton(
                                    tooltip: 'Edit $domain',
                                    visualDensity: VisualDensity.compact,
                                    onPressed: () => _beginEdit(domain),
                                    icon: const Icon(
                                      Icons.edit_outlined,
                                      size: 19,
                                    ),
                                  ),
                                  IconButton(
                                    tooltip: 'Delete $domain',
                                    visualDensity: VisualDensity.compact,
                                    color: AppColors.danger,
                                    onPressed: () => setState(() {
                                      _domains.remove(domain);
                                      if (_editingDomain == domain) {
                                        _editingDomain = null;
                                        _controller.clear();
                                      }
                                    }),
                                    icon: const Icon(
                                      Icons.delete_outline_rounded,
                                      size: 20,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                      ],
                    ),
                  ],
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: FilledButton.icon(
                      onPressed: _finish,
                      icon: const Icon(Icons.check_rounded, size: 19),
                      label: Text(widget.saveLabel),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

String? _normalizeDomain(String raw) {
  var value = raw.trim().toLowerCase();
  if (value.startsWith('https://')) value = value.substring(8);
  if (value.startsWith('http://')) value = value.substring(7);
  value = value.split(RegExp(r'[/#?]')).first.split(':').first;
  value = value.replaceAll(RegExp(r'^\.+|\.+$'), '');
  if (value.length > 253 || !value.contains('.')) return null;
  final labels = value.split('.');
  final labelPattern = RegExp(r'^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$');
  if (labels.any(
    (label) =>
        label.isEmpty || label.length > 63 || !labelPattern.hasMatch(label),
  )) {
    return null;
  }
  return value;
}

class _HeaderBar extends StatelessWidget {
  const _HeaderBar({required this.target});

  final String target;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          IconButton(
            onPressed: () => Navigator.of(context).maybePop(),
            icon: const Icon(Icons.arrow_back_rounded),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 8),
                Text(
                  'Select Apps',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 4),
                Text(
                  'Choose apps for your $target.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.controller});

  final TextEditingController controller;

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      style: const TextStyle(color: AppColors.textPrimary),
      decoration: InputDecoration(
        hintText: 'Search apps...',
        hintStyle: const TextStyle(color: AppColors.textMuted),
        prefixIcon:
            const Icon(Icons.search_rounded, color: AppColors.textSecondary),
        filled: true,
        fillColor: AppColors.surface,
        contentPadding: const EdgeInsets.symmetric(vertical: 4),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.pill),
          borderSide: const BorderSide(color: AppColors.surfaceBorder),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.pill),
          borderSide: const BorderSide(color: AppColors.emerald),
        ),
      ),
    );
  }
}

class _AppRow extends StatelessWidget {
  const _AppRow({
    super.key,
    required this.app,
    required this.category,
    required this.selected,
    required this.webProtected,
    required this.onTap,
  });

  final InstalledAppInfo app;
  final String category;
  final bool selected;
  final bool webProtected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      padding: const EdgeInsets.all(12),
      color: selected ? AppColors.surfaceRaised : AppColors.surface,
      selected: selected,
      child: Column(
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(AppRadius.sm),
            onTap: onTap,
            child: Row(
              children: [
                AppIconTile(
                  appName: app.appName,
                  packageName: app.packageName,
                  size: 44,
                  radius: 13,
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        app.appName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        webProtected
                            ? '$category  •  Web protection on'
                            : category,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: webProtected
                                  ? AppColors.emerald
                                  : AppColors.textMuted,
                            ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                _CheckBox(selected: selected),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CheckBox extends StatelessWidget {
  const _CheckBox({required this.selected});

  final bool selected;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 160),
      width: 26,
      height: 26,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: selected ? AppColors.emerald : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: selected ? AppColors.emerald : AppColors.surfaceBorder,
          width: 2,
        ),
      ),
      child: selected
          ? const Icon(Icons.check_rounded, size: 17, color: Color(0xFF0C1A05))
          : null,
    );
  }
}

/// Read-only section listing the apps the blocker always allows (phone,
/// messages, settings, maps). These can't be selected or blocked — shown so the
/// child understands essential/safety apps stay reachable during focus.
class _AlwaysAllowedCard extends StatelessWidget {
  const _AlwaysAllowedCard({required this.apps});

  final List<InstalledAppInfo> apps;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      color: AppColors.surface,
      borderColor: AppColors.surfaceBorder,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.verified_user_rounded,
                  size: 18, color: AppColors.emerald),
              const SizedBox(width: 8),
              Text('Always allowed',
                  style: Theme.of(context).textTheme.titleSmall),
            ],
          ),
          const SizedBox(height: 6),
          const Text(
            'Calls, messages, settings and maps stay open during focus for your '
            "safety — these can't be blocked.",
            style: TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12.5,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 16,
            runSpacing: 14,
            children: [
              for (final app in apps)
                SizedBox(
                  width: 60,
                  child: Column(
                    children: [
                      Stack(
                        clipBehavior: Clip.none,
                        children: [
                          AppIconTile(
                            appName: app.appName,
                            packageName: app.packageName,
                            size: 46,
                            radius: 13,
                          ),
                          Positioned(
                            right: -3,
                            bottom: -3,
                            child: Container(
                              padding: const EdgeInsets.all(3),
                              decoration: BoxDecoration(
                                color: AppColors.emerald,
                                shape: BoxShape.circle,
                                border: Border.all(
                                    color: AppColors.night, width: 1.5),
                              ),
                              child: const Icon(Icons.lock_rounded,
                                  size: 10, color: Color(0xFF0C1A05)),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        app.appName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
