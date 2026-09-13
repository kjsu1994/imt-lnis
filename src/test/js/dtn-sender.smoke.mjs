import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../../main/resources/static/dtn-sender.html', import.meta.url), 'utf8');
const source = readFileSync(new URL('../../main/resources/static/assets/dtn.js', import.meta.url), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace(/initialize\(\);\s*$/, 'globalThis.ready = initialize();');
class Element {
  constructor() { this.value = ''; this.files = []; this.textContent = ''; this.classList = {toggle() {}}; }
  replaceChildren(...children) { this.value = children[0]?.value ?? ''; }
  setAttribute() {} removeAttribute(key) { delete this[key]; } reportValidity() { return true; }
}
const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
let loaded = null, currentJob = null, failUpload = false, starts = 0;
const tx = {agentId: 'sender-1', role: 'SENDER', state: 'READY'}, rx = {agentId: 'receiver-1', role: 'RECEIVER', state: 'READY'};
const observations = {epochs: [{observation: {week: 2400, receiverTowSeconds: 1, observations: []}}]};
const context = {
  document: {getElementById: id => { assert.ok(elements.has(id), 'missing ' + id); return elements.get(id); }, querySelectorAll: () => []},
  createPayloadViewer: () => ({setJob() {}}),
  createObservationView: () => ({setData(data) { loaded = data; }}),
  numeric: (n, d = 3) => typeof n === 'number' && Number.isFinite(n) ? n.toFixed(d) : '—',
  Option: function(text, value) { this.value = value; },
  location: {protocol: 'http:', host: '127.0.0.1:18090'}, WebSocket: class {},
  URL, setInterval() {}, setTimeout() {},
  fetch: async (url, options) => {
    let body = {};
    if (url.endsWith('/config')) body = {maximumInputBytes: 1048576, exampleEnabled: false};
    else if (url.endsWith('/agents')) body = [tx, rx];
    else if (url.endsWith('/node/connection')) body = {ip: '127.0.0.1', port: 18091, editable: true};
    else if (url.endsWith('/tests') && options.method === 'POST') { starts++; currentJob = {testId: 't1', state: 'PREPARING', updatedAt: '1'}; body = currentJob; }
    else if (url.endsWith('/tests')) body = [];
    else if (url.endsWith('/inputs')) {
      if (failUpload) return {ok: false, json: async () => ({message: 'bad input'})};
      body = {inputId: 'input1'};
    }
    else if (url.endsWith('/complete')) body = {recordCount: 2};
    else if (url.endsWith('/captures')) { assert.equal(JSON.parse(options.body).singleEpoch, true); body = {inputId: 'capture1'}; }
    else if (url.endsWith('/inputs/capture1')) body = {complete: true};
    else if (url.endsWith('/pvt')) body = [{positionValid: true, velocityValid: true, ecefMeters: [1, 2, 3], velocityMetersPerSecond: [0, 0, 0]}];
    else if (url.endsWith('/observations')) body = observations;
    else if (url.endsWith('/tests/t1')) body = currentJob;
    else if (url.endsWith('/report')) body = {referencePvt: [{week: 2400, towSeconds: 1,
      positionValid: false, velocityValid: false, ecefMeters: [999, 999, 999], velocityMetersPerSecond: [999, 999, 999]}], observations};
    return {ok: true, json: async () => body};
  }
};
vm.createContext(context); vm.runInContext(source, context); await context.ready;
assert.equal(elements.get('dtn-example').hidden, true);
assert.equal(elements.get('dtn-start').disabled, true);
assert.equal(elements.get('dtn-send').disabled, true);
elements.get('dtn-send-url').value = 'http://127.0.0.1:18092/transfers';
const file = {name: 'capture.graw', size: 10, arrayBuffer: async () => new ArrayBuffer(10)};
await context.upload(file);
assert.equal(loaded, observations);
assert.equal(elements.get('dtn-send').disabled, false);
await elements.get('dtn-send').onclick();
assert.equal(starts, 1);
assert.equal(elements.get('dtn-upload').disabled, true);
await elements.get('dtn-send').onclick();
assert.equal(starts, 1, 'duplicate start prevented');
currentJob = {testId: 't1', state: 'COMPLETED', referenceEpochs: 1, verdict: 'INCONCLUSIVE', updatedAt: '2'};
await context.poll();
assert.equal(elements.get('pvt-x').textContent, '—', 'invalid PVT must never show stale coordinates');
assert.equal(elements.get('dtn-upload').disabled, false);
failUpload = true;
await assert.rejects(context.upload(file));
assert.equal(loaded, null);
assert.equal(elements.get('dtn-send').disabled, true);
console.log('PASS: sender upload, preview, disabled example/capture, duplicate start, invalid PVT and input failure reset');
elements.get('dtn-port').value = '/dev/ttyACM0';
elements.get('dtn-port').onchange();
assert.equal(elements.get('dtn-start').disabled, false);
await elements.get('dtn-start').onclick();
assert.equal(elements.get('pvt-x').textContent, '1.000');
assert.equal(elements.get('dtn-send').disabled, false);
assert.equal(elements.get('dtn-port').disabled, false);
console.log('PASS: serial one-shot completion, PVT preview and transfer readiness');
