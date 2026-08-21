// Bumps the internal "BENSON N" build label in app.json and logs it in CHANGELOG.md.
// Usage: node scripts/bump-build.js "what changed in this build"
const fs = require('fs');
const path = require('path');

const appJsonPath = path.join(__dirname, '..', 'app.json');
const changelogPath = path.join(__dirname, '..', 'CHANGELOG.md');

const message = process.argv.slice(2).join(' ').trim();
if (!message) {
  console.error('Usage: node scripts/bump-build.js "what changed in this build"');
  process.exit(1);
}

const appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
const current = appJson.expo.extra.buildLabel || 'BENSON 0';
const currentNum = parseInt(current.match(/\d+/)?.[0] ?? '0', 10);
const nextNum = currentNum + 1;
const nextLabel = `BENSON ${nextNum}`;

appJson.expo.extra.buildLabel = nextLabel;
fs.writeFileSync(appJsonPath, JSON.stringify(appJson, null, 2) + '\n');

const date = new Date().toISOString().slice(0, 10);
const entry = `- **${nextLabel}** (${date}): ${message}\n`;
fs.appendFileSync(changelogPath, entry);

console.log(`${current} -> ${nextLabel}`);
