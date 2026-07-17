import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const typescript = require('typescript');
const source = readFileSync(new URL('../App.tsx', import.meta.url), 'utf8');
const compiled = typescript.transpileModule(source, {
  compilerOptions: { jsx: typescript.JsxEmit.ReactJSX, module: typescript.ModuleKind.CommonJS, target: typescript.ScriptTarget.ES2020 },
}).outputText;
const nativeValues = new Map();
const writes = [];
let failWrite = null;
const module = { exports: {} };
const fakeRequire = (name) => {
  if (name === 'react-native') return { Platform: { OS: 'ios' }, StyleSheet: { create: (styles) => styles } };
  if (name === 'expo-secure-store') return {
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 'after-first-unlock',
    getItemAsync: async (key) => nativeValues.get(key) ?? null,
    setItemAsync: async (key, value) => {
      writes.push(key);
      if (failWrite?.(key)) throw new Error(`fault:${key}`);
      nativeValues.set(key, value);
    },
    deleteItemAsync: async (key) => nativeValues.delete(key),
  };
  if (name === 'expo-constants') return { default: {} };
  if (name === 'react/jsx-runtime') return {};
  return { default: {}, useCallback: () => {}, useEffect: () => {}, useRef: () => ({}), useState: () => {}, useShareIntent: () => ({}) };
};
vm.runInNewContext(compiled, { module, exports: module.exports, require: fakeRequire, process, Headers, FormData, TextEncoder, console, globalThis: { performance: { now: () => 1234 } } });
const {
  applyPendingShareDelivery,
  coordinateNativeShareDelivery,
  createPendingShareMetadata,
  createShareRuntimeCorrelation,
  createShareRuntimeEvent,
  secureGetChunked,
  secureSetChunked,
} = module.exports;

const payload = (id) => ({
  clientIntakeId: id,
  contentFingerprint: 'a'.repeat(64),
  receivedAt: Date.now(),
  rawUrl: '',
  rawTitle: '',
  rawText: `fixture ${id}`,
  sourceApp: 'native-share-sheet',
  platform: 'ios',
  receivedVia: 'native_share',
});
const plain = (value) => JSON.parse(JSON.stringify(value));

const resetStorage = () => {
  nativeValues.clear();
  writes.length = 0;
  failWrite = null;
};
test('deep links wait for the restore barrier before registering or enqueueing', () => {
  const normalizedSource = source.replace(/\r\n/g, '\n');
  const effectStart = normalizedSource.indexOf("  useEffect(() => {\n    if (!shareRestoreBarrierComplete) return;\n    const captureUrl = async (url: string | null) => {");
  const effectEnd = normalizedSource.indexOf("  }, [enqueuePendingShare, placesFolder, session, shareRestoreBarrierComplete]);", effectStart);

  assert.notEqual(effectStart, -1);
  assert.notEqual(effectEnd, -1);
  const effect = normalizedSource.slice(effectStart, effectEnd);
  assert.match(effect, /if \(url\.includes\(':\/\/dataUrl='\)\) \{\n\s+await getShareIntent\(url\);\n\s+return;/);
  assert.ok(effect.indexOf('if (!shareRestoreBarrierComplete) return;') < effect.indexOf('Linking.getInitialURL()'));
  assert.ok(effect.indexOf('if (!shareRestoreBarrierComplete) return;') < effect.indexOf("Linking.addEventListener('url'"));
  assert.match(normalizedSource.slice(effectEnd, effectEnd + 100), /shareRestoreBarrierComplete/);
});

test('chunk writes publish metadata only after all chunks are durable', async () => {
  resetStorage();
  failWrite = (key) => key.endsWith('.0');
  await assert.rejects(secureSetChunked('queue', 'a'.repeat(2000), {}), /fault:queue/);
  assert.equal(nativeValues.has('queue.meta'), false);

  resetStorage();
  const metadata = await secureSetChunked('queue', 'a'.repeat(2000), {});
  assert.equal(writes.at(-1), 'queue.meta');
  assert.equal(metadata.count, 2);
  assert.equal((await secureGetChunked('queue')).value.length, 2000);
});

test('coordinator emits source-safe intent, durable commit, and reset events in order', async () => {
  const events = [];
  const metadata = createPendingShareMetadata('generation-a', ['[]']);
  const calls = [];
  const outcome = await coordinateNativeShareDelivery({
    clientIntakeId: 'persisted-intake-a',
    checkpoint: 'cold',
    queueCount: 0,
    persist: async () => { calls.push('persist'); return metadata; },
    reset: () => { calls.push('reset'); },
    clearReceipt: async () => { calls.push('clear'); },
    emit: (event) => events.push(event),
  });
  assert.deepEqual(calls, ['persist', 'reset', 'clear']);
  assert.equal(outcome.resetOutcome, 'reset-completed');
  assert.deepEqual(events.map((event) => event.phase), ['intent-observed', 'durable-commit', 'reset-invoked']);
  assert.equal(events[1].queue_generation, 'generation-a');
  assert.equal(events[1].count, 1);
  assert.deepEqual(new Set(events.map((event) => event.event_id)).size, 1);
  assert.deepEqual(new Set(events.map((event) => event.delivery_hash)).size, 1);
  assert.ok(events.every((event) => event.schema === 'picturejournal.share-runtime.v1' && event.delivery_hash !== 'native:private-delivery'));
  assert.doesNotMatch(JSON.stringify(events), /private-delivery|rawText|token|session/i);
});

test('reset fault leaves the durable receipt intact because the reset outcome is unknown', async () => {
  const calls = [];
  const outcome = await coordinateNativeShareDelivery({
    clientIntakeId: 'persisted-intake-b', checkpoint: 'warm', queueCount: 1,
    persist: async () => { calls.push('persist'); return null; },
    reset: () => { calls.push('reset'); throw new Error('reset fault'); },
    clearReceipt: async () => { calls.push('clear'); }, emit: () => {},
  });
  assert.equal(outcome.resetOutcome, 'reset-outcome-unknown');
  assert.deepEqual(calls, ['persist', 'reset']);
});

test('dedupe receipt prevents relaunch duplicate delivery and cleanup faults remain observable', async () => {
  const first = payload('one');
  const stored = applyPendingShareDelivery([], {}, 'native:hash', first);
  await secureSetChunked('relaunch', JSON.stringify(stored.items), stored.receipts);
  const restored = JSON.parse((await secureGetChunked('relaunch')).value);
  const duplicate = applyPendingShareDelivery(restored, stored.receipts, 'native:hash', payload('two'));
  assert.equal(duplicate.added, false);
  assert.deepEqual([...duplicate.items].map((item) => item.clientIntakeId), ['one']);

  await assert.rejects(coordinateNativeShareDelivery({
    clientIntakeId: 'persisted-intake-c', checkpoint: 'cold-relaunch', queueCount: 1,
    persist: async () => null, reset: () => {}, clearReceipt: async () => { throw new Error('cleanup fault'); }, emit: () => {},
  }), /cleanup fault/);
});

test('event factory derives stable source-safe correlation only from the persisted intake ID', () => {
  const correlation = createShareRuntimeCorrelation('persisted-intake-d');
  const event = createShareRuntimeEvent({
    clientIntakeId: 'persisted-intake-d', phase: 'intent-observed', queueGeneration: null, count: 0, checkpoint: 'warm-relaunch', timestamp: 9,
  });
  assert.deepEqual(plain(event), {
    schema: 'picturejournal.share-runtime.v1', event_id: correlation.eventId, phase: 'intent-observed', delivery_hash: correlation.deliveryHash,
    queue_generation: null, count: 0, checkpoint: 'warm-relaunch', monotonic_timestamp: 9,
  });
  assert.deepEqual(createShareRuntimeCorrelation('persisted-intake-d'), correlation);
  assert.doesNotMatch(JSON.stringify(event), /secret|rawText|token|session/i);
});
