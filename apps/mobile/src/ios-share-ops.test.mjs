import assert from 'node:assert/strict';
import test from 'node:test';
import { Readable } from 'node:stream';
import { dispatch, parseDispatchResponse, validateHistory } from '../scripts/dispatch-ios-share-proof.mjs';

const cutoff = '2026-07-17T00:00:00.000Z';
const options = {
  owner: 'picturejournal', repo: 'picturejournal', ref: 'proof-branch', ledger: '/ledger.jsonl', githubRuns: '/runs.json',
  cutoff, budgetEpoch: 'epoch-1', baseline: 'base', candidateSha: 'candidate', workflowSha: 'workflow', hypothesis: 'single-cause', token: 'secret',
};

function memoryIo(ledger = '', runs = { workflow_runs: [] }) {
  const writes = [];
  const files = new Map([['/ledger.jsonl', ledger]]);
  const record = (path, value) => {
    files.set(path, `${files.get(path) ?? ''}${value}`);
    writes.push(`${path}:${value}`);
  };
  return {
    writes,
    ledger: () => files.get('/ledger.jsonl'),
    readFile: async (path) => path === '/ledger.jsonl' ? files.get(path) : JSON.stringify(runs),
    mkdir: async () => {},
    open: async (path) => ({ writeFile: async (value) => record(path, value), sync: async () => {}, close: async () => {} }),
    writeFile: async (path, value) => record(path, value),
    rename: async (from, to) => { files.set(to, files.get(from)); files.delete(from); },
  };
}

test('historical and another budget epoch do not consume the budget', () => {
  const rows = [
    { type: 'accepted', consumed: true, nonce: 'old', time: '2026-07-16T23:59:59.000Z', budget_epoch: 'epoch-1' },
    { type: 'accepted', consumed: true, nonce: 'other-epoch', time: cutoff, budget_epoch: 'epoch-0' },
  ];
  assert.deepEqual(validateHistory(rows, [], cutoff, 'epoch-1'), { consumed: 0, remaining: 3 });
});

test('accepted and recovery rows count once by nonce and union with the same GitHub run', () => {
  const rows = [
    { type: 'accepted', consumed: true, nonce: 'one', workflow_run_id: 9, time: cutoff, budget_epoch: 'epoch-1' },
    { type: 'recovery', consumed: true, nonce: 'one', time: cutoff, budget_epoch: 'epoch-1' },
  ];
  assert.deepEqual(validateHistory(rows, [{ id: 9, created_at: cutoff, run_attempt: 1 }], cutoff, 'epoch-1'), { consumed: 1, remaining: 2 });
});

test('duplicate accepted or GitHub run IDs and exhausted execution budget hard-stop', () => {
  const accepted = (nonce, workflowRunId) => ({ type: 'accepted', consumed: true, nonce, workflow_run_id: workflowRunId, time: cutoff, budget_epoch: 'epoch-1' });
  assert.throws(() => validateHistory([accepted('one', 9), accepted('two', 9)], [], cutoff, 'epoch-1'), /duplicate accepted run ID/);
  assert.throws(() => validateHistory([], [{ id: 9, created_at: cutoff, run_attempt: 1 }, { id: 9, created_at: cutoff, run_attempt: 1 }], cutoff, 'epoch-1'), /duplicate GitHub run ID/);
  assert.throws(() => validateHistory([accepted('one', 1), accepted('two', 2), accepted('three', 3)], [], cutoff, 'epoch-1'), /budget exhausted/);
  assert.throws(() => validateHistory([], [{ id: 9, created_at: cutoff, run_attempt: 2 }], cutoff, 'epoch-1'), /BUDGET_VIOLATION/);
});

test('dispatch response parser requires the REST 200 identity', () => {
  assert.deepEqual(parseDispatchResponse(200, JSON.stringify({ workflow_run_id: 7, run_url: 'https://api/runs/7', html_url: 'https://github/runs/7', ref: 'proof-branch' }), 'proof-branch').workflow_run_id, 7);
  assert.throws(() => parseDispatchResponse(204, '{}', 'proof-branch'), /HTTP 204/);
});

test('dry-run emits a token-free readiness receipt without network or ledger mutation', async () => {
  const io = memoryIo();
  let requested = false;
  const result = await dispatch({ ...options, dryRun: true, token: undefined }, { io, request: async () => { requested = true; }, nonce: () => 'nonce' });
  assert.deepEqual(result, {
    dry_run: true,
    readiness: {
      budget_epoch: 'epoch-1',
      baseline: 'base',
      candidate_sha: 'candidate',
      workflow_sha: 'workflow',
      ref: 'proof-branch',
      github_runs: [],
      consumed: 0,
      remaining: 3,
    },
  });
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(requested, false);
  assert.deepEqual(io.writes, []);
});
test('stdin GitHub runs support dry-run and reject empty or malformed input before dispatch', async () => {
  const validIo = memoryIo();
  const result = await dispatch(
    { ...options, githubRuns: '-', dryRun: true, token: undefined },
    { io: validIo, stdin: Readable.from(['{"workflow_runs":[]}']), request: async () => assert.fail('dry-run must not request') },
  );
  assert.deepEqual(result.readiness.github_runs, []);
  assert.deepEqual(validIo.writes, []);

  for (const [input, error] of [['', /GitHub runs JSON is empty/], ['not json', /GitHub runs JSON is invalid/]]) {
    const io = memoryIo();
    let requests = 0;
    await assert.rejects(
      () => dispatch({ ...options, githubRuns: '-' }, {
        io,
        stdin: Readable.from([input]),
        request: async () => { requests += 1; },
      }),
      error,
    );
    assert.equal(requests, 0);
    assert.deepEqual(io.writes, []);
  }
});

test('a dangling intent hard-stops rather than retrying', async () => {
  const ledger = `${JSON.stringify({ type: 'intent', state: 'intent', consumed: false, nonce: 'old', time: cutoff, budget_epoch: 'epoch-1', candidate_sha: 'candidate', workflow_sha: 'workflow', ref: 'proof-branch' })}\n`;
  const io = memoryIo(ledger);
  let requests = 0;
  await assert.rejects(() => dispatch(options, { io, request: async () => { requests += 1; } }), /explicit recovery review/);
  assert.equal(requests, 0);
});

test('request rejection records one recovery execution and blocks re-invocation', async () => {
  const io = memoryIo();
  let requests = 0;
  const request = async () => {
    requests += 1;
    throw new Error('connection reset');
  };
  await assert.rejects(() => dispatch(options, { io, nonce: () => 'nonce', request }), /OBSERVABILITY hard stop/);
  assert.match(io.ledger(), /"type":"intent".*"consumed":false/);
  assert.match(io.ledger(), /"type":"recovery".*"consumed":true/);
  assert.deepEqual(validateHistory(parseJsonLinesForTest(io.ledger()), [], cutoff, 'epoch-1'), { consumed: 1, remaining: 2 });
  await assert.rejects(() => dispatch(options, { io, nonce: () => 'other', request }), /explicit recovery review/);
  assert.equal(requests, 1);
});
test('accepted receipt failure records a complete recovery row', async () => {
  const io = memoryIo();
  io.writeFile = async () => { throw new Error('receipt write failed'); };
  await assert.rejects(() => dispatch(options, {
    io,
    nonce: () => 'nonce',
    request: async () => ({
      status: 200,
      text: async () => JSON.stringify({ workflow_run_id: 7, run_url: 'https://api/runs/7', html_url: 'https://github/runs/7', ref: 'proof-branch' }),
    }),
  }), /OBSERVABILITY hard stop/);
  const recovery = parseJsonLinesForTest(io.ledger()).at(-1);
  assert.deepEqual(
    Object.fromEntries(['budget_epoch', 'baseline', 'candidate_sha', 'workflow_sha', 'ref', 'hypothesis', 'execution_ordinal', 'remaining', 'nonce'].map((key) => [key, recovery[key]])),
    { budget_epoch: 'epoch-1', baseline: 'base', candidate_sha: 'candidate', workflow_sha: 'workflow', ref: 'proof-branch', hypothesis: 'single-cause', execution_ordinal: 1, remaining: 2, nonce: 'nonce' },
  );
});

function parseJsonLinesForTest(contents) {
  return contents.trim().split('\n').map((line) => JSON.parse(line));
}
