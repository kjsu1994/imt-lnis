import {pageSource} from './browser-source.mjs';
import {numeric, renderClockBias, receiverInformation} from '../../main/resources/static/assets/dtn/dtn-observations.js';
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
  constructor() { this.value = ''; this.options = []; this.files = []; this.textContent = ''; this.classList = {toggle() {}}; }
  replaceChildren(...children) { this.options = children; this.value = children[0]?.value ?? ''; }
  add(option) { this.options.push(option); }
  addEventListener() {}
  setAttribute() {} removeAttribute(key) { delete this[key]; } reportValidity() { return true; }
  focus() { this.focused = true; }
}
const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
// The observation view creates these controls inside its header at runtime.
for (const id of ['dtn-receiver-info', 'dtn-receiver-info-body']) elements.set(id, new Element());
assert.match(html, /id="dtn-development"[^>]*\bhidden\b/);
elements.get('dtn-development').hidden = true;
elements.get('dtn-settings-view').hidden = true;
let loaded = null, currentJob = null, failUpload = false, starts = 0, lastStartBody, cancels = 0;
let observationSelection, displayedPvt;
let healthFetch, healthCalls = 0, healthUrl;
let gnssStatus = {state: 'DISCONNECTED', timeState: 'UNAVAILABLE'};
const intervals = [];
const tx = {agentId: 'sender-1', role: 'SENDER', state: 'READY'}, rx = {agentId: 'receiver-1', role: 'RECEIVER', state: 'READY'};
const observations = {receiverInfo:{model:'ZED-F9T-20B',firmware:'TIM 2.25',protocol:'29.25',supportedConstellations:['GPS','BeiDou']},epochs: [{observation: {week: 2400, receiverTowSeconds: 1, observations: []}}]};
const context = {
  renderIqFile() {},
  createDtnLog: () => ({write() {},setContext() {},refresh() {}}),
  document: {visibilityState: 'visible', getElementById: id => { assert.ok(elements.has(id), 'missing ' + id); return elements.get(id); }, querySelectorAll: () => []},
  createPayloadViewer: () => ({setJob() {}}),
  createObservationView: (_container, onSelect) => {
    observationSelection = onSelect;
    return {setData(data) { loaded = data; }, select() {}, setPvt(values) { displayedPvt = values; }};
  },
  numeric, renderClockBias, receiverInformation,
  URLSearchParams,
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
    if (url.endsWith('/node/gnss/ports')) return {ok: true, json: async () => []};
    if (url.endsWith('/node/clock')) return {ok: true, json: async () => ({busy:false, clock:{
      trialAt:'2026-09-29T12:00:00Z', rawAt:'2026-09-29T12:00:00Z', source:'SYSTEM',
      offsetSeconds:0, ageSeconds:0, clockChanges:0}})};
    if (url.endsWith('/node/gnss')) return {ok: true, json: async () => gnssStatus};
    if (url.endsWith('/config')) body = {maximumInputBytes: 1048576, exampleEnabled: true, delaySupported:true,
      defaultSendUrl: 'http://sender.default:8080', defaultReceiveUrl: 'http://receiver.default:8080'};
    else if (url.endsWith('/agents')) body = [tx, rx];
    else if (url.endsWith('/node/connection')) body = {ip: '127.0.0.1', port: 18091, editable: true};
    else if (url.endsWith('/tests') && options.method === 'POST') { starts++; lastStartBody = JSON.parse(options.body); currentJob = {testId: 't1', state: 'PREPARING', updatedAt: '1'}; body = currentJob; }
    else if ((url.endsWith('/tests') || url.includes('/tests?'))) body = [];
    else if (url.endsWith('/inputs?dtn=true')) {
      if (failUpload) return {ok: false, json: async () => ({message: 'bad input'})};
      body = {inputId: 'input1'};
    }
    else if (url.endsWith('/complete')) body = {recordCount: 2};
    else if (url.endsWith('/captures')) { assert.equal(JSON.parse(options.body).singleEpoch, true); body = {inputId: 'capture1'}; }
    else if (url.endsWith('/inputs/capture1')) body = {complete: true};
    else if (url.endsWith('/pvt')) body = [{positionValid: true, velocityValid: true, ecefMeters: [1, 2, 3], velocityMetersPerSecond: [0, 0, 0]}];
    else if (url.endsWith('/observations')) body = observations;
    else if (url.endsWith('/delay-epochs')) body = [{epoch:{recordIndex:0,week:2400,towSeconds:1},reference:{positionValid:true}}];
    else if (url.endsWith('/tests/t1/cancel')) { cancels++; currentJob = {...currentJob, state: 'CANCELLED', cancelPending: false}; body = currentJob; }
    else if (url.endsWith('/tests/t1')) body = currentJob;
    else if (url.endsWith('/report')) body = {referencePvt: [{week: 2400, towSeconds: 1,
      positionValid: false, velocityValid: false, ecefMeters: [999, 999, 999], velocityMetersPerSecond: [999, 999, 999]}], observations};
    return {ok: true, json: async () => body};
  }
};
vm.createContext(context); vm.runInContext(source, context); await context.ready;
const selectInputEpoch = observationSelection;
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
assert.equal(elements.get('dtn-receiver-info').hidden,false);
assert.match(elements.get('dtn-receiver-info-body').textContent,/29.25/);
assert.equal(displayedPvt[0].positionValid, true);
assert.equal(elements.get('dtn-send').disabled, false);
await elements.get('dtn-send').onclick();
assert.equal(starts, 1);
assert.equal(lastStartBody.hdtnConfig.maxNumberOfBundlesInPipeline, 50);
assert.equal(lastStartBody.hdtnConfig.enforceBundlePriority, false);
assert.equal(lastStartBody.hdtnConfig.tcpclMaxSegmentSizeBytes, 20000);
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
assert.equal(elements.get('dtn-start').disabled, true, 'selecting a port does not connect it');
gnssStatus = {state: 'CONNECTED', timeState: 'ACQUIRING', portName: '/dev/ttyACM0', baudRate: 38400};
await vm.runInContext('gnss.poll()', context);
assert.equal(elements.get('dtn-start').disabled, false);
const capturing = elements.get('dtn-start').onclick();
assert.equal(elements.get('dtn-settings-lock').hidden, false);
assert.equal(elements.get('dtn-port').disabled, true);
await capturing;
assert.equal(elements.get('pvt-x').textContent, '1.000');
assert.equal(elements.get('dtn-send').disabled, false);
assert.equal(elements.get('dtn-port').disabled, true, 'capture completion keeps the physical connection');
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
context.document.visibilityState = 'visible';
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
assert.equal(elements.get('hdtn-tcpclMaxSegmentSizeBytes').value, '20000', 'old saved settings gain only the new default');
elements.get('hdtn-tcpclMaxSegmentSizeBytes').value = '100000';
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
  await context.loadDelayEpochs();
  context.updateControls();
  await elements.get('dtn-send').onclick();
  const usesHdtn = txMode === 'HDTN' || rxMode === 'HDTN';
  assert.equal(Object.hasOwn(lastStartBody, 'hdtnConfig'), usesHdtn);
  assert.equal(Object.hasOwn(lastStartBody, 'dtnConfig'), false);
  if (usesHdtn) {
    assert.equal(lastStartBody.hdtnConfig.maxNumberOfBundlesInPipeline, 75);
    assert.equal(lastStartBody.hdtnConfig.tcpclMaxSegmentSizeBytes, 100000);
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
for (const boundary of ['20000', '200000']) {
  elements.get('hdtn-tcpclMaxSegmentSizeBytes').value = boundary;
  assert.equal(context.readHdtnConfig().tcpclMaxSegmentSizeBytes, Number(boundary));
}
context.initializeHdtnConfig();
// A single invalid legacy value must not discard valid user settings.
savedAddresses.set('lnis.hdtnConfig.v1', JSON.stringify({maxNumberOfBundlesInPipeline:75,tcpclMaxSegmentSizeBytes:300000,enforceBundlePriority:true}));
context.initializeHdtnConfig();
assert.equal(elements.get('hdtn-maxNumberOfBundlesInPipeline').value,'75');
assert.equal(elements.get('hdtn-tcpclMaxSegmentSizeBytes').value,'20000');
assert.equal(elements.get('hdtn-enforceBundlePriority').value,'true');
assert.equal(elements.get('hdtn-config-notice').hidden,false);
elements.get('hdtn-acsSendPeriodMilliseconds').value='0';
elements.get('hdtn-acsSendPeriodMilliseconds').oninput();
context.updateHdtnControls();
assert.equal(elements.get('hdtn-acsSendPeriodMilliseconds-error').hidden,false);
await elements.get('dtn-send').onclick();
assert.equal(elements.get('hdtn-advanced').open,true);
assert.equal(elements.get('hdtn-acsSendPeriodMilliseconds').focused,true);
elements.get('hdtn-reset').onclick();
assert.equal(elements.get('hdtn-acsSendPeriodMilliseconds').value,'1000');
assert.equal(elements.get('hdtn-enforceBundlePriority').value,'false');
assert.equal(elements.get('hdtn-acsSendPeriodMilliseconds-error').hidden,true);
assert.equal(context.readHdtnConfig().totalStorageCapacityBytes,8589934592);
for (const policy of ['DELETE_AFTER_FORWARDING','on_expiration','on_storage_full','never']) {
  elements.get('hdtn-storageDeletionPolicy').value=policy;
  assert.equal(context.readHdtnConfig().storageDeletionPolicy,policy);
}
elements.get('hdtn-reset').onclick();
for (const [key, rule] of Object.entries(vm.runInContext('hdtnRules',context))) {
  if (typeof rule.default !== 'number') continue;
  const control = elements.get('hdtn-' + key);
  const tag = html.match(new RegExp('<input id="hdtn-' + key + '"[^>]+>'))[0];
  assert.ok(tag.includes('min="' + rule.min + '"'));
  assert.ok(tag.includes('max="' + rule.max + '"'));
  for (const valid of [rule.min,rule.max]) {
    control.value=String(valid);
    assert.equal(context.readHdtnConfig()[key],valid);
  }
  for (const invalid of ['',String(rule.min-1),String(rule.max+1),'1.5']) {
    control.value=invalid;
    assert.throws(()=>context.readHdtnConfig());
  }
  control.value=String(rule.default);
}
assert.ok(!html.includes('id="dtn-config-title"'),'remove empty DTN settings');
elements.get('hdtn-reset').onclick();
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

// RAW/AFS always select exactly one valid source epoch.
vm.runInContext("config.delaySupported=true; selectedType='GNSS_RAW'; job=null; busy=false; inputId='input1';",context);
assert.equal(elements.has('dtn-comparison-mode'),false,'RAW/AFS always use delay PVT');
vm.runInContext("delayChoices=[{epoch:{recordIndex:95},reference:{positionValid:false}},{epoch:{recordIndex:96,week:2400,towSeconds:100000},reference:{positionValid:true,velocityValid:false}}]; inputView=true",context);
selectInputEpoch(1);
context.updateControls();
assert.equal(elements.has('dtn-epoch-summary'),false);
assert.equal(elements.has('dtn-epoch-options'),false);
assert.equal(elements.get('dtn-send').disabled,false);
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.comparisonMode,'DELAY');
assert.equal(lastStartBody.selectedEpoch.recordIndex,96);
currentJob = {testId:'t1',state:'WAITING_DTN',sendStatus:'ACCEPTED',referenceEpochs:1,updatedAt:'first-invalid'};
await context.poll();
assert.equal(elements.get('dtn-send').disabled, false, 'invalid first epoch does not block repeating a valid selection');
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.selectedEpoch.recordIndex,96);
vm.runInContext("job=null; preparedEpoch=null; delayChoices=[];",context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled,true,'invalid Reference cannot start');

vm.runInContext("config.sendBusy=false; busy=false; job=null; selectedType='GNSS_RAW'; inputId='real10'; senderMode='DTN'; receiverMode='DTN'; delayChoices=[{epoch:{recordIndex:4},reference:{positionValid:true}},{epoch:{recordIndex:9},reference:{positionValid:false}},{epoch:{recordIndex:15},reference:{positionValid:true}}]; inputView=true;", context);
selectInputEpoch(2);
assert.equal(context.selectedDelayEpoch().epoch.recordIndex, 15, 'use selected epoch, not first valid epoch');
assert.equal(elements.get('dtn-send').disabled, false);
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.selectedEpoch.recordIndex, 15);
// Polling a one-Epoch result must never replace the prepared input selection.
currentJob = {testId:'t1', state:'WAITING_DTN', sendStatus:'ACCEPTED', referenceEpochs:1, updatedAt:'repeat'};
await context.poll();
assert.equal(vm.runInContext('inputView', context), false);
assert.equal(vm.runInContext('epochIndex', context), 0);
assert.equal(elements.get('dtn-send').disabled, false);
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.inputId, 'real10');
assert.equal(lastStartBody.selectedEpoch.recordIndex, 15, 'repeat sends keep the selected source epoch');
// Selecting a report row is display-only even when its local index is zero.
vm.runInContext("job={testId:'historical',state:'COMPLETED'}; inputView=false;", context);
selectInputEpoch(0);
assert.equal(context.selectedDelayEpoch().epoch.recordIndex, 15, 'history does not reselect the prepared input');
await elements.get('dtn-send').onclick();
assert.equal(lastStartBody.selectedEpoch.recordIndex, 15);
vm.runInContext("busy=false; job=null; inputView=true;", context);
selectInputEpoch(1);
assert.equal(elements.get('dtn-send').disabled, true, 'invalid selected epoch cannot silently substitute another epoch');
assert.match(html, /실측 GNSS 10에폭 불러오기/);
console.log('PASS: selected real-data epoch reaches request and invalid selection is not substituted');

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

// A pending receipt does not block another send, even while the receiver computes.
vm.runInContext("busy=false; config.sendBusy=false; selectedType='IQ_SAMPLE'; iqJob={state:'READY'}; job={testId:'old',state:'WAITING_DTN',sendStatus:'ACCEPTED'};", context);
elements.get('dtn-send-url').value = 'http://adapter:8080';
rx.state = 'BUSY';
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, false);
assert.equal(elements.get('dtn-cancel').textContent, '대기 종료');
vm.runInContext("job.sendStatus='REQUESTING';", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, true);
vm.runInContext("job.sendStatus='UNKNOWN';", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, false);
vm.runInContext("config.sendBusy=true;", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, true, 'other browser send still locks this screen');
console.log('PASS: multiple sends, busy receiver, ambiguous adapter response and global send lock');

elements.get('dtn-port').value = 'COM5';
context.showPorts([{name:'COM4', description:'FT232R USB UART'}, {name:'COM5', description:'u-blox GNSS receiver'}]);
assert.equal(elements.get('dtn-port').value, 'COM5', 'refresh preserves the connected selection');
assert.match(elements.get('dtn-port-status').textContent, /2개 포트/);
context.showPorts([{name:'COM4', description:'FT232R USB UART'}]);
assert.equal(elements.get('dtn-port').value, '', 'unplugged port is no longer selected');
context.showPorts([]);
assert.match(elements.get('dtn-port-status').textContent, /0개 포트/);
assert.match(html, />1 Epoch 수집<\/button>/);
console.log('PASS: live serial-port refresh, selection preservation and unplug removal');

for (const [state, verdict, label, tone] of [
  ['COMPLETED', 'PASS', '완료', 'success'], ['WAITING_DTN', null, '수신 대기', 'waiting'],
  ['FAILED', null, '실패', 'failure'], ['COMPLETED', 'FAIL', '완료 · 불일치', 'failure'],
  ['INCONCLUSIVE', 'INCONCLUSIVE', '비교 불가', 'waiting'], ['CANCELLED', null, '종료', 'neutral']
]) {
  const status = context.trialStatus({state, verdict});
  assert.equal(status.label, label);
  assert.equal(status.tone, tone);
  assert.equal(context.trialOption({testId: 'sample-id', state, verdict}, '오늘').className, 'trial-' + tone);
}
assert.match(html, /class="sender-trial-toolbar"/);
assert.match(html, /class="sender-trial-buttons"[\s\S]*id="dtn-start"[\s\S]*id="dtn-send"/);
vm.runInContext("busy=false; config.sendBusy=false; config.delaySupported=true; job=null; selectedType='GNSS_RAW'; agents[0].state='READY'; pendingCapture={inputId:'pending'}; inputId=null;", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, true);
assert.equal(elements.get('dtn-start').disabled, true);
assert.equal(elements.get('capture-use').disabled, false);
assert.equal(elements.get('dtn-tests').disabled, true, 'pending capture cannot be overwritten by history selection');
vm.runInContext("job={testId:'old',state:'COMPLETED'};", context);
await context.showCaptureDecision({inputId: 'pending'});
assert.equal(vm.runInContext('job', context), null, 'restored capture clears the previous report before polling');
vm.runInContext("pendingCapture=null; acceptedCapture=true; inputId='approved'; delayChoices=[{reference:{positionValid:false},afsReady:false}]; preparedEpoch={inputId,choice:delayChoices[0]}; epochIndex=0;", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, false, 'approved RAW may transmit without valid PVT');
vm.runInContext("selectedType='AFS_METADATA';", context);
context.updateControls();
assert.equal(elements.get('dtn-send').disabled, true, 'AFS still needs navigation');
vm.runInContext("config.iqEnabled=true; inputPvt=[{positionValid:false,velocityValid:false}];", context);
context.updateControls();
assert.equal(elements.get('iq-generate').disabled, true);
console.log('PASS: Korean history states, colors, compact action group and incomplete capture approval controls');

const previousFetch = context.fetch;
let ubxUploads = 0;
context.fetch = async (url, options) => {
  if (url.includes('/inputs/ubx?')) {
    ubxUploads++;
    assert.equal(options.method, 'POST');
    assert.equal(options.body.name, 'receiver.UBX');
    assert.ok(url.includes('archiveTime='));
    return {ok: true, json: async () => ({inputId: 'ubx-input', recordCount: 12})};
  }
  return previousFetch(url, options);
};
vm.runInContext("pendingCapture=null; busy=false; config.sendBusy=false; selectedType='GNSS_RAW';", context);
await context.upload({name: 'receiver.UBX', size: 256, lastModified: 1000});
assert.equal(ubxUploads, 1);
assert.equal(vm.runInContext('inputId', context), 'ubx-input');
assert.match(elements.get('dtn-input-state').textContent, /receiver.UBX · 12건/);
assert.match(html, /accept="\.ubx,\.graw,application\/octet-stream"/);
await assert.rejects(context.upload({name: 'large.ubx', size: 64 * 1024 * 1024 + 1}), /64 MiB/);
context.fetch = previousFetch;
console.log('PASS: direct UBX upload reuses observation/PVT preview and retains GRAW compatibility');

assert.match(html, /class="sender-summary-row">[\s\S]*?id="dtn-condition-summary"[\s\S]*?class="service-clock-row"/);
assert.equal((html.match(/id="service-clock-value"/g) || []).length, 1);
assert.ok(html.indexOf('id="service-clock-sync"') > html.indexOf('id="dtn-main-view"'));
console.log('PASS: sender clock shares the trial summary row with unique existing controls');

const originalGnssPoll = vm.runInContext('gnss', context).poll;
vm.runInContext('gnss', context).poll = async () => { throw new Error('GNSS view update failed'); };
await context.poll();
assert.equal(vm.runInContext('polling', context), false, 'GNSS exception must release polling guard');
vm.runInContext('gnss', context).poll = originalGnssPoll;
await context.poll();
assert.equal(vm.runInContext('polling', context), false);
console.log('PASS: repeated sends preserve prepared Epoch and polling recovers from GNSS rendering errors');

let bulkCount = 3, bulkPosts = 0, bulkRefresh = 0;
const bulkMessages = [], bulkRequests = [];
context.confirm = () => false;
context.fetch = async (url, options) => {
  bulkRequests.push({url, options});
  if (url.endsWith('/waiting-summary')) return {ok:true, json:async()=>({count:bulkCount, asOf:'2026-10-01T00:00:00Z', testIds:bulkCount ? ['waiting-a','waiting-b','waiting-c'] : []})};
  assert.ok(url.endsWith('/cancel-waiting'));
  bulkPosts++;
  assert.deepEqual(JSON.parse(options.body), {asOf:'2026-10-01T00:00:00Z', testIds:['waiting-a','waiting-b','waiting-c']});
  return {ok:true, json:async()=>({requested:3, cancelled:2, skipped:1, pending:1})};
};
context.initWaitingCancellation({refresh:async()=>{bulkRefresh++;}, log:(message,level)=>bulkMessages.push({message,level})});
await elements.get('dtn-cancel-waiting').onclick();
assert.equal(bulkPosts, 0, 'declining confirmation sends no cancellation request');
context.confirm = message => { assert.match(message, /3건/); return true; };
await elements.get('dtn-cancel-waiting').onclick();
assert.equal(bulkPosts, 1);
assert.equal(bulkRefresh, 1);
assert.equal(bulkMessages.at(-1).level, 'INFO');
assert.match(bulkMessages.at(-1).message, /즉시 종료 2건.*제외 1건.*상대 대기 상태 확인·종료 전달 중 1건/);
bulkCount = 0;
await elements.get('dtn-cancel-waiting').onclick();
assert.equal(bulkPosts, 1, 'empty waiting set needs no mutation');
assert.match(bulkMessages.at(-1).message, /없습니다/);
assert.equal(elements.get('dtn-cancel-waiting').disabled, false);
console.log('PASS: bulk waiting cancellation confirms count, fixes cutoff, preserves records and reports peer retries');
