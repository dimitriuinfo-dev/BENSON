#!/usr/bin/env node
// FIX_NODE_MODULES_STALE_SYNC_1 (2026-10-03, user-directed) — npm's "file:" dependency installs on
// Windows COPY the local module into node_modules instead of symlinking; editing
// modules/<name>/index.js or index.d.ts does NOT update node_modules/<name>, so tsc/Metro keep
// reading the stale copy until a full `npm install` re-copies it. Device-proven this session:
// `setBrainCredentials` was exported from modules/benson-foreground-service/index.d.ts but tsc
// still reported "has no exported member" from the node_modules copy, dated days earlier.
//
// This re-copies the handful of files that matter (index.js, index.d.ts, package.json) for every
// local "file:" dependency in package.json — not the whole module tree (no android/, no build
// artifacts) — so it's fast enough to run on every `npm install` (wired as "postinstall" below)
// and safe to run by hand mid-session after editing a module's JS-exposed surface.
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const deps = { ...(pkg.dependencies || {}), ...(pkg.devDependencies || {}) };
const FILES_TO_SYNC = ['index.js', 'index.d.ts', 'package.json'];

let synced = 0;
for (const [name, spec] of Object.entries(deps)) {
  if (typeof spec !== 'string' || !spec.startsWith('file:')) continue;
  const srcDir = path.resolve(root, spec.slice('file:'.length));
  const destDir = path.join(root, 'node_modules', name);
  if (!fs.existsSync(srcDir) || !fs.existsSync(destDir)) continue;
  for (const file of FILES_TO_SYNC) {
    const srcFile = path.join(srcDir, file);
    if (!fs.existsSync(srcFile)) continue;
    const destFile = path.join(destDir, file);
    const srcContent = fs.readFileSync(srcFile);
    const destContent = fs.existsSync(destFile) ? fs.readFileSync(destFile) : null;
    if (destContent === null || !srcContent.equals(destContent)) {
      fs.copyFileSync(srcFile, destFile);
      console.log(`[sync-local-modules] ${name}/${file} updated`);
      synced++;
    }
  }
}
console.log(synced > 0 ? `[sync-local-modules] ${synced} file(s) synced.` : '[sync-local-modules] already up to date.');
