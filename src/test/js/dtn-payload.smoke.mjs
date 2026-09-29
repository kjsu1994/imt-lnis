import assert from 'node:assert/strict';
import {payloadUrl, displayedJson, createPayloadViewer, renderIqFile} from '../../main/resources/static/assets/dtn/dtn-payload.js';

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

// 수신 전용 화면도 원문을 항상 표시하고 제목 줄에서 정렬·다운로드한다.
const receiverContainer = new Element('section');
const receiverViewer = createPayloadViewer(receiverContainer, {receivedOnly: true});
const receiverControls = receiverContainer.children.find(element => element.className?.includes('dtn-payload-controls'));
assert.equal(receiverControls.children.some(element => element.tag === 'button'), false);
assert.equal(receiverControls.children[0].textContent, '수신 JSON 원문');
const receiverPanel = receiverContainer.children.find(element => element.children?.some(child => child.tag === 'textarea'));
const receiverText = receiverPanel.children[0];
const receiverPretty = receiverControls.children[1].children[0];
const receiverDownload = receiverControls.children[2];
assert.equal(receiverPanel.hidden, false);
globalThis.fetch = async () => ({ok: true, text: async () => original, headers: {get: () => 'original'}});
await receiverViewer.setJob({testId: 'received-test', receivedPayloadAvailable: true});
assert.equal(receiverText.value, displayedJson(original, true));
assert.equal(receiverDownload.href, payloadUrl('received-test', 'received', true));
receiverPretty.checked = false; receiverPretty.onchange();
await receiverViewer.setJob({testId: 'received-test', receivedPayloadAvailable: true});
assert.equal(receiverText.value, original, 'polling preserves original formatting');
await receiverViewer.setJob({testId: 'waiting-test', receivedPayloadAvailable: false});
assert.equal(receiverPanel.hidden, false);
assert.equal(receiverText.value, '');
assert.equal(receiverDownload.href, undefined);
await receiverViewer.setJob({testId: 'waiting-test', receivedPayloadAvailable: true});
assert.equal(receiverText.value, displayedJson(original, true));
console.log('PASS: receiver always visible, delayed availability and header download');
const senderContainer = new Element('section');
const senderViewer = createPayloadViewer(senderContainer, {sentOnly: true});
const senderControls = senderContainer.children.find(element => element.className?.includes('dtn-payload-controls'));
assert.equal(senderControls.children.some(element => element.tag === 'button'), false);
assert.equal(senderControls.children[0].textContent, '송신 JSON 원문');
await senderViewer.setJob({testId: 'sent-test', sentPayloadAvailable: true});
const senderPanel = senderContainer.children.find(element => element.children?.some(child => child.tag === 'textarea'));
const senderText = senderPanel.children.find(element => element.tag === 'textarea');
const senderPretty = senderControls.children[1].children[0];
const senderDownload = senderControls.children[2];
assert.equal(senderText.value, displayedJson(original, true));
assert.equal(senderDownload.href, payloadUrl('sent-test', 'sent', true));
assert.equal(senderPretty.checked, true);
assert.equal(senderPanel.hidden, false);
await senderViewer.setJob({testId: 'pending-test', sentPayloadAvailable: false});
assert.equal(senderPanel.hidden, false, 'sender always keeps the text area visible');
assert.equal(senderText.value, '');
assert.equal(senderDownload.href, undefined, 'old download cleared when changing trial');
await senderViewer.setJob({testId: 'pending-test', sentPayloadAvailable: true});
assert.equal(senderPanel.hidden, false);
assert.equal(senderText.value, displayedJson(original, true));
senderPretty.checked = false; senderPretty.onchange();
assert.equal(senderText.value, original);
await senderViewer.setJob({testId: 'pending-test', sentPayloadAvailable: true});
assert.equal(senderPretty.checked, false, 'polling preserves the selected format');
console.log('PASS: sender always visible, header formatting/download and delayed availability');

// Rejected/unidentified receipts share the existing original/pretty/download panel.
globalThis.fetch = async () => ({ok:true,text:async()=>original,headers:{get:()=>null}});
await receiverViewer.setJob({testId:'rejected',receivedPayloadAvailable:false});
await receiverViewer.setReceipts([{id:'receipt-1',testId:'rejected',status:'REJECTED',message:'mismatch',arrivedAt:'2026-09-17T02:14:10Z'}]);
assert.equal(receiverPanel.hidden,false);
assert.match(receiverDownload.href,/receipts\/receipt-1\/body\?download=true$/);
assert.equal(receiverPanel.children.find(e=>e.tag==='textarea').value,displayedJson(original,true));
receiverPretty.checked=false; receiverPretty.onchange();
await receiverViewer.setReceipts([{id:'receipt-1',testId:'rejected',status:'REJECTED',message:'mismatch',arrivedAt:'2026-09-17T02:14:10Z'}]);
assert.equal(receiverPanel.hidden,false);
assert.equal(receiverText.value,original,'receipt polling preserves formatting');
assert.equal(receiverContainer.children.find(e=>e.role==='status').hidden,false,'rejection remains visible');
console.log('PASS: rejected receipts integrated into original JSON viewer');

await receiverViewer.setJob({testId:'another',receivedPayloadAvailable:false});
await receiverViewer.setReceipts([
  {id:'foreign',testId:'rejected',status:'REJECTED'},
  {id:'unknown',testId:null,status:'REJECTED'}
]);
assert.equal(receiverText.value, '', 'unrelated and unidentified receipts never replace the selected trial');
assert.equal(receiverDownload.href, undefined);
assert.ok(!JSON.stringify(receiverContainer).includes('수신 원문 기록'));
await receiverViewer.setJob({testId:'rejected',receivedPayloadAvailable:false});
await receiverViewer.setReceipts([{id:'receipt-1',testId:'rejected',status:'REJECTED'}]);
await receiverViewer.setJob({testId:'rejected',receivedPayloadAvailable:true});
assert.ok(!receiverDownload.href.includes('/receipts/'), 'accepted original replaces rejected receipt automatically');
console.log('PASS: JSON and downloads follow only the trial selected in the main history');
