import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../widgets/glass_card.dart';
import '../widgets/primary_button.dart';

class StrictPreparationScreen extends StatefulWidget {
  const StrictPreparationScreen({
    super.key,
    this.lockedStrict = false,
  });

  final bool lockedStrict;

  @override
  State<StrictPreparationScreen> createState() =>
      _StrictPreparationScreenState();
}

class _StrictPreparationScreenState extends State<StrictPreparationScreen> {
  static const _preparationSeconds = 60;

  Timer? _timer;
  int _remaining = _preparationSeconds;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      if (_remaining <= 1) {
        timer.cancel();
        setState(() => _remaining = 0);
      } else {
        setState(() => _remaining -= 1);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _remaining == 0;
    return Scaffold(
      backgroundColor: AppColors.page,
      body: ColoredBox(
        color: AppColors.page,
        child: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) => SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: IntrinsicHeight(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
                    child: Column(
                      children: [
                        Align(
                          alignment: Alignment.centerLeft,
                          child: IconButton(
                            tooltip: 'Cancel',
                            onPressed: () => Navigator.of(context).pop(false),
                            icon: const Icon(Icons.close_rounded),
                          ),
                        ),
                        const Spacer(),
                        Icon(
                          ready
                              ? Icons.verified_user_outlined
                              : Icons.lock_clock,
                          color: ready ? AppColors.emerald : AppColors.warning,
                          size: 58,
                        ),
                        const SizedBox(height: 22),
                        Text(
                          ready
                              ? 'Ready to start'
                              : widget.lockedStrict
                                  ? 'Locked Strict preparation'
                                  : 'Strict Focus preparation',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 10),
                        Text(
                          ready
                              ? widget.lockedStrict
                                  ? 'Once started, there is no emergency exit. Timed breaks and safety apps remain available; Android DND stays active.'
                                  : 'Once started, Strict Focus uses the protected emergency-exit flow and keeps Android DND active.'
                              : 'Check the duration and selected apps now. '
                                  'You can wait, skip this countdown, or cancel before Focus begins.',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 28),
                        SolidCard(
                          child: Column(
                            children: [
                              Text(
                                ready
                                    ? '00'
                                    : _remaining.toString().padLeft(2, '0'),
                                style: const TextStyle(
                                  color: AppColors.textPrimary,
                                  fontSize: 52,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                              Text(
                                ready
                                    ? 'preparation complete'
                                    : 'seconds remaining',
                                style: const TextStyle(
                                  color: AppColors.textMuted,
                                ),
                              ),
                              const SizedBox(height: 14),
                              ClipRRect(
                                borderRadius: BorderRadius.circular(6),
                                child: LinearProgressIndicator(
                                  value: ready
                                      ? 1
                                      : 1 - (_remaining / _preparationSeconds),
                                  minHeight: 6,
                                  backgroundColor: AppColors.textPrimary
                                      .withValues(alpha: 0.08),
                                  valueColor: AlwaysStoppedAnimation(
                                    widget.lockedStrict
                                        ? AppColors.warning
                                        : AppColors.emerald,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const Spacer(),
                        PrimaryButton(
                          label: ready
                              ? widget.lockedStrict
                                  ? 'Start Locked Strict'
                                  : 'Start Strict Focus'
                              : 'Preparing...',
                          icon: widget.lockedStrict
                              ? Icons.lock_rounded
                              : Icons.lock_rounded,
                          onPressed: ready
                              ? () => Navigator.of(context).pop(true)
                              : null,
                        ),
                        if (!ready) ...[
                          const SizedBox(height: 10),
                          GhostButton(
                            label: 'Skip wait & start now',
                            icon: Icons.fast_forward_rounded,
                            onPressed: () => Navigator.of(context).pop(true),
                          ),
                        ],
                        const SizedBox(height: 10),
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(false),
                          child: const Text('Cancel'),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
