import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

export const WORKFLOW = 'mobile-share-proof.yml';
export const API_VERSION = '2026-03-10';
export const MAX_EXECUTIONS = 3;

export function parseArgs(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index].startsWith('--')) values.set(argv[index], argv[index + 1]);
  }
  return values;
}

export function parseJsonLines(contents) {
  if (!contents.trim()) return [];
  return contents.trimEnd().split(/\r?\n/).map((line, index) => {
    try { return JSON.parse(line); } catch { throw new Error(`Invalid ledger JSONL at line ${index + 1}.`); }
  });
}
export async function readGitHubRuns(source, io, stdin = process.stdin) {
  const contents = source === '-'
    ? await readStdin(stdin)
    : await io.readFile(source, 'utf8');
  if (!contents.trim()) throw new Error('GitHub runs JSON is empty.');
  try {
    return JSON.parse(contents);
  } catch {
    throw new Error('GitHub runs JSON is invalid.');
  }
}

async function readStdin(stdin) {
  stdin.setEncoding('utf8');
  let contents = '';
  for await (const chunk of stdin) contents += chunk;
  return contents;
}

export function validateHistory(rows, githubRuns, cutoff, budgetEpoch) {
  const cutoffTime = Date.parse(cutoff);
  if (!Number.isFinite(cutoffTime)) throw new Error('Historical cutoff must be an ISO timestamp.');
  const relevantRows = rows.filter((row) => Date.parse(row.time ?? row.timestamp ?? '') >= cutoffTime && row.budget_epoch === budgetEpoch);
  const currentRuns = githubRuns.filter((run) => Date.parse(run.created_at ?? '') >= cutoffTime && (run.budget_epoch === undefined || run.budget_epoch === budgetEpoch));
  const parents = new Map();
  const add = (key) => { if (!parents.has(key)) parents.set(key, key); };
  const root = (key) => {
    let parent = parents.get(key);
    while (parent !== parents.get(parent)) parent = parents.get(parent);
    let current = key;
    while (current !== parent) {
      const next = parents.get(current);
      parents.set(current, parent);
      current = next;
    }
    return parent;
  };
  const join = (left, right) => {
    add(left);
    add(right);
    parents.set(root(left), root(right));
  };
  const acceptedNonces = new Set();
  const acceptedRunIds = new Set();
  for (const [index, row] of relevantRows.entries()) {
    if (!((row.type === 'accepted' || row.type === 'recovery') && row.consumed === true)) continue;
    const keys = [];
    if (row.nonce) keys.push(`nonce:${row.nonce}`);
    if (row.workflow_run_id !== undefined && row.workflow_run_id !== null) keys.push(`run:${row.workflow_run_id}`);
    if (!keys.length) throw new Error(`BUDGET_VIOLATION: execution ledger row ${index + 1} lacks a nonce or workflow run ID.`);
    if (row.type === 'accepted' && row.nonce) {
      if (acceptedNonces.has(row.nonce)) throw new Error(`BUDGET_VIOLATION: duplicate accepted nonce ${row.nonce}.`);
      acceptedNonces.add(row.nonce);
    }
    if (row.type === 'accepted' && row.workflow_run_id !== undefined && row.workflow_run_id !== null) {
      if (acceptedRunIds.has(row.workflow_run_id)) throw new Error(`BUDGET_VIOLATION: duplicate accepted run ID ${row.workflow_run_id}.`);
      acceptedRunIds.add(row.workflow_run_id);
    }
    add(keys[0]);
    for (const key of keys.slice(1)) join(keys[0], key);
  }
  const githubRunIds = new Set();
  for (const run of currentRuns) {
    if (Number(run.run_attempt) !== 1) throw new Error(`BUDGET_VIOLATION: run ${run.id ?? 'unknown'} has run_attempt=${run.run_attempt}.`);
    if (run.id === undefined || run.id === null) throw new Error('BUDGET_VIOLATION: GitHub run lacks an ID.');
    if (githubRunIds.has(run.id)) throw new Error(`BUDGET_VIOLATION: duplicate GitHub run ID ${run.id}.`);
    githubRunIds.add(run.id);
    add(`run:${run.id}`);
  }
  const executions = new Set([...parents.keys()].map(root)).size;
  if (executions >= MAX_EXECUTIONS) throw new Error('Execution budget exhausted.');
  return { consumed: executions, remaining: MAX_EXECUTIONS - executions };
}

export function parseDispatchResponse(status, body, expectedRef) {
  if (status !== 200) throw new Error(`Dispatch endpoint returned HTTP ${status}.`);
  let value;
  try { value = JSON.parse(body); } catch { throw new Error('Dispatch response was not JSON.'); }
  if (!Number.isInteger(value.workflow_run_id) || !value.run_url || !value.html_url || value.ref !== expectedRef) throw new Error('Dispatch response identity is incomplete or mismatched.');
  return value;
}

export async function appendFsync(path, row, io = { mkdir, open }) {
  await io.mkdir(dirname(path), { recursive: true });
  const handle = await io.open(path, 'a');
  try { await handle.writeFile(`${JSON.stringify(row)}\n`); await handle.sync(); } finally { await handle.close(); }
}

export async function atomicReceipt(path, row, io = { mkdir, writeFile, rename, open }) {
  await io.mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${row.nonce}.tmp`;
  await io.writeFile(temporary, `${JSON.stringify(row)}\n`, { flag: 'wx' });
  const handle = await io.open(temporary, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
  await io.rename(temporary, path);
}

function requiresRecoveryReview(rows, options) {
  return rows.some((row) => (row.type === 'intent' || row.type === 'recovery')
    && row.candidate_sha === options.candidateSha
    && row.workflow_sha === options.workflowSha
    && row.ref === options.ref
    && (row.type === 'recovery'
      || !rows.some((terminal) => (terminal.type === 'accepted' || terminal.type === 'recovery') && terminal.nonce === row.nonce)));
}

function executionRow(type, intent, options, time, extra = {}) {
  return {
    type,
    state: type === 'accepted' ? 'accepted' : 'OBSERVABILITY',
    consumed: true,
    time,
    nonce: intent.nonce,
    budget_epoch: options.budgetEpoch,
    baseline: options.baseline,
    candidate_sha: options.candidateSha,
    workflow_sha: options.workflowSha,
    execution_ordinal: intent.execution_ordinal,
    remaining: intent.remaining,
    hypothesis: options.hypothesis,
    ref: options.ref,
    workflow: WORKFLOW,
    ...extra,
  };
}

export async function dispatch(options, dependencies = {}) {
  const io = dependencies.io ?? { readFile, mkdir, open, writeFile, rename };
  const request = dependencies.request ?? globalThis.fetch;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const nonce = dependencies.nonce ?? randomUUID;
  const rows = parseJsonLines(await io.readFile(options.ledger, 'utf8').catch((error) => error.code === 'ENOENT' ? '' : Promise.reject(error)));
  const githubRuns = await readGitHubRuns(options.githubRuns, io, dependencies.stdin);
  const suppliedRuns = githubRuns.workflow_runs ?? githubRuns;
  const budget = validateHistory(rows, suppliedRuns, options.cutoff, options.budgetEpoch);
  if (requiresRecoveryReview(rows, options)) throw new Error('Existing dispatch intent requires explicit recovery review.');
  if (options.dryRun) {
    return {
      dry_run: true,
      readiness: {
        budget_epoch: options.budgetEpoch,
        baseline: options.baseline,
        candidate_sha: options.candidateSha,
        workflow_sha: options.workflowSha,
        ref: options.ref,
        github_runs: suppliedRuns,
        consumed: budget.consumed,
        remaining: budget.remaining,
      },
    };
  }
  const token = options.token;
  if (!token) throw new Error('Missing GitHub token environment variable.');
  const intent = {
    type: 'intent',
    state: 'intent',
    consumed: false,
    time: now(),
    nonce: nonce(),
    budget_epoch: options.budgetEpoch,
    baseline: options.baseline,
    candidate_sha: options.candidateSha,
    workflow_sha: options.workflowSha,
    execution_ordinal: budget.consumed + 1,
    remaining: budget.remaining - 1,
    hypothesis: options.hypothesis,
    ref: options.ref,
    workflow: WORKFLOW,
  };
  await appendFsync(options.ledger, intent, io);
  const endpoint = `https://api.github.com/repos/${options.owner}/${options.repo}/actions/workflows/${WORKFLOW}/dispatches`;
  try {
    const response = await request(endpoint, { method: 'POST', headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${token}`, 'X-GitHub-Api-Version': API_VERSION, 'Content-Type': 'application/json' }, body: JSON.stringify({ ref: options.ref }) });
    const body = await response.text();
    const identity = parseDispatchResponse(response.status, body, options.ref);
    const accepted = executionRow('accepted', intent, options, now(), {
      response_sha256: createHash('sha256').update(body).digest('hex'),
      workflow_run_id: identity.workflow_run_id,
      run_url: identity.run_url,
      html_url: identity.html_url,
      expected_run_attempt: 1,
    });
    await atomicReceipt(`${options.ledger}.${intent.nonce}.accepted`, accepted, io);
    await appendFsync(options.ledger, accepted, io);
    return accepted;
  } catch (error) {
    const recovery = executionRow('recovery', intent, options, now(), { error: error.message, run_identity: 'unknown' });
    try { await appendFsync(options.ledger, recovery, io); } catch { /* dispatch may be consumed; preserve the hard stop */ }
    throw new Error(`OBSERVABILITY hard stop after dispatch attempt: ${error.message}`);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const required = ['--owner', '--repo', '--ref', '--ledger', '--github-runs', '--cutoff', '--budget-epoch', '--baseline', '--candidate-sha', '--workflow-sha', '--hypothesis'];
  for (const name of required) if (!args.get(name)) throw new Error(`Missing ${name}.`);
  const options = { owner: args.get('--owner'), repo: args.get('--repo'), ref: args.get('--ref'), ledger: args.get('--ledger'), githubRuns: args.get('--github-runs'), cutoff: args.get('--cutoff'), budgetEpoch: args.get('--budget-epoch'), baseline: args.get('--baseline'), candidateSha: args.get('--candidate-sha'), workflowSha: args.get('--workflow-sha'), hypothesis: args.get('--hypothesis'), dryRun: args.has('--dry-run'), token: process.env.GITHUB_TOKEN };
  const result = await dispatch(options);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (import.meta.url === new URL(process.argv[1], 'file:').href) main().catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
