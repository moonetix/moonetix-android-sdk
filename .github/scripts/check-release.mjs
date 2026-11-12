#!/usr/bin/env node
// Weekly check: has Moonetix published a newer release than the version in
// this repository? If so, one issue "<name> x.y.z released" is opened (or the
// open one is moved to the newest version). It closes itself once the
// repository catches up. Node 22, no dependencies.
//
//   node scripts/check-release.mjs --registry maven|pub|npm|podspec --url URL \
//     --local FILE [--dependency NAME] --name "Android SDK" --docs URL [--issue]
//
// --local is read by file type: package.json or manifest.json ("version", or
// dependencies[NAME] with --dependency), pubspec.yaml, *.gradle(.kts)
// (sdkVersion = "..."), *.podspec, or CHANGELOG.md (newest released heading).
// Without --issue the would-be issue is printed.

import { readFileSync, existsSync } from 'node:fs';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { githubClient, upsertIssue, closeIssue, runUrl } from './github-issue.mjs';

// Paths are relative to the repository root, where the workflow runs.
const ROOT = process.cwd();
export const TOPIC = 'sdk-release';
const USER_AGENT = 'moonetix-release-check (+https://github.com/moonetix)';

const SEMVER = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/;

export function parseSemver(text) {
  const m = SEMVER.exec(String(text).trim());
  if (!m) return null;
  return { major: +m[1], minor: +m[2], patch: +m[3], pre: m[4] ? m[4].split('.') : [], raw: String(text).trim().replace(/^v/, '') };
}

// Semantic Versioning 2.0.0, section 11. Build metadata is ignored.
export function compareSemver(a, b) {
  const x = typeof a === 'string' ? parseSemver(a) : a;
  const y = typeof b === 'string' ? parseSemver(b) : b;
  if (!x || !y) throw new Error(`not a semantic version: ${!x ? a : b}`);
  for (const k of ['major', 'minor', 'patch']) if (x[k] !== y[k]) return x[k] < y[k] ? -1 : 1;
  if (!x.pre.length || !y.pre.length) return x.pre.length === y.pre.length ? 0 : x.pre.length ? -1 : 1;
  for (let i = 0; i < Math.max(x.pre.length, y.pre.length); i++) {
    const p = x.pre[i];
    const q = y.pre[i];
    if (p === undefined) return -1;
    if (q === undefined) return 1;
    if (p === q) continue;
    const pn = /^\d+$/.test(p);
    const qn = /^\d+$/.test(q);
    if (pn && qn) return +p < +q ? -1 : 1;
    if (pn !== qn) return pn ? -1 : 1;
    return p < q ? -1 : 1;
  }
  return 0;
}

// Highest stable version in a list; pre-releases and unparsable entries are skipped.
export function newestStable(versions) {
  const ok = versions.map(parseSemver).filter((v) => v && v.pre.length === 0);
  if (!ok.length) return null;
  return ok.sort(compareSemver).at(-1).raw;
}

export function registryVersion(kind, body) {
  switch (kind) {
    case 'maven': {
      const versions = [...body.matchAll(/<(?:version|release)>\s*([^<\s]+)\s*<\/(?:version|release)>/g)].map((m) => m[1]);
      return newestStable(versions);
    }
    case 'pub': {
      const doc = JSON.parse(body);
      return newestStable([doc.latest?.version, ...(doc.versions ?? []).map((v) => v.version)].filter(Boolean));
    }
    case 'npm': {
      const doc = JSON.parse(body);
      const latest = doc['dist-tags']?.latest;
      return latest && parseSemver(latest) ? parseSemver(latest).raw : newestStable(Object.keys(doc.versions ?? {}));
    }
    case 'podspec': {
      const m = /\.version\s*=\s*['"]([^'"]+)['"]/.exec(body);
      return m ? parseSemver(m[1])?.raw ?? null : null;
    }
    default:
      throw new Error(`unknown registry kind: ${kind}`);
  }
}

export function localVersion(file, text, dependency) {
  const name = basename(file);
  let v = null;
  if (name.endsWith('.json')) {
    const doc = JSON.parse(text);
    v = dependency ? doc.dependencies?.[dependency] : doc.version;
  } else if (name === 'pubspec.yaml') {
    v = /^version:\s*['"]?([^\s'"]+)/m.exec(text)?.[1];
  } else if (/\.gradle(\.kts)?$/.test(name)) {
    v = /\bsdkVersion\s*=\s*"([^"]+)"/.exec(text)?.[1] ?? /^\s*version\s*=\s*"([^"]+)"/m.exec(text)?.[1];
  } else if (name.endsWith('.podspec')) {
    v = /\.version\s*=\s*['"]([^'"]+)['"]/.exec(text)?.[1];
  } else if (/^CHANGELOG/i.test(name)) {
    v = /^##\s*\[?v?(\d+\.\d+\.\d+[^\]\s]*)\]?/m.exec(text)?.[1];
  }
  if (!v || !parseSemver(v)) throw new Error(`could not read a version from ${file}${dependency ? ` (dependency ${dependency})` : ''}`);
  return parseSemver(v).raw;
}

export function releaseBody({ name, version, current, url, docs, localFile, hasChangelog, run }) {
  const steps = hasChangelog
    ? `Update the version in \`${localFile}\` and everywhere else \`${current}\` appears, add the release to \`CHANGELOG.md\` from the changelog linked above, run the tests, and publish a GitHub release.`
    : `Update \`${localFile}\` to \`${version}\`, run the tests, and check the example against the changelog linked above.`;
  return [
    `Moonetix published ${name} ${version}. This repository is on ${current}.`,
    '',
    `- Registry: ${url}`,
    `- Changelog: ${docs}#changelog`,
    `- Docs: ${docs}`,
    '',
    steps,
    '',
    `This issue closes itself once the repository reaches ${version}.`,
    '',
    `Last checked: ${run}`,
  ].join('\n');
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--issue') out.issue = true;
    else if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[++i];
  }
  return out;
}

async function main() {
  const a = parseArgs(process.argv.slice(2));
  for (const k of ['registry', 'url', 'local', 'name', 'docs']) if (!a[k]) throw new Error(`missing --${k}`);

  const current = localVersion(a.local, readFileSync(join(ROOT, a.local), 'utf8'), a.dependency);
  const res = await fetch(a.url, { headers: { 'user-agent': USER_AGENT, accept: '*/*' }, signal: AbortSignal.timeout(30000) });
  const body = await res.text();
  if (!res.ok) throw new Error(`${a.url}: HTTP ${res.status}`);
  const published = registryVersion(a.registry, body);
  if (!published) throw new Error(`${a.url}: no stable version found in the response (${body.slice(0, 120).replace(/\s+/g, ' ')})`);
  const order = compareSemver(published, current);
  console.log(`${a.name}: registry ${published}, repository ${current}`);

  const request = a.issue ? githubClient() : null;
  if (a.issue && !request) throw new Error('--issue needs GITHUB_TOKEN and GITHUB_REPOSITORY');

  if (order > 0) {
    await upsertIssue(request, {
      topic: TOPIC,
      title: `${a.name} ${published} released`,
      body: releaseBody({ name: a.name, version: published, current, url: a.url, docs: a.docs, localFile: a.local, hasChangelog: existsSync(join(ROOT, 'CHANGELOG.md')), run: runUrl() }),
    });
  } else {
    if (order < 0) console.log(`The repository is ahead of the registry (${current} > ${published}); a release is probably in progress.`);
    await closeIssue(request, { topic: TOPIC, comment: `The repository is on ${current}; the newest published release is ${published}.` });
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((err) => {
    console.error(err.message);
    process.exit(1);
  });
}
