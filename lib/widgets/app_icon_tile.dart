import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../services/app_icon_cache.dart';

/// Renders an installed app's real launcher icon.
///
/// The native side hands back the actual icon PNG per package (loaded once and
/// cached in [AppIconCache]). While that resolves — or if the icon can't be
/// loaded — we fall back to a branded lettered tile so the row is never blank.
class AppIconTile extends StatefulWidget {
  const AppIconTile({
    super.key,
    required this.appName,
    required this.packageName,
    this.size = 52,
    this.radius = 16,
  });

  final String appName;
  final String packageName;
  final double size;
  final double radius;

  @override
  State<AppIconTile> createState() => _AppIconTileState();
}

class _AppIconTileState extends State<AppIconTile> {
  Uint8List? _icon;
  int _requestSerial = 0;

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  @override
  void didUpdateWidget(covariant AppIconTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.packageName != widget.packageName) {
      _icon = AppIconCache.instance.cached(widget.packageName);
      _resolve();
    }
  }

  void _resolve() {
    final requestedPackage = widget.packageName;
    final requestSerial = ++_requestSerial;
    final cache = AppIconCache.instance;
    final immediate = cache.cached(requestedPackage);
    if (cache.isResolved(requestedPackage)) {
      _icon = immediate;
      return;
    }
    cache.load(requestedPackage).then((bytes) {
      if (!mounted ||
          requestSerial != _requestSerial ||
          widget.packageName != requestedPackage) {
        return;
      }
      setState(() {
        _icon = bytes;
      });
    });
  }

  @override
  void dispose() {
    _requestSerial++;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final icon = _icon;
    if (icon != null) {
      final decodedSize = (widget.size * MediaQuery.devicePixelRatioOf(context))
          .round()
          .clamp(1, 512);
      return Container(
        width: widget.size,
        height: widget.size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(widget.radius),
          color: Colors.white.withValues(alpha: 0.04),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.28),
              blurRadius: 10,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Image.memory(
          icon,
          key: ValueKey(widget.packageName),
          width: widget.size,
          height: widget.size,
          cacheWidth: decodedSize,
          cacheHeight: decodedSize,
          fit: BoxFit.cover,
          // Never retain the previous package's decoded frame while this tile
          // is recycled for another app.
          gaplessPlayback: false,
          filterQuality: FilterQuality.medium,
        ),
      );
    }

    return _LetterTile(
      appName: widget.appName,
      packageName: widget.packageName,
      size: widget.size,
      radius: widget.radius,
      // Show a visible branded fallback immediately; the real icon replaces it
      // when native loading finishes.
      muted: false,
    );
  }
}

/// Branded lettered stand-in used while the real icon loads or if it is
/// unavailable. Known apps get their brand colour; everything else gets a
/// stable colour derived from the package name.
class _LetterTile extends StatelessWidget {
  const _LetterTile({
    required this.appName,
    required this.packageName,
    required this.size,
    required this.radius,
    required this.muted,
  });

  final String appName;
  final String packageName;
  final double size;
  final double radius;
  final bool muted;

  static const Map<String, _Brand> _known = {
    'com.whatsapp': _Brand(Color(0xFF25D366), 'W'),
    'com.whatsapp.w4b': _Brand(Color(0xFF25D366), 'W'),
    'com.spotify.music': _Brand(Color(0xFF1DB954), 'S'),
    'com.instagram.android': _Brand(Color(0xFFE1306C), 'IG'),
    'com.snapchat.android': _Brand(Color(0xFFFFFC00), 'S'),
    'org.telegram.messenger': _Brand(Color(0xFF2AABEE), 'T'),
    'com.google.android.youtube': _Brand(Color(0xFFFF0000), 'YT'),
    'com.google.android.apps.docs': _Brand(Color(0xFF1FA463), 'D'),
    'com.android.chrome': _Brand(Color(0xFF4285F4), 'C'),
    'com.google.android.gm': _Brand(Color(0xFFEA4335), 'M'),
    'com.microsoft.office.outlook': _Brand(Color(0xFF0078D4), 'O'),
    'com.google.android.apps.messaging': _Brand(Color(0xFF1A73E8), 'M'),
    'com.samsung.android.messaging': _Brand(Color(0xFF1A73E8), 'M'),
    'com.notion.id': _Brand(Color(0xFF222222), 'N'),
  };

  @override
  Widget build(BuildContext context) {
    final brand = _known[packageName];
    final color = brand?.color ?? _colorForPackage(packageName);
    final label = brand?.label ?? _initials(appName, packageName);
    final onColor =
        color.computeLuminance() > 0.6 ? Colors.black87 : Colors.white;

    if (muted) {
      // Neutral placeholder while the real icon is still loading.
      return Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(radius),
          color: Colors.white.withValues(alpha: 0.05),
        ),
      );
    }

    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color.lerp(color, Colors.white, 0.18)!,
            color,
          ],
        ),
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.35),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Text(
        label,
        style: TextStyle(
          color: onColor,
          fontSize: size * 0.34,
          fontWeight: FontWeight.w900,
          letterSpacing: -0.5,
        ),
      ),
    );
  }

  static String _initials(String appName, String packageName) {
    final trimmed = appName.trim();
    if (trimmed.isEmpty) return '?';
    final parts = trimmed.split(RegExp(r'\s+'));
    if (parts.length >= 2 && parts[0].isNotEmpty && parts[1].isNotEmpty) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return trimmed.characters.first.toUpperCase();
  }

  static Color _colorForPackage(String packageName) {
    var hash = 0;
    for (final code in packageName.codeUnits) {
      hash = (hash * 31 + code) & 0x7fffffff;
    }
    const palette = [
      Color(0xFF3DDC84),
      Color(0xFF5B8DEF),
      Color(0xFFEF6F6C),
      Color(0xFFF2A65A),
      Color(0xFF9B7EDE),
      Color(0xFF35C4C4),
      Color(0xFFE86AA6),
    ];
    return palette[hash % palette.length];
  }
}

class _Brand {
  const _Brand(this.color, this.label);
  final Color color;
  final String label;
}
