import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class PandaLoadingScreen extends StatefulWidget {
  const PandaLoadingScreen({
    super.key,
    this.message = 'Preparing focus space...',
    this.compact = false,
  });

  final String message;
  final bool compact;

  @override
  State<PandaLoadingScreen> createState() => _PandaLoadingScreenState();
}

class _PandaLoadingScreenState extends State<PandaLoadingScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2200),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final imageSize = widget.compact ? 132.0 : 176.0;

    return Center(
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          final t = _controller.value;
          final breathing = math.sin(t * math.pi * 2);
          final wake = Curves.easeOutCubic.transform((t * 1.7).clamp(0.0, 1.0));

          return Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Transform.translate(
                offset: Offset(0, 8 - wake * 8 + breathing * 2),
                child: Transform.scale(
                  scale: 0.96 + wake * 0.04 + breathing * 0.012,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Container(
                        width: imageSize + 28,
                        height: imageSize + 28,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          boxShadow: [
                            BoxShadow(
                              color: AppColors.emeraldGlow.withValues(
                                alpha: 0.18 + breathing.abs() * 0.08,
                              ),
                              blurRadius: 34,
                              spreadRadius: 1,
                            ),
                          ],
                        ),
                      ),
                      ClipOval(
                        child: Image.asset(
                          'assets/images/panda_loader.png',
                          width: imageSize,
                          height: imageSize,
                          fit: BoxFit.cover,
                        ),
                      ),
                      Positioned.fill(
                        child: IgnorePointer(
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: AppColors.nightDeep.withValues(
                                alpha: (0.36 * (1 - wake)).clamp(0.0, 0.36),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: widget.compact ? 18 : 24),
              Text(
                widget.message,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      color: AppColors.textSecondary,
                      fontWeight: FontWeight.w800,
                    ),
              ),
              const SizedBox(height: 12),
              _LoadingDots(progress: t),
            ],
          );
        },
      ),
    );
  }
}

class _LoadingDots extends StatelessWidget {
  const _LoadingDots({required this.progress});

  final double progress;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (index) {
        final phase = (progress + index * 0.16) % 1.0;
        final lift = math.sin(phase * math.pi * 2).clamp(0.0, 1.0);
        return AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          margin: const EdgeInsets.symmetric(horizontal: 4),
          width: 7,
          height: 7 + lift * 5,
          decoration: BoxDecoration(
            color: AppColors.lime.withValues(alpha: 0.45 + lift * 0.45),
            borderRadius: BorderRadius.circular(999),
          ),
        );
      }),
    );
  }
}
