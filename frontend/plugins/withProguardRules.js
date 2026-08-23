const { withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// R8/ProGuard keep rules for BENSON's local Expo modules + whisper.rn (product-owner-directed
// 2026-08-02). These modules register themselves via reflection (Expo Modules'
// ModuleDefinition/Events/Function/AsyncFunction by name), which R8 can silently strip or rename
// when minification is on — a class of bug that fails at RUNTIME, not at compile time, and only
// shows up in a release build, never in debug. Currently minifyEnabled is false (see
// gradle.properties), so this doesn't bite today, but the rules were previously a manual edit to
// android/app/proguard-rules.pro that vanished on every `expo prebuild --clean` — the exact kind
// of silent gap that would explode the first time minify is switched back on after a clean
// prebuild. Appended (not overwritten) so react-native-reanimated's own plugin-managed rule (added
// automatically by its own Expo config plugin, confirmed present after a fresh prebuild) is left
// alone. Idempotent via TAG so repeated prebuilds never double-append.

const TAG = '@benson-proguard-rules';

const KEEP_PACKAGES = [
  'expo.modules.speechrecognition', // expo-speech-recognition
  'expo.modules.audiocapture',
  'expo.modules.foregroundservice',
  'expo.modules.accessibility',
  'expo.modules.appregistry',
  'expo.modules.carbluetooth',
  'expo.modules.notificationlistener',
  'expo.modules.overlay',
  'com.rnwhisper', // whisper.rn's native bridge
];

function buildRulesBlock() {
  const lines = KEEP_PACKAGES.map((pkg) => `-keep class ${pkg}.** { *; }`);
  return `\n# ${TAG} — BENSON local modules + whisper.rn (see plugins/withProguardRules.js)\n${lines.join('\n')}\n`;
}

module.exports = function withProguardRules(config) {
  return withDangerousMod(config, [
    'android',
    (config) => {
      const proguardPath = path.join(config.modRequest.platformProjectRoot, 'app', 'proguard-rules.pro');
      if (!fs.existsSync(proguardPath)) {
        console.warn(`[withProguardRules] ${proguardPath} not found — skipping.`);
        return config;
      }
      const contents = fs.readFileSync(proguardPath, 'utf8');
      if (contents.includes(TAG)) return config; // already appended — idempotent across re-prebuilds
      fs.appendFileSync(proguardPath, buildRulesBlock());
      console.log(`[withProguardRules] appended ${KEEP_PACKAGES.length} keep rules -> ${proguardPath}`);
      return config;
    },
  ]);
};
