import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

function argument(name) {
  const index = process.argv.indexOf(name);
  if (index === -1 || !process.argv[index + 1]) {
    throw new Error(`Missing required argument: ${name}`);
  }
  return process.argv[index + 1];
}

const output = argument('--output');
const stage = argument('--stage');
const status = argument('--status');

if (!/^[a-z0-9][a-z0-9-]*$/.test(stage)) {
  throw new Error('Stage must be lowercase kebab-case.');
}
if (!['passed', 'failed', 'skipped'].includes(status)) {
  throw new Error('Status must be passed, failed, or skipped.');
}

const existing = await readFile(output, 'utf8').then(JSON.parse).catch((error) => {
  if (error.code === 'ENOENT') return {
    schemaVersion: 2,
    workflow: process.env.GITHUB_WORKFLOW ?? 'local',
    runId: process.env.GITHUB_RUN_ID ?? 'local',
    runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? 'local',
    sha: process.env.GITHUB_SHA ?? 'local',
    stages: [],
  };
  throw error;
});

if (!Array.isArray(existing.stages)) {
  throw new Error('Manifest stages must be an array.');
if (existing.schemaVersion !== 2 || !existing.workflow || !existing.runId || !existing.runAttempt || !existing.sha) {
  throw new Error('Manifest provenance is missing or invalid.');
}
}
if (existing.stages.some((entry) => entry.stage === stage)) {
  throw new Error(`Manifest already contains stage: ${stage}`);
}

const entry = { stage, status };
const presentIndex = process.argv.indexOf('--present');
const hashIndex = process.argv.indexOf('--sha256');
if (presentIndex !== -1) entry.present = process.argv[presentIndex + 1] === 'true';
if (hashIndex !== -1) entry.sha256 = process.argv[hashIndex + 1];

existing.stages.push(entry);
const serialized = `${JSON.stringify(existing, null, 2)}\n`;
await mkdir(dirname(output), { recursive: true });
await writeFile(output, serialized);
console.log(createHash('sha256').update(serialized).digest('hex'));
