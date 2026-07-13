import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const sax = require('sax');
const ANDROID_NAMESPACE = 'http://schemas.android.com/apk/res/android';

const expandedAttribute = (node, namespace, localName) =>
  Object.values(node.attributes).find((attribute) => attribute.uri === namespace && attribute.local === localName)?.value;

function parseManifest(source) {
  const filters = [];
  const elementStack = [];
  let currentFilter = null;
  let rootSeen = false;
  let parseFailure = null;
  const parser = sax.parser(true, { xmlns: true, position: true, trim: false, normalize: false });

  parser.onerror = (error) => {
    parseFailure = error;
  };
  parser.onopentag = (node) => {
    if (parseFailure) return;
    const parent = elementStack.at(-1);
    if (!rootSeen) {
      rootSeen = true;
      if (node.local !== 'manifest' || node.uri) throw new Error('XML root must be an unnamespaced manifest element.');
    } else if (!parent) {
      throw new Error('XML document must contain exactly one root element.');
    }

    const directIntentChild = currentFilter && parent?.local === 'intent-filter' && !parent.uri;
    if (node.local === 'intent-filter') {
      const application = elementStack.at(-2);
      const manifest = elementStack.at(-3);
      if (
        node.uri
        || !parent
        || parent.uri
        || !['activity', 'activity-alias'].includes(parent.local)
        || !application
        || application.uri
        || application.local !== 'application'
        || !manifest
        || manifest.uri
        || manifest.local !== 'manifest'
        || currentFilter
      ) {
        throw new Error('intent-filter must follow manifest > application > activity or activity-alias.');
      }
      currentFilter = { actions: [], categories: [], data: [], depth: elementStack.length };
      filters.push(currentFilter);
    } else if (directIntentChild && !node.uri && node.local === 'action') {
      currentFilter.actions.push(expandedAttribute(node, ANDROID_NAMESPACE, 'name'));
    } else if (directIntentChild && !node.uri && node.local === 'category') {
      currentFilter.categories.push(expandedAttribute(node, ANDROID_NAMESPACE, 'name'));
    } else if (directIntentChild && !node.uri && node.local === 'data') {
      currentFilter.data.push(expandedAttribute(node, ANDROID_NAMESPACE, 'mimeType'));
    }
    elementStack.push(node);
  };
  parser.onclosetag = () => {
    const closed = elementStack.pop();
    if (closed?.local === 'intent-filter') currentFilter = null;
  };
  parser.ontext = (text) => {
    if (!elementStack.length && text.trim()) throw new Error('Non-whitespace text is not allowed outside the manifest root.');
  };
  parser.write(source).close();
  if (parseFailure) throw parseFailure;
  if (!rootSeen || elementStack.length) throw new Error('XML manifest is incomplete.');
  return filters;
}

export function inspectAndroidShareManifest(source) {
  try {
    const filters = parseManifest(source);
    const matchingFilters = filters.filter((filter) =>
      filter.actions.includes('android.intent.action.SEND')
      && filter.categories.includes('android.intent.category.DEFAULT')
      && filter.data.includes('text/*'),
    );
    const sendActionCount = filters.flatMap((filter) => filter.actions)
      .filter((value) => value === 'android.intent.action.SEND').length;
    const textWildcardCount = filters.flatMap((filter) => filter.data)
      .filter((value) => value === 'text/*').length;
    return {
      intentFilterCount: filters.length,
      matchingFilterCount: matchingFilters.length,
      sendActionCount,
      textWildcardCount,
      status: matchingFilters.length === 1 && sendActionCount === 1 && textWildcardCount === 1 ? 'passed' : 'failed',
      parseError: null,
    };
  } catch (error) {
    return {
      intentFilterCount: 0,
      matchingFilterCount: 0,
      sendActionCount: 0,
      textWildcardCount: 0,
      status: 'failed',
      parseError: error instanceof Error ? error.message : String(error),
    };
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifestPath = resolve('android/app/src/main/AndroidManifest.xml');
  const inspection = inspectAndroidShareManifest(readFileSync(manifestPath, 'utf8'));
  const receipt = {
    checkedAt: new Date().toISOString(),
    manifestPath,
    ...inspection,
  };
  mkdirSync(resolve('../../artifacts/mobile'), { recursive: true });
  writeFileSync(resolve('../../artifacts/mobile/android-share-intent-manifest-check.json'), JSON.stringify(receipt, null, 2));
  if (inspection.status !== 'passed') {
    console.error(
      `Expected exactly one complete SEND + DEFAULT + text/* filter; found matching=${inspection.matchingFilterCount}, `
        + `SEND=${inspection.sendActionCount}, text/*=${inspection.textWildcardCount}, totalFilters=${inspection.intentFilterCount}, `
        + `parseError=${inspection.parseError ?? 'none'}`,
    );
    process.exit(1);
  }
  console.log('Android share intent manifest check passed.');
}
