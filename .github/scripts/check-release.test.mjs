import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareSemver, newestStable, registryVersion, localVersion, releaseBody } from './check-release.mjs';

test('semver order follows the spec, not string order', () => {
  assert.equal(compareSemver('2.0.1', '2.0.0'), 1);
  assert.equal(compareSemver('2.0.10', '2.0.9'), 1);
  assert.equal(compareSemver('10.0.0', '9.9.9'), 1);
  assert.equal(compareSemver('2.0.0', '2.0.0'), 0);
  assert.equal(compareSemver('v2.0.0', '2.0.0+build.7'), 0);
  assert.equal(compareSemver('2.0.1-rc.1', '2.0.1'), -1);
  assert.equal(compareSemver('2.0.1-rc.1', '2.0.0'), 1);
  assert.equal(compareSemver('1.0.0-alpha', '1.0.0-alpha.1'), -1);
  assert.equal(compareSemver('1.0.0-alpha.1', '1.0.0-alpha.beta'), -1);
  assert.equal(compareSemver('1.0.0-beta.2', '1.0.0-beta.11'), -1);
  assert.equal(compareSemver('1.0.0-rc.1', '1.0.0-beta.11'), 1);
  assert.throws(() => compareSemver('2.0', '2.0.0'));
});

test('newest stable skips pre-releases and junk', () => {
  assert.equal(newestStable(['2.0.0', '2.1.0-rc.1', '2.0.10', '2.0.9', 'latest']), '2.0.10');
  assert.equal(newestStable(['3.0.0-beta.1']), null);
});

test('reads each registry format', () => {
  const maven = '<metadata><versioning><latest>2.0.1</latest><release>2.0.1</release><versions><version>2.0.0</version><version>2.0.1</version><version>2.1.0-rc.1</version></versions></versioning></metadata>';
  assert.equal(registryVersion('maven', maven), '2.0.1');
  assert.equal(registryVersion('pub', JSON.stringify({ name: 'moonetix_offerwall', latest: { version: '2.0.1' }, versions: [{ version: '2.0.0' }, { version: '2.0.1' }] })), '2.0.1');
  assert.equal(registryVersion('npm', JSON.stringify({ 'dist-tags': { latest: '2.0.1', next: '2.1.0-rc.1' }, versions: { '2.0.0': {}, '2.0.1': {}, '2.1.0-rc.1': {} } })), '2.0.1');
  assert.equal(registryVersion('podspec', "Pod::Spec.new do |s|\n  s.name = 'MoonetixOfferwall'\n  s.version               = '2.0.1'\nend\n"), '2.0.1');
});

test('reads the repository version from each file type', () => {
  assert.equal(localVersion('package.json', '{"version":"2.0.0"}'), '2.0.0');
  assert.equal(localVersion('unity/Packages/manifest.json', '{"dependencies":{"com.moonetix.offerwall":"2.0.0"}}', 'com.moonetix.offerwall'), '2.0.0');
  assert.equal(localVersion('pubspec.yaml', 'name: x\nversion: 2.0.0\n'), '2.0.0');
  assert.equal(localVersion('offerwall/build.gradle.kts', 'plugins {}\nval sdkVersion = "2.0.0"\nversion = sdkVersion\n'), '2.0.0');
  assert.equal(localVersion('CHANGELOG.md', '# Changelog\n\n## [Unreleased]\n\n## [2.0.0] - 2026-11-06\n\n## [1.0.1] - 2026-10-01\n'), '2.0.0');
  assert.throws(() => localVersion('CHANGELOG.md', '# Changelog\n'));
});

test('release issue body links the registry, changelog and docs', () => {
  const body = releaseBody({ name: 'Android SDK', version: '2.0.1', current: '2.0.0', url: 'https://moonetix.com/maven/x.xml', docs: 'https://docs.moonetix.com/android-sdk', localFile: 'offerwall/build.gradle.kts', hasChangelog: true, run: 'https://github.com/o/r/actions/runs/5' });
  assert.match(body, /^Moonetix published Android SDK 2\.0\.1\. This repository is on 2\.0\.0\./);
  assert.match(body, /Changelog: https:\/\/docs\.moonetix\.com\/android-sdk#changelog/);
  assert.match(body, /`offerwall\/build\.gradle\.kts`/);
  assert.match(body, /closes itself once the repository reaches 2\.0\.1/);
});
