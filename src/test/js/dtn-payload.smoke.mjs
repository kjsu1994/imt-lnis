import assert from 'node:assert/strict';
import {payloadUrl, displayedJson, createPayloadViewer, renderIqFile} from '../../main/resources/static/assets/dtn-payload.js';

const original = '{\r\n  "note": "한글 <script>alert(1)</script>", "value": 1\r\n}\r\n';
assert.equal(displayedJson(original, false), original);
assert.deepEqual(JSON.parse(displayedJson(original, true)), JSON.parse(original));
assert.equal(payloadUrl('a/b', 'sent', true), '/lnis/api/v1/dtn/tests/a%2Fb/payload/sent?download=true');
assert.throws(() => payloadUrl('id', 'unknown'));

// 브라우저와 같은 DOM 조작 경계를 제공한다. 본문은 textarea.value에만 들어가야 한다.
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.value = ''; this.hidden = false; }
  append(...elements) { this.children.push(...elements); }
  replaceChildren(...elements) { this.children = elements; }
  querySelector(tag) { return this.children.find(e=>e.tag===tag); }
  setAttribute(name, value) { this[name] = value; }
  removeAttribute(name) { delete this[name]; }
}
globalThis.document = {createElement: tag => new Element(tag), createTextNode: text => ({textContent: text})};
const container = new Element('section');
const iqContainer = new Element('div');
const iqResult = {verdict:'PASS',sizeBytes:2160000000,filePath:'/exchange/<unsafe>.bin',sha256:'ABC',preview:[[-1,1],[3,-3]]};
renderIqFile(iqContainer,iqResult);
assert.equal(iqContainer.children[0].children[0].textContent,'파일 검증 일치');
assert.equal(iqContainer.children[0].children[1].textContent,'2.16 GB');
assert.equal(iqContainer.children[1].textContent,iqResult.filePath);
assert.equal(iqContainer.querySelector('details').open,false);
iqContainer.querySelector('details').open=true;
renderIqFile(iqContainer,{...iqResult,verdict:'FAIL'});
assert.equal(iqContainer.children[0].children[0].className,'pill error');
assert.equal(iqContainer.querySelector('details').open,true);
renderIqFile(iqContainer,null,'수신 대기');
assert.equal(iqContainer.children.length,1);
assert.equal(iqContainer.children[0].textContent,'수신 대기');
const viewer = createPayloadViewer(container);
// 안내 문구 추가/삭제와 무관하게 실제 제어 요소로 찾는다.
const controls = container.children.find(element => element.className === 'dtn-payload-controls');
const status = container.children.find(element => element.role === 'status');
const panel = container.children.find(element => element.children?.some(child => child.tag === 'textarea'));
const [sent, received] = controls.children;
const [caption, toolbar, text] = panel.children;
const [prettyLabel, download, close] = toolbar.children;
const pretty = prettyLabel.children[0];
assert.equal(panel.hidden, true);
assert.equal(pretty.disabled, true);
pretty.checked = true;
pretty.onchange();
assert.equal(pretty.checked, true);
assert.doesNotMatch(status.textContent, /정렬할 수 없어/);
assert.equal(sent.disabled, true);
assert.equal(received.disabled, true);
globalThis.fetch = async () => ({ok: true, text: async () => original, headers: {get: () => 'original'}});
await viewer.setJob({testId: 'first', sentPayloadAvailable: true, receivedPayloadAvailable: false});
assert.equal(panel.hidden, false);
assert.equal(pretty.checked, true);
assert.equal(sent.disabled, false);
assert.equal(received.disabled, true);
globalThis.fetch = async () => ({ok: true, text: async () => original, headers: {get: () => 'original'}});
await sent.onclick();
assert.equal(text.value, displayedJson(original, true));
assert.equal(panel.hidden, false);
assert.equal(download.href, payloadUrl('first', 'sent', true));
pretty.checked = true;
pretty.onchange();
assert.equal(text.value, displayedJson(original, true));
assert.equal(download.href, payloadUrl('first', 'sent', true));
pretty.checked = false;
pretty.onchange();
assert.equal(text.value, original);

// 이전 시험 조회가 늦게 도착해도 새 시험의 내용으로 표시하지 않는다.
let finish;
globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
const loading = sent.onclick();
viewer.setJob({testId: 'second', sentPayloadAvailable: false});
finish({ok: true, text: async () => original, headers: {get: () => 'original'}});
await loading;
assert.equal(panel.hidden, true);
assert.equal(text.value, '');
assert.equal(download.href, undefined);
close.onclick();
assert.equal(panel.hidden, true);
viewer.setJob({testId: 'second', sentPayloadAvailable: false});
assert.equal(panel.hidden, true);
assert.equal(pretty.disabled, true);
globalThis.fetch = async () => ({ok: true, text: async () => 'invalid JSON', headers: {get: () => 'original'}});
await sent.onclick();
pretty.checked = true;
pretty.onchange();
assert.match(status.textContent, /정렬할 수 없어/);
assert.equal(text.value, 'invalid JSON');
close.onclick();
assert.equal(panel.hidden, true);
console.log('PASS: DTN JSON original/pretty/download, availability and stale-response guards');

// 수신 전용 화면에서 송신 버튼만 숨기고 원문 조회·다운로드 계약은 동일하게 유지한다.
const receiverContainer = new Element('section');
const receiverViewer = createPayloadViewer(receiverContainer, {receivedOnly: true});
const receiverControls = receiverContainer.children.find(element => element.className?.includes('dtn-payload-controls'));
assert.equal(receiverControls.children[0].hidden, true);
await receiverViewer.setJob({testId: 'received-test', receivedPayloadAvailable: true});
assert.equal(receiverControls.children[1].disabled, false);
globalThis.fetch = async () => ({ok: true, text: async () => original, headers: {get: () => 'original'}});
await receiverControls.children[1].onclick();
const receiverPanel = receiverContainer.children.find(element => element.children?.some(child => child.tag === 'textarea'));
assert.equal(receiverPanel.hidden, true);
await receiverViewer.setJob({testId:'received-test',receivedPayloadAvailable:true});
assert.equal(receiverPanel.hidden,true,'polling preserves receiver collapse');
await receiverControls.children[1].onclick();
assert.equal(receiverPanel.hidden,false);
assert.equal(receiverControls.children[1].textContent,'수신 JSON 원문');
assert.equal(receiverControls.children[1]['aria-expanded'],'true');
assert.equal(receiverControls.children[2].children[0].checked,true);
assert.equal(receiverPanel.children[1].children.length,0);
assert.equal(receiverPanel.children[2].value, displayedJson(original, true));
assert.equal(receiverControls.children[3].href, payloadUrl('received-test', 'received', true));
console.log('PASS: receiver-only original JSON and download');
const senderContainer = new Element('section');
const senderViewer = createPayloadViewer(senderContainer, {sentOnly: true});
const senderControls = senderContainer.children.find(element => element.className?.includes('dtn-payload-controls'));
assert.equal(senderControls.children[1].hidden, true);
await senderViewer.setJob({testId: 'sent-test', sentPayloadAvailable: true});
const senderPanel = senderContainer.children.find(element => element.children?.some(child => child.tag === 'textarea'));
assert.equal(senderPanel.children[2].value, displayedJson(original, true));
assert.equal(senderControls.children[3].href, payloadUrl('sent-test', 'sent', true));
const senderPretty = senderControls.children[2].children[0];
assert.equal(senderPretty.checked, true);
senderControls.children[0].onclick();
await senderViewer.setJob({testId: 'sent-test', sentPayloadAvailable: true});
assert.equal(senderPanel.hidden, true, 'polling must preserve manual collapse');
await senderViewer.setJob({testId: 'pending-test', sentPayloadAvailable: false});
assert.equal(senderPanel.hidden, true);
await senderViewer.setJob({testId: 'pending-test', sentPayloadAvailable: true});
assert.equal(senderPanel.hidden, false, 'new payload opens when available');
assert.equal(senderPretty.checked, true);
assert.equal(senderPanel.children[2].value, displayedJson(original, true));
console.log('PASS: automatic pretty opening, delayed availability and manual collapse persistence');
assert.equal(senderControls.children[0].textContent,'송신 JSON 원문');
assert.equal(senderPanel.children[1].children.length,0,'separate close/download toolbar removed on sender');
assert.equal(senderControls.children[0]['aria-expanded'],'true');
senderControls.children[0].onclick();
assert.equal(senderPanel.hidden,true);
await senderControls.children[0].onclick();
assert.equal(senderPanel.hidden,false);
console.log('PASS: sender title toggle and header download');
