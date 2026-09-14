import assert from 'node:assert/strict';
import {connectionRequest, mountConnection} from '../../main/resources/static/assets/node-connection.js';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

let call;
globalThis.fetch = async (url, options) => {
  call = {url, options};
  return {ok: true, status: 200, json: async () => ({connected: true})};
};
await connectionRequest('POST', {ip: '192.168.1.2', port: 8088});
assert.equal(call.url, '/lnis/api/v1/node/connection/test');
assert.equal(call.options.headers.Authorization, undefined);
assert.deepEqual(JSON.parse(call.options.body), {ip: '192.168.1.2', port: 8088});
await connectionRequest('PUT', {ip: '192.168.1.2', port: 8088});
assert.equal(call.url, '/lnis/api/v1/node/connection');
assert.equal(call.options.method, 'PUT');
globalThis.fetch = async () => ({ok: false, status: 409, json: async () => ({detail: '시험 진행 중'})});
await assert.rejects(() => connectionRequest('PUT', {}), /시험 진행 중/);
globalThis.fetch = async () => ({status: 404});
assert.equal(await connectionRequest('GET'), null);
console.log('PASS: node connection test/save separation, no token, errors and legacy fallback');

// 기존 입력란만 바인딩하며 테스트와 저장 버튼이 서로 다른 요청을 보내는지 검증한다.
const field = () => ({value: '', hidden: true, disabled: false, textContent: '',
  reportValidity: () => true, addEventListener(event, action) { this[event] = action; }});
const elements = Object.fromEntries(['peer-ip', 'peer-port', 'connection-test', 'connection-save', 'connection-message']
  .map(name => ['[data-' + name + ']', field()]));
const area = {querySelector: selector => elements[selector]};
const root = {querySelector: () => area};
globalThis.fetch = async (url, options) => {
  call = {url, options};
  return {ok: true, status: 200, json: async () => options.method === 'GET'
    ? {editable: true, ip: '192.168.0.20', port: 8088, scheme: 'http', tokenConfigured: true}
    : {message: '연결 확인', elapsedMilliseconds: 12}};
};
await mountConnection(root);
assert.equal(elements['[data-peer-ip]'].value, '192.168.0.20');
assert.equal(elements['[data-connection-test]'].hidden, false);
elements['[data-peer-ip]'].value = '192.168.0.21';
elements['[data-peer-ip]'].input();
assert.match(elements['[data-connection-message]'].textContent, /주소 변경됨/);
await elements['[data-connection-test]'].onclick();
assert.equal(call.options.method, 'POST');
assert.equal(JSON.parse(call.options.body).ip, '192.168.0.21');
assert.match(elements['[data-connection-message]'].textContent, /12ms/);
await elements['[data-connection-save]'].onclick();
assert.equal(call.options.method, 'PUT');
assert.equal(elements['[data-connection-save]'].disabled, false);

// 관리 IP/Port가 아닌 어댑터 URL 원문을 사용하고 사용자 경로·쿼리를 보존한다.
const dtn = readFileSync(new URL('../../main/resources/static/assets/dtn.js', import.meta.url), 'utf8');
const build = dtn.match(/function buildAdapterUrl\(id\) \{[\s\S]*?const buildReceiveUrl = \(\) =>[^;]+;/)[0];
let adapter = 'https://adapter.example:8443/custom/transfers?route=1';
const context = {URL, $: id => { assert.equal(id, 'dtn-send-url'); return {value: adapter}; }};
vm.createContext(context);
vm.runInContext(build + '\nglobalThis.buildSendUrlForTest = buildSendUrl;', context);
assert.equal(context.buildSendUrlForTest(), adapter);
adapter = 'javascript:alert(1)';
assert.equal(context.buildSendUrlForTest(), null);
adapter = '';
assert.equal(context.buildSendUrlForTest(), null);
assert.doesNotMatch(dtn, /applyReceiverAddress/);
console.log('PASS: inline controls, input change, adapter URL isolation and preservation');
