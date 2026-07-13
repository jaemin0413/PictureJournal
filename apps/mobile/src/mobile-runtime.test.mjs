import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';
import { deflateSync } from 'node:zlib';
import vm from 'node:vm';
import { inspectAndroidShareManifest } from '../scripts/verify-android-share-intent.mjs';
import { inspectPngBytes } from '../scripts/verify-png.mjs';

const require = createRequire(import.meta.url);
const typescript = require('typescript');
const source = readFileSync(new URL('../App.tsx', import.meta.url), 'utf8');
const compiled = typescript.transpileModule(source, {
  compilerOptions: { jsx: typescript.JsxEmit.ReactJSX, module: typescript.ModuleKind.CommonJS, target: typescript.ScriptTarget.ES2020 },
}).outputText;
const module = { exports: {} };
const reactNative = { Platform: { OS: 'ios' }, StyleSheet: { create: (styles) => styles } };
const browserValues = new Map();
const nativeValues = new Map();
const localStorage = {
  getItem: (key) => browserValues.get(key) ?? null,
  setItem: (key, value) => browserValues.set(key, value),
  removeItem: (key) => browserValues.delete(key),
};
const fakeRequire = (name) => {
  if (name === 'react-native') return reactNative;
  if (name === 'expo-constants') return { default: {} };
  if (name === 'expo-secure-store') return {
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 'after-first-unlock',
    getItemAsync: async (key) => nativeValues.get(key) ?? null,
    setItemAsync: async (key, value) => nativeValues.set(key, value),
    deleteItemAsync: async (key) => nativeValues.delete(key),
  };
  if (name === 'react/jsx-runtime') return {};
  return { default: {}, useCallback: () => {}, useEffect: () => {}, useRef: () => {}, useState: () => {}, useShareIntent: () => ({}) };
};
vm.runInNewContext(compiled, { module, exports: module.exports, require: fakeRequire, process, Headers, FormData, TextEncoder, console, localStorage, globalThis: { localStorage } });
const {
  applyPendingShareDelivery,
  classifyAuthResponse,
  classifyShareRetry,
  confirmPendingShareOwnership,
  createPendingShareMetadata,
  dedupePendingShares,
  normalizeExifCoordinate,
  normalizeExifTakenAt,
  parseCoordinate,
  parsePendingShares,
  scopePendingShare,
  isSessionOperationCurrent,
  secureDelete,
  secureDeleteChunked,
  secureGet,
  secureGetChunked,
  secureSet,
  secureSetChunked,
  shouldInvalidateSession,
  updatePendingShareQueue,
  utf8Checksum,
  utf8Chunks,
} = module.exports;

const pending = (clientIntakeId, extra = {}) => ({ clientIntakeId, rawText: '', ...extra });
const plain = (value) => JSON.parse(JSON.stringify(value));

const pngCrcTable = Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
  return value >>> 0;
});
const pngCrc32 = (value) => {
  let crc = 0xffffffff;
  for (const byte of value) crc = pngCrcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
};
const pngChunk = (type, data) => {
  const typeBytes = Buffer.from(type);
  const chunk = Buffer.alloc(data.length + 12);
  chunk.writeUInt32BE(data.length, 0);
  typeBytes.copy(chunk, 4);
  data.copy(chunk, 8);
  chunk.writeUInt32BE(pngCrc32(Buffer.concat([typeBytes, data])), data.length + 8);
  return chunk;
};
const rgbaPng = (pixels) => {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(2, 0);
  ihdr.writeUInt32BE(2, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const rows = Buffer.from([0, ...pixels.slice(0, 8), 0, ...pixels.slice(8)]);
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(rows)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
};

test('Expo config is strict JSON with one non-overlapping Android text filter', () => {
  const config = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  const shareIntent = config.expo.plugins.find((plugin) => Array.isArray(plugin) && plugin[0] === 'expo-share-intent');
  assert.deepEqual(shareIntent[1].androidIntentFilters, ['text/*']);
});

test('Android share verifier requires one complete exact filter and rejects misleading fixtures', () => {
  const filter = (action = 'SEND', category = true, mime = 'text/*') => `
    <intent-filter>
      <action android:name="android.intent.action.${action}" />
      ${category ? '<category android:name="android.intent.category.DEFAULT" />' : ''}
      <data android:mimeType="${mime}" />
    </intent-filter>`;
  const manifest = (body) => `<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"><application><activity>${body}</activity></application></manifest>`;

  assert.equal(inspectAndroidShareManifest(manifest(filter())).status, 'passed');
  const alternatePrefix = `
    <manifest xmlns:a="http://schemas.android.com/apk/res/android">
      <application><activity><intent-filter>
        <action a:name="android.intent.action.SEND" />
        <category a:name="android.intent.category.DEFAULT" />
        <data a:mimeType="text/*" />
      </intent-filter></activity></application>
    </manifest>`;
  assert.equal(inspectAndroidShareManifest(alternatePrefix).status, 'passed');
  assert.equal(inspectAndroidShareManifest(manifest(`${filter()}${filter()}`)).status, 'failed');
  assert.equal(inspectAndroidShareManifest(manifest(filter('SEND_MULTIPLE'))).status, 'failed');
  assert.equal(
    inspectAndroidShareManifest(manifest(`${filter('SEND', false)}${filter('VIEW', true)}`)).status,
    'failed',
  );
  assert.equal(
    inspectAndroidShareManifest(manifest('<!-- android.intent.action.SEND android.intent.category.DEFAULT android:mimeType="text/*" -->')).status,
    'failed',
  );
  assert.equal(
    inspectAndroidShareManifest(manifest(`
      <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" tools:ignore='android:mimeType="text/*"' />
      </intent-filter>`)).status,
    'failed',
  );
  const reboundNamespace = manifest(`
    <intent-filter>
      <action android:name="android.intent.action.SEND" />
      <category android:name="android.intent.category.DEFAULT" />
      <data xmlns:android="urn:not-android" android:mimeType="text/*"
            xmlns:a="http://schemas.android.com/apk/res/android" a:mimeType="text/plain" />
    </intent-filter>`);
  assert.equal(inspectAndroidShareManifest(reboundNamespace).status, 'failed');
  const activityOutsideApplication = `
    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <activity>${filter()}</activity>
    </manifest>`;
  assert.equal(inspectAndroidShareManifest(activityOutsideApplication).status, 'failed');
  const foreignLookalikes = `
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:x="urn:lookalike">
      <application><x:activity><x:intent-filter>
        <x:action android:name="android.intent.action.SEND" />
        <x:category android:name="android.intent.category.DEFAULT" />
        <x:data android:mimeType="text/*" />
      </x:intent-filter></x:activity></application>
    </manifest>`;
  assert.equal(inspectAndroidShareManifest(foreignLookalikes).status, 'failed');
  const unclosed = inspectAndroidShareManifest('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>');
  assert.equal(unclosed.status, 'failed');
  assert.ok(unclosed.parseError);
  const missingNamespace = inspectAndroidShareManifest('<manifest><application><activity><intent-filter></intent-filter></activity></application></manifest>');
  assert.equal(missingNamespace.status, 'failed');
  const multiRoot = inspectAndroidShareManifest(`${manifest('')}<manifest />`);
  assert.equal(multiRoot.status, 'failed');
  assert.ok(multiRoot.parseError);
  const trailingText = inspectAndroidShareManifest(`${manifest(filter())}not-xml`);
  assert.equal(trailingText.status, 'failed');
  assert.ok(trailingText.parseError);
});
test('PNG verifier compares visible pixels and rejects hidden RGB differences', () => {
  const transparentRgbNoise = rgbaPng([
    255, 0, 0, 0, 0, 255, 0, 0,
    0, 0, 255, 0, 255, 255, 0, 0,
  ]);
  assert.throws(() => inspectPngBytes(transparentRgbNoise), /visually uniform/);

  const visibleDifference = rgbaPng([
    255, 0, 0, 255, 0, 255, 0, 255,
    0, 0, 255, 255, 255, 255, 0, 255,
  ]);
  assert.equal(inspectPngBytes(visibleDifference).status, 'passed');
});
test('web storage bypasses unavailable native SecureStore and persists values', async () => {
  reactNative.Platform.OS = 'web';
  try {
    await secureSet('picturejournal.test', 'stored');
    assert.equal(await secureGet('picturejournal.test'), 'stored');
    assert.equal(browserValues.has('picturejournal.web.v1.picturejournal.test'), true);
    await secureDelete('picturejournal.test');
    assert.equal(await secureGet('picturejournal.test'), null);
  } finally {
    browserValues.clear();
    reactNative.Platform.OS = 'ios';
  }
});
test('stale session responses cannot invalidate a newer login', () => {
  const first = { token: 'token-a', user: { userId: 'user-a' } };
  const second = { token: 'token-b', user: { userId: 'user-b' } };
  assert.equal(shouldInvalidateSession({ epoch: 1, token: first.token, userId: first.user.userId }, second, 2), false);
  assert.equal(shouldInvalidateSession({ epoch: 2, token: second.token, userId: second.user.userId }, second, 2), true);
  assert.equal(isSessionOperationCurrent({ epoch: 1, token: first.token, userId: first.user.userId }, second, 2), false);
  assert.equal(isSessionOperationCurrent({ epoch: 2, token: second.token, userId: second.user.userId }, second, 2), true);
});

test('share capture scope distinguishes authenticated records from quarantine', () => {
  const item = pending('share-1');
  assert.deepEqual(plain(scopePendingShare(item, 'user-a', 'folder-a')), { ...item, userId: 'user-a', intendedFolderId: 'folder-a' });
  assert.deepEqual(plain(scopePendingShare(item, null, 'folder-a')), item);
  const ownedFolderless = scopePendingShare(item, 'user-a', null);
  assert.deepEqual(
    plain(confirmPendingShareOwnership([ownedFolderless], 'user-a', 'folder-a', item.clientIntakeId)[0]),
    { ...item, userId: 'user-a', intendedFolderId: 'folder-a' },
  );
  assert.deepEqual(
    plain(confirmPendingShareOwnership([ownedFolderless], 'user-b', 'folder-b', item.clientIntakeId)[0]),
    { ...item, userId: 'user-a' },
  );
  assert.deepEqual(plain(confirmPendingShareOwnership([item], 'user-b', 'folder-b', item.clientIntakeId)[0]), { ...item, userId: 'user-b', intendedFolderId: 'folder-b' });
});

test('corrupt chunk metadata can be reset and rewritten', async () => {
  nativeValues.set('queue.meta', '{malformed');
  await assert.rejects(secureDeleteChunked('queue'));
  await secureSetChunked('queue', JSON.stringify([pending('rewritten')]), {});
  assert.equal((await secureGetChunked('queue')).value.includes('rewritten'), true);
  nativeValues.clear();
});

test('UTF-8 chunks honor byte limits without splitting emoji and metadata checksums the whole generation', () => {
  const value = '한글😀é'.repeat(30);
  const chunks = utf8Chunks(value, 17);
  assert.equal(chunks.join(''), value);
  assert.ok(chunks.every((chunk) => Buffer.byteLength(chunk, 'utf8') <= 17));
  const metadata = createPendingShareMetadata('generation-1', chunks, { 'native:event-1': 'intake-1' });
  assert.equal(metadata.count, chunks.length);
  assert.equal(metadata.checksum, utf8Checksum(value));
  assert.deepEqual(metadata.receipts, { 'native:event-1': 'intake-1' });
  assert.notEqual(metadata.checksum, utf8Checksum(`${value}!`));
});

test('queue updater merges against its latest input and deduplicates delivery IDs', () => {
  const first = pending('first');
  const second = pending('second');
  const duplicateSecond = pending('second', { rawText: 'newer duplicate' });
  const afterFirst = updatePendingShareQueue([], (current) => [...current, first]);
  const afterSecond = updatePendingShareQueue(afterFirst, (current) => [...current, second, duplicateSecond]);
  assert.deepEqual(afterSecond.map((item) => item.clientIntakeId), ['first', 'second']);
  assert.deepEqual(dedupePendingShares([first, first]), [first]);
});
test('native delivery receipts survive a duplicate callback but allow a later identical delivery after reset', () => {
  const captured = applyPendingShareDelivery([], {}, 'native:event', pending('first'));
  const replayed = applyPendingShareDelivery(captured.items, captured.receipts, 'native:event', pending('duplicate'));
  assert.equal(replayed.added, false);
  const later = applyPendingShareDelivery(captured.items, {}, 'native:event', pending('later-identical-content'));
  assert.equal(later.added, true);
});

test('replay and session failures have explicit retry classifications', () => {
  assert.equal(classifyShareRetry(401), 'auth');
  assert.equal(classifyShareRetry(403), 'auth');
  assert.equal(classifyShareRetry(409), 'conflict');
  assert.equal(classifyShareRetry(422), 'validation');
  assert.equal(classifyShareRetry(429), 'retryable');
  assert.equal(classifyShareRetry(undefined), 'retryable');
  assert.equal(classifyShareRetry(408), 'retryable');
  assert.equal(classifyShareRetry(425), 'retryable');
  assert.equal(classifyAuthResponse(401), 'reauthenticate');
  assert.equal(classifyAuthResponse(403), 'reauthenticate');
  assert.equal(classifyAuthResponse(429), 'retry');
  assert.equal(classifyAuthResponse(503), 'retry');
  assert.equal(classifyAuthResponse(200), 'valid');
});
test('EXIF timestamps require ISO timezone data and normalize valid instants', () => {
  assert.equal(normalizeExifTakenAt(' 2024-06-15T09:30:45.123+09:00 '), '2024-06-15T00:30:45.123Z');
  assert.equal(normalizeExifTakenAt('2024-06-15T00:30:45Z'), '2024-06-15T00:30:45.000Z');
  assert.equal(normalizeExifTakenAt('2024-06-15T09:30:45'), undefined);
  assert.equal(normalizeExifTakenAt('2024-06-15T09:30:45+25:00'), undefined);
});

test('pending share storage rejects malformed JSON and records', () => {
  assert.throws(() => parsePendingShares('{'));
  assert.throws(() => parsePendingShares('[null]'));
  assert.throws(() => parsePendingShares('[{"clientIntakeId":"intake-1"}]'));
});

test('coordinate writes reject blank, non-finite, and out-of-range values', () => {
  assert.equal(parseCoordinate('-33.8688', 'latitude'), -33.8688);
  assert.equal(parseCoordinate('-151.2093', 'longitude'), -151.2093);
  for (const [value, label] of [['', 'latitude'], ['91', 'latitude'], ['-181', 'longitude'], ['NaN', 'longitude']]) {
    assert.throws(() => parseCoordinate(value, label));
  }
});

test('EXIF direction requires an axis-appropriate reference and unsigned value', () => {
  assert.equal(normalizeExifCoordinate(33.8688, 'S', 'S'), -33.8688);
  assert.equal(normalizeExifCoordinate(151.2093, 'W', 'W'), -151.2093);
  assert.equal(normalizeExifCoordinate(33.8688, 'N', 'S'), 33.8688);
  assert.equal(normalizeExifCoordinate(33.8688, undefined, 'S'), undefined);
  assert.equal(normalizeExifCoordinate(33.8688, 'E', 'S'), undefined);
  assert.equal(normalizeExifCoordinate(-33.8688, 'S', 'S'), undefined);
  assert.equal(normalizeExifCoordinate(181, 'E', 'W'), undefined);
});
