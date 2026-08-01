import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// A consistent marker for features that need Android Accessibility access.
/// Core Focus features deliberately do not show this badge.
class AccessibilityRequirementBadge extends StatelessWidget {
  const AccessibilityRequirementBadge({
    super.key,
    this.showLabel = false,
  });

  final bool showLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Requires optional Accessibility access',
      child: Tooltip(
        message: 'Requires Accessibility',
        child: Container(
          padding: EdgeInsets.symmetric(
            horizontal: showLabel ? 7 : 5,
            vertical: 4,
          ),
          decoration: BoxDecoration(
            color: AppColors.emerald.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(AppRadius.pill),
            border: Border.all(
              color: AppColors.emerald.withValues(alpha: 0.35),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.accessibility_new_rounded,
                size: 14,
                color: AppColors.emerald,
              ),
              if (showLabel) ...[
                const SizedBox(width: 4),
                const Text(
                  'Accessibility',
                  style: TextStyle(
                    color: AppColors.emerald,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
