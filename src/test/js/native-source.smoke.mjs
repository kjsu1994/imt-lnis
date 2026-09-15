// Read-only checks: vendored bytes must never be silently normalized or replaced.
import assert from 'node:assert/strict';
import {readFileSync, readdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
const text = path => readFileSync(path, 'utf8');
const manifest = text('native/UPSTREAM-SHA256.txt').trim().split(/\r?\n/);
const files = [];
function walk(directory, prefix = '') {
  for (const entry of readdirSync(directory, {withFileTypes: true})) {
    const name = prefix + entry.name;
    if (entry.isDirectory()) walk(directory + '/' + entry.name, name + '/');
    else files.push(name);
  }
}
walk('native/vendor');
const listed = manifest.map(line => {
  const match = /^([a-f0-9]{64})  (.+)$/.exec(line);
  assert.ok(match, 'Malformed vendor manifest entry');
  const [, hash, name] = match;
  assert.equal(createHash('sha256').update(readFileSync('native/vendor/' + name)).digest('hex'), hash, name);
  return name;
});
assert.deepEqual(files.sort(), listed.sort(), 'Every imported original must have a hash');
assert.match(text('.gitattributes'), /native\/vendor\/\*\* -text -filter/);
for (const name of readdirSync('native/patches')) {
  assert.ok(name.endsWith('.patch'));
  assert.match(text('native/patches/' + name), /LNIS 변경/, 'Explain modifications at their source locations');
}
assert.match(text('deployment/node/.dockerignore'), /!iq\/\*\*/);
assert.match(text('deployment/node/Dockerfile'), /chmod 755 \/app\/iq\/afs_sim/);
assert.doesNotMatch(text('native/build.sh'), /libsdr\.a|libldpc\.a/);
console.log(`PASS: ${listed.length} immutable source files, annotated patches, source-only build, IQ packaging`);
