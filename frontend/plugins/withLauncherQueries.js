const { withAndroidManifest } = require('@expo/config-plugins');

// Adds a single <queries><intent> MAIN + LAUNCHER </intent></queries> block to AndroidManifest.xml
// so queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER) — used by benson-app-registry's
// getInstalledApps() and lib/appIndex.ts — returns EVERY installed app that has a launcher icon.
//
// Why this is needed (confirmed live 2026-08-29): on Android 11+ that call is subject to package
// visibility filtering. Without a matching <queries> entry it returns only the auto-visible subset
// — 49 of 277 launchable apps on the OnePlus Nord 4 test device, so "open YouTube" failed even
// though YouTube was installed. This intent form is the documented, Play-Store-allowed way to
// enumerate launcher apps. It is NOT QUERY_ALL_PACKAGES (a restricted permission).
//
// The same block is also committed directly in android/app/src/main/AndroidManifest.xml so the
// current (no-prebuild) build already has it; this plugin only guarantees a future `expo prebuild`
// regenerates it. Idempotent — re-runs never add a duplicate.

const TAG = '@benson-launcher-queries';

module.exports = function withLauncherQueries(config) {
  return withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;

    manifest.queries = manifest.queries || [];

    const hasLauncherQuery = manifest.queries.some((q) =>
      (q.intent || []).some((intent) => {
        const actions = intent.action || [];
        const categories = intent.category || [];
        const hasMain = actions.some((a) => a?.$?.['android:name'] === 'android.intent.action.MAIN');
        const hasLauncher = categories.some((c) => c?.$?.['android:name'] === 'android.intent.category.LAUNCHER');
        return hasMain && hasLauncher;
      }),
    );

    if (hasLauncherQuery) return config;

    manifest.queries.push({
      // xml2js round-trips leading comments on '_' — keeps the TAG visible in the generated file.
      _: ` ${TAG} — see plugins/withLauncherQueries.js `,
      intent: [
        {
          action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
          category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
        },
      ],
    });

    return config;
  });
};
