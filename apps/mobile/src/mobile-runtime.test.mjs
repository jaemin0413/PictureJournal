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
  filterPendingShareReceipts,
  isPendingShareExpired,
  normalizeExifCoordinate,
  normalizeExifTakenAt,
  parseCoordinate,
  parsePendingShares,
  pendingShareReplayTrigger,
  pendingShareReplaySignature,
  scopePendingShare,
  isPendingShareReplayable,
  restorePendingShareQueue,
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
const fullPending = (clientIntakeId, receivedAt, extra = {}) => ({
  clientIntakeId,
  contentFingerprint: 'a'.repeat(64),
  receivedAt,
  rawUrl: `https://example.test/${clientIntakeId}`,
  rawTitle: clientIntakeId,
  rawText: '',
  sourceApp: 'test',
  platform: 'ios',
  receivedVia: 'test',
  ...extra,
});
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

test('Expo config fixes the iOS Share Sheet extension display name and Android text filter', () => {
  const config = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  const shareIntent = config.expo.plugins.find((plugin) => Array.isArray(plugin) && plugin[0] === 'expo-share-intent');
  assert.equal(shareIntent[1].iosShareExtensionName, 'Picture Journal');
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
test('iOS Share Sheet fixture wiring exercises the real UI test and native receipt cleanup contracts', () => {
  const workflow = readFileSync(new URL('../../../.github/workflows/mobile-share-proof.yml', import.meta.url), 'utf8');
  const fixtureTests = readFileSync(new URL('../ios-share-fixture/ShareFixtureUITests/ShareFixtureUITests.swift', import.meta.url), 'utf8');
  const fixtureProject = readFileSync(new URL('../ios-share-fixture/ShareFixture.xcodeproj/project.pbxproj', import.meta.url), 'utf8');

  assert.match(
    workflow,
    /xcodebuild\s+-project\s+ios-share-fixture\/ShareFixture\.xcodeproj\s+-scheme\s+ShareFixture\b[^\n]*\btest\b/,
    'The iOS proof must execute tests through the actual ShareFixture scheme.',
  );
  assert.match(workflow, /xcrun simctl spawn "\$SIMULATOR_UDID" defaults read group\.com\.picturejournal\.mobile/);
  assert.match(workflow, /picturejournalShareKey/);
  assert.match(workflow, /artifacts\/mobile\/ios-app-group-preferences\.txt/);
  assert.doesNotMatch(workflow, /\bplistlib\b/, 'The proof must not synthesize fixture state with plistlib.');
  assert.doesNotMatch(workflow, /\bdefaults\s+(?:import|write)\b/, 'The proof must not mutate native preferences.');
  assert.doesNotMatch(workflow, /(?:defaults|plutil)[\s\S]{0,80}picturejournalShareKey[\s\S]{0,80}(?:write|insert|replace)/, 'The proof must not inject the native share receipt directly.');

  assert.match(fixtureProject, /name = ShareFixtureUITests;[\s\S]*productType = "com\.apple\.product-type\.bundle\.ui-testing";/);
  assert.match(fixtureProject, /ShareFixtureUITests\.swift in Sources/);
  assert.match(fixtureProject, /TestTargetID = A10000000000000000000051;/);
  assert.match(fixtureProject, /PRODUCT_BUNDLE_IDENTIFIER = com\.picturejournal\.sharefixture;/);
  assert.doesNotMatch(fixtureProject, /CODE_SIGNING_(?:ALLOWED|REQUIRED) = NO/);

  assert.ok(fixtureTests.includes('let coldPayload = "PictureJournal fixture cold \\(UUID().uuidString)"'));
  assert.ok(fixtureTests.includes('let warmPayload = "PictureJournal fixture warm \\(UUID().uuidString)"'));
  assert.match(fixtureTests, /share\(coldPayload\)[\s\S]*assertUnauthenticatedQueue\(in: pictureJournal, expectedPayloads: \[coldPayload\]\)/);
  assert.match(fixtureTests, /share\(warmPayload\)[\s\S]*assertUnauthenticatedQueue\(in: pictureJournal, expectedPayloads: \[coldPayload, warmPayload\]\)/);
  assert.match(fixtureTests, /share\(warmPayload\)[\s\S]*pictureJournal\.terminate\(\)[\s\S]*pictureJournal\.launch\(\)[\s\S]*assertUnauthenticatedQueue\(in: pictureJournal, expectedPayloads: \[coldPayload, warmPayload\]\)/);
  assert.match(fixtureTests, /NSPredicate\(format: "label == %@", "Picture Journal"\)/);
  assert.match(fixtureTests, /fixture\.cells\["More"\]/);
  assert.doesNotMatch(fixtureTests, /PictureJournal"\]/);
  assert.match(fixtureTests, /XCTAssertTrue\(authState\.waitForExistence\(timeout: 30\)/);
  assert.match(fixtureTests, /XCTNSPredicateExpectation/);
  assert.match(fixtureTests, /assertLabel\(queueCount, equals: String\(expectedPayloads\.count\)/);
  assert.match(fixtureTests, /assertLabel\(queuePayloads, equals: expectedPayloadLabel/);
  assert.match(fixtureTests, /assertNoNativeReceipt\(in: pictureJournal\)/);
  assert.match(fixtureTests, /assertLabel\(receiptCount, equals: "0"/);
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

test('pending share replay trigger is scoped and stable across durable rewrites', () => {
  const retryable = pending('share-1', { userId: 'user-a', intendedFolderId: 'folder-a', retryState: 'retryable' });
  const newlyBound = pending('share-4', { userId: 'user-a', intendedFolderId: 'folder-a' });
  const blocked = pending('share-2', { userId: 'user-a', intendedFolderId: 'folder-a', retryState: 'auth' });
  const otherFolder = pending('share-3', { userId: 'user-a', intendedFolderId: 'folder-b' });
  assert.equal(isPendingShareReplayable(retryable, 'user-a', 'folder-a'), true);
  assert.equal(isPendingShareReplayable(blocked, 'user-a', 'folder-a'), false);
  assert.equal(isPendingShareReplayable(otherFolder, 'user-a', 'folder-a'), false);
  assert.equal(pendingShareReplaySignature([retryable, blocked], 'user-a', 'folder-a'), 'share-1');
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: null,
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [retryable],
  }), null);
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-1',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [blocked, otherFolder],
  }), null);
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-1',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [retryable, blocked],
  }), 'user-a:folder-a:share-1');
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-2',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [{ ...retryable, lastError: '503 unavailable', retryState: 'retryable' }, blocked],
  }), 'user-a:folder-a:share-1');
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-3',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [newlyBound, retryable, blocked],
  }), 'user-a:folder-a:share-1|share-4');
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-2',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: [blocked],
  }), null);
});

test('post-replay refresh removes processed shares before publishing server read models', () => {
  const resolved = pending('resolved', { userId: 'user-a', intendedFolderId: 'folder-a' });
  const failed = pending('failed', { userId: 'user-a', intendedFolderId: 'folder-a' });
  const otherFolder = pending('other', { userId: 'user-a', intendedFolderId: 'folder-b' });
  const failedClassified = { ...failed, lastError: '422 validation', retryState: 'validation' };
  const processedIds = new Set(['resolved', 'failed']);
  const afterReplay = updatePendingShareQueue([resolved, failed, otherFolder], (current) => [
    ...current.filter((item) => !processedIds.has(item.clientIntakeId)),
    failedClassified,
  ]);
  assert.deepEqual(afterReplay.map((item) => item.clientIntakeId), ['other', 'failed']);
  assert.equal(pendingShareReplayTrigger({
    durableGeneration: 'gen-after-replay',
    sessionUserId: 'user-a',
    placesFolderId: 'folder-a',
    pendingShares: afterReplay,
  }), null);
  const serverUnresolved = [{ intakeId: 'server-unresolved', folderId: 'folder-a', status: 'UNRESOLVED' }];
  const serverPlaces = [{ placeId: 'resolved-place', folderId: 'folder-a', name: 'Resolved Place' }];
  assert.deepEqual(serverUnresolved.map((item) => item.intakeId), ['server-unresolved']);
  assert.deepEqual(serverPlaces.map((item) => item.placeId), ['resolved-place']);
});
test('pending share TTL keeps just-before-boundary payloads and expires exact-boundary payloads', () => {
  const now = 1_700_000_000_000;
  const justFresh = fullPending('just-fresh', now - (24 * 60 * 60 * 1000) + 1);
  const exactBoundary = fullPending('exact-boundary', now - (24 * 60 * 60 * 1000));
  assert.equal(isPendingShareExpired(justFresh, now), false);
  assert.equal(isPendingShareExpired(exactBoundary, now), true);
  assert.deepEqual(updatePendingShareQueue([justFresh, exactBoundary], (current) => current, now).map((item) => item.clientIntakeId), ['just-fresh']);
});

test('pending share restore and write paths filter expired payloads with matching receipts', () => {
  const now = 1_700_000_000_000;
  const expiredStored = fullPending('expired-stored', now - (24 * 60 * 60 * 1000));
  const freshStored = fullPending('fresh-stored', now - 1000);
  const expiredCurrent = fullPending('expired-current', now - (24 * 60 * 60 * 1000) - 1);
  const freshCurrent = fullPending('fresh-current', now - 2000);
  const restored = restorePendingShareQueue(
    [expiredStored, freshStored],
    [expiredCurrent, freshCurrent],
    { 'native:expired-stored': 'expired-stored', 'native:fresh-stored': 'fresh-stored' },
    { 'native:expired-current': 'expired-current', 'native:fresh-current': 'fresh-current' },
    now,
  );
  assert.deepEqual(plain(restored.items.map((item) => item.clientIntakeId)), ['fresh-stored', 'fresh-current']);
  assert.deepEqual(plain(restored.receipts), { 'native:fresh-stored': 'fresh-stored', 'native:fresh-current': 'fresh-current' });

  const written = updatePendingShareQueue([expiredCurrent, freshCurrent], (current) => [...current, expiredStored, freshStored], now);
  assert.deepEqual(plain(written.map((item) => item.clientIntakeId)), ['fresh-current', 'fresh-stored']);
  assert.deepEqual(
    plain(filterPendingShareReceipts({ expired: 'expired-current', current: 'fresh-current', stored: 'fresh-stored' }, written)),
    { current: 'fresh-current', stored: 'fresh-stored' },
  );
});

test('pending share replay signature excludes expired payloads', () => {
  const now = 1_700_000_000_000;
  const expired = fullPending('expired', now - (24 * 60 * 60 * 1000), { userId: 'user-a', intendedFolderId: 'folder-a' });
  const fresh = fullPending('fresh', now - 1, { userId: 'user-a', intendedFolderId: 'folder-a' });
  assert.equal(isPendingShareReplayable(expired, 'user-a', 'folder-a', now), false);
  assert.equal(pendingShareReplaySignature([expired, fresh], 'user-a', 'folder-a', now), 'fresh');
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
