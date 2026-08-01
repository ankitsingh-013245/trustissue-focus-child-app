import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../widgets/aurora_background.dart';
import '../widgets/glass_card.dart';

/// Full-screen "Emergency Exit" confirmation shown when the user wants to stop
/// a running focus session. Returns the typed reason, or null if cancelled.
class EmergencyExitScreen extends StatefulWidget {
  const EmergencyExitScreen(
      {super.key, this.minWords = 10, this.countdown = 60});

  final int minWords;
  final int countdown;

  @override
  State<EmergencyExitScreen> createState() => _EmergencyExitScreenState();
}

class _EmergencyExitScreenState extends State<EmergencyExitScreen> {
  final _controller = TextEditingController();
  Timer? _timer;
  late int _secondsLeft;

  @override
  void initState() {
    super.initState();
    _secondsLeft = widget.countdown;
    _controller.addListener(() => setState(() {}));
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_secondsLeft <= 1) {
        timer.cancel();
        setState(() => _secondsLeft = 0);
      } else {
        setState(() => _secondsLeft -= 1);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  int get _wordCount {
    final text = _controller.text.trim();
    if (text.isEmpty) return 0;
    return text.split(RegExp(r'\s+')).length;
  }

  bool get _canExit => _secondsLeft == 0 && _wordCount >= widget.minWords;

  @override
  Widget build(BuildContext context) {
    final progress = 1 - _secondsLeft / widget.countdown;
    return Scaffold(
      backgroundColor: AppColors.page,
      body: ColoredBox(
        color: AppColors.page,
        child: SafeArea(
          child: Column(
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
              ),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(24, 4, 24, 8),
                  children: [
                    Center(
                      child: Stack(
                        alignment: Alignment.center,
                        children: [
                          const AuroraGlow(
                              size: 130,
                              color: AppColors.warning,
                              opacity: 0.4),
                          Container(
                            width: 84,
                            height: 84,
                            decoration: BoxDecoration(
                              color: AppColors.warning.withValues(alpha: 0.16),
                              shape: BoxShape.circle,
                              border: Border.all(
                                  color:
                                      AppColors.warning.withValues(alpha: 0.5)),
                            ),
                            child: const Icon(Icons.directions_run_rounded,
                                color: AppColors.warning, size: 40),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 20),
                    Text(
                      'Emergency Exit',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Are you sure you want to exit your Focus Session?',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 22),
                    const _ReasonHint(
                      icon: Icons.help_outline_rounded,
                      title: 'Is it necessary?',
                      subtitle: 'Do you really need to exit?',
                    ),
                    const SizedBox(height: 12),
                    const _ReasonHint(
                      icon: Icons.local_fire_department_outlined,
                      title: 'Or just a temptation?',
                      subtitle: 'Think before you react.',
                    ),
                    const SizedBox(height: 22),
                    Text('Please tell us the reason',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 6),
                    Text(
                      'Write at least ${widget.minWords} words about why you '
                      'want to exit.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 12),
                    SolidCard(
                      padding: const EdgeInsets.all(14),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          TextField(
                            controller: _controller,
                            maxLines: 4,
                            style:
                                const TextStyle(color: AppColors.textPrimary),
                            decoration: const InputDecoration(
                              hintText: 'Type your reason here...',
                              hintStyle: TextStyle(color: AppColors.textMuted),
                              border: InputBorder.none,
                              isCollapsed: true,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '$_wordCount/${widget.minWords} words',
                            style: TextStyle(
                              color: _wordCount >= widget.minWords
                                  ? AppColors.emerald
                                  : AppColors.textMuted,
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 22),
                    Center(
                      child: Text(
                        _secondsLeft > 0
                            ? 'You can exit in'
                            : 'You can exit now',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    ),
                    const SizedBox(height: 8),
                    if (_secondsLeft > 0) ...[
                      Center(
                        child: Text(
                          '${_secondsLeft}s',
                          style: const TextStyle(
                            color: AppColors.emerald,
                            fontSize: 34,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: LinearProgressIndicator(
                          value: progress,
                          minHeight: 8,
                          backgroundColor:
                              AppColors.textPrimary.withValues(alpha: 0.1),
                          valueColor:
                              const AlwaysStoppedAnimation(AppColors.emerald),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
                child: _ExitButton(
                  enabled: _canExit,
                  secondsLeft: _secondsLeft,
                  onExit: () =>
                      Navigator.of(context).pop(_controller.text.trim()),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ReasonHint extends StatelessWidget {
  const _ReasonHint({
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: AppColors.emerald.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(11),
            ),
            child: Icon(icon, color: AppColors.emerald, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleSmall),
                Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ExitButton extends StatelessWidget {
  const _ExitButton({
    required this.enabled,
    required this.secondsLeft,
    required this.onExit,
  });

  final bool enabled;
  final int secondsLeft;
  final VoidCallback onExit;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: enabled ? 1 : 0.55,
      child: Material(
        color: enabled ? AppColors.danger : AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadius.pill),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppRadius.pill),
          onTap: enabled ? onExit : null,
          child: Container(
            height: 56,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppRadius.pill),
              border: Border.all(
                color: enabled ? Colors.transparent : AppColors.surfaceBorder,
              ),
            ),
            child: Text(
              secondsLeft > 0
                  ? 'Exit Now (Available in ${secondsLeft}s)'
                  : 'Exit Now',
              style: TextStyle(
                color: enabled ? Colors.white : AppColors.textMuted,
                fontSize: 15.5,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
