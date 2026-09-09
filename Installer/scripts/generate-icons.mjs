#!/usr/bin/env node
// Export the shared artwork to desktop icons and RestoreMode launcher resources.
import {spawnSync} from 'node:child_process';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {copyFileSync, writeFileSync} from 'node:fs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const desktop = join(root, 'Installer/desktop');
const icons = join(desktop, 'src-tauri/icons');
const android = join(root, 'RestoreMode/app/src/main');
function run(command, args) {
  const result = spawnSync(command, args, {cwd: root, stdio: 'inherit'});
  if (result.error || result.status !== 0) throw result.error || new Error(`${command}: ${result.status}`);
}
run(process.execPath, [join(desktop, 'node_modules/@tauri-apps/cli/tauri.js'),
  'icon', join(root, 'Packaging/branding/icon.json'), '--output', icons]);
for (const density of ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']) {
  for (const [source, target] of [
    ['ic_launcher', 'ic_restoremode'],
    ['ic_launcher_round', 'ic_restoremode_round'],
    ['ic_launcher_foreground', 'restoremode'],
  ]) {
    run('cwebp', ['-quiet', '-lossless', join(icons, `android/mipmap-${density}/${source}.png`),
      '-o', join(android, `res/mipmap-${density}/${target}.webp`)]);
  }
}
// Tauri's custom-size export is PNG; keep the Play Store asset at 512 pixels.
run(process.execPath, [join(desktop, 'node_modules/@tauri-apps/cli/tauri.js'),
  'icon', join(root, 'Packaging/branding/app-icon.png'), '--png', '512',
  '--output', join(root, 'Releases/build/branding')]);
copyFileSync(join(root, 'Releases/build/branding/512x512.png'), join(android, 'ic_restoremode-playstore.png'));
writeFileSync(join(android, 'res/values/ic_restoremode_background.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_restoremode_background">#101820</color>\n</resources>\n');
