import { createHash } from 'node:crypto';
import { access, readFile, writeFile } from 'node:fs/promises';

const workflowPath = new URL('../../../.github/workflows/mobile-share-proof.yml', import.meta.url);
const outputIndex = process.argv.indexOf('--output');
const output = outputIndex === -1 ? null : process.argv[outputIndex + 1];
if (outputIndex !== -1 && !output) throw new Error('Missing --output value.');

const workflow = await readFile(workflowPath, 'utf8');
const lines = workflow.split(/\r?\n/);
const failures = [];
const packageJson = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
const forbidden = [
  /\bandroid\b/i, /\bgradle\b/i, /\bjava\b/i, /android-emulator-runner/i,
  /reactivecircus/i, /\badb\b/i, /npm test\b/i, /pull_request:/, /push:/,
];
const prebuild = 'npx --no-install expo prebuild --platform ios --no-install --clean';
const watcher = 'node scripts/watch-native-share-checkpoints.mjs --metro-log ../../artifacts/mobile/ios-metro.log --udid "$SIMULATOR_UDID" --output ../../artifacts/mobile/ios-native-key-checkpoints.json --sentinel "$SENTINEL" --timeout-ms 180000 &';
const finalKeyQuery = 'xcrun simctl spawn "$SIMULATOR_UDID" defaults read group.com.picturejournal.mobile picturejournalShareKey > "$STDOUT" 2> "$STDERR"';
const auditInvocation = 'node scripts/audit-ios-share-workflow.mjs --output ../../artifacts/mobile/ios-share-workflow-audit.json';
const expectedArtifacts = [
  'ios-share-stage-manifest.json', 'ios-share-workflow-audit.json',
  'ios-native-key-checkpoints.json', 'ios-native-key-final.json',
  'ios-share-fixture.xcresult',
];

function fail(message) { failures.push(message); }
function extractCommands(sourceLines) {
  const extracted = [];
  for (let index = 0; index < sourceLines.length; index += 1) {
    const block = sourceLines[index].match(/^ {8}run:\s*\|\s*$/);
    const inline = sourceLines[index].match(/^ {8}run:\s+(.+?)\s*$/);
    if (inline) extracted.push(inline[1]);
    if (!block) continue;
    for (index += 1; index < sourceLines.length; index += 1) {
      const line = sourceLines[index];
      if (line === '') continue;
      if (!line.startsWith('          ')) {
        index -= 1;
        break;
      }
      extracted.push(line.slice(10));
    }
  }
  return extracted;
}
function extractJobNames(sourceLines) {
  const jobsIndex = sourceLines.findIndex((line) => line === 'jobs:');
  if (jobsIndex === -1) return [];
  const names = [];
  for (let index = jobsIndex + 1; index < sourceLines.length; index += 1) {
    const line = sourceLines[index];
    if (line && !line.startsWith(' ')) break;
    const match = line.match(/^ {2}([a-z][\w-]*):\s*$/);
    if (match) names.push(match[1]);
  }
  return names;
}

const commands = extractCommands(lines);
const jobNames = extractJobNames(lines);
const workflowScripts = [...workflow.matchAll(/node scripts\/([\w.-]+\.mjs)/g)].map((match) => match[1]);
const countCommand = (command) => commands.filter((line) => line === command).length;
const hasCommandPrefix = (prefix) => commands.some((line) => line.startsWith(prefix));

if (!/^on:\n  workflow_dispatch:\s*$/m.test(workflow) || (workflow.match(/^  workflow_dispatch:\s*$/gm) ?? []).length !== 1) fail('Workflow trigger must be workflow_dispatch only.');
if (jobNames.length !== 1) fail('Workflow must define exactly one top-level job below jobs:.');
if (!/^    runs-on: macos-14\s*$/m.test(workflow)) fail('Workflow must use macos-14.');
if (countCommand('test "$GITHUB_RUN_ATTEMPT" = \'1\'') !== 1) fail('Workflow must use one exact first-attempt gate.');
for (const pattern of forbidden) if (pattern.test(workflow)) fail(`Forbidden workflow edge: ${pattern}`);
for (const command of ['npm ci --ignore-scripts', './node_modules/.bin/patch-package', 'npm run typecheck -- --pretty false', 'npm run ios-share:test', auditInvocation]) {
  if (countCommand(command) !== 1) fail(`Expected exactly one command: ${command}`);
}
if (countCommand(prebuild) !== 2) fail(`Expected exactly two clean prebuild commands: ${prebuild}`);
if (countCommand('pod install --project-directory=ios | tee ../../artifacts/mobile/ios-pod-install.log') !== 1) fail('pod install must use the supported iOS project-directory argv and retain its log.');
for (const prefix of ['xcodebuild -workspace ', 'xcodebuild -project ios-share-fixture/ShareFixture.xcodeproj ', 'xcrun simctl ']) {
  if (!hasCommandPrefix(prefix)) fail(`Missing pinned third-party argv: ${prefix}`);
}
if (countCommand(watcher) !== 1) fail('Missing exact watcher argv with sentinel.');
if (countCommand(finalKeyQuery) !== 1) fail('Missing exact final external native-key query.');
const metroCommand = 'npx --no-install expo start --dev-client --localhost --port 8081 > ../../artifacts/mobile/ios-metro.log 2>&1 &';
if (countCommand(metroCommand) !== 1) fail('Metro must use the exact no-install argv.');
const launchIndex = commands.indexOf('xcrun simctl launch "$SIMULATOR_UDID" com.picturejournal.mobile');
const bundleWaitIndex = commands.indexOf("for attempt in {1..60}; do grep -F 'iOS Bundled' ../../artifacts/mobile/ios-metro.log && break || sleep 1; done");
const bundleGateIndex = commands.indexOf("grep -F 'iOS Bundled' ../../artifacts/mobile/ios-metro.log");
const terminateIndex = commands.indexOf('xcrun simctl terminate "$SIMULATOR_UDID" com.picturejournal.mobile || true');
if (!(launchIndex >= 0 && launchIndex < bundleWaitIndex && bundleWaitIndex < bundleGateIndex && bundleGateIndex < terminateIndex)) fail('Host must remain launched until the bounded iOS Bundled gate passes.');
if (countCommand('node scripts/write-ios-share-manifest.mjs --output ../../artifacts/mobile/ios-share-stage-manifest.json --stage workflow-audit --status passed') !== 1) fail('Workflow audit must record its stage manifest entry.');
if (!commands.some((line) => line.startsWith('node scripts/finalize-ios-share-artifacts.mjs --output ../../artifacts/mobile/ios-share-evidence-manifest.json --closure-output ../../artifacts/mobile/ios-share-closure-receipt.json --workflow ../../.github/workflows/mobile-share-proof.yml --outcome '))) fail('Missing evidence finalizer command.');
if (!/uses: actions\/upload-artifact@v4\n        if: always\(\)\n        with:\n          name: ios-share-proof\n          if-no-files-found: error\n          path: artifacts\/mobile/.test(workflow)) fail('Artifact upload must always fail on missing evidence.');
for (const artifact of expectedArtifacts) if (!workflow.includes(artifact)) fail(`Artifact upload missing: ${artifact}`);
for (const boundary of ['actions/checkout@v4', 'actions/setup-node@v4', 'actions/upload-artifact@v4']) {
  if (!workflow.includes(`uses: ${boundary}`)) fail(`Missing pinned third-party boundary: ${boundary}`);
}

const closure = [];
const sources = new Map();
const visited = new Set();
async function inspectRepoOwned(path, label) {
  if (visited.has(path.href)) return;
  visited.add(path.href);
  try {
    await access(path);
    const source = await readFile(path, 'utf8');
    sources.set(label, source);
    const imports = [...source.matchAll(/(?:from\s+|import\s*\()(['"])([^'"]+)\1/g)].map((match) => match[2]);
    const forbiddenImport = imports.find((item) => /android|gradle|java|adb/i.test(item));
    if (forbiddenImport) fail(`Forbidden repo-owned import in ${label}: ${forbiddenImport}`);
    closure.push({ script: label, imports, boundary: 'node_modules atomic' });
    for (const item of imports) {
      if (!item.startsWith('.')) continue;
      const child = new URL(item, path);
      await inspectRepoOwned(child, new URL(item, new URL('./', path)).pathname.replace(/^.*\/apps\/mobile\//, ''));
    }
  } catch (error) {
    fail(`Missing or unreadable repo-owned script ${label}: ${error.message}`);
  }
}
for (const name of [...new Set(workflowScripts)]) await inspectRepoOwned(new URL(`./${name}`, import.meta.url), `scripts/${name}`);
const watcherSource = sources.get('scripts/watch-native-share-checkpoints.mjs') ?? '';
for (const checkpoint of ['cold', 'cold-relaunch', 'warm', 'warm-relaunch']) {
  if (!watcherSource.includes(`checkpoint: '${checkpoint}'`)) fail(`Watcher script contract missing checkpoint: ${checkpoint}`);
}
for (const [name, value] of Object.entries(packageJson.scripts ?? {})) {
  if (workflow.includes(`npm run ${name}`) && /android|gradle|java|adb/i.test(value)) fail(`Forbidden package-script edge: ${name}`);
}

const semantics = {
  trigger_count: (workflow.match(/^  workflow_dispatch:\s*$/gm) ?? []).length,
  job_count: jobNames.length,
  first_attempt_gate: countCommand('test "$GITHUB_RUN_ATTEMPT" = \'1\''),
  lifecycle: {
    npm_ci_ignore_scripts: countCommand('npm ci --ignore-scripts'),
    explicit_patch_package: countCommand('./node_modules/.bin/patch-package'),
    workflow_audit: countCommand(auditInvocation),
    focused_typecheck: countCommand('npm run typecheck -- --pretty false'),
    focused_ios_share_test: countCommand('npm run ios-share:test'),
  },
  prebuild_count: countCommand(prebuild),
  watcher: { exact_argv: countCommand(watcher), checkpoints: ['cold', 'cold-relaunch', 'warm', 'warm-relaunch'], final_query: countCommand(finalKeyQuery) },
  artifact_finalizer: { always: /if: always\(\)/.test(workflow), artifacts: expectedArtifacts },
  repo_owned_closure: closure,
};
const receipt = {
  workflow: '.github/workflows/mobile-share-proof.yml',
  passed: failures.length === 0,
  failures,
  semanticFingerprint: createHash('sha256').update(JSON.stringify(semantics)).digest('hex'),
  semantics,
};
if (output) await writeFile(output, `${JSON.stringify(receipt, null, 2)}\n`);
if (failures.length) throw new Error(failures.join('\n'));
