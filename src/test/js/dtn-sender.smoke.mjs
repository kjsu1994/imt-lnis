import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../../main/resources/static/dtn-sender.html', import.meta.url), 'utf8');
const capturePanel = html.split('id="dtn-capture-panel"')[1].split('</div>')[0];
assert.ok(!html.includes('class="card dtn-comparison-card"'), 'PVT comparison must not occupy a separate card');
assert.ok(html.split('송신 기준 지구 PVT')[1].split('</section>')[0].includes('id="dtn-comparison"'), 'comparison stays inside sender PVT section');
assert.ok(capturePanel.includes('id="dtn-baud"'), 'serial speed remains available in COM input');
assert.ok(!capturePanel.includes('<details'), 'serial settings must be visible without expanding');
const source = readFileSync(new URL('../../main/resources/static/assets/dtn.js', import.meta.url), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace(/initialize\(\);\s*$/, 'globalThis.ready = initialize();');
class Element {
  constructor() { this.value = ''; this.files = []; this.textContent = ''; this.classList = {toggle() {}}; }
  replaceChildren(...children) { this.value = children[0]?.value ?? ''; }
  setAttribute() {} removeAttribute(key) { delete this[key]; } reportValidity() { return true; }
}
const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
assert.match(html, /id="dtn-development"[^>]*\bhidden\b/);
elements.get('dtn-development').hidden = true;
let loaded = null, currentJob = null, failUpload = false, starts = 0;
let healthFetch, healthCalls = 0, healthUrl;
const intervals = [];
const tx = {agentId: 'sender-1', role: 'SENDER', state: 'READY'}, rx = {agentId: 'receiver-1', role: 'RECEIVER', state: 'READY'};
const observations = {epochs: [{observation: {week: 2400, receiverTowSeconds: 1, observations: []}}]};
const context = {
  document: {visibilityState: 'visible', getElementById: id => { assert.ok(elements.has(id), 'missing ' + id); return elements.get(id); }, querySelectorAll: () => []},
  createPayloadViewer: () => ({setJob() {}}),
  createObservationView: () => ({setData(data) { loaded = data; }}),
  numeric: (n, d = 3) => typeof n === 'number' && Number.isFinite(n) ? n.toFixed(d) : '—',
  Option: function(text, value) { this.value = value; },
  location: {protocol: 'http:', host: '127.0.0.1:18090'}, WebSocket: class {},
  URL, AbortSignal, setInterval(callback, delay) { intervals.push({callback, delay}); }, setTimeout() {},
  fetch: async (url, options) => {
    if (url.includes('/adapter-health?')) {
      healthCalls++; healthUrl = url;
      assert.equal(options.method, undefined, 'health checks use GET');
      return healthFetch();
    }
    let body = {};
    if (url.endsWith('/config')) body = {maximumInputBytes: 1048576, exampleEnabled: true,
      defaultSendUrl: 'http://sender.default:8080', defaultReceiveUrl: 'http://receiver.default:8080'};
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
vm.createContext(context); vm.runInContext(readFileSync(new URL('../../main/resources/static/assets/dtn-adapter-health.js', import.meta.url), 'utf8').replace('export function', 'function') + '\n' + source, context); await context.ready;
assert.equal(elements.has('dtn-example'), false);
assert.equal(html.includes('합성 GRAW 다운로드'), false);
assert.equal(html.includes('합성 데이터 · 실측 아님'), false);
assert.equal(elements.has('dtn-replay'), true);
assert.equal(elements.has('reverse-state'), false);
assert.equal(elements.has('destination-state'), true);
assert.equal(elements.get('dtn-development').hidden, true);
assert.equal(elements.get('dtn-start').disabled, true);
assert.equal(elements.get('dtn-send').disabled, true);
assert.equal(elements.get('dtn-send-url').value, 'http://sender.default:8080');
assert.equal(elements.has('dtn-receive-url'), false);
elements.get('dtn-send-url').value = 'http://127.0.0.1:18092';
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

await elements.get('dtn-send').onclick();
assert.ok(intervals.some(value => value.delay === 10000), 'adapter health polls every 10 seconds');
let resolveHealth;
healthFetch = () => new Promise(resolve => { resolveHealth = resolve; });

const checking = elements.get('dtn-adapter-health').onclick();
assert.equal(elements.get('dtn-adapter-health').disabled, true);
assert.match(elements.get('dtn-adapter-status').textContent, /확인 중/);
await elements.get('dtn-adapter-health').onclick();
assert.equal(healthCalls, 1, 'duplicate probes must be prevented');
const report = {
  checkedAt: '2026-09-14T01:00:00Z',
  adapter: {ok: true, status: 'ready', message: '정상연결', httpStatus: 200, elapsedMillis: 12,
    url: 'http://127.0.0.1:18092/sender/health', response: {status: 'ready'}, rawResponse: '{"status":"ready"}'},
  receiver: {ok: false, status: 'busy', message: '시험대기', httpStatus: 200, elapsedMillis: 13,
    url: 'http://127.0.0.1:18092/receiver/health', response: {status: 'busy'}, rawResponse: '{"status":"busy"}'}
};
resolveHealth({ok: true, json: async () => report});
await checking;
assert.match(healthUrl, /adapterUrl=http%3A%2F%2F127\.0\.0\.1%3A18092$/);
assert.equal(elements.get('dtn-adapter-status').textContent, '정상연결');
assert.equal(elements.get('dtn-adapter-status').className, 'pill online');
assert.match(elements.get('dtn-adapter-health-json').textContent, /"rawResponse": "{\\"status\\":\\"ready\\"}"/);
assert.match(elements.get('dtn-adapter-health-time').textContent, /마지막 확인/);
assert.equal(elements.get('dtn-adapter-health').disabled, false);
assert.equal(elements.get('dtn-send').disabled, true, 'health check must not unlock the active trial');
healthFetch = async () => { throw new Error('server unreachable'); };
await elements.get('dtn-adapter-health').onclick();
assert.equal(elements.get('dtn-adapter-status').textContent, '연결실패');
assert.equal(elements.get('dtn-adapter-status').className, 'pill error', 'old success must not survive a failed check');
assert.match(elements.get('dtn-adapter-health-json').textContent, /server unreachable/);
assert.equal(elements.get('dtn-adapter-health').disabled, false);
console.log('PASS: adapter status JSON, URL derivation, collapsible details, polling and manual retry');

healthFetch = async () => ({ok: true, json: async () => ({...report, adapter: report.receiver})});
await elements.get('dtn-adapter-health').onclick();
assert.equal(elements.get('dtn-adapter-status').textContent, '시험대기');
assert.equal(elements.get('dtn-adapter-status').className, 'pill warning');
healthFetch = () => new Promise(resolve => { resolveHealth = resolve; });
const stale = elements.get('dtn-adapter-health').onclick();
elements.get('dtn-send-url').value = 'http://changed:8080';
elements.get('dtn-send-url').oninput();
resolveHealth({ok: true, json: async () => report});
await stale;
assert.equal(elements.get('dtn-adapter-status').textContent, '확인 대기');
const callsBeforeHidden = healthCalls;
context.document.visibilityState = 'hidden';
intervals.find(value => value.delay === 10000).callback();
assert.equal(healthCalls, callsBeforeHidden);
console.log('PASS: busy status, stale response ignored and hidden tab skips polling');

vm.runInContext("job=null; selectedType='IQ_SAMPLE'; inputId=null; config.iqEnabled=true; updateControls();", context);
assert.equal(elements.get('iq-generate').disabled, true, 'GNSS input required for generation');
vm.runInContext("inputId='captured'; updateControls();", context);
assert.equal(elements.get('iq-generate').disabled, false);
assert.equal(elements.get('dtn-start').disabled, false, 'COM capture remains available for I/Q');
let iqPanelHidden;
elements.get('dtn-iq-panel').classList.toggle = (name, hidden) => { if(name==='hidden') iqPanelHidden=hidden; };
vm.runInContext("selectedType='AFS_METADATA'; iqJob={id:'running',state:'GENERATING'}; updateInputPanels(); updateControls();",context);
assert.equal(iqPanelHidden,false,'reload during generation keeps cancel visible even on another trial tab');
assert.equal(elements.get('iq-cancel').disabled,false);
vm.runInContext("iqJob={id:'old',state:'READY',file:{}}; clearIqSelection(); updateControls();",context);
assert.equal(elements.get('iq-file').textContent,'');
assert.equal(elements.get('iq-saved').value,'');
console.log('PASS: I/Q GNSS requirement, COM controls, active generation visibility and stale file reset');
