import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  EXTENSION_BUNDLE_ID,
  EXTENSION_TARGET,
  applyIosShareExtensionConfig,
  inspectIosShareProject,
} from '../scripts/verify-ios-share-extension.mjs';

const config = () => ({ expo: { extra: { eas: { build: { experimental: { ios: { appExtensions: [{
  targetName: EXTENSION_TARGET,
  bundleIdentifier: EXTENSION_BUNDLE_ID,
}] } } } } } } });

const require = createRequire(import.meta.url);
const { withShareExtensionXcodeTarget } = require('expo-share-intent/plugin/build/ios/withIosShareExtensionXcodeTarget.js');
function project({ productType = 'com.apple.product-type.app-extension', proxyTarget = 'EXT', destination = 13, includeDependency = true } = {}) {
  return {
    PBXProject: { PROJECT: { targets: ['HOST', 'EXT'] } },
    PBXNativeTarget: {
      HOST: { name: 'PictureJournal', buildPhases: ['COPY'], dependencies: includeDependency ? ['DEPENDENCY'] : [] },
      EXT: { name: EXTENSION_TARGET, productName: EXTENSION_TARGET, productType, productReference: 'PRODUCT', buildConfigurationList: 'CONFIG_LIST' },
    },
    PBXFileReference: { PRODUCT: { explicitFileType: 'wrapper.app-extension', path: `${EXTENSION_TARGET}.appex` } },
    PBXBuildFile: { BUILD_FILE: { fileRef: 'PRODUCT' } },
    PBXCopyFilesBuildPhase: { COPY: { dstSubfolderSpec: destination, files: ['BUILD_FILE'] } },
    PBXTargetDependency: { DEPENDENCY: { target: 'EXT', targetProxy: 'PROXY' } },
    PBXContainerItemProxy: { PROXY: { containerPortal: 'PROJECT', remoteGlobalIDString: proxyTarget, proxyType: 1 } },
    XCConfigurationList: { CONFIG_LIST: { buildConfigurations: ['DEBUG', 'RELEASE'] } },
    XCBuildConfiguration: {
      DEBUG: { buildSettings: { PRODUCT_NAME: `"${EXTENSION_TARGET}"`, PRODUCT_BUNDLE_IDENTIFIER: `"${EXTENSION_BUNDLE_ID}"` } },
      RELEASE: { buildSettings: { PRODUCT_NAME: `"${EXTENSION_TARGET}"`, PRODUCT_BUNDLE_IDENTIFIER: `"${EXTENSION_BUNDLE_ID}"` } },
    },
  };
}

const inspect = (objects, appConfig = config()) => inspectIosShareProject(objects, appConfig, { rootObject: 'PROJECT' });
const errorsFor = (options, appConfig = config()) => inspect(project(options), appConfig).errors.join('\n');
const addedPatchSource = () => readFileSync(new URL('../patches/expo-share-intent+2.7.0.patch', import.meta.url), 'utf8')
  .split('\n')
  .filter((line) => line.startsWith('+') && !line.startsWith('+++'))
  .map((line) => line.slice(1))
  .join('\n');

function reidentify(objects) {
  const replacements = ['PROJECT', 'CONFIG_LIST', 'BUILD_FILE', 'DEPENDENCY', 'PRODUCT', 'RELEASE', 'DEBUG', 'PROXY', 'COPY', 'HOST', 'EXT'];
  return replacements.reduce(
    (serialized, value) => serialized.replaceAll(`"${value}"`, `"${value}_OTHER"`),
    JSON.stringify(objects),
  );
}
function existingProjectMod(objects) {
  return {
    hash: { project: { rootObject: 'PROJECT', objects } },
    pbxTargetByName: (name) => Object.values(objects.PBXNativeTarget)
      .find((target) => target.name === name) ?? null,
  };
}

function minimalProjectMod({ omitDependencySections = false } = {}) {
  const objects = {
    PBXProject: { PROJECT: { targets: ['HOST'] } },
    PBXNativeTarget: { HOST: { name: 'PictureJournal', buildPhases: ['COPY'], dependencies: [] } },
    PBXFileReference: {},
    PBXBuildFile: {},
    PBXCopyFilesBuildPhase: { COPY: { dstSubfolderSpec: 13, files: [] } },
    PBXTargetDependency: {},
    PBXContainerItemProxy: {},
    XCConfigurationList: {},
    XCBuildConfiguration: {},
    PBXGroup: {},
    PBXSourcesBuildPhase: {},
    PBXResourcesBuildPhase: {},
  };
  if (omitDependencySections) {
    delete objects.PBXTargetDependency;
    delete objects.PBXContainerItemProxy;
  }
  const calls = { addTarget: 0, addTargetDependency: 0, addBuildPhase: 0, pbxCreateGroup: 0, addFile: 0, addSourceFile: 0, addResourceFile: 0 };
  const modResults = {
    ...existingProjectMod(objects),
    addTarget: (name, type, extensionName) => {
      calls.addTarget += 1;
      assert.equal(name, EXTENSION_TARGET);
      assert.equal(type, 'app_extension');
      assert.equal(extensionName, 'PictureJournal');
      objects.PBXNativeTarget.EXT = {
        name,
        productName: name,
        productType: 'com.apple.product-type.app-extension',
        productReference: 'PRODUCT',
        buildConfigurationList: 'CONFIG_LIST',
      };
      objects.PBXProject.PROJECT.targets.push('EXT');
      objects.PBXFileReference.PRODUCT = { explicitFileType: 'wrapper.app-extension', path: `${name}.appex` };
      objects.XCConfigurationList.CONFIG_LIST = { buildConfigurations: ['DEBUG', 'RELEASE'] };
      for (const uuid of ['DEBUG', 'RELEASE']) {
        objects.XCBuildConfiguration[uuid] = {
          buildSettings: { PRODUCT_NAME: `"${name}"`, PRODUCT_BUNDLE_IDENTIFIER: `"${EXTENSION_BUNDLE_ID}"` },
        };
      }
      objects.PBXBuildFile.BUILD_FILE = { fileRef: 'PRODUCT' };
      objects.PBXCopyFilesBuildPhase.COPY.files.push('BUILD_FILE');
      return { ...objects.PBXNativeTarget.EXT };
    },
    addTargetDependency: (targetUuid, dependencyTargets) => {
      calls.addTargetDependency += 1;
      assert.equal(targetUuid, 'HOST');
      assert.deepEqual(dependencyTargets, ['EXT']);
      objects.PBXContainerItemProxy.PROXY = { containerPortal: 'PROJECT', remoteGlobalIDString: 'EXT', proxyType: 1 };
      objects.PBXTargetDependency.DEPENDENCY = { target: 'EXT', targetProxy: 'PROXY' };
      objects.PBXNativeTarget.HOST.dependencies.push('DEPENDENCY');
    },
    addBuildPhase: (files, isa, name, targetUuid) => {
      calls.addBuildPhase += 1;
      const uuid = isa === 'PBXSourcesBuildPhase' ? 'SOURCES' : 'RESOURCES';
      objects[isa][uuid] = { files, name };
      objects.PBXNativeTarget[targetUuid].buildPhases ??= [];
      objects.PBXNativeTarget[targetUuid].buildPhases.push(uuid);
    },
    pbxCreateGroup: (name, path) => {
      calls.pbxCreateGroup += 1;
      objects.PBXGroup.GROUP = { children: [], name, path };
      return 'GROUP';
    },
    addFile: (path, groupUuid) => {
      calls.addFile += 1;
      objects.PBXFileReference.INFO = { path };
      objects.PBXGroup[groupUuid].children.push('INFO');
    },
    addSourceFile: (path, { target }, groupUuid) => {
      calls.addSourceFile += 1;
      objects.PBXFileReference.SOURCE_FILE = { path };
      objects.PBXBuildFile.SOURCE_BUILD_FILE = { fileRef: 'SOURCE_FILE' };
      objects.PBXSourcesBuildPhase.SOURCES.files.push('SOURCE_BUILD_FILE');
      objects.PBXGroup[groupUuid].children.push('SOURCE_FILE');
      assert.equal(target, 'EXT');
    },
    addResourceFile: (path, { target }, groupUuid) => {
      calls.addResourceFile += 1;
      const uuid = `RESOURCE_${calls.addResourceFile}`;
      objects.PBXFileReference[uuid] = { path };
      objects.PBXBuildFile[`${uuid}_BUILD_FILE`] = { fileRef: uuid };
      objects.PBXResourcesBuildPhase.RESOURCES.files.push(`${uuid}_BUILD_FILE`);
      objects.PBXGroup[groupUuid].children.push(uuid);
      assert.equal(target, 'EXT');
    },
    pbxXCBuildConfigurationSection: () => objects.XCBuildConfiguration,
  };
  return { objects, calls, modResults };
}

async function runInstalledXcodeMod(modResults) {
  const platformProjectRoot = mkdtempSync(join(tmpdir(), 'picture-journal-share-extension-'));
  const pluginConfig = withShareExtensionXcodeTarget({
    name: 'Picture Journal',
    scheme: 'PictureJournal',
    version: '1.0.0',
    ios: { bundleIdentifier: 'com.picturejournal.mobile', buildNumber: '1' },
    modRequest: { platformProjectRoot },
    modResults,
  }, { iosShareExtensionName: 'Picture Journal' });
  try {
    await pluginConfig.mods.ios.xcodeproj({
      ...pluginConfig,
      modRequest: { platformProjectRoot },
      modResults,
    });
    return {
      infoPlist: readFileSync(join(platformProjectRoot, 'PictureJournal', 'ShareExtension-Info.plist'), 'utf8'),
    };
  } finally {
    rmSync(platformProjectRoot, { recursive: true, force: true });
  }
}

test('patch keeps a distinct internal target when the normalized display name collides with PictureJournal', () => {
  const patch = addedPatchSource();
  assert.match(patch, /const targetName = `\$\{extensionName\}ShareExtension`;/);
  assert.match(patch, /addTarget\(targetName, "app_extension", extensionName\)/);
  assert.match(patch, /Existing \$\{targetName\} target is malformed; refusing to create a duplicate/);
  assert.match(patch, /ensureHostTargetDependency\(pbxProject, hostTargetUuid, extensionTargetUuid\)/);
});
test('installed patched Xcode mod initializes missing dependency sections and remains idempotent', async () => {
  const { objects, calls, modResults } = minimalProjectMod({ omitDependencySections: true });

  const first = await runInstalledXcodeMod(modResults);
  assert.match(first.infoPlist, /<key>CFBundleDisplayName<\/key>\s*<string>Picture Journal<\/string>/);
  assert.deepEqual(calls, {
    addTarget: 1,
    addTargetDependency: 1,
    addBuildPhase: 2,
    pbxCreateGroup: 1,
    addFile: 1,
    addSourceFile: 1,
    addResourceFile: 3,
  });
  assert.deepEqual(inspect(objects).errors, []);
  assert.deepEqual(Object.keys(objects.PBXTargetDependency), ['DEPENDENCY']);
  assert.deepEqual(Object.keys(objects.PBXContainerItemProxy), ['PROXY']);
  assert.deepEqual(objects.PBXTargetDependency.DEPENDENCY, { target: 'EXT', targetProxy: 'PROXY' });
  assert.deepEqual(objects.PBXContainerItemProxy.PROXY, {
    containerPortal: 'PROJECT',
    remoteGlobalIDString: 'EXT',
    proxyType: 1,
  });

  await runInstalledXcodeMod(modResults);
  assert.deepEqual(inspect(objects).errors, []);
  assert.equal(Object.keys(objects.PBXNativeTarget).filter((uuid) => objects.PBXNativeTarget[uuid].name === EXTENSION_TARGET).length, 1);
  assert.equal(Object.keys(objects.PBXFileReference).filter((uuid) => objects.PBXFileReference[uuid].path === `${EXTENSION_TARGET}.appex`).length, 1);
  assert.equal(Object.keys(objects.XCConfigurationList).length, 1);
  assert.equal(Object.keys(objects.XCBuildConfiguration).length, 2);
  assert.equal(Object.keys(objects.PBXTargetDependency).length, 1);
  assert.equal(Object.keys(objects.PBXContainerItemProxy).length, 1);
  assert.equal(objects.PBXNativeTarget.HOST.dependencies.length, 1);
  assert.equal(objects.PBXCopyFilesBuildPhase.COPY.files.length, 1);
  assert.equal(calls.addTarget, 1);
  assert.equal(calls.addTargetDependency, 1);

  const malformed = project({ productType: 'com.apple.product-type.application' });
  await assert.rejects(runInstalledXcodeMod(existingProjectMod(malformed)), /Existing PictureJournalShareExtension target is malformed/);
  const wrongBundle = project();
  wrongBundle.XCBuildConfiguration.DEBUG.buildSettings.PRODUCT_BUNDLE_IDENTIFIER = '"com.example.wrong"';
  await assert.rejects(runInstalledXcodeMod(existingProjectMod(wrongBundle)), /Existing PictureJournalShareExtension target is malformed/);
});

test('patch preserves an app extension at index zero on a second invocation', () => {
  const patch = addedPatchSource();
  assert.match(patch, /extConfigIndex === null \|\| extConfigIndex === undefined/);
  assert.doesNotMatch(patch, /if \(!extConfigIndex\)/);
});

test('accepts a complete host-to-extension project and exactly one EAS configuration', () => {
  assert.deepEqual(inspect(project(), config()).errors, []);
});
test('derives exactly one EAS extension configuration from app.json after two plugin invocations', () => {
  const rawConfig = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  assert.equal(rawConfig.expo.extra.eas, undefined);

  const { config: configured, errors } = applyIosShareExtensionConfig(rawConfig);
  const appExtensions = configured.expo.extra.eas.build.experimental.ios.appExtensions;
  assert.deepEqual(errors, []);
  assert.equal(rawConfig.expo.extra.eas, undefined);
  assert.deepEqual(appExtensions, [{
    targetName: EXTENSION_TARGET,
    bundleIdentifier: EXTENSION_BUNDLE_ID,
    entitlements: {
      'com.apple.security.application-groups': ['group.com.picturejournal.mobile'],
    },
  }]);
});

test('reports a missing or malformed expo-share-intent plugin entry', () => {
  assert.deepEqual(applyIosShareExtensionConfig({ expo: { plugins: [] } }).errors, [
    'app.json expo.plugins must contain exactly one expo-share-intent plugin entry.',
  ]);
  assert.deepEqual(applyIosShareExtensionConfig({
    expo: { plugins: ['expo-share-intent'] },
  }).errors, ['expo-share-intent plugin entry must be ["expo-share-intent", parameters-object].']);
});

test('rejects duplicate or missing EAS extension configuration', () => {
  const duplicate = config();
  duplicate.expo.extra.eas.build.experimental.ios.appExtensions.push({
    targetName: EXTENSION_TARGET,
    bundleIdentifier: EXTENSION_BUNDLE_ID,
  });
  assert.match(errorsFor({}, duplicate), /exactly one matching/);
  assert.match(errorsFor({}, { expo: {} }), /exactly one matching/);
});

test('rejects duplicate exact target names and product identity drift', () => {
  const duplicate = project();
  duplicate.PBXNativeTarget.EXT_DUPLICATE = { ...duplicate.PBXNativeTarget.EXT };
  assert.match(inspect(duplicate).errors.join('\n'), /exactly one extension target/);

  const wrongName = project();
  wrongName.PBXNativeTarget.EXT.productName = 'WrongExtension';
  assert.match(inspect(wrongName).errors.join('\n'), /productName/);

  const wrongProduct = project();
  wrongProduct.PBXFileReference.PRODUCT.path = 'Wrong.appex';
  assert.match(inspect(wrongProduct).errors.join('\n'), /PictureJournalShareExtension.appex/);
});

test('rejects an extension with the wrong product type or product reference wrapper', () => {
  assert.match(errorsFor({ productType: 'com.apple.product-type.application' }), /productType/);
  const objects = project();
  objects.PBXFileReference.PRODUCT.explicitFileType = 'wrapper.application';
  assert.match(inspect(objects).errors.join('\n'), /app-extension product/);
});

test('rejects dangling and semantically wrong extension configuration UUIDs', () => {
  const dangling = project();
  dangling.XCConfigurationList.CONFIG_LIST.buildConfigurations.push('MISSING');
  assert.match(inspect(dangling).errors.join('\n'), /resolve every build configuration UUID/);

  const wrong = project();
  wrong.XCBuildConfiguration.DEBUG.buildSettings.PRODUCT_NAME = 'Wrong';
  assert.match(inspect(wrong).errors.join('\n'), /PRODUCT_NAME/);
});
test('rejects an extension configuration with the wrong bundle identifier', () => {
  const wrongBundle = project();
  wrongBundle.XCBuildConfiguration.DEBUG.buildSettings.PRODUCT_BUNDLE_IDENTIFIER = '"com.example.wrong"';
  assert.match(inspect(wrongBundle).errors.join('\n'), /PRODUCT_NAME/);
});

test('patch fail-closes same-name targets with wrong type or bundle instead of adding a duplicate', () => {
  const patch = addedPatchSource();
  assert.match(patch, /unquote\(target\.productType\) === "com\.apple\.product-type\.app-extension"/);
  assert.match(patch, /PRODUCT_BUNDLE_IDENTIFIER\) === shareExtensionIdentifier/);
  assert.match(patch, /throw new Error\(`\[expo-share-intent\] Existing \$\{targetName\} target is malformed/);
});

test('rejects wrong or missing owning PBXProject containerPortal', () => {
  const wrong = project();
  wrong.PBXContainerItemProxy.PROXY.containerPortal = 'OTHER_PROJECT';
  assert.match(inspect(wrong).errors.join('\n'), /container proxy/);
  const missing = project();
  delete missing.PBXContainerItemProxy.PROXY.containerPortal;
  assert.match(inspect(missing).errors.join('\n'), /container proxy/);
});

test('rejects duplicate dependency and proxy edges', () => {
  const duplicateDependency = project();
  duplicateDependency.PBXTargetDependency.DEPENDENCY_2 = { ...duplicateDependency.PBXTargetDependency.DEPENDENCY };
  duplicateDependency.PBXNativeTarget.HOST.dependencies.push('DEPENDENCY_2');
  assert.match(inspect(duplicateDependency).errors.join('\n'), /exactly one PBXTargetDependency/);

  const duplicateProxy = project();
  duplicateProxy.PBXContainerItemProxy.PROXY_2 = { ...duplicateProxy.PBXContainerItemProxy.PROXY };
  assert.match(inspect(duplicateProxy).errors.join('\n'), /exactly one PBXTargetDependency/);
});

test('rejects dangling dependency proxies, wrong extension UUIDs, and missing dependencies', () => {
  const dangling = project();
  delete dangling.PBXContainerItemProxy.PROXY;
  assert.match(inspect(dangling).errors.join('\n'), /PBXTargetDependency/);
  assert.match(errorsFor({ proxyTarget: 'WRONG' }), /PBXTargetDependency/);
  assert.match(errorsFor({ includeDependency: false }), /PBXTargetDependency/);
});

test('rejects a copy phase that does not embed into destination 13', () => {
  assert.match(errorsFor({ destination: 16 }), /destination-13/);
});

test('semantic fingerprint ignores UUID changes but records semantic drift', () => {
  const first = inspect(project());
  const uuidChanged = inspectIosShareProject(JSON.parse(reidentify(project())), config(), { rootObject: 'PROJECT_OTHER' });
  assert.deepEqual(first.errors, []);
  assert.deepEqual(uuidChanged.errors, []);
  assert.equal(first.semanticFingerprint, uuidChanged.semanticFingerprint);

  const drifted = project();
  drifted.PBXFileReference.PRODUCT.path = 'Wrong.appex';
  const driftedReceipt = inspect(drifted);
  assert.notEqual(first.semanticFingerprint, driftedReceipt.semanticFingerprint);
  assert.equal(driftedReceipt.status, 'failed');
});
