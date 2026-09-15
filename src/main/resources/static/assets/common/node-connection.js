// AFS/DTN 송신 화면이 함께 사용하는 작은 연결 설정 패널이다.
import {requestJson} from './http.js?v=20260915-structure';

export function connectionRequest(method, body) {
  return requestJson('/node/connection' + (method === 'POST' ? '/test' : ''), {
    method, cache: 'no-store',
    headers: body ? {'Content-Type': 'application/json'} : {},
    body: body ? JSON.stringify(body) : undefined
  }, {notFoundIsNull: method === 'GET'});
}

// 기존 전송 설정 안의 입력란에 연결한다. 별도 카드나 폼을 만들지 않는다.
export async function mountConnection(root = document) {
  const area = root.querySelector('[data-node-connection]');
  if (!area) return;
  const status = area.querySelector('[data-connection-message]');
  const ip = area.querySelector('[data-peer-ip]');
  const port = area.querySelector('[data-peer-port]');
  const test = area.querySelector('[data-connection-test]');
  const save = area.querySelector('[data-connection-save]');
  try {
    const configuration = await connectionRequest('GET');
    if (!configuration?.editable) return;
    ip.value = configuration.ip;
    port.value = configuration.port;
    test.hidden = save.hidden = false;
    const controls = [ip, port, test, save];
    const hint = () => configuration.tokenConfigured
      ? '관리 REST 연결 확인 · DTN 전송 주소는 별도'
      : '양쪽 서버에 관리 인증 토큰을 설정하세요.';
    status.textContent = hint();
    const run = async method => {
      if (!ip.reportValidity() || !port.reportValidity()) return;
      const body = {ip: ip.value.trim(), port: Number(port.value), scheme: configuration.scheme || 'http'};
      controls.forEach(control => control.disabled = true);
      status.textContent = method === 'POST' ? '연결 확인 중…' : '확인 후 저장 중…';
      try {
        const result = await connectionRequest(method, body);
        status.textContent = method === 'POST'
          ? result.message + ' · ' + result.elapsedMilliseconds + 'ms'
          : '저장·적용 완료 · 재시작 후에도 유지됩니다.';
      } catch (error) {
        status.textContent = error.message;
      } finally {
        controls.forEach(control => control.disabled = false);
      }
    };
    test.onclick = () => run('POST');
    save.onclick = () => run('PUT');
    // 주소를 수정하면 이전 주소에 대한 테스트 결과를 지운다.
    for (const field of [ip, port]) field.addEventListener('input', () => {
      status.textContent = '주소 변경됨 · 연결 테스트 후 저장·적용하세요.';
    });
  } catch (error) {
    status.textContent = '설정 조회 실패: ' + error.message;
  }
}

if (typeof document !== 'undefined') mountConnection();
