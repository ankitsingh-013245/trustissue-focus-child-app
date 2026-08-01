import 'dart:collection';
import 'dart:typed_data';

import 'native_bridge.dart';

/// Process-wide cache of real app launcher icons fetched from the native side.
///
/// Native only hands back PNG bytes per package on demand, so we memoise the
/// in-flight future (and its result) per package. This means the Select Apps
/// list and the Home "Allowed Apps" row can both ask for the same icon without
/// hitting the platform channel twice.
class AppIconCache {
  AppIconCache._();
  static final AppIconCache instance = AppIconCache._();
  static const _maxEntries = 192;
  static const _maxBytes = 8 * 1024 * 1024;

  final _bridge = NativeBridge();
  final LinkedHashMap<String, Uint8List?> _resolved = LinkedHashMap();
  final Map<String, Future<Uint8List?>> _pending = {};
  int _resolvedBytes = 0;

  /// Returns the cached icon bytes immediately if we already have them, else
  /// null. Useful for a synchronous first paint before the future resolves.
  Uint8List? cached(String packageName) {
    if (!_resolved.containsKey(packageName)) return null;
    final value = _resolved.remove(packageName);
    _resolved[packageName] = value;
    return value;
  }

  bool isResolved(String packageName) => _resolved.containsKey(packageName);

  Future<Uint8List?> load(String packageName) {
    if (packageName.isEmpty) return Future.value(null);
    if (_resolved.containsKey(packageName)) {
      return Future.value(cached(packageName));
    }
    return _pending.putIfAbsent(packageName, () async {
      try {
        final bytes = await _bridge.getAppIcon(packageName);
        final resolved = (bytes != null && bytes.isNotEmpty) ? bytes : null;
        _remember(packageName, resolved);
        return resolved;
      } catch (_) {
        _remember(packageName, null);
        return null;
      } finally {
        _pending.remove(packageName);
      }
    });
  }

  void _remember(String packageName, Uint8List? bytes) {
    final previous = _resolved.remove(packageName);
    if (previous != null) _resolvedBytes -= previous.lengthInBytes;
    _resolved[packageName] = bytes;
    if (bytes != null) _resolvedBytes += bytes.lengthInBytes;

    while (_resolved.length > _maxEntries || _resolvedBytes > _maxBytes) {
      final oldest = _resolved.keys.first;
      final removed = _resolved.remove(oldest);
      if (removed != null) _resolvedBytes -= removed.lengthInBytes;
    }
  }
}
