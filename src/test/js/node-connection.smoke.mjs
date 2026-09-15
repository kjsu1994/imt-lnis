import assert from 'node:assert/strict';
import {connectionRequest, mountConnection} from '../../main/resources/static/assets/common/node-connection.js';
import {validAdapterUrl} from '../../main/resources/static/assets/dtn/dtn-adapter-health.js';

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

// Actual exported validator, independent of page function names and whitespace.
assert.equal(validAdapterUrl('https://adapter.example:8443/custom/transfers?route=1'),
  'https://adapter.example:8443/custom/transfers?route=1');
assert.equal(validAdapterUrl('javascript:alert(1)'), null);
assert.equal(validAdapterUrl(''), null);
console.log('PASS: inline controls, input change, adapter URL isolation and preservation');
