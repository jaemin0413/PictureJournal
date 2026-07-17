import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';

function argument(name) {
  const index = process.argv.indexOf(name);
  if (index === -1 || !process.argv[index + 1]) throw new Error(`Missing required argument: ${name}`);
  return process.argv[index + 1];
}

const output = argument('--output');
const closureOutput = argument('--closure-output');
const workflow = argument('--workflow');
const outcome = argument('--outcome');
const artifactDirectory = resolve(dirname(output));
const requiredStages = [
  'dispatch-attempt',
  'workflow-audit',
  'dependency-install',
  'ios-project-structure',
  'pod-install',
  'simulator-build',
  'native-identities',
  'focused-checks',
  'simulator-registration',
  'fixture-xcuitest',
  'native-key-final',
];

function validObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

function passedStageManifest(value) {
  if (!validObject(value) || value.schemaVersion !== 2 || !Array.isArray(value.stages)
    || !['workflow', 'runId', 'runAttempt', 'sha'].every((key) => typeof value[key] === 'string' && value[key])) {
    return 'invalid stage manifest schema or provenance';
  }
  const missing = requiredStages.filter((stage) =>
    !value.stages.some((entry) => validObject(entry) && entry.stage === stage && entry.status === 'passed'));
  return missing.length ? `required stages not passed: ${missing.join(', ')}` : null;
}

function passedWorkflowAudit(value) {
  if (!validObject(value) || value.passed !== true || !Array.isArray(value.failures)
    || value.failures.length !== 0 || !validObject(value.semantics)) {
    return 'workflow audit did not report a passed, schema-valid receipt';
  }
  return null;
}

function passedVerifier(value) {
  if (!validObject(value) || value.status !== 'passed' || !Array.isArray(value.errors)
    || value.errors.length !== 0 || !/^[a-f0-9]{64}$/.test(value.semanticFingerprint ?? '')) {
    return 'project verifier did not report a passed semantic fingerprint';
  }
  return null;
}

function validTargetGraph(value) {
  return validObject(value) && validObject(value.workspace) && Array.isArray(value.workspace.schemes)
    && value.workspace.schemes.includes('PictureJournal')
    ? null
    : 'target graph is missing the PictureJournal workspace scheme';
}

function passedBundleGates(value) {
  return validObject(value) && value.status === 'passed'
    && value.hostBundleId === 'com.picturejournal.mobile'
    && value.extensionBundleId === 'com.picturejournal.mobile.share-extension'
    ? null
    : 'bundle gates did not report the required host and extension identities';
}

function validCheckpoints(value) {
  if (!validObject(value) || value.schema !== 'picturejournal.native-share-watch.v1'
    || value.chain_count !== 2 || value.recognized_event_count !== 8
    || !Array.isArray(value.receipts) || value.receipts.length !== 4) {
    return 'watcher receipt is missing the ordered event-correlation or four native-key queries';
  }
  const expectedReceipts = [
    { checkpoint: 'cold', phase: 'reset-invoked', count: 1 },
    { checkpoint: 'cold-relaunch', phase: 'checkpoint', count: 1 },
    { checkpoint: 'warm', phase: 'reset-invoked', count: 2 },
    { checkpoint: 'warm-relaunch', phase: 'checkpoint', count: 2 },
  ];
  const redacted = /^sha256:[a-f0-9]{16}$/;
  const nativeKeyQuerySource = 'xcrun simctl spawn <UDID> defaults read group.com.picturejournal.mobile picturejournalShareKey';
  return value.receipts.every((receipt, index) => {
    const expected = expectedReceipts[index];
    return validObject(receipt)
      && receipt.checkpoint === expected.checkpoint
      && receipt.phase === expected.phase
      && receipt.count === expected.count
      && redacted.test(receipt.event_id ?? '')
      && redacted.test(receipt.delivery_hash ?? '')
      && redacted.test(receipt.queue_generation ?? '')
      && validObject(receipt.native_key_query)
      && receipt.native_key_query.source === nativeKeyQuerySource
      && receipt.native_key_query.absent === true
      && receipt.native_key_query.exit_status === 1
      && /^[a-f0-9]{64}$/.test(receipt.native_key_query.stdout_sha256 ?? '')
      && /^[a-f0-9]{64}$/.test(receipt.native_key_query.stderr_sha256 ?? '');
  })
    ? null
    : 'watcher receipt has invalid event-correlation or native-key query evidence';
}

function validFinalKey(value) {
  return validObject(value) && value.key === 'picturejournalShareKey' && value.status === 'absent'
    && value.exitStatus === 1 && /^[a-f0-9]{64}$/.test(value.stdoutSha256 ?? '')
    && /^[a-f0-9]{64}$/.test(value.stderrSha256 ?? '')
    ? null
    : 'final native-key receipt does not prove key absence';
}

function validXcresultSummary(value) {
  return validObject(value) && Object.keys(value).length > 0
    ? null
    : 'xcresult summary is not a nonempty JSON object';
}

const expected = [
  { name: 'stage-manifest', basename: 'ios-share-stage-manifest.json', semantics: 'stage-ledger', validate: passedStageManifest },
  { name: 'workflow-audit', basename: 'ios-share-workflow-audit.json', semantics: 'workflow-contract-audit', validate: passedWorkflowAudit },
  { name: 'prebuild-first-verifier', basename: 'ios-prebuild-first-verifier.json', semantics: 'project-semantic-fingerprint', validate: passedVerifier },
  { name: 'prebuild-second-verifier', basename: 'ios-prebuild-second-verifier.json', semantics: 'project-semantic-fingerprint', validate: passedVerifier },
  { name: 'prebuild-first-project', basename: 'ios-prebuild-first-project.zip', semantics: 'generated-project-snapshot' },
  { name: 'prebuild-second-project', basename: 'ios-prebuild-second-project.zip', semantics: 'generated-project-snapshot' },
  { name: 'pod-install-log', basename: 'ios-pod-install.log', semantics: 'pod-install' },
  { name: 'build-log', basename: 'ios-simulator-build.log', semantics: 'simulator-build' },
  { name: 'build-settings-host', basename: 'ios-host-build-settings.txt', semantics: 'target-build-settings' },
  { name: 'build-settings-extension', basename: 'ios-extension-build-settings.txt', semantics: 'target-build-settings' },
  { name: 'target-graph', basename: 'ios-target-graph.json', semantics: 'target-graph', validate: validTargetGraph },
  { name: 'bundle-gates', basename: 'ios-bundle-gates.json', semantics: 'bundle-and-signature-gates', validate: passedBundleGates },
  { name: 'pluginkit', basename: 'ios-pluginkit-registration.txt', semantics: 'pluginkit-registration' },
  { name: 'checkpoints', basename: 'ios-native-key-checkpoints.json', semantics: 'event-correlation-and-four-native-key-query-receipts', validate: validCheckpoints },
  { name: 'final-key', basename: 'ios-native-key-final.json', semantics: 'whole-domain-key-absence', validate: validFinalKey },
  { name: 'xcresult-summary', basename: 'ios-share-fixture-summary.json', semantics: 'xcuitest-summary', validate: validXcresultSummary },
  { name: 'xcresult', basename: 'ios-share-fixture.xcresult', semantics: 'xcuitest-result-bundle', directory: true },
];

async function describeDirectory(source) {
  const digest = createHash('sha256');
  let size = 0;
  let files = 0;

  async function visit(path) {
    const entries = await readdir(path, { withFileTypes: true });
    for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
      const child = join(path, entry.name);
      const info = await stat(child);
      const name = relative(source, child);
      if (info.isDirectory()) {
        digest.update(`directory\0${name}\0`);
        await visit(child);
      } else if (info.isFile()) {
        const bytes = await readFile(child);
        digest.update(`file\0${name}\0`);
        digest.update(bytes);
        size += bytes.length;
        files += 1;
      } else {
        throw new Error(`unsupported directory entry: ${name}`);
      }
    }
  }

  await visit(source);
  return { size, files, sha256: digest.digest('hex') };
}

async function describe(entry) {
  const source = join(artifactDirectory, entry.basename);
  try {
    const info = await stat(source);
    if (entry.directory) {
      if (!info.isDirectory()) return { ...entry, source, status: 'invalid', reason: 'expected directory' };
      const directory = await describeDirectory(source);
      if (directory.files === 0 || directory.size === 0) {
        return { ...entry, source, ...directory, status: 'not-produced', reason: 'empty artifact directory' };
      }
      return { ...entry, source, ...directory, status: 'present', reason: 'produced directory' };
    }
    if (!info.isFile()) return { ...entry, source, status: 'invalid', reason: 'expected regular file' };
    if (info.size === 0) return { ...entry, source, size: 0, status: 'not-produced', reason: 'empty artifact' };

    const bytes = await readFile(source);
    const receipt = { ...entry, source, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    if (entry.validate) {
      let document;
      try {
        document = JSON.parse(bytes.toString('utf8'));
      } catch {
        return { ...receipt, status: 'invalid', reason: 'invalid JSON' };
      }
      const reason = entry.validate(document);
      if (reason) return { ...receipt, status: 'invalid', reason };
    }
    return { ...receipt, status: 'present', reason: 'produced and valid' };
  } catch (error) {
    if (error.code === 'ENOENT') {
      return { ...entry, source, status: 'not-produced', reason: 'producer did not complete' };
    }
    return { ...entry, source, status: 'invalid', reason: `unreadable artifact: ${error.message}` };
  }
}

const artifacts = await Promise.all(expected.map(describe));
const stageManifest = artifacts.find((artifact) => artifact.name === 'stage-manifest');
const stageManifestValid = stageManifest?.status === 'present';
const workflowBytes = await readFile(workflow);
const allArtifactsValid = artifacts.every((artifact) => artifact.status === 'present');
const receipt = {
  schemaVersion: 1,
  artifactDirectory,
  workflow: process.env.GITHUB_WORKFLOW ?? 'mobile-share-proof',
  runId: process.env.GITHUB_RUN_ID ?? 'local',
  runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? 'local',
  sha: process.env.GITHUB_SHA ?? 'local',
  workflowSha256: createHash('sha256').update(workflowBytes).digest('hex'),
  outcome,
  artifacts,
  stageManifestValid,
};
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify(receipt, null, 2)}\n`);
const closed = outcome === 'success' && stageManifestValid && allArtifactsValid;
const closure = {
  schemaVersion: 1,
  status: closed ? 'closed' : 'failed',
  manifest: output,
  workflow: receipt.workflow,
  runId: receipt.runId,
  runAttempt: receipt.runAttempt,
  sha: receipt.sha,
  workflowSha256: receipt.workflowSha256,
  reason: closed
    ? 'all required stages and artifacts are present, schema-valid, and nonempty'
    : 'job failed or required stage evidence was not produced or was invalid',
};
await writeFile(closureOutput, `${JSON.stringify(closure, null, 2)}\n`);
if (!closed) process.exitCode = 1;
