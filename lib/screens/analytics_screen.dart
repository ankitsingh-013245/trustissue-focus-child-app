import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon_tile.dart';
import '../widgets/glass_card.dart';

enum _AnalyticsPeriod { daily, weekly, monthly }

class AnalyticsScreen extends StatefulWidget {
  const AnalyticsScreen({
    super.key,
    required this.analytics,
    required this.loading,
    required this.onRefresh,
  });

  final List<SelfControlDayMetrics> analytics;
  final bool loading;
  final Future<void> Function() onRefresh;

  @override
  State<AnalyticsScreen> createState() => _AnalyticsScreenState();
}

class _AnalyticsScreenState extends State<AnalyticsScreen> {
  _AnalyticsPeriod _period = _AnalyticsPeriod.daily;
  String? _selectedDate;

  @override
  Widget build(BuildContext context) {
    final days = widget.analytics.toList()
      ..sort((a, b) => a.date.compareTo(b.date));
    final selectedDate = days.any((day) => day.date == _selectedDate)
        ? _selectedDate!
        : days.isEmpty
            ? ''
            : days.last.date;
    final selectedIndex = days.indexWhere((day) => day.date == selectedDate);
    final scopeDays = _daysForPeriod(days, selectedIndex);
    final comparisonDays = _comparisonDays(days, selectedIndex);
    final summary = _AnalyticsSummary.fromDays(scopeDays);
    final comparison = _AnalyticsSummary.fromDays(comparisonDays);
    final trendDays = _trendDays(days, selectedIndex, scopeDays);
    final hasAnyActivity = days.any((day) => day.hasActivity);

    return RefreshIndicator(
      onRefresh: widget.onRefresh,
      color: AppColors.lime,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 120),
        children: [
          _AnalyticsHeader(
            loading: widget.loading,
            onRefresh: widget.loading ? null : widget.onRefresh,
          ),
          const SizedBox(height: 16),
          _PeriodSelector(
            selected: _period,
            onChanged: (period) => setState(() => _period = period),
          ),
          if (_period == _AnalyticsPeriod.daily && days.isNotEmpty) ...[
            const SizedBox(height: 13),
            _DaySelector(
              days: days,
              selectedDate: selectedDate,
              onSelected: (date) => setState(() => _selectedDate = date),
            ),
          ],
          const SizedBox(height: 16),
          if (widget.loading && !hasAnyActivity)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(44),
                child: CircularProgressIndicator(color: AppColors.lime),
              ),
            )
          else if (!hasAnyActivity)
            const _EmptyReport()
          else ...[
            _SummaryCard(
              period: _period,
              selectedDate: selectedDate,
              summary: summary,
              comparison: comparison,
            ),
            const SizedBox(height: 12),
            _MotivationCard(
              period: _period,
              summary: summary,
              comparison: comparison,
            ),
            const SizedBox(height: 12),
            _FocusTrendCard(
              days: trendDays,
              highlightedDate:
                  _period == _AnalyticsPeriod.daily ? selectedDate : '',
              title: _period == _AnalyticsPeriod.monthly
                  ? '30-day focus rhythm'
                  : '7-day focus rhythm',
            ),
            const SizedBox(height: 12),
            _AppAttemptsCard(summary: summary),
            if (summary.focusApps.isNotEmpty) ...[
              const SizedBox(height: 12),
              _FocusAppsCard(apps: summary.focusApps),
            ],
            if (summary.breakCount > 0) ...[
              const SizedBox(height: 12),
              _BreakControlCard(summary: summary),
            ],
          ],
        ],
      ),
    );
  }

  List<SelfControlDayMetrics> _daysForPeriod(
    List<SelfControlDayMetrics> days,
    int selectedIndex,
  ) {
    if (days.isEmpty) return const [];
    switch (_period) {
      case _AnalyticsPeriod.daily:
        return selectedIndex < 0 ? [days.last] : [days[selectedIndex]];
      case _AnalyticsPeriod.weekly:
        final start = days.length > 7 ? days.length - 7 : 0;
        return days.sublist(start);
      case _AnalyticsPeriod.monthly:
        final start = days.length > 30 ? days.length - 30 : 0;
        return days.sublist(start);
    }
  }

  List<SelfControlDayMetrics> _comparisonDays(
    List<SelfControlDayMetrics> days,
    int selectedIndex,
  ) {
    if (days.isEmpty) return const [];
    switch (_period) {
      case _AnalyticsPeriod.daily:
        if (selectedIndex <= 0) return const [];
        return [days[selectedIndex - 1]];
      case _AnalyticsPeriod.weekly:
        final end = days.length > 7 ? days.length - 7 : 0;
        final start = end > 7 ? end - 7 : 0;
        return days.sublist(start, end);
      case _AnalyticsPeriod.monthly:
        return const [];
    }
  }

  List<SelfControlDayMetrics> _trendDays(
    List<SelfControlDayMetrics> days,
    int selectedIndex,
    List<SelfControlDayMetrics> scopeDays,
  ) {
    if (_period != _AnalyticsPeriod.daily) return scopeDays;
    if (selectedIndex < 0) return const [];
    final start = selectedIndex > 6 ? selectedIndex - 6 : 0;
    return days.sublist(start, selectedIndex + 1);
  }
}

class _AnalyticsHeader extends StatelessWidget {
  const _AnalyticsHeader({required this.loading, required this.onRefresh});

  final bool loading;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Reports',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 3),
              Text(
                'Your private focus story',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        IconButton.filledTonal(
          tooltip: 'Refresh analytics',
          onPressed: onRefresh == null ? null : () => onRefresh!(),
          icon: loading
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.refresh_rounded, size: 21),
        ),
      ],
    );
  }
}

class _PeriodSelector extends StatelessWidget {
  const _PeriodSelector({required this.selected, required this.onChanged});

  final _AnalyticsPeriod selected;
  final ValueChanged<_AnalyticsPeriod> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 44,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadius.pill),
        border: Border.all(color: AppColors.surfaceBorder),
      ),
      child: Row(
        children: [
          _PeriodTab(
            label: 'Daily',
            selected: selected == _AnalyticsPeriod.daily,
            onTap: () => onChanged(_AnalyticsPeriod.daily),
          ),
          _PeriodTab(
            label: 'Weekly',
            selected: selected == _AnalyticsPeriod.weekly,
            onTap: () => onChanged(_AnalyticsPeriod.weekly),
          ),
          _PeriodTab(
            label: 'Monthly',
            selected: selected == _AnalyticsPeriod.monthly,
            onTap: () => onChanged(_AnalyticsPeriod.monthly),
          ),
        ],
      ),
    );
  }
}

class _PeriodTab extends StatelessWidget {
  const _PeriodTab({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Material(
        color: selected ? AppColors.lime : Colors.transparent,
        borderRadius: BorderRadius.circular(AppRadius.pill),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppRadius.pill),
          child: Center(
            child: Text(
              label,
              style: TextStyle(
                color: selected
                    ? const Color(0xFF0C1A05)
                    : AppColors.textSecondary,
                fontSize: 12.5,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _DaySelector extends StatelessWidget {
  const _DaySelector({
    required this.days,
    required this.selectedDate,
    required this.onSelected,
  });

  final List<SelfControlDayMetrics> days;
  final String selectedDate;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    final reversed = days.reversed.toList();
    return SizedBox(
      height: 64,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: reversed.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final day = reversed[index];
          final selected = day.date == selectedDate;
          final date = DateTime.tryParse(day.date);
          final isToday = day.date == _todayKey();
          return Material(
            color: selected
                ? AppColors.emerald.withValues(alpha: 0.18)
                : AppColors.surface,
            borderRadius: BorderRadius.circular(AppRadius.md),
            child: InkWell(
              onTap: () => onSelected(day.date),
              borderRadius: BorderRadius.circular(AppRadius.md),
              child: Container(
                width: 58,
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(AppRadius.md),
                  border: Border.all(
                    color:
                        selected ? AppColors.emerald : AppColors.surfaceBorder,
                  ),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      isToday ? 'Today' : _shortWeekday(date),
                      style: TextStyle(
                        color:
                            selected ? AppColors.emerald : AppColors.textMuted,
                        fontSize: 10,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      date?.day.toString() ?? '--',
                      style: TextStyle(
                        color: AppColors.textPrimary,
                        fontSize: 16,
                        fontWeight:
                            selected ? FontWeight.w900 : FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.period,
    required this.selectedDate,
    required this.summary,
    required this.comparison,
  });

  final _AnalyticsPeriod period;
  final String selectedDate;
  final _AnalyticsSummary summary;
  final _AnalyticsSummary comparison;

  @override
  Widget build(BuildContext context) {
    final completion = summary.completionRate;
    final delta = _focusDelta(summary.focusMs, comparison.focusMs);
    final periodLabel = switch (period) {
      _AnalyticsPeriod.daily => _friendlyDate(selectedDate),
      _AnalyticsPeriod.weekly => 'Last 7 days',
      _AnalyticsPeriod.monthly => 'Last 30 days',
    };

    return SolidCard(
      padding: const EdgeInsets.all(16),
      radius: AppRadius.md,
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(periodLabel,
                        style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(height: 7),
                    Text(
                      _formatDuration(summary.focusMs),
                      style: const TextStyle(
                        color: AppColors.textPrimary,
                        fontSize: 32,
                        height: 1,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -1,
                      ),
                    ),
                    const SizedBox(height: 7),
                    Row(
                      children: [
                        const Text(
                          'Focused time',
                          style: TextStyle(
                            color: AppColors.textSecondary,
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        if (delta != null) ...[
                          const SizedBox(width: 8),
                          _DeltaBadge(delta: delta),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: 82,
                height: 82,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    SizedBox.expand(
                      child: CircularProgressIndicator(
                        value: completion,
                        strokeWidth: 8,
                        backgroundColor: AppColors.surfaceRaised,
                        color: AppColors.lime,
                        strokeCap: StrokeCap.round,
                      ),
                    ),
                    Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          summary.studyStarts == 0
                              ? '--'
                              : '${(completion * 100).round()}%',
                          style: const TextStyle(
                            color: AppColors.textPrimary,
                            fontSize: 17,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        const Text(
                          'finished',
                          style: TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 9.5,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          const Divider(height: 28),
          Row(
            children: [
              Expanded(
                child: _MiniMetric(
                  icon: Icons.flag_outlined,
                  value: '${summary.studyCompleted}/${summary.studyStarts}',
                  label: 'Sessions',
                  color: AppColors.lime,
                ),
              ),
              Expanded(
                child: _MiniMetric(
                  icon: Icons.block_outlined,
                  value: '${summary.blockedAttempts}',
                  label: 'Attempts',
                  color: AppColors.warning,
                ),
              ),
              Expanded(
                child: _MiniMetric(
                  icon: Icons.undo_rounded,
                  value: '${summary.handledCount}',
                  label: 'Returned',
                  color: AppColors.emerald,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _MiniMetric extends StatelessWidget {
  const _MiniMetric({
    required this.icon,
    required this.value,
    required this.label,
    required this.color,
  });

  final IconData icon;
  final String value;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(height: 5),
        Text(
          value,
          style: const TextStyle(
            color: AppColors.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w900,
          ),
        ),
        const SizedBox(height: 1),
        Text(
          label,
          style: const TextStyle(color: AppColors.textMuted, fontSize: 10),
        ),
      ],
    );
  }
}

class _DeltaBadge extends StatelessWidget {
  const _DeltaBadge({required this.delta});

  final double delta;

  @override
  Widget build(BuildContext context) {
    final positive = delta >= 0;
    final color = positive ? AppColors.emerald : AppColors.warning;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(AppRadius.pill),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            positive ? Icons.trending_up_rounded : Icons.trending_down_rounded,
            color: color,
            size: 13,
          ),
          const SizedBox(width: 3),
          Text(
            '${delta.abs().round()}%',
            style: TextStyle(
              color: color,
              fontSize: 10.5,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
      ),
    );
  }
}

class _MotivationCard extends StatelessWidget {
  const _MotivationCard({
    required this.period,
    required this.summary,
    required this.comparison,
  });

  final _AnalyticsPeriod period;
  final _AnalyticsSummary summary;
  final _AnalyticsSummary comparison;

  @override
  Widget build(BuildContext context) {
    final insight = _Insight.forData(period, summary, comparison);
    return SolidCard(
      padding: const EdgeInsets.all(15),
      radius: AppRadius.md,
      color: AppColors.surface,
      borderColor: AppColors.surfaceBorder,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  color: insight.color.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(AppRadius.md),
                ),
                child: Icon(insight.icon, color: insight.color, size: 24),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      insight.badge.toUpperCase(),
                      style: TextStyle(
                        color: insight.color,
                        fontSize: 9.5,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      insight.title,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      insight.detail,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 13),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
            decoration: BoxDecoration(
              color: AppColors.night.withValues(alpha: 0.34),
              borderRadius: BorderRadius.circular(AppRadius.sm),
              border: Border.all(color: AppColors.surfaceBorder),
            ),
            child: Row(
              children: [
                Icon(Icons.lightbulb_outline_rounded,
                    color: insight.color, size: 17),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    insight.tip,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _FocusTrendCard extends StatelessWidget {
  const _FocusTrendCard({
    required this.days,
    required this.highlightedDate,
    required this.title,
  });

  final List<SelfControlDayMetrics> days;
  final String highlightedDate;
  final String title;

  @override
  Widget build(BuildContext context) {
    final total = days.fold<int>(0, (sum, day) => sum + day.studyDurationMs);
    final average = days.isEmpty ? 0 : total ~/ days.length;
    return SolidCard(
      padding: const EdgeInsets.all(15),
      radius: AppRadius.md,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 2),
                    Text(
                      '${_formatDuration(average)} average per day',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              const Icon(Icons.bar_chart_rounded,
                  color: AppColors.emerald, size: 22),
            ],
          ),
          const SizedBox(height: 18),
          _FocusBarChart(days: days, highlightedDate: highlightedDate),
        ],
      ),
    );
  }
}

class _FocusBarChart extends StatelessWidget {
  const _FocusBarChart({required this.days, required this.highlightedDate});

  final List<SelfControlDayMetrics> days;
  final String highlightedDate;

  @override
  Widget build(BuildContext context) {
    if (days.isEmpty) return const SizedBox(height: 110);
    final maxValue = days.fold<int>(
      1,
      (max, day) => day.studyDurationMs > max ? day.studyDurationMs : max,
    );
    final dense = days.length > 10;
    return SizedBox(
      height: 132,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var index = 0; index < days.length; index++) ...[
            Expanded(
              child: Tooltip(
                message:
                    '${_friendlyDate(days[index].date)}: ${_formatDuration(days[index].studyDurationMs)}',
                child: Column(
                  children: [
                    Expanded(
                      child: Align(
                        alignment: Alignment.bottomCenter,
                        child: FractionallySizedBox(
                          heightFactor: days[index].studyDurationMs == 0
                              ? 0.035
                              : days[index].studyDurationMs / maxValue,
                          widthFactor: dense ? 0.64 : 0.52,
                          child: Container(
                            decoration: BoxDecoration(
                              color: days[index].date == highlightedDate
                                  ? AppColors.lime
                                  : days[index].studyDurationMs == 0
                                      ? AppColors.surfaceRaised
                                      : AppColors.emerald.withValues(
                                          alpha: 0.42 +
                                              (0.48 *
                                                  days[index].studyDurationMs /
                                                  maxValue),
                                        ),
                              borderRadius: const BorderRadius.vertical(
                                top: Radius.circular(5),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 7),
                    SizedBox(
                      height: 15,
                      child: Text(
                        _chartLabel(days[index], index, days.length),
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 8.5,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (index != days.length - 1) SizedBox(width: dense ? 2 : 5),
          ],
        ],
      ),
    );
  }
}

class _AppAttemptsCard extends StatelessWidget {
  const _AppAttemptsCard({required this.summary});

  final _AnalyticsSummary summary;

  @override
  Widget build(BuildContext context) {
    final apps = summary.blockedApps.take(5).toList();
    final maxAttempts =
        apps.isEmpty || apps.first.attempts < 1 ? 1 : apps.first.attempts;
    final outcomeTotal = summary.handledCount + summary.exitSuccessCount;
    final returnedFraction =
        outcomeTotal == 0 ? 0.0 : summary.handledCount / outcomeTotal;

    return SolidCard(
      padding: const EdgeInsets.all(15),
      radius: AppRadius.md,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Distraction attempts',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 2),
                    Text(
                      'Apps that pulled your attention',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
                decoration: BoxDecoration(
                  color: AppColors.warning.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(AppRadius.pill),
                ),
                child: Text(
                  '${summary.blockedAttempts} total',
                  style: const TextStyle(
                    color: AppColors.warning,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 15),
          if (apps.isEmpty)
            const _CleanState(
              icon: Icons.shield_outlined,
              title: 'No blocked-app attempts',
              subtitle: 'Your selected period stayed distraction-free.',
            )
          else
            for (var index = 0; index < apps.length; index++) ...[
              _AttemptRow(
                app: apps[index],
                maxAttempts: maxAttempts,
              ),
              if (index != apps.length - 1) const SizedBox(height: 13),
            ],
          const Divider(height: 28),
          Text('What happened after a block',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          if (outcomeTotal == 0)
            const Text(
              'Outcome detail will appear after you respond to a blocked app.',
              style: TextStyle(color: AppColors.textMuted, fontSize: 11.5),
            )
          else ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.pill),
              child: SizedBox(
                height: 9,
                child: Row(
                  children: [
                    if (summary.handledCount > 0)
                      Expanded(
                        flex: summary.handledCount,
                        child: Container(color: AppColors.emerald),
                      ),
                    if (summary.exitSuccessCount > 0)
                      Expanded(
                        flex: summary.exitSuccessCount,
                        child: Container(color: AppColors.warning),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: _OutcomeLegend(
                    color: AppColors.emerald,
                    value: '${summary.handledCount}',
                    label: 'Returned to focus',
                  ),
                ),
                Expanded(
                  child: _OutcomeLegend(
                    color: AppColors.warning,
                    value: '${summary.exitSuccessCount}',
                    label: 'Ended focus',
                  ),
                ),
              ],
            ),
            if (outcomeTotal > 0) ...[
              const SizedBox(height: 9),
              Text(
                '${(returnedFraction * 100).round()}% of recorded decisions returned to focus.',
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ],
        ],
      ),
    );
  }
}

class _AttemptRow extends StatelessWidget {
  const _AttemptRow({required this.app, required this.maxAttempts});

  final _AppSummary app;
  final int maxAttempts;

  @override
  Widget build(BuildContext context) {
    final fraction = app.attempts / maxAttempts;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AppIconTile(
          appName: app.appName,
          packageName: app.packageName,
          size: 42,
          radius: 12,
        ),
        const SizedBox(width: 11),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      app.appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                  Text(
                    '${app.attempts} ${app.attempts == 1 ? 'attempt' : 'attempts'}',
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 10.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 7),
              ClipRRect(
                borderRadius: BorderRadius.circular(AppRadius.pill),
                child: LinearProgressIndicator(
                  value: fraction,
                  minHeight: 6,
                  backgroundColor: AppColors.surfaceRaised,
                  color: AppColors.warning,
                ),
              ),
              if (app.returnedCount > 0 || app.gaveUpCount > 0) ...[
                const SizedBox(height: 7),
                Wrap(
                  spacing: 6,
                  children: [
                    if (app.returnedCount > 0)
                      _TinyOutcome(
                        icon: Icons.undo_rounded,
                        label: '${app.returnedCount} returned',
                        color: AppColors.emerald,
                      ),
                    if (app.gaveUpCount > 0)
                      _TinyOutcome(
                        icon: Icons.flag_outlined,
                        label: '${app.gaveUpCount} ended',
                        color: AppColors.warning,
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _TinyOutcome extends StatelessWidget {
  const _TinyOutcome({
    required this.icon,
    required this.label,
    required this.color,
  });

  final IconData icon;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(AppRadius.pill),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: color, size: 11),
          const SizedBox(width: 3),
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 9.5,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _OutcomeLegend extends StatelessWidget {
  const _OutcomeLegend({
    required this.color,
    required this.value,
    required this.label,
  });

  final Color color;
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Flexible(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                value,
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w900,
                ),
              ),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 9.5,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _FocusAppsCard extends StatelessWidget {
  const _FocusAppsCard({required this.apps});

  final List<_AppSummary> apps;

  @override
  Widget build(BuildContext context) {
    final visible = apps.take(4).toList();
    final maxDuration =
        visible.first.durationMs < 1 ? 1 : visible.first.durationMs;
    return SolidCard(
      padding: const EdgeInsets.all(15),
      radius: AppRadius.md,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Where focus happened',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 2),
          Text('Focused time by study app',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 15),
          for (var index = 0; index < visible.length; index++) ...[
            _FocusAppRow(
              app: visible[index],
              maxDuration: maxDuration,
            ),
            if (index != visible.length - 1) const SizedBox(height: 12),
          ],
        ],
      ),
    );
  }
}

class _FocusAppRow extends StatelessWidget {
  const _FocusAppRow({required this.app, required this.maxDuration});

  final _AppSummary app;
  final int maxDuration;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        AppIconTile(
          appName: app.appName,
          packageName: app.packageName,
          size: 38,
          radius: 11,
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      app.appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                  Text(
                    _formatDuration(app.durationMs),
                    style: const TextStyle(
                      color: AppColors.emerald,
                      fontSize: 10.5,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              ClipRRect(
                borderRadius: BorderRadius.circular(AppRadius.pill),
                child: LinearProgressIndicator(
                  value: app.durationMs / maxDuration,
                  minHeight: 6,
                  backgroundColor: AppColors.surfaceRaised,
                  color: AppColors.emerald,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _BreakControlCard extends StatelessWidget {
  const _BreakControlCard({required this.summary});

  final _AnalyticsSummary summary;

  @override
  Widget build(BuildContext context) {
    final useRate = summary.breakAllottedMs == 0
        ? 0.0
        : (summary.breakActualMs / summary.breakAllottedMs)
            .clamp(0.0, 1.0)
            .toDouble();
    return SolidCard(
      padding: const EdgeInsets.all(15),
      radius: AppRadius.md,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: AppColors.lime.withValues(alpha: 0.11),
                  borderRadius: BorderRadius.circular(AppRadius.md),
                ),
                child: const Icon(Icons.free_breakfast_outlined,
                    color: AppColors.lime, size: 20),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Break control',
                        style: Theme.of(context).textTheme.titleMedium),
                    Text(
                      '${summary.breakCount} ${summary.breakCount == 1 ? 'break' : 'breaks'} taken',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              Text(
                '${(useRate * 100).round()}% used',
                style: const TextStyle(
                  color: AppColors.lime,
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
          const SizedBox(height: 13),
          ClipRRect(
            borderRadius: BorderRadius.circular(AppRadius.pill),
            child: LinearProgressIndicator(
              value: useRate,
              minHeight: 8,
              backgroundColor: AppColors.surfaceRaised,
              color: AppColors.lime,
            ),
          ),
          const SizedBox(height: 9),
          Row(
            children: [
              Expanded(
                child: Text(
                  '${_formatDuration(summary.breakActualMs)} actually used',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              Text(
                '${_formatDuration(summary.breakUnusedMs)} returned early',
                style: const TextStyle(
                  color: AppColors.emerald,
                  fontSize: 10.5,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _CleanState extends StatelessWidget {
  const _CleanState({
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: AppColors.emerald.withValues(alpha: 0.07),
        borderRadius: BorderRadius.circular(AppRadius.sm),
      ),
      child: Row(
        children: [
          Icon(icon, color: AppColors.emerald, size: 22),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 2),
                Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _EmptyReport extends StatelessWidget {
  const _EmptyReport();

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 30),
      radius: AppRadius.md,
      child: Column(
        children: [
          Container(
            width: 58,
            height: 58,
            decoration: BoxDecoration(
              color: AppColors.emerald.withValues(alpha: 0.13),
              borderRadius: BorderRadius.circular(AppRadius.md),
            ),
            child: const Icon(
              Icons.insights_outlined,
              color: AppColors.emerald,
              size: 30,
            ),
          ),
          const SizedBox(height: 15),
          Text('Your story starts here',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 6),
          Text(
            'Complete one focus session and this screen will show your rhythm, distractions and wins.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _AnalyticsSummary {
  const _AnalyticsSummary({
    required this.focusMs,
    required this.studyStarts,
    required this.studyCompleted,
    required this.blockedAttempts,
    required this.handledCount,
    required this.exitSuccessCount,
    required this.breakCount,
    required this.breakAllottedMs,
    required this.breakActualMs,
    required this.breakUnusedMs,
    required this.activeDays,
    required this.blockedApps,
    required this.focusApps,
    required this.breakApps,
  });

  final int focusMs;
  final int studyStarts;
  final int studyCompleted;
  final int blockedAttempts;
  final int handledCount;
  final int exitSuccessCount;
  final int breakCount;
  final int breakAllottedMs;
  final int breakActualMs;
  final int breakUnusedMs;
  final int activeDays;
  final List<_AppSummary> blockedApps;
  final List<_AppSummary> focusApps;
  final List<_AppSummary> breakApps;

  double get completionRate => studyStarts == 0
      ? 0
      : (studyCompleted / studyStarts).clamp(0.0, 1.0).toDouble();

  bool get hasActivity =>
      focusMs > 0 || studyStarts > 0 || blockedAttempts > 0 || breakCount > 0;

  factory _AnalyticsSummary.fromDays(List<SelfControlDayMetrics> days) {
    var focusMs = 0;
    var studyStarts = 0;
    var studyCompleted = 0;
    var blockedAttempts = 0;
    var handledCount = 0;
    var exitSuccessCount = 0;
    var breakCount = 0;
    var breakAllottedMs = 0;
    var breakActualMs = 0;
    var breakUnusedMs = 0;
    var activeDays = 0;
    final blockedApps = <String, _AppSummary>{};
    final focusApps = <String, _AppSummary>{};
    final breakApps = <String, _AppSummary>{};

    for (final day in days) {
      focusMs += day.studyDurationMs;
      studyStarts += day.studyStarts;
      studyCompleted += day.studyCompletedCount;
      blockedAttempts += day.blockedAttempts;
      handledCount += day.handledCount;
      exitSuccessCount += day.exitSuccessCount;
      breakCount += day.breakCount;
      breakAllottedMs += day.breakDurationMs;
      breakActualMs += day.breakActualDurationMs;
      breakUnusedMs += day.breakUnusedReturnedMs;
      if (day.studyDurationMs > 0 || day.studyCompletedCount > 0) activeDays++;
      _mergeApps(blockedApps, day.blockedApps);
      _mergeApps(focusApps, day.focusApps);
      _mergeApps(breakApps, day.breakApps);
    }

    final blocked = blockedApps.values.toList()
      ..sort((a, b) => b.attempts.compareTo(a.attempts));
    final focused = focusApps.values.toList()
      ..sort((a, b) => b.durationMs.compareTo(a.durationMs));
    final breaks = breakApps.values.toList()
      ..sort((a, b) => b.actualMs.compareTo(a.actualMs));

    return _AnalyticsSummary(
      focusMs: focusMs,
      studyStarts: studyStarts,
      studyCompleted: studyCompleted,
      blockedAttempts: blockedAttempts,
      handledCount: handledCount,
      exitSuccessCount: exitSuccessCount,
      breakCount: breakCount,
      breakAllottedMs: breakAllottedMs,
      breakActualMs: breakActualMs,
      breakUnusedMs: breakUnusedMs,
      activeDays: activeDays,
      blockedApps: blocked,
      focusApps: focused,
      breakApps: breaks,
    );
  }

  static void _mergeApps(
    Map<String, _AppSummary> target,
    List<AnalyticsAppMetric> source,
  ) {
    for (final item in source) {
      final existing = target.putIfAbsent(
        item.packageName,
        () => _AppSummary(
          packageName: item.packageName,
          appName: _safeAppName(item.appName, item.packageName),
        ),
      );
      existing.add(item);
    }
  }
}

class _AppSummary {
  _AppSummary({required this.packageName, required this.appName});

  final String packageName;
  final String appName;
  int durationMs = 0;
  int attempts = 0;
  int breakCount = 0;
  int allottedMs = 0;
  int actualMs = 0;
  int returnedCount = 0;
  int gaveUpCount = 0;

  void add(AnalyticsAppMetric metric) {
    durationMs += metric.durationMs;
    attempts += metric.attempts;
    breakCount += metric.breakCount;
    allottedMs += metric.allottedMs;
    actualMs += metric.actualMs;
    returnedCount += metric.returnedCount;
    gaveUpCount += metric.gaveUpCount;
  }
}

class _Insight {
  const _Insight({
    required this.badge,
    required this.title,
    required this.detail,
    required this.tip,
    required this.icon,
    required this.color,
  });

  final String badge;
  final String title;
  final String detail;
  final String tip;
  final IconData icon;
  final Color color;

  factory _Insight.forData(
    _AnalyticsPeriod period,
    _AnalyticsSummary summary,
    _AnalyticsSummary comparison,
  ) {
    final delta = _focusDelta(summary.focusMs, comparison.focusMs);
    final topBlocked =
        summary.blockedApps.isEmpty ? null : summary.blockedApps.first;

    if (!summary.hasActivity) {
      return const _Insight(
        badge: 'Next move',
        title: 'One focused block is enough to begin',
        detail: 'There is no activity in this selected period yet.',
        tip: 'Start with a realistic 25-minute session.',
        icon: Icons.play_arrow_rounded,
        color: AppColors.lime,
      );
    }
    if (delta != null && delta >= 10) {
      return _Insight(
        badge: 'Momentum',
        title: 'Your focus time is climbing',
        detail:
            'You focused ${delta.round()}% more than the comparison period.',
        tip:
            'Repeat the same session length while this rhythm feels sustainable.',
        icon: Icons.trending_up_rounded,
        color: AppColors.emerald,
      );
    }
    if (summary.studyStarts > 0 && summary.completionRate >= 0.75) {
      return _Insight(
        badge: 'Strong finish',
        title: 'You are finishing what you start',
        detail:
            '${summary.studyCompleted} of ${summary.studyStarts} sessions reached the finish line.',
        tip: 'Keep the next session close to the duration that worked today.',
        icon: Icons.workspace_premium_outlined,
        color: AppColors.lime,
      );
    }
    if (summary.handledCount > summary.exitSuccessCount &&
        summary.handledCount > 0) {
      return _Insight(
        badge: 'Self-control win',
        title: 'You kept choosing focus',
        detail:
            '${summary.handledCount} blocked moments ended with you returning to focus.',
        tip:
            'That return is the habit to protect—not a perfect zero-attempt day.',
        icon: Icons.shield_outlined,
        color: AppColors.emerald,
      );
    }
    if (topBlocked != null && topBlocked.attempts > 0) {
      return _Insight(
        badge: 'Pattern found',
        title: '${topBlocked.appName} pulled you most',
        detail:
            'It was opened ${topBlocked.attempts} ${topBlocked.attempts == 1 ? 'time' : 'times'} in this period.',
        tip:
            'Keep it blocked and place its shortcut away from your first screen.',
        icon: Icons.travel_explore_rounded,
        color: AppColors.warning,
      );
    }
    return const _Insight(
      badge: 'Keep building',
      title: 'Your focus pattern is taking shape',
      detail: 'Every completed session makes the next decision easier to see.',
      tip: 'Use the same start time again to build a repeatable cue.',
      icon: Icons.auto_graph_rounded,
      color: AppColors.emerald,
    );
  }
}

double? _focusDelta(int current, int previous) {
  if (previous <= 0) return null;
  return ((current - previous) / previous) * 100;
}

String _safeAppName(String appName, String packageName) {
  final cleaned = appName.trim();
  if (cleaned.isEmpty || cleaned == packageName) return 'Installed app';
  if (!cleaned.contains(' ') && cleaned.split('.').length >= 3) {
    return 'Installed app';
  }
  return cleaned;
}

String _formatDuration(int milliseconds) {
  final minutes = milliseconds ~/ 60000;
  if (minutes < 60) return '${minutes}m';
  final hours = minutes ~/ 60;
  final remainder = minutes % 60;
  return remainder == 0 ? '${hours}h' : '${hours}h ${remainder}m';
}

String _friendlyDate(String raw) {
  final date = DateTime.tryParse(raw);
  if (date == null) return 'Selected day';
  if (raw == _todayKey()) return 'Today';
  return '${_shortWeekday(date)}, ${_shortMonth(date)} ${date.day}';
}

String _chartLabel(SelfControlDayMetrics day, int index, int length) {
  final date = DateTime.tryParse(day.date);
  if (date == null) return '';
  if (length <= 10) return _shortWeekday(date).substring(0, 1);
  if (index == 0 || index == length - 1 || index % 6 == 0) {
    return '${date.day}';
  }
  return '';
}

String _shortWeekday(DateTime? date) {
  if (date == null) return '--';
  const values = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return values[date.weekday - 1];
}

String _shortMonth(DateTime date) {
  const values = [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  return values[date.month - 1];
}

String _todayKey() {
  final now = DateTime.now();
  final month = now.month.toString().padLeft(2, '0');
  final day = now.day.toString().padLeft(2, '0');
  return '${now.year}-$month-$day';
}
