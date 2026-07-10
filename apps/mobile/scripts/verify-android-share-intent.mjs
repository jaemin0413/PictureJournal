import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const manifestPath = resolve('android/app/src/main/AndroidManifest.xml');
const manifest = readFileSync(manifestPath, 'utf8');
const required = [
  'android.intent.action.SEND',
  'android.intent.category.DEFAULT',
  'android:mimeType="text/plain"',
];
const missing = required.filter((value) => !manifest.includes(value));
const receipt = {
  checkedAt: new Date().toISOString(),
  manifestPath,
  required,
  missing,
  status: missing.length === 0 ? 'passed' : 'failed',
};
mkdirSync(resolve('../../artifacts/mobile'), { recursive: true });
writeFileSync(resolve('../../artifacts/mobile/android-share-intent-manifest-check.json'), JSON.stringify(receipt, null, 2));
if (missing.length > 0) {
  console.error(`Missing Android share intent manifest entries: ${missing.join(', ')}`);
  process.exit(1);
}
console.log('Android share intent manifest check passed.');
