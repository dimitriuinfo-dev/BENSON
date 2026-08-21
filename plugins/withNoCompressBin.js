const { withAppBuildGradle } = require('@expo/config-plugins');

// whisper.rn reads ggml-base.bin via Android's AssetManager/isBundleAsset directly — it needs the
// file to stay uncompressed inside the APK (zip-stored, not deflated), otherwise AssetManager
// can't hand back a plain file descriptor for the native (C++) side to read. `noCompress 'bin'`
// in androidResources{} was previously a manual, undocumented edit to android/app/build.gradle —
// lost on every `expo prebuild --clean` just like the signing config and bundled models were (see
// SESSION_REPORT.md). Idempotent via TAG so repeated prebuilds never double-insert.

const TAG = '@benson-nocompress-bin';

function findMatchingBrace(content, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < content.length; i++) {
    if (content[i] === '{') depth++;
    else if (content[i] === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function injectNoCompress(contents) {
  if (contents.includes(TAG)) return contents; // already injected — idempotent across re-prebuilds
  const arIdx = contents.indexOf('androidResources {');
  if (arIdx === -1) {
    console.warn('[withNoCompressBin] could not find "androidResources {" in build.gradle — skipping.');
    return contents;
  }
  const arOpen = contents.indexOf('{', arIdx);
  const arClose = findMatchingBrace(contents, arOpen);
  if (arClose === -1) return contents;
  const insertion = `\n        noCompress 'bin' // ${TAG} — whisper.rn needs ggml-base.bin uncompressed in the APK\n    `;
  return contents.slice(0, arClose) + insertion + contents.slice(arClose);
}

module.exports = function withNoCompressBin(config) {
  return withAppBuildGradle(config, (config) => {
    config.modResults.contents = injectNoCompress(config.modResults.contents);
    return config;
  });
};
