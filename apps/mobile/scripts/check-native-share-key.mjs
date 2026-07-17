import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

function argument(name) {
  const index = process.argv.indexOf(name);
  if (index === -1 || !process.argv[index + 1]) throw new Error(`Missing required argument: ${name}`);
  return process.argv[index + 1];
}

const stdout = await readFile(argument('--stdout'));
const stderr = await readFile(argument('--stderr'));
const output = argument('--output');
const key = argument('--key');
const exitStatus = Number(argument('--exit-status'));
if (!Number.isInteger(exitStatus) || exitStatus < 0) throw new Error('Exit status must be a non-negative integer.');

const combined = Buffer.concat([stdout, stderr]);
const keyPresent = combined.includes(Buffer.from(key));
const absent = exitStatus === 1 && /does not exist/i.test(combined.toString('utf8')) && !keyPresent;
const receipt = {
  key,
  status: absent ? 'absent' : keyPresent ? 'present' : 'abnormal',
  exitStatus,
  stdoutSha256: createHash('sha256').update(stdout).digest('hex'),
  stderrSha256: createHash('sha256').update(stderr).digest('hex'),
};
await writeFile(output, `${JSON.stringify(receipt, null, 2)}\n`);
if (!absent) throw new Error(`Native key query was ${receipt.status}: ${key}`);
