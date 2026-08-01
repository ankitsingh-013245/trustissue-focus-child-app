import 'package:flutter/material.dart';

/// Central design tokens for the aurora "Focus" redesign.
///
/// The whole app uses a dark northern-lights aesthetic: deep green-black
/// surfaces, a bright lime accent for primary actions and an emerald accent
/// for the "Focus" highlight and glows.
class AppColors {
  AppColors._();

  // Backgrounds
  static const Color night = Color(0xFF05100C);
  static const Color nightDeep = Color(0xFF030A07);
  static const Color page = Color(0xFF090B0A);
  static const Color surface = Color(0xFF151816);
  static const Color surfaceRaised = Color(0xFF1B1F1C);
  static const Color surfaceBorder = Color(0xFF2A2F2B);
  static const Color surfaceDivider = Color(0xFF272B28);

  // Accents
  static const Color lime = Color(0xFFB8E62E);
  static const Color limeSoft = Color(0xFFCDF25C);
  static const Color emerald = Color(0xFF39D98A);
  static const Color emeraldGlow = Color(0xFF4BE3A0);

  // Kept for legacy decorative surfaces. Primary app screens use the opaque
  // surface tokens above so the interface stays crisp and predictable.
  static const Color glass = Color(0x14FFFFFF);
  static const Color glassStrong = Color(0x1FFFFFFF);
  static const Color glassBorder = Color(0x24FFFFFF);
  static const Color card = Color(0xFF151816);
  static const Color cardBorder = Color(0xFF2A2F2B);

  // Text
  static const Color textPrimary = Color(0xFFF3F7F2);
  static const Color textSecondary = Color(0xB3E8F0E6);
  static const Color textMuted = Color(0x80D6E2D4);

  // Semantic
  static const Color danger = Color(0xFFFF5A5F);
  static const Color dangerSoft = Color(0x1AFF5A5F);
  static const Color warning = Color(0xFFFFC24B);
}

class AppRadius {
  AppRadius._();
  static const double sm = 8;
  static const double md = 10;
  static const double lg = 10;
  static const double pill = 40;
}

class AppTheme {
  AppTheme._();

  static ThemeData build() {
    final base = ThemeData(
      brightness: Brightness.dark,
      useMaterial3: true,
      fontFamily: 'Roboto',
      scaffoldBackgroundColor: AppColors.page,
      colorScheme: const ColorScheme.dark(
        primary: AppColors.lime,
        onPrimary: Color(0xFF0C1A05),
        secondary: AppColors.emerald,
        surface: AppColors.card,
        onSurface: AppColors.textPrimary,
        error: AppColors.danger,
      ),
    );

    return base.copyWith(
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        iconTheme: IconThemeData(color: AppColors.textPrimary),
        titleTextStyle: TextStyle(
          color: AppColors.textPrimary,
          fontSize: 20,
          fontWeight: FontWeight.w700,
        ),
      ),
      textTheme: base.textTheme
          .apply(
            bodyColor: AppColors.textPrimary,
            displayColor: AppColors.textPrimary,
          )
          .copyWith(
            headlineMedium: const TextStyle(
              fontSize: 32,
              height: 1.1,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary,
            ),
            headlineSmall: const TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary,
            ),
            titleLarge: const TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary,
            ),
            titleMedium: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
            titleSmall: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
            bodyMedium: const TextStyle(
              fontSize: 14,
              height: 1.4,
              color: AppColors.textSecondary,
            ),
            bodySmall: const TextStyle(
              fontSize: 12.5,
              height: 1.35,
              color: AppColors.textMuted,
            ),
          ),
      dividerColor: AppColors.surfaceDivider,
      iconTheme: const IconThemeData(color: AppColors.textPrimary),
      // Without an explicit theme, floating SnackBars with a dark background
      // kept the Material default (dark) text color -> invisible dark-on-dark.
      // This makes every focus confirmation / validation message readable.
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.card,
        contentTextStyle: const TextStyle(
          color: AppColors.textPrimary,
          fontSize: 14,
          height: 1.35,
          fontWeight: FontWeight.w600,
        ),
        actionTextColor: AppColors.lime,
        elevation: 10,
        insetPadding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          side: const BorderSide(color: AppColors.surfaceBorder),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: const TextStyle(
          color: AppColors.textPrimary,
          fontSize: 19,
          fontWeight: FontWeight.w800,
        ),
        contentTextStyle: const TextStyle(
          color: AppColors.textSecondary,
          fontSize: 14,
          height: 1.4,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.lg),
          side: const BorderSide(color: AppColors.surfaceBorder),
        ),
      ),
    );
  }

  /// Gradient used behind the aurora photo overlay to deepen contrast.
  static const LinearGradient scrim = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0x1A05100C),
      Color(0x99040D09),
      Color(0xF2030A07),
    ],
    stops: [0.0, 0.55, 1.0],
  );

  static const LinearGradient limeButton = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [AppColors.limeSoft, AppColors.lime],
  );
}
