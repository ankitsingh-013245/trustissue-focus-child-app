import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Full-screen aurora photo background with a darkening scrim so foreground
/// content stays readable. Used as the base layer on almost every screen.
class AuroraBackground extends StatelessWidget {
  const AuroraBackground({
    super.key,
    required this.child,
    this.alignment = Alignment.topCenter,
    this.dim = 1.0,
  });

  final Widget child;
  final Alignment alignment;

  /// Extra darkening multiplier (1 = default scrim, >1 = darker).
  final double dim;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(color: AppColors.night),
      child: Stack(
        fit: StackFit.expand,
        children: [
          Image.asset(
            'assets/images/aurora_bg.png',
            fit: BoxFit.cover,
            alignment: alignment,
          ),
          const DecoratedBox(
            decoration: BoxDecoration(gradient: AppTheme.scrim),
          ),
          if (dim > 1.0)
            Opacity(
              opacity: (dim - 1.0).clamp(0.0, 1.0),
              child: const ColoredBox(color: AppColors.nightDeep),
            ),
          child,
        ],
      ),
    );
  }
}

/// A soft radial glow, e.g. behind the focus timer or a shield badge.
class AuroraGlow extends StatelessWidget {
  const AuroraGlow({
    super.key,
    this.color = AppColors.emerald,
    this.size = 240,
    this.opacity = 0.35,
  });

  final Color color;
  final double size;
  final double opacity;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [
              color.withValues(alpha: opacity),
              color.withValues(alpha: 0),
            ],
          ),
        ),
      ),
    );
  }
}
