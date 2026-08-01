import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../services/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon_tile.dart';
import '../widgets/glass_card.dart';
import '../widgets/primary_button.dart';

class FocusClockState {
  const FocusClockState({
    required this.breakState,
    required this.sessionState,
  });

  final FocusBreakState breakState;
  final FocusSessionState sessionState;
}

class FocusHomeScreen extends StatelessWidget {
  const FocusHomeScreen({
    super.key,
    required this.study,
    required this.breakState,
    required this.sessionState,
    required this.liveClock,
    required this.todayUsageMs,
    required this.usageReady,
    required this.todayFocusMs,
    required this.busy,
    required this.nameForPackage,
    required this.onSetPolicy,
    required this.onSetTrackingMode,
    required this.onSetDuration,
    required this.onSetSessionType,
    required this.onSetProtectionLevel,
    required this.onEditApps,
    required this.onStartFocus,
    required this.onStopFocus,
    required this.onEndBreak,
    required this.onOpenBlocks,
    required this.onOpenSettings,
  });

  final StudySettings study;
  final FocusBreakState breakState;
  final FocusSessionState sessionState;
  final ValueListenable<FocusClockState> liveClock;
  final int todayUsageMs;
  final bool usageReady;
  final int todayFocusMs;
  final bool busy;
  final String Function(String packageName) nameForPackage;
  final Future<void> Function(String policy) onSetPolicy;
  final ValueChanged<String> onSetTrackingMode;
  final ValueChanged<int> onSetDuration;
  final ValueChanged<String> onSetSessionType;
  final ValueChanged<String> onSetProtectionLevel;
  final Future<int?> Function() onEditApps;
  final VoidCallback onStartFocus;
  final VoidCallback onStopFocus;
  final VoidCallback onEndBreak;
  final VoidCallback onOpenBlocks;
  final VoidCallback onOpenSettings;

  bool get _sessionActive => study.enabled || sessionState.active;

  @override
  Widget build(BuildContext context) {
    final packages = (study.policy == StudySettings.blocklist
            ? study.blockedPackages
            : study.allowedPackages)
        .toList()
      ..sort((a, b) => nameForPackage(a).compareTo(nameForPackage(b)));
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 126),
      children: [
        Row(
          children: [
            Expanded(
              child: _MetricCard(
                label: 'Usage Time',
                value: usageReady ? _compactDuration(todayUsageMs) : 'Allow',
                onTap: usageReady ? null : onOpenSettings,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: _MetricCard(
                label: 'Focus Time',
                value: _compactDuration(todayFocusMs),
              ),
            ),
            const SizedBox(width: 10),
            Material(
              color: Colors.black.withValues(alpha: 0.48),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(18),
                side: BorderSide(
                  color: Colors.white.withValues(alpha: 0.12),
                ),
              ),
              child: InkWell(
                borderRadius: BorderRadius.circular(18),
                onTap: onOpenBlocks,
                child: const Padding(
                  padding: EdgeInsets.all(15),
                  child:
                      Icon(Icons.hourglass_top_rounded, color: AppColors.lime),
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 30),
        _FocusTimerDial(
          study: study,
          breakState: breakState,
          sessionState: sessionState,
          liveClock: liveClock,
          packages: packages,
          nameForPackage: nameForPackage,
          onEdit: () => _openSetup(context),
        ),
        const SizedBox(height: 30),
        if (breakState.active) ...[
          GhostButton(
            label: 'End break early',
            icon: Icons.skip_next_rounded,
            onPressed: busy ? null : onEndBreak,
          ),
          const SizedBox(height: 12),
        ],
        PrimaryButton(
          label: busy
              ? 'Please wait...'
              : _sessionActive
                  ? study.lockedStrictModeEnabled
                      ? 'Locked until timer ends'
                      : study.strictModeEnabled
                          ? 'Emergency Exit'
                          : 'End Focus'
                  : 'Start Focus Now',
          icon: _sessionActive
              ? study.lockedStrictModeEnabled
                  ? Icons.lock_rounded
                  : Icons.stop_circle_outlined
              : Icons.play_arrow_rounded,
          onPressed: busy
              ? null
              : _sessionActive
                  ? study.lockedStrictModeEnabled
                      ? null
                      : onStopFocus
                  : onStartFocus,
        ),
        const SizedBox(height: 12),
        Text(
          _modeSummary(study),
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  void _openSetup(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (_) => FocusSetupSheet(
        study: study,
        allowedCount: study.allowedPackages.length,
        blockedCount: study.blockedPackages.length,
        onSetPolicy: onSetPolicy,
        onSetTrackingMode: onSetTrackingMode,
        onSetDuration: onSetDuration,
        onSetSessionType: onSetSessionType,
        onSetProtectionLevel: onSetProtectionLevel,
        onEditApps: onEditApps,
        onOpenBlocks: onOpenBlocks,
        onStart: () {
          Navigator.of(context).pop();
          onStartFocus();
        },
      ),
    );
  }
}

class _FocusTimerDial extends StatelessWidget {
  const _FocusTimerDial({
    required this.study,
    required this.breakState,
    required this.sessionState,
    required this.liveClock,
    required this.packages,
    required this.nameForPackage,
    required this.onEdit,
  });

  final StudySettings study;
  final FocusBreakState breakState;
  final FocusSessionState sessionState;
  final ValueListenable<FocusClockState> liveClock;
  final List<String> packages;
  final String Function(String) nameForPackage;
  final VoidCallback onEdit;

  bool get _sessionActive => study.enabled || sessionState.active;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final size = constraints.maxWidth.clamp(268.0, 320.0).toDouble();
        final accent = breakState.active
            ? AppColors.warning
            : _sessionActive
                ? AppColors.emerald
                : AppColors.textMuted;
        return Center(
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            width: size,
            height: size,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              boxShadow: _sessionActive
                  ? [
                      BoxShadow(
                        color: accent.withValues(alpha: 0.24),
                        blurRadius: 34,
                        spreadRadius: -8,
                      ),
                    ]
                  : const [],
            ),
            child: GlassCard(
              radius: size / 2,
              blur: 10,
              padding: const EdgeInsets.all(18),
              color: Colors.black.withValues(alpha: 0.38),
              borderColor: Colors.white.withValues(alpha: 0.20),
              onTap: _sessionActive ? null : onEdit,
              child: Stack(
                fit: StackFit.expand,
                alignment: Alignment.center,
                children: [
                  ValueListenableBuilder<FocusClockState>(
                    valueListenable: liveClock,
                    builder: (context, clockState, _) {
                      return CircularProgressIndicator(
                        value: _dialProgress(clockState),
                        strokeWidth: 6,
                        strokeCap: StrokeCap.round,
                        backgroundColor: Colors.white.withValues(alpha: 0.12),
                        color: breakState.active
                            ? AppColors.warning
                            : AppColors.lime,
                      );
                    },
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(
                          breakState.active
                              ? 'BREAK TIME'
                              : _sessionActive
                                  ? 'FOCUS IN PROGRESS'
                                  : 'READY TO FOCUS',
                          style: TextStyle(
                            color: accent,
                            fontSize: 10.5,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.15,
                            shadows: const [
                              Shadow(color: Colors.black, blurRadius: 8),
                            ],
                          ),
                        ),
                        const SizedBox(height: 14),
                        ValueListenableBuilder<FocusClockState>(
                          valueListenable: liveClock,
                          builder: (context, clockState, _) {
                            final displayedMs = _displayedMs(clockState);
                            return Text(
                              _clock(displayedMs),
                              maxLines: 1,
                              style: TextStyle(
                                color: AppColors.textPrimary,
                                fontSize:
                                    displayedMs >= 60 * 60 * 1000 ? 40 : 54,
                                height: 1,
                                fontWeight: FontWeight.w700,
                                letterSpacing: -2,
                                fontFeatures: const [
                                  FontFeature.tabularFigures(),
                                ],
                                shadows: const [
                                  Shadow(color: Colors.black, blurRadius: 12),
                                ],
                              ),
                            );
                          },
                        ),
                        const SizedBox(height: 16),
                        _AppStrip(
                          packages: packages,
                          nameForPackage: nameForPackage,
                        ),
                        const SizedBox(height: 14),
                        if (!_sessionActive)
                          Material(
                            color: Colors.white.withValues(alpha: 0.10),
                            borderRadius: BorderRadius.circular(AppRadius.pill),
                            child: InkWell(
                              borderRadius:
                                  BorderRadius.circular(AppRadius.pill),
                              onTap: onEdit,
                              child: const Padding(
                                padding: EdgeInsets.symmetric(
                                  horizontal: 14,
                                  vertical: 8,
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.edit_outlined, size: 15),
                                    SizedBox(width: 7),
                                    Text(
                                      'Edit',
                                      style: TextStyle(
                                        color: AppColors.textPrimary,
                                        fontSize: 12.5,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          )
                        else
                          Text(
                            breakState.active
                                ? 'Automatic break'
                                : sessionState.isStopwatch
                                    ? 'Stopwatch focus'
                                    : 'Stay with the plan',
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              shadows: [
                                Shadow(color: Colors.black, blurRadius: 8),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  int _displayedMs(FocusClockState clockState) {
    final liveBreak = clockState.breakState;
    final liveSession = clockState.sessionState;
    final liveSessionActive = study.enabled || liveSession.active;
    if (liveBreak.active) return liveBreak.remainingMs;
    if (liveSessionActive) {
      return liveSession.isStopwatch
          ? liveSession.focusedMs
          : liveSession.remainingMs;
    }
    return study.isStopwatch ? 0 : study.durationMinutes * 60 * 1000;
  }

  double _dialProgress(FocusClockState clockState) {
    final liveBreak = clockState.breakState;
    final liveSession = clockState.sessionState;
    if (liveBreak.active) {
      const breakMs = 5 * 60 * 1000;
      return (1 - liveBreak.remainingMs / breakMs).clamp(0.0, 1.0);
    }
    if (liveSession.active && liveSession.targetMs > 0) {
      if (liveSession.isStopwatch) {
        return (liveSession.focusedMs / liveSession.targetMs).clamp(0.0, 1.0);
      }
      return (1 - liveSession.remainingMs / liveSession.targetMs)
          .clamp(0.0, 1.0);
    }
    return 0;
  }
}

class FocusSetupSheet extends StatefulWidget {
  const FocusSetupSheet({
    super.key,
    required this.study,
    required this.allowedCount,
    required this.blockedCount,
    required this.onSetPolicy,
    required this.onSetTrackingMode,
    required this.onSetDuration,
    required this.onSetSessionType,
    required this.onSetProtectionLevel,
    required this.onEditApps,
    required this.onOpenBlocks,
    required this.onStart,
  });

  final StudySettings study;
  final int allowedCount;
  final int blockedCount;
  final Future<void> Function(String policy) onSetPolicy;
  final ValueChanged<String> onSetTrackingMode;
  final ValueChanged<int> onSetDuration;
  final ValueChanged<String> onSetSessionType;
  final ValueChanged<String> onSetProtectionLevel;
  final Future<int?> Function() onEditApps;
  final VoidCallback onOpenBlocks;
  final VoidCallback onStart;

  @override
  State<FocusSetupSheet> createState() => _FocusSetupSheetState();
}

class _FocusSetupSheetState extends State<FocusSetupSheet> {
  late String _sessionType;
  late String _protection;
  late String _policy;
  late String _trackingMode;
  late int _duration;
  late int _allowedCount;
  late int _blockedCount;

  @override
  void initState() {
    super.initState();
    _sessionType = widget.study.sessionType;
    _protection = widget.study.lockedStrictModeEnabled
        ? 'locked'
        : widget.study.strictModeEnabled
            ? 'strict'
            : 'normal';
    _policy = widget.study.policy;
    _trackingMode = widget.study.trackingMode;
    _duration = widget.study.durationMinutes;
    _allowedCount = widget.allowedCount;
    _blockedCount = widget.blockedCount;
  }

  @override
  Widget build(BuildContext context) {
    final automaticBreaks = _sessionType == StudySettings.stopwatch
        ? 'Earn 1 every 30 focused minutes'
        : '${_duration ~/ 30} automatic';
    return Container(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(context).height * 0.9,
      ),
      decoration: const BoxDecoration(
        color: Colors.black,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppRadius.lg),
        ),
      ),
      child: Column(
        children: [
          const SizedBox(height: 10),
          Container(
            width: 56,
            height: 5,
            decoration: BoxDecoration(
              color: AppColors.textMuted,
              borderRadius: BorderRadius.circular(5),
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 18),
              children: [
                _SegmentedChoices(
                  values: const [
                    _Choice(StudySettings.timer, 'Timer'),
                    _Choice(StudySettings.stopwatch, 'Stopwatch'),
                  ],
                  selected: _sessionType,
                  onChanged: (value) {
                    setState(() {
                      _sessionType = value;
                      if (value == StudySettings.stopwatch &&
                          _protection == 'locked') {
                        _protection = 'strict';
                        widget.onSetProtectionLevel('strict');
                      }
                    });
                    widget.onSetSessionType(value);
                  },
                ),
                const SizedBox(height: 18),
                _SetupRow(
                  icon: Icons.schedule_rounded,
                  title: _sessionType == StudySettings.stopwatch
                      ? 'Maximum safety stop'
                      : 'Focus Time',
                  value: _sessionType == StudySettings.stopwatch
                      ? (_protection == 'strict' ? '8 hours' : '24 hours')
                      : _longDuration(_duration * 60 * 1000),
                  enabled: _sessionType == StudySettings.timer,
                  onTap: () async {
                    final selected = await showDurationPicker(
                      context,
                      selectedMinutes: _duration,
                      maximumMinutes: _protection == 'locked'
                          ? StudySettings.maxLockedStrictMinutes
                          : _protection == 'strict'
                              ? StudySettings.maxStrictMinutes
                              : StudySettings.maxNormalMinutes,
                    );
                    if (selected == null || !mounted) return;
                    setState(() => _duration = selected);
                    widget.onSetDuration(selected);
                  },
                ),
                _SetupRow(
                  icon: Icons.coffee_rounded,
                  title: 'Breaks',
                  value: automaticBreaks,
                ),
                _SetupRow(
                  icon: Icons.apps_rounded,
                  title: _policy == StudySettings.blocklist
                      ? 'Blocked Apps'
                      : 'Allowed Apps',
                  value:
                      '${_policy == StudySettings.blocklist ? _blockedCount : _allowedCount} apps',
                  onTap: () async {
                    await widget.onSetPolicy(_policy);
                    final updatedCount = await widget.onEditApps();
                    if (updatedCount == null || !mounted) return;
                    setState(() {
                      if (_policy == StudySettings.blocklist) {
                        _blockedCount = updatedCount;
                      } else {
                        _allowedCount = updatedCount;
                      }
                    });
                  },
                ),
                const SizedBox(height: 20),
                Text('Protection level',
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 10),
                _SegmentedChoices(
                  values: [
                    const _Choice('normal', 'Normal'),
                    const _Choice('strict', 'Strict'),
                    _Choice(
                      'locked',
                      'Locked',
                      enabled: _sessionType != StudySettings.stopwatch,
                    ),
                  ],
                  selected: _protection,
                  onChanged: (value) {
                    setState(() {
                      _protection = value;
                      final maximum = value == 'locked'
                          ? StudySettings.maxLockedStrictMinutes
                          : value == 'strict'
                              ? StudySettings.maxStrictMinutes
                              : StudySettings.maxNormalMinutes;
                      if (_duration > maximum) _duration = maximum;
                    });
                    widget.onSetProtectionLevel(value);
                    widget.onSetDuration(_duration);
                  },
                ),
                if (_sessionType == StudySettings.stopwatch) ...[
                  const SizedBox(height: 8),
                  const Text(
                    'Locked Strict needs a fixed Timer deadline, so it is unavailable for Stopwatch.',
                    style:
                        TextStyle(color: AppColors.textMuted, fontSize: 12.5),
                  ),
                ],
                const SizedBox(height: 20),
                Text('Focus rules',
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 10),
                _SegmentedChoices(
                  values: const [
                    _Choice(StudySettings.blocklist, 'Block selected'),
                    _Choice(StudySettings.allowlist, 'Allow only selected'),
                  ],
                  selected: _policy,
                  onChanged: (value) {
                    if (_trackingMode == StudySettings.appStudy &&
                        value == StudySettings.blocklist) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text(
                              'Selected-app timing requires Allow only selected.'),
                        ),
                      );
                      return;
                    }
                    setState(() => _policy = value);
                    widget.onSetPolicy(value);
                  },
                ),
                const SizedBox(height: 10),
                _SegmentedChoices(
                  values: const [
                    _Choice(StudySettings.bookStudy, 'Continuous timer'),
                    _Choice(StudySettings.appStudy, 'Selected apps only'),
                  ],
                  selected: _trackingMode,
                  onChanged: (value) {
                    setState(() {
                      _trackingMode = value;
                      if (value == StudySettings.appStudy) {
                        _policy = StudySettings.allowlist;
                      }
                    });
                    widget.onSetTrackingMode(value);
                  },
                ),
                const SizedBox(height: 12),
                _SetupRow(
                  icon: Icons.language_rounded,
                  title: 'Website Protection',
                  value:
                      '${widget.study.customBlockedDomains.length} custom rules',
                  onTap: () {
                    Navigator.of(context).pop();
                    widget.onOpenBlocks();
                  },
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 10, 20, 24),
            child: PrimaryButton(
              label: 'Start Focus Now',
              icon: Icons.play_arrow_rounded,
              onPressed: widget.onStart,
            ),
          ),
        ],
      ),
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({required this.label, required this.value, this.onTap});
  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      onTap: onTap,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 3),
          Text(value, style: Theme.of(context).textTheme.titleMedium),
        ],
      ),
    );
  }
}

class _AppStrip extends StatelessWidget {
  const _AppStrip({required this.packages, required this.nameForPackage});
  final List<String> packages;
  final String Function(String) nameForPackage;

  @override
  Widget build(BuildContext context) {
    if (packages.isEmpty) {
      return const Text(
        'No apps selected',
        style: TextStyle(color: AppColors.textMuted, fontSize: 13),
      );
    }
    final visible = packages.toSet().take(3).toList();
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ...visible.map(
          (packageName) => Padding(
            key: ValueKey(packageName),
            padding: const EdgeInsets.only(right: 4),
            child: AppIconTile(
              appName: nameForPackage(packageName),
              packageName: packageName,
              size: 28,
              radius: 14,
            ),
          ),
        ),
        const SizedBox(width: 6),
        Text(
          '${packages.length} apps',
          style: const TextStyle(
            color: AppColors.textSecondary,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}

class _SetupRow extends StatelessWidget {
  const _SetupRow({
    required this.icon,
    required this.title,
    required this.value,
    this.enabled = true,
    this.onTap,
  });
  final IconData icon;
  final String title;
  final String value;
  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 4),
      enabled: enabled,
      leading: Icon(icon,
          color: enabled ? AppColors.textSecondary : AppColors.textMuted),
      title: Text(title, style: Theme.of(context).textTheme.titleMedium),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(value, style: Theme.of(context).textTheme.bodyMedium),
          if (onTap != null) const Icon(Icons.chevron_right_rounded),
        ],
      ),
      onTap: enabled ? onTap : null,
    );
  }
}

class _Choice {
  const _Choice(this.value, this.label, {this.enabled = true});
  final String value;
  final String label;
  final bool enabled;
}

class _SegmentedChoices extends StatelessWidget {
  const _SegmentedChoices({
    required this.values,
    required this.selected,
    required this.onChanged,
  });
  final List<_Choice> values;
  final String selected;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(color: AppColors.surfaceBorder),
      ),
      child: Row(
        children: values.map((choice) {
          final active = choice.value == selected;
          return Expanded(
            child: InkWell(
              borderRadius: BorderRadius.circular(AppRadius.sm),
              onTap: choice.enabled ? () => onChanged(choice.value) : null,
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 160),
                padding:
                    const EdgeInsets.symmetric(vertical: 12, horizontal: 4),
                decoration: BoxDecoration(
                  color: active
                      ? AppColors.emerald.withValues(alpha: 0.18)
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(AppRadius.sm),
                  boxShadow: active
                      ? [
                          BoxShadow(
                            color: AppColors.emerald.withValues(alpha: 0.24),
                            blurRadius: 18,
                            spreadRadius: -5,
                          ),
                        ]
                      : const [],
                ),
                alignment: Alignment.center,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (!choice.enabled) ...[
                      const Icon(Icons.lock_outline_rounded,
                          size: 14, color: AppColors.textMuted),
                      const SizedBox(width: 4),
                    ],
                    Flexible(
                      child: Text(
                        choice.label,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: !choice.enabled
                              ? AppColors.textMuted
                              : active
                                  ? AppColors.emerald
                                  : AppColors.textSecondary,
                          fontWeight: FontWeight.w800,
                          fontSize: 12.5,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

Future<int?> showDurationPicker(
  BuildContext context, {
  required int selectedMinutes,
  required int maximumMinutes,
}) {
  return showModalBottomSheet<int>(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (_) => _DurationPicker(
      selectedMinutes: selectedMinutes,
      maximumMinutes: maximumMinutes,
    ),
  );
}

class _DurationPicker extends StatefulWidget {
  const _DurationPicker({
    required this.selectedMinutes,
    required this.maximumMinutes,
  });
  final int selectedMinutes;
  final int maximumMinutes;

  @override
  State<_DurationPicker> createState() => _DurationPickerState();
}

class _DurationPickerState extends State<_DurationPicker> {
  late int _hours;
  late int _minutes;
  late FixedExtentScrollController _hoursController;
  late FixedExtentScrollController _minutesController;

  @override
  void initState() {
    super.initState();
    final selected =
        widget.selectedMinutes.clamp(1, widget.maximumMinutes).toInt();
    _hours = selected ~/ 60;
    _minutes = selected % 60;
    _hoursController = FixedExtentScrollController(initialItem: _hours);
    _minutesController =
        FixedExtentScrollController(initialItem: _minutes ~/ 5);
  }

  @override
  void dispose() {
    _hoursController.dispose();
    _minutesController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final maxHours = widget.maximumMinutes ~/ 60;
    final maxMinutesForHour =
        _hours == maxHours ? widget.maximumMinutes % 60 : 55;
    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFF111513),
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(AppRadius.lg),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 5,
              decoration: BoxDecoration(
                color: AppColors.textMuted,
                borderRadius: BorderRadius.circular(5),
              ),
            ),
            const SizedBox(height: 30),
            Text('Set duration for your focus session.',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 18),
            SizedBox(
              height: 210,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  Container(
                    height: 58,
                    decoration: BoxDecoration(
                      color: AppColors.lime.withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(AppRadius.md),
                    ),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: _Wheel(
                          controller: _hoursController,
                          count: maxHours + 1,
                          labelBuilder: (index) => '$index hours',
                          onChanged: (value) {
                            setState(() {
                              _hours = value;
                              final allowedMinutes = value == maxHours
                                  ? widget.maximumMinutes % 60
                                  : 55;
                              if (_minutes > allowedMinutes) {
                                _minutes = allowedMinutes;
                                if (_minutesController.hasClients) {
                                  _minutesController.jumpToItem(_minutes ~/ 5);
                                }
                              }
                            });
                          },
                        ),
                      ),
                      Expanded(
                        child: _Wheel(
                          controller: _minutesController,
                          count: maxMinutesForHour ~/ 5 + 1,
                          labelBuilder: (index) => '${index * 5} mins',
                          onChanged: (value) =>
                              setState(() => _minutes = value * 5),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
            PrimaryButton(
              label: 'Confirm',
              showIcon: false,
              onPressed: () {
                final rawTotal = _hours * 60 + _minutes;
                if (rawTotal <= 0) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Choose at least 5 minutes.'),
                    ),
                  );
                  return;
                }
                final total = rawTotal.clamp(1, widget.maximumMinutes).toInt();
                Navigator.of(context).pop(total);
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _Wheel extends StatelessWidget {
  const _Wheel({
    required this.controller,
    required this.count,
    required this.labelBuilder,
    required this.onChanged,
  });
  final FixedExtentScrollController controller;
  final int count;
  final String Function(int) labelBuilder;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return ListWheelScrollView.useDelegate(
      controller: controller,
      itemExtent: 52,
      physics: const FixedExtentScrollPhysics(),
      perspective: 0.004,
      diameterRatio: 1.7,
      onSelectedItemChanged: onChanged,
      childDelegate: ListWheelChildBuilderDelegate(
        childCount: count,
        builder: (context, index) => Center(
          child: Text(
            labelBuilder(index),
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 20,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ),
    );
  }
}

String _clock(int milliseconds) {
  final totalSeconds =
      (milliseconds ~/ 1000).clamp(0, 99 * 3600 + 3599).toInt();
  final hours = totalSeconds ~/ 3600;
  final minutes = (totalSeconds % 3600) ~/ 60;
  final seconds = totalSeconds % 60;
  if (hours > 0) {
    return '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }
  return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
}

String _compactDuration(int milliseconds) {
  final minutes = (milliseconds ~/ 60000).clamp(0, 24 * 60).toInt();
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  if (hours <= 0) return '${remainder}m';
  if (remainder == 0) return '${hours}h';
  return '${hours}h ${remainder}m';
}

String _longDuration(int milliseconds) {
  final totalMinutes = milliseconds ~/ 60000;
  final hours = totalMinutes ~/ 60;
  final minutes = totalMinutes % 60;
  if (hours <= 0) return '$minutes mins';
  if (minutes == 0) return '$hours hours';
  return '$hours h $minutes mins';
}

String _modeSummary(StudySettings study) {
  final type = study.isStopwatch ? 'Stopwatch' : 'Timer';
  if (study.lockedStrictModeEnabled) {
    return '$type | Locked Strict | No emergency exit | 3-hour maximum';
  }
  if (study.strictModeEnabled) {
    return '$type | Strict | DND and protected emergency exit';
  }
  return '$type | Normal protection | End anytime';
}
