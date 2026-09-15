import {pageSource} from './browser-source.mjs';
import {numeric} from '../../main/resources/static/assets/dtn/dtn-observations.js';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

// 수신 화면의 실제 스크립트를 실행한다. 운영 DB에 시험 자료를 넣지 않는 DOM/API 대역이다.
const html = readFileSync(new URL('../../main/resources/static/dtn-receiver.html', import.meta.url), 'utf8');
const source = pageSource('dtn-receiver.js');
const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, {
  value: '', textContent: '', hidden: false, disabled: false,
  setAttribute(key, value) { this[key] = value; },
  replaceChildren(...options) { this.options = options; this.value = options[0]?.value ?? ''; },
  removeAttribute(key) { delete this[key]; }, focus() {}, select() {}
}]));
let nextTests = [], report = {}, reportRequest = null;
const requests = [];
const context = {
  numeric,
  renderIqFile() {},
  createDtnLog: () => ({write() {},setContext() {},refresh() {}}),
  document: {getElementById(id) { assert.ok(elements.has(id), 'DOM missing: ' + id); return elements.get(id); }},
  createPayloadViewer(container, options) { assert.equal(options.receivedOnly, true); return {setJob() {}}; },
  createObservationView() { return {setData() {}, select() {}}; },
  Option: function(text, value) { this.text = text; this.value = value; },
  location: {origin: 'http://localhost:8089'}, navigator: {}, setTimeout() {}, setInterval() {},
  fetch: async (url, options) => {
    assert.ok(!options?.method || options.method === 'GET', 'Receiver screen must not start a test');
    requests.push(url);
    const body = url.endsWith('/node') ? {baseUrl: 'http://192.168.1.72:8089'}
      : url.endsWith('/agents') ? [{role: 'RECEIVER', state: 'BUSY'}, {role: 'SENDER', state: 'READY'}]
      : url.endsWith('/config') ? {receiveConfigured: true}
      : url.endsWith('/tests') ? nextTests
      : reportRequest ? await reportRequest : report;
    return {ok: true, json: async () => body};
  }
};
vm.createContext(context);
vm.runInContext(source, context);
await context.ready;
assert.equal(elements.get('receive-url').value, 'http://192.168.1.72:8089/lnis/api/v1/dtn/receive');
assert.equal(elements.get('receive-state').textContent, '수신 대기');
assert.match(elements.get('dtn-receiver-status').textContent, /처리 중/);
assert.equal(elements.get('dtn-report').hidden, true);
vm.runInContext("renderSummary({testType:'IQ_SAMPLE',state:'COMPLETED'})",context);
assert.equal(elements.get('receiver-type').textContent,'I/Q Sample');
assert.ok(!html.includes('class="test-type-selector"') && !html.includes('class="transport-mode-selector"'), 'receiver displays only the received selection, without option buttons');
assert.equal(elements.get('step-process').textContent,'③ I/Q 파일 검증');
assert.equal(elements.get('receive-state').textContent,'I/Q 파일 검증 완료');
for (const testType of ['GNSS_RAW', 'AFS_METADATA', 'IQ_SAMPLE']) {
  for (const senderMode of ['DTN', 'HDTN']) for (const receiverMode of ['DTN', 'HDTN']) {
    context.renderSummary({testType, senderMode, receiverMode, state: 'COMPLETED'});
    assert.equal(elements.get('dtn-observations').hidden, testType === 'IQ_SAMPLE');
    assert.equal(elements.get('receiver-type').textContent, {GNSS_RAW:'GNSS RAW',AFS_METADATA:'AFS Frame + Metadata',IQ_SAMPLE:'I/Q Sample'}[testType]);
    assert.equal(elements.get('receiver-mode').textContent, senderMode + ' → ' + receiverMode);
  }
}
context.renderSummary(null);
assert.equal(elements.get('dtn-observations').hidden, false);
assert.equal(elements.get('receiver-mode').textContent, '경로 정보 없음');
assert.equal(elements.get('receiver-type').textContent, '시험 선택 대기');

const done = {testId: 'completed', state: 'COMPLETED', dtnReceived: true, receivedEpochs: 2, updatedAt: '2026-09-11T00:00:00Z'};
const waiting = {testId: 'waiting', state: 'WAITING_DTN', receivedEpochs: 0};
nextTests = [done, waiting];
report = {receivedPvt: [
  {week: 2400, towSeconds: 10, positionValid: true, velocityValid: true,
    ecefMeters: [1, 2, 3], velocityMetersPerSecond: [4, 5, 6], satellitesUsed: 7, receiverClockBiasSeconds: 0},
  {week: 2400, towSeconds: 11, positionValid: false, velocityValid: false,
    ecefMeters: [999, 999, 999], velocityMetersPerSecond: [999, 999, 999], satellitesUsed: 2}
]};
await context.poll(true);
assert.equal(elements.get('receive-state').textContent, '수신 계산 완료');
assert.equal(elements.get('pvt-x').textContent, '1.000');
assert.equal(elements.get('pvt-count').textContent, '2개 관측');
assert.equal(elements.get('step-process').className, 'done');
assert.equal(elements.get('reference-x').textContent, '-');
assert.equal(elements.get('pvt-match').textContent, 'PVT 비교 불가');
report.referencePvt = structuredClone(report.receivedPvt);
report.comparison = {verdict: 'PASS'};
await context.poll(true);
assert.equal(elements.get('reference-x').textContent, '1.000');
assert.equal(elements.get('pvt-match').className, 'pill online');
report.comparison = {verdict: 'FAIL'};
await context.poll(true);
assert.equal(elements.get('pvt-match').textContent, '전체 PVT 불일치');
assert.equal(elements.get('pvt-match').className, 'pill error');
elements.get('pvt-epoch').value = '1';
context.renderEpoch();
assert.equal(elements.get('pvt-x').textContent, '-');
assert.equal(elements.get('pvt-vx').textContent, '-');
assert.match(elements.get('pvt-validity').textContent, /위치 무효/);

elements.get('dtn-tests').value = 'waiting';
await context.renderTest();
assert.equal(elements.get('pvt-satellites').textContent, '-');
assert.equal(elements.get('reference-x').textContent, '-');
assert.equal(elements.get('pvt-match').textContent, 'PVT 비교 불가');
assert.equal(elements.get('dtn-report').hidden, true);
assert.equal(elements.get('step-receive').className, 'active');

// 이전 시험의 늦은 보고서가 새로 선택한 시험의 값을 덮어쓰지 않는다.
let release;
reportRequest = new Promise(resolve => { release = resolve; });
elements.get('dtn-tests').value = 'completed';
const pending = context.renderTest(true);
elements.get('dtn-tests').value = 'waiting';
await context.renderTest();
release(report);
await pending;
assert.equal(elements.get('pvt-x').textContent, '-');
assert.equal(elements.get('receive-state').textContent, '외부 JSON 수신 대기');
reportRequest = null;
nextTests = [{testId: 'failed', state: 'FAILED', dtnReceived: true, receivedEpochs: 0, message: 'AFS 검증 실패'}];
await context.poll();
assert.equal(elements.get('step-process').className, 'failed');
assert.equal(elements.get('receive-message').textContent, 'AFS 검증 실패');
assert.ok(!html.includes('id="dtn-process"'));
console.log('PASS: receiver empty/completed/invalid/failed states, epochs, stale-response guard, endpoint and read-only UI');

nextTests = [done];
const cleared = vm.createContext({...context, location: {...context.location, pathname: '/lnis/dtntest/receiver/clear'}});
elements.get('dtn-tests').value = '';
vm.runInContext(source, cleared);
await cleared.ready;
assert.equal(elements.get('dtn-tests').value, '', 'clear must not auto-select stored tests');
await cleared.poll();
assert.equal(elements.get('dtn-tests').value, '', 'polling must preserve cleared selection');
assert.equal(elements.get('dtn-report').hidden, true);
console.log('PASS: receiver clear preserves empty selection through polling');
