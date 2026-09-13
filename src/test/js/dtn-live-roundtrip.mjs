// Explicit local integration check, not a production adapter. Never claims DTN/HDTN validation.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, writeFile} from 'node:fs/promises';

const tx = 'http://127.0.0.1:18090/lnis/api/v1';
const rx = 'http://127.0.0.1:18091/lnis/api/v1';
let sent;
const relay = createServer(async (request, response) => {
  if (request.method !== 'POST' || request.url !== '/transfers') { response.writeHead(404).end(); return; }
  try {
    const chunks = []; let count = 0;
    for await (const chunk of request) {
      count += chunk.length; if (count > 16777216) throw new Error('Body too large'); chunks.push(chunk);
    }
    sent = Buffer.concat(chunks);
    const result = await fetch(rx + '/dtn/receive', {method: 'POST', signal: AbortSignal.timeout(15000),
      headers: {'Content-Type': 'application/json', Authorization: 'Bearer dtn-ui-local-test'}, body: sent});
    response.writeHead(result.status).end(await result.text());
  } catch { response.writeHead(500).end(); }
});
async function json(url, method = 'GET', body) {
  const response = await fetch(url, {method, signal: AbortSignal.timeout(15000), headers: {'Content-Type': 'application/json'}, body: body && JSON.stringify(body)});
  const value = await response.json(); assert.ok(response.ok, JSON.stringify(value)); return value;
}
await new Promise(resolve => relay.listen(18092, '127.0.0.1', resolve));
try {
  const file = await readFile('build/dtn-example/f9t-example.graw');
  const input = await json(tx + '/inputs', 'POST', {fileName: 'F9T-live-observation-check.graw', size: file.length, kind: 'GRAW_UPLOAD'});
  const upload = await fetch(tx + '/inputs/' + input.inputId + '/chunks/0', {
    method: 'PUT', signal: AbortSignal.timeout(15000), headers: {'Content-Type': 'application/octet-stream'}, body: file});
  assert.ok(upload.ok); await json(tx + '/inputs/' + input.inputId + '/complete', 'POST');
  const preview = await json(tx + '/dtn/inputs/' + input.inputId + '/observations');
  assert.equal(preview.epochs.length, 1); assert.equal(preview.navigationCount, 0);
  const job = await json(tx + '/dtn/tests', 'POST', {inputId: input.inputId,
    senderAgentId: 'sender-1', receiverAgentId: 'receiver-1', sendUrl: 'http://127.0.0.1:18092/transfers'});
  let status;
  for (let i = 0; i < 80; i++) {
    status = await json(tx + '/dtn/tests/' + job.testId);
    if (['COMPLETED', 'FAILED', 'INCONCLUSIVE'].includes(status.state)) break;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  assert.equal(status.verdict, 'INCONCLUSIVE', JSON.stringify(status));
  const [txReport, rxReport] = await Promise.all([
    json(tx + '/dtn/tests/' + job.testId + '/report'), json(rx + '/dtn/tests/' + job.testId + '/report')]);
  assert.deepEqual(rxReport.observations, preview);
  assert.deepEqual(txReport.referencePvt, rxReport.receivedPvt);
  assert.equal(rxReport.receivedPvt[0].positionValid, false);
  const downloaded = await fetch(rx + '/dtn/tests/' + job.testId + '/payload/received?download=true', {signal: AbortSignal.timeout(15000)});
  assert.ok(downloaded.ok); assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), sent);
  const result = {testId: job.testId, observationCount: preview.epochs[0].observation.observations.length,
    restoredObservationsMatch: true, originalJsonBytesMatch: true, verdict: status.verdict,
    note: 'Local REST relay only; not a DTN/HDTN test. No navigation in public F9T fixture.'};
  await writeFile('build/dtn-ui-test/roundtrip.json', JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally { await new Promise(resolve => relay.close(resolve)); }
