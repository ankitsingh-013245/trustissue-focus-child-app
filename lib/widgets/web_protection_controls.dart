import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class WebProtectionInlineControls extends StatelessWidget {
  const WebProtectionInlineControls({
    super.key,
    required this.customDomains,
    required this.onAddDomain,
    required this.onShowInfo,
  });

  final Set<String> customDomains;
  final VoidCallback onAddDomain;
  final VoidCallback onShowInfo;

  @override
  Widget build(BuildContext context) {
    final domains = customDomains.toList()..sort();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              width: 32,
              height: 32,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.surfaceRaised,
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(
                Icons.language_rounded,
                color: AppColors.emerald,
                size: 18,
              ),
            ),
            const SizedBox(width: 9),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Focus website rules',
                    style: TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  Text(
                    domains.isEmpty
                        ? 'Focus only · no custom domains'
                        : 'Focus only · ${domains.length} custom domain${domains.length == 1 ? '' : 's'}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.emerald,
                      fontSize: 10.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: 'How Web Protection works',
              visualDensity: VisualDensity.compact,
              constraints: const BoxConstraints.tightFor(width: 34, height: 34),
              onPressed: onShowInfo,
              icon: const Icon(
                Icons.info_outline_rounded,
                color: AppColors.textSecondary,
                size: 19,
              ),
            ),
            IconButton(
              tooltip: domains.isEmpty ? 'Add a domain' : 'Manage domains',
              visualDensity: VisualDensity.compact,
              constraints: const BoxConstraints.tightFor(width: 34, height: 34),
              style: IconButton.styleFrom(
                backgroundColor: AppColors.surfaceRaised,
                foregroundColor: AppColors.lime,
              ),
              onPressed: onAddDomain,
              icon: Icon(
                domains.isEmpty ? Icons.add_rounded : Icons.edit_outlined,
                size: 20,
              ),
            ),
          ],
        ),
        if (domains.isNotEmpty) ...[
          const SizedBox(height: 8),
          InkWell(
            borderRadius: BorderRadius.circular(AppRadius.sm),
            onTap: onAddDomain,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 3),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.language_rounded,
                    color: AppColors.textMuted,
                    size: 16,
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      domains.join('  ·  '),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 11.5,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}

Future<void> showWebProtectionInfoDialog(BuildContext context) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black.withValues(alpha: 0.68),
    builder: (dialogContext) => Dialog(
      backgroundColor: AppColors.surface,
      surfaceTintColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 28),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: const BorderSide(color: AppColors.surfaceBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 14, 12, 14),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Web Protection',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                IconButton(
                  tooltip: 'Close',
                  visualDensity: VisualDensity.compact,
                  onPressed: () => Navigator.of(dialogContext).pop(),
                  icon: const Icon(Icons.close_rounded, size: 19),
                ),
              ],
            ),
            const Padding(
              padding: EdgeInsets.only(right: 6, bottom: 6),
              child: Text(
                'Global protection blocks adult and custom domains whenever it '
                'is enabled. During Focus, allowed browsers also receive the '
                'active blocked-app and Focus custom-domain rules.',
                style: TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 13,
                  height: 1.45,
                ),
              ),
            ),
            const Padding(
              padding: EdgeInsets.only(right: 6),
              child: Text(
                'It does not read page content, passwords, searches or browser '
                'history. Browser DNS and common encrypted-DNS resolver '
                'endpoints are kept inside the protection path. Android allows '
                'one VPN at a time, so TrustIssue asks before replacing an '
                'existing VPN.',
                style: TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12.5,
                  height: 1.4,
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
