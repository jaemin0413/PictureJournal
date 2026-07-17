import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);

export const EXTENSION_TARGET = 'PictureJournalShareExtension';
export const HOST_TARGET = 'PictureJournal';
export const EXTENSION_BUNDLE_ID = 'com.picturejournal.mobile.share-extension';
const SHARE_INTENT_PLUGIN = 'expo-share-intent';
const SHARE_EXTENSION_CONFIG_ERROR = 'expo-share-intent plugin entry must be ["expo-share-intent", parameters-object].';

export function applyIosShareExtensionConfig(rawConfig) {
  const plugins = rawConfig?.expo?.plugins;
  if (!Array.isArray(plugins)) {
    return { errors: ['app.json expo.plugins must be an array containing the expo-share-intent plugin entry.'] };
  }

  const pluginEntries = plugins.filter((entry) => entry === SHARE_INTENT_PLUGIN
    || (Array.isArray(entry) && entry[0] === SHARE_INTENT_PLUGIN));
  if (pluginEntries.length === 0) {
    return { errors: ['app.json expo.plugins must contain exactly one expo-share-intent plugin entry.'] };
  }
  if (pluginEntries.length !== 1) {
    return { errors: ['app.json expo.plugins must contain only one expo-share-intent plugin entry.'] };
  }

  const pluginEntry = pluginEntries[0];
  if (!Array.isArray(pluginEntry) || pluginEntry.length !== 2
    || pluginEntry[0] !== SHARE_INTENT_PLUGIN
    || !pluginEntry[1] || typeof pluginEntry[1] !== 'object' || Array.isArray(pluginEntry[1])) {
    return { errors: [SHARE_EXTENSION_CONFIG_ERROR] };
  }

  const config = JSON.parse(JSON.stringify(rawConfig.expo));
  const { withShareExtensionConfig } = require('expo-share-intent/plugin/build/ios/withIosShareExtensionConfig.js');
  withShareExtensionConfig(config, pluginEntry[1]);
  withShareExtensionConfig(config, pluginEntry[1]);
  return { config: { expo: config }, errors: [] };
}

const uncommented = (section = {}) => Object.entries(section)
  .filter(([key]) => !key.endsWith('_comment'))
  .map(([uuid, value]) => ({ uuid, ...value }));
const entriesByUuid = (section = {}) => new Map(uncommented(section).map((entry) => [entry.uuid, entry]));
const unquote = (value) => typeof value === 'string' ? value.replace(/^"|"$/g, '') : value;
const referenceUuid = (reference) => typeof reference === 'string' ? reference : reference?.value;

function targetsByExactName(targets, name) {
  return targets.filter((target) => unquote(target.name) === name);
}

export function inspectIosShareProject(objects, config, { checkEasConfig = true, rootObject } = {}) {
  const targets = uncommented(objects.PBXNativeTarget);
  const buildFiles = entriesByUuid(objects.PBXBuildFile);
  const phases = uncommented(objects.PBXCopyFilesBuildPhase);
  const dependencies = entriesByUuid(objects.PBXTargetDependency);
  const proxies = entriesByUuid(objects.PBXContainerItemProxy);
  const configurationLists = objects.XCConfigurationList ?? {};
  const configurations = objects.XCBuildConfiguration ?? {};
  const fileReferences = objects.PBXFileReference ?? {};
  const errors = [];
  const extensions = targetsByExactName(targets, EXTENSION_TARGET);
  const hosts = targetsByExactName(targets, HOST_TARGET);
  const extension = extensions[0];
  const host = hosts[0];

  if (extensions.length !== 1) {
    errors.push(`Expected exactly one extension target named ${EXTENSION_TARGET}.`);
  }
  if (hosts.length !== 1) {
    errors.push(`Expected exactly one host target named ${HOST_TARGET}.`);
  }

  if (extension) {
    if (unquote(extension.productName) !== EXTENSION_TARGET) {
      errors.push(`Extension target must have productName ${EXTENSION_TARGET}.`);
    }
    if (unquote(extension.productType) !== 'com.apple.product-type.app-extension') {
      errors.push(`Extension target has productType ${extension.productType ?? 'missing'}.`);
    }
    const configurationList = configurationLists[referenceUuid(extension.buildConfigurationList)];
    const configurationIds = configurationList?.buildConfigurations?.map(referenceUuid);
    if (!Array.isArray(configurationIds) || !configurationIds.length
      || configurationIds.some((uuid) => !configurations[uuid])) {
      errors.push('Extension target must resolve every build configuration UUID.');
    } else if (configurationIds.some((uuid) => {
      const settings = configurations[uuid].buildSettings;
      return unquote(settings?.PRODUCT_NAME) !== EXTENSION_TARGET
        || unquote(settings?.PRODUCT_BUNDLE_IDENTIFIER) !== EXTENSION_BUNDLE_ID;
    })) {
      errors.push(`Extension configurations must all use PRODUCT_NAME ${EXTENSION_TARGET} and ${EXTENSION_BUNDLE_ID}.`);
    }

    const product = fileReferences[referenceUuid(extension.productReference)];
    if (!product || unquote(product.explicitFileType) !== 'wrapper.app-extension'
      || (unquote(product.path) !== `${EXTENSION_TARGET}.appex`
        && unquote(product.name) !== `${EXTENSION_TARGET}.appex`)) {
      errors.push(`Extension target must reference ${EXTENSION_TARGET}.appex as an app-extension product file.`);
    }
  }

  if (host && extension) {
    const extensionDependencies = (host.dependencies ?? []).map(referenceUuid)
      .map((uuid) => dependencies.get(uuid))
      .filter((entry) => referenceUuid(entry?.target) === extension.uuid);
    const dependency = extensionDependencies[0];
    const matchingProxies = [...proxies.values()].filter((proxy) =>
      referenceUuid(proxy.remoteGlobalIDString) === extension.uuid
      && String(proxy.proxyType) === '1'
      && referenceUuid(proxy.containerPortal) === rootObject,
    );
    const proxy = dependency && proxies.get(referenceUuid(dependency.targetProxy));
    if (extensionDependencies.length !== 1 || matchingProxies.length !== 1 || !proxy
      || proxy.uuid !== matchingProxies[0]?.uuid) {
      errors.push('Host must own exactly one PBXTargetDependency with one valid extension container proxy.');
    }

    const extensionProduct = referenceUuid(extension.productReference);
    const linkedExtension = phases.some((phase) => phase.dstSubfolderSpec === 13 || phase.dstSubfolderSpec === '13'
      ? (phase.files ?? []).map(referenceUuid).some((uuid) => referenceUuid(buildFiles.get(uuid)?.fileRef) === extensionProduct)
      : false);
    const hostPhases = new Set((host.buildPhases ?? []).map(referenceUuid));
    const hostLinksExtension = phases.some((phase) => hostPhases.has(phase.uuid)
      && (phase.dstSubfolderSpec === 13 || phase.dstSubfolderSpec === '13')
      && (phase.files ?? []).map(referenceUuid).some((uuid) => referenceUuid(buildFiles.get(uuid)?.fileRef) === extensionProduct));
    if (!linkedExtension || !hostLinksExtension) {
      errors.push('Host must own a destination-13 copy phase linked to the extension product.');
    }
  }

  if (checkEasConfig) {
    const appExtensions = config?.expo?.extra?.eas?.build?.experimental?.ios?.appExtensions;
    const matchingExtensions = Array.isArray(appExtensions)
      ? appExtensions.filter((entry) => entry?.targetName === EXTENSION_TARGET && entry?.bundleIdentifier === EXTENSION_BUNDLE_ID)
      : [];
    if (matchingExtensions.length !== 1 || appExtensions?.length !== 1) {
      errors.push('EAS appExtensions must contain exactly one matching share extension configuration.');
    }
  }
  const extensionConfigurationIds = extension
    ? configurationLists[referenceUuid(extension.buildConfigurationList)]?.buildConfigurations?.map(referenceUuid) ?? []
    : [];
  const extensionConfigurationSemantics = extensionConfigurationIds.map((uuid) => {
    const settings = configurations[uuid]?.buildSettings;
    return {
      productName: unquote(settings?.PRODUCT_NAME) ?? null,
      bundleIdentifier: unquote(settings?.PRODUCT_BUNDLE_IDENTIFIER) ?? null,
      resolved: Boolean(configurations[uuid]),
    };
  }).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
  const extensionProduct = extension && fileReferences[referenceUuid(extension.productReference)];
  const hostExtensionDependencies = host && extension
    ? (host.dependencies ?? []).map(referenceUuid)
      .map((uuid) => dependencies.get(uuid))
      .filter((entry) => referenceUuid(entry?.target) === extension.uuid)
    : [];
  const extensionProxies = extension
    ? [...proxies.values()].filter((proxy) => referenceUuid(proxy.remoteGlobalIDString) === extension.uuid)
    : [];
  const hostPhaseIds = new Set((host?.buildPhases ?? []).map(referenceUuid));
  const copyPhasesForExtension = extension
    ? phases.filter((phase) => (phase.dstSubfolderSpec === 13 || phase.dstSubfolderSpec === '13')
      && (phase.files ?? []).map(referenceUuid)
        .some((uuid) => referenceUuid(buildFiles.get(uuid)?.fileRef) === referenceUuid(extension.productReference)))
    : [];
  const semanticGraph = {
    host: {
      count: hosts.length,
      name: host ? unquote(host.name) : null,
      productName: host ? unquote(host.productName) ?? null : null,
    },
    extension: {
      count: extensions.length,
      name: extension ? unquote(extension.name) : null,
      productName: extension ? unquote(extension.productName) ?? null : null,
      productType: extension ? unquote(extension.productType) ?? null : null,
      product: {
        fileType: unquote(extensionProduct?.explicitFileType) ?? null,
        isExpectedAppex: unquote(extensionProduct?.path) === `${EXTENSION_TARGET}.appex`
          || unquote(extensionProduct?.name) === `${EXTENSION_TARGET}.appex`,
      },
      configurations: extensionConfigurationSemantics,
    },
    dependency: {
      count: hostExtensionDependencies.length,
      proxyCount: extensionProxies.length,
      validProxyCount: extensionProxies.filter((proxy) => String(proxy.proxyType) === '1'
        && referenceUuid(proxy.containerPortal) === rootObject).length,
      linkedProxyCount: hostExtensionDependencies.filter((dependency) =>
        extensionProxies.some((proxy) => proxy.uuid === referenceUuid(dependency.targetProxy))).length,
    },
    copyEdge: {
      count: copyPhasesForExtension.length,
      hostCount: copyPhasesForExtension.filter((phase) => hostPhaseIds.has(phase.uuid)).length,
    },
  };
  const semanticFingerprint = createHash('sha256')
    .update(JSON.stringify(semanticGraph))
    .digest('hex');


  return {
    status: errors.length ? 'failed' : 'passed',
    extensionTarget: EXTENSION_TARGET,
    hostTarget: HOST_TARGET,
    extensionBundleIdentifier: EXTENSION_BUNDLE_ID,
    semanticFingerprint,
    errors,
  };
}

export function inspectIosShareProjectFile(projectPath, configPath) {
  const rawConfig = JSON.parse(readFileSync(configPath, 'utf8'));
  const configured = applyIosShareExtensionConfig(rawConfig);
  const xcode = require('xcode');
  const project = xcode.project(projectPath);
  project.parseSync();
  const receipt = inspectIosShareProject(
    project.hash.project.objects,
    configured.config,
    {
      checkEasConfig: configured.errors.length === 0,
      rootObject: referenceUuid(project.hash.project.rootObject),
    },
  );
  receipt.errors.unshift(...configured.errors);
  receipt.status = receipt.errors.length ? 'failed' : 'passed';
  return receipt;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [projectPath = 'ios/PictureJournal.xcodeproj/project.pbxproj', configPath = 'app.json'] = process.argv.slice(2);
  const receipt = {
    checkedAt: new Date().toISOString(),
    projectPath: resolve(projectPath),
    configPath: resolve(configPath),
    ...inspectIosShareProjectFile(resolve(projectPath), resolve(configPath)),
  };
  console.log(JSON.stringify(receipt));
  if (receipt.status !== 'passed') process.exitCode = 1;
}
