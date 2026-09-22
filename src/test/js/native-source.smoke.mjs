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

// Diagnostic dumps must not return to the immutable baseline or functional patches.
const diagnosticSymbols = /\b(?:log_AFS_bits|set_AFS_log_context|log_LDPC_SF2|log_LDPC_SF34|log_AFS_rx_bits|log_AFS_rx_crc|sdr_log_write|AFS_DETAIL_LOG_COUNT)\b/;
for (const name of files.filter(name => /\.[ch]$/.test(name))) {
  assert.doesNotMatch(text('native/vendor/' + name), diagnosticSymbols, name);
}
assert.ok(readdirSync('native/patches').includes('01-korean-comments.patch'));
assert.ok(!readdirSync('native/patches').includes('01-logging.patch'));
assert.match(text('native/patches/04-iq-receiver.patch'), /\$IQOBS,/);
assert.match(text('native/patches/05-afs-pvt-payload.patch'), /\$IQAFS,/);
assert.match(text('native/patches/01-korean-comments.patch'), /확인 - SB2 LDPC 부호화/);
