import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Pro broadcast studio design system — CricRelay Live.
abstract final class AppColors {
  // Canvas
  static const background = Color(0xFF07080C);
  static const canvas = Color(0xFF0A0B10);
  static const surface = Color(0xFF12151C);
  static const surfaceElevated = Color(0xFF1A1E28);
  static const surfaceVariant = Color(0xFF252A36);
  static const glass = Color(0xCC12151C);
  static const glassBorder = Color(0x33FFFFFF);

  // Brand
  static const primary = Color(0xFFFF3B47);
  static const primaryMuted = Color(0xFFCC2F38);
  static const primaryGlow = Color(0x40FF3B47);
  static const accent = Color(0xFF00D4AA);
  static const accentBlue = Color(0xFF4DA3FF);
  static const accentGreen = Color(0xFF2EE59D);
  static const accentPurple = Color(0xFF8B7CFF);

  // Text
  static const onBackground = Color(0xFFF4F6FA);
  static const onBackgroundMuted = Color(0xFFB4BBC8);
  static const onBackgroundDim = Color(0xFF6B7380);

  // Structure
  static const border = Color(0xFF2E3440);
  static const borderSubtle = Color(0xFF1E222B);
  static const divider = Color(0xFF252932);

  // Semantic
  static const success = Color(0xFF2EE59D);
  static const warning = Color(0xFFFFB020);
  static const error = Color(0xFFFF5A52);
  static const live = Color(0xFFFF3B47);

  // Preview / HUD
  static const previewScrim = Color(0x99000000);
  static const hudBackground = Color(0xB3101218);
  static const safeGuide = Color(0x66FFFFFF);
  static const overlayFrame = Color(0xFF00D4AA);
  static const overlayFrameLocked = Color(0x55FFFFFF);
}

abstract final class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
  static const xxl = 48.0;
  static const radiusSm = 10.0;
  static const radiusMd = 14.0;
  static const radiusLg = 18.0;
  static const radiusXl = 24.0;
  static const radiusPill = 999.0;
}

abstract final class AppMotion {
  static const fast = Duration(milliseconds: 180);
  static const normal = Duration(milliseconds: 280);
  static const slow = Duration(milliseconds: 420);
  static const curve = Curves.easeOutCubic;
}

/// Tabular figures for timers and metrics.
TextStyle metricStyle({
  double size = 12,
  Color color = AppColors.onBackground,
  FontWeight weight = FontWeight.w600,
}) {
  return TextStyle(
    fontSize: size,
    fontWeight: weight,
    color: color,
    letterSpacing: 0.4,
    fontFeatures: const [FontFeature.tabularFigures()],
  );
}

ThemeData buildAppTheme() {
  const scheme = ColorScheme.dark(
    brightness: Brightness.dark,
    primary: AppColors.primary,
    onPrimary: Colors.white,
    secondary: AppColors.accent,
    onSecondary: AppColors.background,
    surface: AppColors.surface,
    onSurface: AppColors.onBackground,
    error: AppColors.error,
    onError: Colors.white,
  );

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.background,
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: CupertinoPageTransitionsBuilder(),
        TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
      },
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.transparent,
      foregroundColor: AppColors.onBackground,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(
        fontSize: 17,
        fontWeight: FontWeight.w600,
        color: AppColors.onBackground,
        letterSpacing: -0.2,
      ),
      systemOverlayStyle: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarColor: AppColors.background,
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    ),
    cardTheme: CardThemeData(
      color: AppColors.surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        side: const BorderSide(color: AppColors.borderSubtle),
      ),
      margin: EdgeInsets.zero,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.surfaceVariant.withValues(alpha: 0.6),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        borderSide: const BorderSide(color: AppColors.borderSubtle),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        borderSide: const BorderSide(color: AppColors.error),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      labelStyle: const TextStyle(color: AppColors.onBackgroundMuted),
      hintStyle: const TextStyle(color: AppColors.onBackgroundDim),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        disabledBackgroundColor: AppColors.surfaceVariant,
        padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 15),
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        ),
        textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15, letterSpacing: 0.2),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: AppColors.onBackground,
        side: const BorderSide(color: AppColors.border),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 13),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.accentBlue,
        textStyle: const TextStyle(fontWeight: FontWeight.w600),
      ),
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: AppColors.accent,
      foregroundColor: AppColors.background,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppSpacing.radiusLg)),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: AppColors.surfaceElevated,
      contentTextStyle: const TextStyle(color: AppColors.onBackground),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppSpacing.radiusSm)),
      behavior: SnackBarBehavior.floating,
      elevation: 8,
    ),
    dividerTheme: const DividerThemeData(color: AppColors.divider, thickness: 1),
    listTileTheme: const ListTileThemeData(
      iconColor: AppColors.onBackgroundMuted,
      textColor: AppColors.onBackground,
      contentPadding: EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.xs),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: AppColors.surfaceElevated,
      modalBackgroundColor: AppColors.surfaceElevated,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
      ),
    ),
    sliderTheme: SliderThemeData(
      activeTrackColor: AppColors.accent,
      thumbColor: AppColors.accent,
      inactiveTrackColor: AppColors.surfaceVariant,
      overlayColor: AppColors.accent.withValues(alpha: 0.12),
      trackHeight: 3,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.accent),
    dialogTheme: DialogThemeData(
      backgroundColor: AppColors.surfaceElevated,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppSpacing.radiusLg)),
      titleTextStyle: appTextTheme.titleMedium,
      contentTextStyle: appTextTheme.bodyMedium,
    ),
  );
}

TextTheme get appTextTheme => TextTheme(
      headlineLarge: const TextStyle(
        fontSize: 30,
        fontWeight: FontWeight.w800,
        color: AppColors.onBackground,
        letterSpacing: -0.8,
        height: 1.15,
      ),
      headlineSmall: const TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w700,
        color: AppColors.onBackground,
        letterSpacing: -0.3,
      ),
      titleMedium: const TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w600,
        color: AppColors.onBackground,
        letterSpacing: -0.1,
      ),
      bodyLarge: const TextStyle(fontSize: 16, color: AppColors.onBackground, height: 1.5),
      bodyMedium: const TextStyle(fontSize: 14, color: AppColors.onBackgroundMuted, height: 1.5),
      bodySmall: const TextStyle(fontSize: 12, color: AppColors.onBackgroundDim, height: 1.45),
      labelLarge: const TextStyle(
        fontSize: 13,
        fontWeight: FontWeight.w700,
        color: AppColors.onBackground,
        letterSpacing: 0.6,
      ),
    );
