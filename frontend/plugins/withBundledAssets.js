const { withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// Generic "copy a large binary asset from a stable out-of-project-android/ location into
// android/app/src/main/assets/ on every prebuild" plugin (product-owner-directed 2026-08-02).
//
// Started as two near-identical plugins (withPorcupineModelAsset.js for the wake-word model,
// then the same problem turned out to affect the Whisper STT model too — see SESSION_REPORT.md's
// "ggml-base.bin was also unprotected" incident). Unified into one list-driven plugin instead of
// two copy-pasted files: the find/mkdir/copy/no-op logic is identical for every entry, so adding a
// third bundled asset later is a one-line addition to ASSETS below, not a new plugin file.
//
// Every entry is independent and safe: if its source file is missing, this logs a warning and
// skips ONLY that entry — it must never fail the build, since the app already has a working
// fallback for both (Porcupine → classic SpeechRecognizer; missing Whisper model would be a real
// problem, which is exactly why this plugin exists now).
const ASSETS = [
  {
    label: 'Porcupine wake-word model',
    source: ['porcupine-model', 'benson.ppn'],
    dest: ['assets', 'porcupine', 'benson.ppn'],
  },
  {
    label: 'Whisper STT model',
    source: ['whisper-models', 'ggml-base.bin'],
    dest: ['assets', 'models', 'ggml-base.bin'],
  },
];

module.exports = function withBundledAssets(config) {
  return withDangerousMod(config, [
    'android',
    (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const platformProjectRoot = config.modRequest.platformProjectRoot; // .../android

      for (const asset of ASSETS) {
        const sourcePath = path.join(projectRoot, ...asset.source);
        if (!fs.existsSync(sourcePath)) {
          console.warn(
            `[withBundledAssets] ${asset.label}: ${path.join(...asset.source)} not found at project root — skipping. ` +
            `See SESSION_REPORT.md for where this file needs to be placed.`
          );
          continue;
        }
        const destPath = path.join(platformProjectRoot, 'app', 'src', 'main', ...asset.dest);
        try {
          fs.mkdirSync(path.dirname(destPath), { recursive: true });
          fs.copyFileSync(sourcePath, destPath);
          console.log(`[withBundledAssets] ${asset.label}: copied -> ${destPath}`);
        } catch (e) {
          console.warn(`[withBundledAssets] ${asset.label}: failed to copy (${e.message}) — skipping.`);
        }
      }
      return config;
    },
  ]);
};
