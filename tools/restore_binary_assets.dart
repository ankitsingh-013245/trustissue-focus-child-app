import 'dart:convert';
import 'dart:io';

Future<void> main() async {
  final scriptFile = File.fromUri(Platform.script);
  final root = scriptFile.parent.parent;
  final manifestFile = File(
    '${root.path}${Platform.pathSeparator}binary_assets'
    '${Platform.pathSeparator}manifest.json',
  );

  if (!manifestFile.existsSync()) {
    stderr.writeln('Binary asset manifest not found: ${manifestFile.path}');
    exitCode = 1;
    return;
  }

  final manifest = jsonDecode(await manifestFile.readAsString());
  final assets = (manifest['assets'] as List<dynamic>? ?? const []);
  var restored = 0;

  for (final rawAsset in assets) {
    final asset = rawAsset as Map<String, dynamic>;
    final relativePath = asset['path'] as String;
    final expectedBytes = asset['bytes'] as int;
    final parts = (asset['parts'] as List<dynamic>).cast<String>();
    final encoded = StringBuffer();

    for (final part in parts) {
      final partFile = File(
        '${root.path}${Platform.pathSeparator}'
        '${part.replaceAll('/', Platform.pathSeparator)}',
      );
      if (!partFile.existsSync()) {
        throw StateError('Missing binary asset part: ${partFile.path}');
      }
      encoded.write((await partFile.readAsString()).trim());
    }

    final bytes = base64Decode(encoded.toString());
    if (bytes.length != expectedBytes) {
      throw StateError(
        'Size verification failed for $relativePath: '
        'expected $expectedBytes bytes, restored ${bytes.length}.',
      );
    }

    final output = File(
      '${root.path}${Platform.pathSeparator}'
      '${relativePath.replaceAll('/', Platform.pathSeparator)}',
    );
    await output.parent.create(recursive: true);
    await output.writeAsBytes(bytes, flush: true);
    restored += 1;
    stdout.writeln('Restored $relativePath');
  }

  stdout.writeln('Restored $restored binary assets successfully.');
}
