import {pageSource} from './browser-source.mjs';
import {numeric} from '../../main/resources/static/assets/dtn/dtn-observations.js';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../../main/resources/static/dtn-sender.html', import.meta.url), 'utf8');
const capturePanel = html.split('id="dtn-capture-panel"')[1].split('</div>')[0];
const settingsMarkup = html.split('id="dtn-settings-view"')[1].split('id="dtn-main-view"')[0];
const mainMarkup = html.split('id="dtn-main-view"')[1];
for (const id of ['dtn-send-url', 'dtn-receiver-ip', 'dtn-port', 'dtn-baud', 'dtn-graw-file', 'dtn-upload', 'iq-saved', 'iq-delete', 'dtn-development']) {
  assert.ok(settingsMarkup.includes('id="' + id + '"'), id + ' belongs in settings');
  assert.ok(!mainMarkup.includes('id="' + id + '"'));
}
for (const id of ['dtn-start', 'dtn-send', 'iq-generate', 'iq-cancel', 'iq-file', 'dtn-observations', 'dtn-payload', 'dtn-log']) {
  assert.ok(mainMarkup.includes('id="' + id + '"'), id + ' stays on main view');
  assert.ok(!settingsMarkup.includes('id="' + id + '"'));
}
const ids = [...html.matchAll(/id="([^"]+)"/g)].map(match => match[1]);
assert.equal(new Set(ids).size, ids.length, 'controls are moved, not duplicated');
assert.ok(!html.includes('class="card dtn-comparison-card"'), 'PVT comparison must not occupy a separate card');
assert.ok(!html.includes('id="dtn-comparison"') && !html.includes('id="dtn-report"'), 'comparison and report link are receiver-only');
assert.ok(capturePanel.includes('id="dtn-baud"'), 'serial speed remains available in COM input');
assert.ok(!capturePanel.includes('<details'), 'serial settings must be visible without expanding');
for (const key of ['maxNumberOfBundlesInPipeline', 'maxSumOfBundleBytesInPipeline', 'enforceBundlePriority',
  'neighborDepletedStorageDelaySeconds', 'maxBundleSizeBytes', 'tcpclMaxSegmentSizeBytes', 'storageDeletionPolicy']) {
  assert.ok(html.includes('id="hdtn-' + key + '" title="' + key + ':'), 'each HDTN control explains its meaning on hover');
}
const source = pageSource('dtn.js');
class Element {
  constructor() { this.value = ''; this.files = []; this.textContent = ''; this.classList = {toggle() {}}; }
  replaceChildren(...children) { this.value = children[0]?.value ?? ''; }
  setAttribute() {} removeAttribute(key) { delete this[key]; } reportValidity() { return true; }
  focus() { this.focused = true; }
}
const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
assert.match(html, /id="dtn-development"[^>]*\bhidden\b/);
elements.get('dtn-development').hidden = true;
elements.get('dtn-settings-view').hidden = true;
let loaded = null, currentJob = null, failUpload = false, starts = 0, lastStartBody, cancels = 0;
let healthFetch, healthCalls = 0, healthUrl;
const intervals = [];
const tx = {agentId: 'sender-1', role: 'SENDER', state: 'READY'}, rx = {agentId: 'receiver-1', role: 'RECEIVER', state: 'READY'};
const observations = {epochs: [{observation: {week: 2400, receiverTowSeconds: 1, observations: []}}]};
const context = {
  renderIqFile() {},
  createDtnLog: () => ({write() {},setContext() {},refresh() {}}),
  document: {visibilityState: 'visible', getElementById: id => { assert.ok(elements.has(id), 'missing ' + id); return elements.get(id); }, querySelectorAll: () => []},
  createPayloadViewer: () => ({setJob() {}}),
  createObservationView: () => ({setData(data) { loaded = data; }, select() {}}),
  numeric,
  Option: function(text, value) { this.value = value; },
  location: {protocol: 'http:', host: '127.0.0.1:18090'}, WebSocket: class {},
  window: {scrollY: 250, scrollTo({top}) { this.scrollY = top; }},
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
    else if (url.endsWith('/tests') && options.method === 'POST') { starts++; lastStartBody = JSON.parse(options.body); currentJob = {testId: 't1', state: 'PREPARING', updatedAt: '1'}; body = currentJob; }
    else if (url.endsWith('/tests')) body = [];
    else if (url.endsWith('/inputs?dtn=true')) {
      if (failUpload) return {ok: false, json: async () => ({message: 'bad input'})};
      body = {inputId: 'input1'};
    }
    else if (url.endsWith('/complete')) body = {recordCount: 2};
    else if (url.endsWith('/captures')) { assert.equal(JSON.parse(options.body).singleEpoch, true); body = {inputId: 'capture1'}; }
    else if (url.endsWith('/inputs/capture1')) body = {complete: true};
    else if (url.endsWith('/pvt')) body = [{positionValid: true, velocityValid: true, ecefMeters: [1, 2, 3], velocityMetersPerSecond: [0, 0, 0]}];
    else if (url.endsWith('/observations')) body = observations;
    else if (url.endsWith('/tests/t1/cancel')) { cancels++; currentJob = {...currentJob, state: 'CANCELLED', cancelPending: false}; body = currentJob; }
    else if (url.endsWith('/tests/t1')) body = currentJob;
    else if (url.endsWith('/report')) body = {referencePvt: [{week: 2400, towSeconds: 1,
      positionValid: false, velocityValid: false, ecefMeters: [999, 999, 999], velocityMetersPerSecond: [999, 999, 999]}], observations};
    return {ok: true, json: async () => body};
  }
};
vm.createContext(context); vm.runInContext(source, context); await context.ready;
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
elements.get('dtn-graw-file').files = [file];
const timerCount = intervals.length;
await elements.get('dtn-settings-open').onclick();
assert.equal(elements.get('dtn-main-view').hidden, true);
assert.equal(elements.get('dtn-settings-view').hidden, false);
assert.equal(elements.get('dtn-settings-title').focused, true);
const uploading = context.upload(file);
assert.equal(elements.get('dtn-settings-lock').hidden, false);
assert.equal(elements.get('dtn-graw-file').disabled, true);
await uploading;
assert.equal(elements.get('dtn-settings-view').hidden, false, 'file apply stays in settings');
assert.match(elements.get('dtn-settings-feedback').textContent, /입력 완료/);
assert.match(elements.get('dtn-input-summary').textContent, /capture.graw/);
await elements.get('dtn-settings-close').onclick();
assert.equal(elements.get('dtn-main-view').hidden, false);
assert.equal(context.window.scrollY, 250);
assert.equal(elements.get('dtn-graw-file').files[0], file, 'selected file survives navigation');
assert.equal(intervals.length, timerCount, 'navigation does not start extra polling');
assert.equal(elements.get('dtn-development').hidden, true);
assert.equal(loaded, observations);
assert.equal(elements.get('dtn-send').disabled, false);
await elements.get('dtn-send').onclick();
assert.equal(starts, 1);
assert.equal(lastStartBody.hdtnConfig.maxNumberOfBundlesInPipeline, 50);
assert.equal(lastStartBody.hdtnConfig.enforceBundlePriority, true);
assert.equal(lastStartBody.hdtnConfig.tcpclMaxSegmentSizeBytes, 200000);
assert.equal(elements.get('hdtn-maxBundleSizeBytes').disabled, true);
assert.equal(elements.get('dtn-upload').disabled, true);
await elements.get('dtn-settings-open').onclick();
assert.equal(elements.get('dtn-settings-view').hidden, false, 'settings remain readable during transfer');
assert.equal(elements.get('dtn-settings-lock').hidden, false);
assert.equal(elements.get('dtn-adapter-save').disabled, true);
await elements.get('dtn-settings-close').onclick();
await elements.get('dtn-send').onclick();
assert.equal(starts, 1, 'duplicate start prevented');
currentJob = {testId: 't1', state: 'COMPLETED', referenceEpochs: 1, verdict: 'INCONCLUSIVE', updatedAt: '2'};
await context.poll();
assert.equal(elements.get('pvt-x').textContent, '—', 'invalid PVT must never show stale coordinates');
assert.equal(elements.get('dtn-upload').disabled, false);
assert.equal(elements.get('dtn-settings-lock').hidden, true);
assert.equal(elements.get('dtn-adapter-save').disabled, false);
failUpload = true;
await assert.rejects(context.upload(file));
assert.equal(loaded, null);
assert.equal(elements.get('dtn-send').disabled, true);
console.log('PASS: sender upload, preview, disabled example/capture, duplicate start, invalid PVT and input failure reset');
elements.get('dtn-port').value = '/dev/ttyACM0';
elements.get('dtn-port').onchange();
assert.equal(elements.get('dtn-start').disabled, false);
const capturing = elements.get('dtn-start').onclick();
assert.equal(elements.get('dtn-settings-lock').hidden, false);
assert.equal(elements.get('dtn-port').disabled, true);
await capturing;
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
assert.equal(elements.get('dtn-adapter-status').textContent, '연결됨');
assert.equal(elements.get('dtn-adapter-dot').className, 'connection-dot online');
assert.equal(elements.get('dtn-adapter-summary').textContent, '연결됨');
assert.equal(elements.get('dtn-adapter-summary-dot').className, 'connection-dot online');
assert.match(elements.get('dtn-adapter-health-json').textContent, /"rawResponse": "{\\"status\\":\\"ready\\"}"/);
assert.match(elements.get('dtn-adapter-health-time').textContent, /마지막 확인/);
assert.equal(elements.get('dtn-adapter-health').disabled, false);
assert.equal(elements.get('dtn-send').disabled, true, 'health check must not unlock the active trial');
healthFetch = async () => { throw new Error('server unreachable'); };
await elements.get('dtn-adapter-health').onclick();
assert.equal(elements.get('dtn-adapter-status').textContent, '연결실패');
assert.equal(elements.get('dtn-adapter-dot').className, 'connection-dot offline', 'old success must not survive a failed check');
assert.match(elements.get('dtn-adapter-health-json').textContent, /server unreachable/);
assert.equal(elements.get('dtn-adapter-health').disabled, false);
console.log('PASS: adapter status JSON, URL derivation, collapsible details, polling and manual retry');

healthFetch = async () => ({ok: true, json: async () => ({...report, adapter: report.receiver})});
await elements.get('dtn-adapter-health').onclick();
assert.equal(elements.get('dtn-adapter-status').textContent, '시험대기');
assert.equal(elements.get('dtn-adapter-dot').className, 'connection-dot unknown');
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
assert.equal(elements.get('dtn-adapter-save').disabled,true);
vm.runInContext("inputMode='capture'; updateInputPanels();",context);
assert.equal(elements.get('dtn-start').hidden,false);
vm.runInContext("inputMode='upload'; updateInputPanels();",context);
assert.equal(elements.get('dtn-start').hidden,true);
vm.runInContext("iqJob={id:'old',state:'READY',file:{}}; clearIqSelection(); updateControls();",context);
assert.equal(elements.get('iq-file').textContent,'');
assert.equal(elements.get('iq-saved').value,'');
console.log('PASS: I/Q GNSS requirement, COM controls, active generation visibility and stale file reset');
const savedAddresses = new Map();
context.localStorage = {getItem: key => savedAddresses.get(key), removeItem: key => savedAddresses.delete(key), setItem: (key, value) => savedAddresses.set(key, value)};
healthFetch = async () => ({ok: true, json: async () => report});
elements.get('dtn-send-url').disabled = false;
elements.get('dtn-send-url').value = 'http://saved-adapter:8080';
await elements.get('dtn-adapter-save').onclick();
assert.equal(savedAddresses.get('lnis.adapter-url.dtn'), 'http://saved-adapter:8080');
vm.runInContext("initAdapterHealth('http://default:8080')", context);
assert.equal(elements.get('dtn-send-url').value, 'http://saved-adapter:8080');
elements.get('dtn-send-url').value = 'javascript:alert(1)';
await elements.get('dtn-adapter-save').onclick();
assert.equal(savedAddresses.get('lnis.adapter-url.dtn'), 'http://saved-adapter:8080');
console.log('PASS: adapter address browser persistence, restore and invalid address rejection');

// Settings validation, persistence and route-specific wire values.
savedAddresses.set('lnis.hdtnConfig.v1', JSON.stringify({maxNumberOfBundlesInPipeline: 65}));
context.initializeHdtnConfig();
assert.equal(elements.get('hdtn-maxNumberOfBundlesInPipeline').value, '65');
assert.equal(elements.get('hdtn-tcpclMaxSegmentSizeBytes').value, '200000', 'old saved settings gain only the new default');
elements.get('hdtn-tcpclMaxSegmentSizeBytes').value = '300000';
elements.get('hdtn-maxNumberOfBundlesInPipeline').value = '75';
elements.get('hdtn-enforceBundlePriority').value = 'false';
elements.get('hdtn-neighborDepletedStorageDelaySeconds').value = '0';
elements.get('hdtn-maxNumberOfBundlesInPipeline').onchange();
assert.equal(JSON.parse(savedAddresses.get('lnis.hdtnConfig.v1')).maxNumberOfBundlesInPipeline, 75);
context.initializeHdtnConfig();
assert.equal(elements.get('hdtn-maxNumberOfBundlesInPipeline').value, '75');
for (const [txMode, rxMode] of [['DTN', 'HDTN'], ['HDTN', 'DTN'], ['HDTN', 'HDTN'], ['DTN', 'DTN']]) {
  vm.runInContext(`job=null; busy=false; captureId=null; iqJob=null; inputId='input1'; selectedType='GNSS_RAW'; senderMode='${txMode}'; receiverMode='${rxMode}';`,context);
  elements.get('dtn-send-url').value = 'http://adapter:8080';
  context.updateControls();
  await elements.get('dtn-send').onclick();
  const usesHdtn = txMode === 'HDTN' || rxMode === 'HDTN';
  assert.equal(Object.hasOwn(lastStartBody, 'hdtnConfig'), usesHdtn);
  assert.equal(Object.hasOwn(lastStartBody, 'dtnConfig'), false);
  if (usesHdtn) {
    assert.equal(lastStartBody.hdtnConfig.maxNumberOfBundlesInPipeline, 75);
    assert.equal(lastStartBody.hdtnConfig.tcpclMaxSegmentSizeBytes, 300000);
    assert.equal(lastStartBody.hdtnConfig.enforceBundlePriority, false);
    assert.equal(lastStartBody.hdtnConfig.neighborDepletedStorageDelaySeconds, 0);
  }
}
vm.runInContext("job=null; senderMode='DTN'; receiverMode='HDTN'; updateControls();", context);
for (const invalid of ['', '-1', '0', '1.5', '9007199254740992']) {
  elements.get('hdtn-maxBundleSizeBytes').value = invalid;
  const before = starts;
  await elements.get('dtn-send').onclick();
  assert.equal(starts, before, 'invalid setting must not start/reset a trial');
}
context.initializeHdtnConfig();
for (const invalid of ['', '1399', '1000001', '1400.5']) {
  elements.get('hdtn-tcpclMaxSegmentSizeBytes').value = invalid;
  const before = starts;
  await elements.get('dtn-send').onclick();
  assert.equal(starts, before);
}
for (const boundary of ['1400', '1000000']) {
  elements.get('hdtn-tcpclMaxSegmentSizeBytes').value = boundary;
  assert.equal(context.readHdtnConfig().tcpclMaxSegmentSizeBytes, Number(boundary));
}
context.initializeHdtnConfig();
console.log('PASS: HDTN defaults, custom numeric/boolean values, route omission, validation, persistence and locking');

vm.runInContext("job={testId:'t1',state:'WAITING_DTN'}; busy=false; updateControls();", context);
assert.equal(elements.get('dtn-cancel').disabled, false);
const ordinaryFetch = context.fetch;
let releaseOldJob;
context.fetch = async (url, options) => {
  if (url.endsWith('/tests/t1')) return {ok:true,json:async()=>await new Promise(resolve=>{releaseOldJob=resolve;})};
  return ordinaryFetch(url, options);
};
const oldPoll = context.poll();
while (!releaseOldJob) await new Promise(setImmediate);
await elements.get('dtn-cancel').onclick();
assert.equal(cancels, 1);
releaseOldJob({testId:'t1',state:'WAITING_DTN'});
await oldPoll;
assert.equal(vm.runInContext('job.state',context),'CANCELLED', 'late poll cannot revive cancelled trial');
assert.equal(elements.get('dtn-cancel').disabled,true);
assert.equal(elements.get('dtn-upload').disabled,false);
await elements.get('dtn-cancel').onclick(); assert.equal(cancels,1);
vm.runInContext("job={testId:'t1',state:'FAILED'};updateControls();",context);
assert.equal(elements.get('dtn-cancel').disabled,false,'failed sender can clean up waiting receiver');
context.fetch = ordinaryFetch;
console.log('PASS: trial cancellation, repeat prevention, failed trial cleanup and stale polling guard');

// New mode selects exactly one source epoch; original API path above stays unchanged.
vm.runInContext("config.delaySupported=true; selectedType='GNSS_RAW'; job=null; busy=false; inputId='input1';",context);
elements.get('dtn-comparison-mode').checked=true;
vm.runInContext("delayChoices=[{epoch:{recordIndex:95},reference:{positionValid:false}},{epoch:{recordIndex:96,week:2400,towSeconds:100000},reference:{positionValid:true,velocityValid:false}}]",context);
context.updateControls();
assert.equal(elements.has('dtn-epoch-summary'),false);
assert.equal(elements.has('dtn-epoch-options'),false);
assert.equal(elements.get('dtn-send').disabled,false);
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.comparisonMode,'DELAY');
assert.equal(lastStartBody.selectedEpoch.recordIndex,96);
vm.runInContext("job=null; delayChoices=[];",context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled,true,'invalid Reference cannot start');

let clearedHistoryRequests = 0;
const cleared = vm.createContext({...context, location: {...context.location, pathname: '/lnis/dtntest/sender/clear'},
  fetch: async (url, options) => {
    if (url.endsWith('/dtn/tests')) { clearedHistoryRequests++; return {ok: true, json: async () => [{testId:'previous',state:'COMPLETED'}]}; }
    return context.fetch(url, options);
  }});
vm.runInContext(source, cleared);
await cleared.ready;
assert.equal(clearedHistoryRequests, 0);
assert.equal(savedAddresses.has('lnis.hdtnConfig.v1'), false);
assert.equal(elements.get('hdtn-maxNumberOfBundlesInPipeline').value, '50');
assert.equal(savedAddresses.has('lnis.adapter-url.dtn'), false);
assert.equal(elements.get('dtn-send-url').value, 'http://sender.default:8080');
assert.equal(vm.runInContext('job', cleared), null);
assert.equal(vm.runInContext('inputId', cleared), null);
console.log('PASS: sender clear starts without previous job or input');
