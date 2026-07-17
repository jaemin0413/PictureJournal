import { createHash } from 'node:crypto';
import { access, readFile, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) args.set(process.argv[index], process.argv[index + 1]);
const metroLog = args.get('--metro-log');
const udid = args.get('--udid');
const output = args.get('--output');
const sentinel = args.get('--sentinel');
const timeoutMs = Number(args.get('--timeout-ms') ?? 180000);
if (!metroLog || !udid || !output || !sentinel || !Number.isInteger(timeoutMs) || timeoutMs < 1) {
  throw new Error('Usage: watch-native-share-checkpoints.mjs --metro-log <path> --udid <udid> --output <path> --sentinel <path> [--timeout-ms <positive integer>]');
}

const schema = 'picturejournal.share-runtime.v1';
const expectedSteps = [
  { checkpoint: 'cold', phase: 'intent-observed', count: 0 },
  { checkpoint: 'cold', phase: 'durable-commit', count: 1 },
  { checkpoint: 'cold', phase: 'reset-invoked', count: 1, receipt: true },
  { checkpoint: 'cold-relaunch', phase: 'checkpoint', count: 1, receipt: true, chain: 'cold' },
  { checkpoint: 'warm', phase: 'intent-observed', count: 1 },
  { checkpoint: 'warm', phase: 'durable-commit', count: 2 },
  { checkpoint: 'warm', phase: 'reset-invoked', count: 2, receipt: true },
  { checkpoint: 'warm-relaunch', phase: 'checkpoint', count: 2, receipt: true, chain: 'warm' },
];
const receipts = [];
const chains = new Map();
let stepIndex = 0;
let offset = 0;
let pendingLine = '';

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function redact(value) {
  return `sha256:${sha256(value).slice(0, 16)}`;
}

function validateEvent(event) {
  if (!event || typeof event !== 'object' || Array.isArray(event)) throw new Error('PJ_SHARE_EVENT must be an object.');
  const expectedKeys = ['checkpoint', 'count', 'delivery_hash', 'event_id', 'monotonic_timestamp', 'phase', 'queue_generation', 'schema'];
  const actualKeys = Object.keys(event).sort();
  if (actualKeys.length !== expectedKeys.length || actualKeys.some((key, index) => key !== expectedKeys[index])) {
    throw new Error(`PJ_SHARE_EVENT schema keys are not exact: ${actualKeys.join(', ')}`);
  }
  if (
    event.schema !== schema
    || typeof event.event_id !== 'string' || !event.event_id
    || typeof event.delivery_hash !== 'string' || !event.delivery_hash
    || typeof event.phase !== 'string'
    || typeof event.checkpoint !== 'string'
    || !Number.isInteger(event.count) || event.count < 0
    || !Number.isFinite(event.monotonic_timestamp)
    || (event.queue_generation !== null && (typeof event.queue_generation !== 'string' || !event.queue_generation))
  ) throw new Error('PJ_SHARE_EVENT schema values are invalid.');
}

function runNativeKeyQuery() {
  return new Promise((resolve, reject) => {
    const child = spawn('xcrun', ['simctl', 'spawn', udid, 'defaults', 'read', 'group.com.picturejournal.mobile', 'picturejournalShareKey'], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const stdout = [];
    const stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => stderr.push(chunk));
    child.once('error', reject);
    child.once('close', (exitStatus, signal) => {
      if (signal || exitStatus === null) return reject(new Error('Native key query did not exit normally.'));
      const stdoutData = Buffer.concat(stdout);
      const stderrData = Buffer.concat(stderr);
      const combined = Buffer.concat([stdoutData, stderrData]).toString('utf8');
      resolve({
        source: 'xcrun simctl spawn <UDID> defaults read group.com.picturejournal.mobile picturejournalShareKey',
        absent: exitStatus === 1 && /does not exist/i.test(combined),
        exit_status: exitStatus,
        stdout_sha256: sha256(stdoutData),
        stderr_sha256: sha256(stderrData),
      });
    });
  });
}

async function recordReceipt(event) {
  const query = await runNativeKeyQuery();
  if (!query.absent) throw new Error(`Native share key was present or query was abnormal at ${event.checkpoint}.`);
  receipts.push({
    checkpoint: event.checkpoint,
    phase: event.phase,
    event_id: redact(event.event_id),
    delivery_hash: redact(event.delivery_hash),
    queue_generation: redact(event.queue_generation ?? ''),
    count: event.count,
    native_key_query: query,
  });
}

async function processEvent(event) {
  validateEvent(event);
  const expected = expectedSteps[stepIndex];
  if (!expected) throw new Error(`Duplicate recognized event after completion: ${event.checkpoint}/${event.phase}.`);
  if (event.checkpoint !== expected.checkpoint || event.phase !== expected.phase || event.count !== expected.count) {
    throw new Error(`Wrong-order PJ_SHARE_EVENT: expected ${expected.checkpoint}/${expected.phase}/${expected.count}, received ${event.checkpoint}/${event.phase}/${event.count}.`);
  }
  if (expected.phase === 'intent-observed') {
    if (event.queue_generation !== null) throw new Error('Intent-observed must not claim a durable generation.');
    chains.set(expected.checkpoint, { eventId: event.event_id, deliveryHash: event.delivery_hash });
  } else {
    if (!event.queue_generation) throw new Error(`A generation is required after durable commit at ${event.checkpoint}.`);
    const chain = chains.get(expected.chain ?? expected.checkpoint);
    if (!chain || chain.eventId !== event.event_id || chain.deliveryHash !== event.delivery_hash) {
      throw new Error(`Correlation mismatch at ${event.checkpoint}/${event.phase}.`);
    }
  }
  stepIndex += 1;
  if (expected.receipt) await recordReceipt(event);
}

async function readNewLog() {
  let contents;
  try {
    contents = await readFile(metroLog, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return [];
    throw error;
  }
  if (contents.length < offset) {
    offset = 0;
    pendingLine = '';
  }
  const lines = (pendingLine + contents.slice(offset)).split(/\r?\n/);
  offset = contents.length;
  pendingLine = lines.pop() ?? '';
  return lines;
}

async function poll() {
  for (const line of await readNewLog()) {
    const marker = line.indexOf('PJ_SHARE_EVENT ');
    if (marker === -1) continue;
    let event;
    try {
      event = JSON.parse(line.slice(marker + 'PJ_SHARE_EVENT '.length));
    } catch {
      throw new Error('Malformed PJ_SHARE_EVENT JSON.');
    }
    await processEvent(event);
  }
}

async function sentinelExists() {
  try {
    await access(sentinel);
    return true;
  } catch {
    return false;
  }
}

async function writeReceipts() {
  await writeFile(output, `${JSON.stringify({
    schema: 'picturejournal.native-share-watch.v1',
    receipts,
    chain_count: chains.size,
    recognized_event_count: stepIndex,
  }, null, 2)}\n`);
}

const deadline = Date.now() + timeoutMs;
try {
  while (!await sentinelExists()) {
    await poll();
    if (Date.now() >= deadline) throw new Error('Timed out waiting for the native share sentinel.');
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  await poll();
  await new Promise((resolve) => setTimeout(resolve, 250));
  await poll();
  if (stepIndex !== expectedSteps.length || receipts.length !== 4 || chains.size !== 2) {
    throw new Error(`Incomplete ordered share evidence: events=${stepIndex}/${expectedSteps.length}, receipts=${receipts.length}/4, chains=${chains.size}/2.`);
  }
  await writeReceipts();
} catch (error) {
  await writeReceipts();
  throw error;
}
