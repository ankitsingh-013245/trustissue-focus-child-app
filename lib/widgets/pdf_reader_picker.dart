import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../theme/app_theme.dart';
import 'app_icon_tile.dart';
import 'primary_button.dart';

const noPdfReaderChoice = '__no_pdf_reader__';

Future<String?> showPdfReaderPicker({
  required BuildContext context,
  required List<InstalledAppInfo> readers,
  required String currentPackage,
  bool dismissible = true,
}) {
  final hasCurrent = readers.any(
    (reader) => reader.packageName == currentPackage,
  );
  var selectedPackage = hasCurrent ? currentPackage : noPdfReaderChoice;

  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    isDismissible: dismissible,
    enableDrag: dismissible,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (sheetContext) => StatefulBuilder(
      builder: (context, setSheetState) => Container(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.82,
        ),
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
        decoration: const BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(AppRadius.lg),
          ),
          border: Border(
            top: BorderSide(color: AppColors.surfaceBorder),
          ),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.textMuted,
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text(
              'Choose your PDF reader',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 6),
            const Text(
              'Used only when a study app opens a PDF. You can change this later in Settings.',
              style: TextStyle(
                color: AppColors.textSecondary,
                fontSize: 13,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 16),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final reader in readers) ...[
                    _PdfReaderOption(
                      reader: reader,
                      selected: selectedPackage == reader.packageName,
                      onTap: () => setSheetState(
                        () => selectedPackage = reader.packageName,
                      ),
                    ),
                    const SizedBox(height: 8),
                  ],
                  _PdfReaderOption(
                    selected: selectedPackage == noPdfReaderChoice,
                    onTap: () => setSheetState(
                      () => selectedPackage = noPdfReaderChoice,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            PrimaryButton(
              label: 'Save choice',
              showIcon: false,
              onPressed: () => Navigator.of(sheetContext).pop(selectedPackage),
            ),
          ],
        ),
      ),
    ),
  );
}

class _PdfReaderOption extends StatelessWidget {
  const _PdfReaderOption({
    this.reader,
    required this.selected,
    required this.onTap,
  });

  final InstalledAppInfo? reader;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final app = reader;
    final title = app == null
        ? 'I don\'t use PDFs'
        : app.appName.trim().isEmpty
            ? 'PDF reader'
            : app.appName.trim();

    return Material(
      color: selected
          ? AppColors.lime.withValues(alpha: 0.12)
          : AppColors.surfaceRaised,
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppRadius.md),
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppRadius.md),
            border: Border.all(
              color: selected ? AppColors.lime : AppColors.surfaceBorder,
            ),
          ),
          child: Row(
            children: [
              if (app != null)
                AppIconTile(
                  appName: title,
                  packageName: app.packageName,
                  size: 42,
                  radius: 12,
                )
              else
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.picture_as_pdf_outlined,
                    color: AppColors.textSecondary,
                    size: 22,
                  ),
                ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              const SizedBox(width: 10),
              Icon(
                selected ? Icons.check_circle_rounded : Icons.circle_outlined,
                color: selected ? AppColors.lime : AppColors.textMuted,
                size: 22,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
