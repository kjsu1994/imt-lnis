import {createPayloadViewer} from './dtn-payload.js?v=20260913-compact';
import {createObservationView, numeric} from './dtn-observations.js?v=20260913';

const api = '/lnis/api/v1', $ = id => document.getElementById(id);
const payload = createPayloadViewer($('dtn-payload'));
let inputId = null, agents = [], busy = false, job = null, config = {}, peerConfig = null;
let selectedType = 'AFS_METADATA', senderMode = 'DTN', receiverMode = 'HDTN';
let pvt = [], comparison = null, epochIndex = 0, reportKey = '', lastEvent = '', lastAgentState = '';
let polling = false, inputMode = 'upload';
const active = () => ['PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING'].includes(job?.state);
const locked = () => busy || active();
const view = createObservationView($('dtn-observations'), index => { epochIndex = index; renderPvt(); });
view.setData(null);

async function request(path, options = {}) {
  const response = await fetch(api + path, {cache: 'no-store', ...options});
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.detail || body.message || ('HTTP ' + response.status));
  return body;
}
const post = (path, body) => request(path, {method: 'POST', headers: {'Content-Type': 'application/json'},
  body: body === undefined ? undefined : JSON.stringify(body)});
function log(message, level = 'INFO') {
  const time = new Date().toLocaleTimeString('ko-KR', {hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'});
  $('dtn-log').textContent = ($('dtn-log').textContent + time + ' [' + level + '] ' + message + '\n').slice(-16000);
  $('dtn-log').scrollTop = $('dtn-log').scrollHeight;
}
function pill(id, text, state = '') { $(id).textContent = text; $(id).className = 'pill ' + state; }
function destination(text, state = 'unknown') {
  $('destination-state').textContent = text; $('destination-dot').className = 'connection-dot ' + state;
}
function buildSendUrl() {
  const value = $('dtn-send-url').value.trim();
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? value : null;
  } catch { return null; }
}
const urlValid = () => !!buildSendUrl();
function renderPvt() {
  const value = pvt[epochIndex];
  for (const [i, axis] of ['x', 'y', 'z'].entries()) {
    $('pvt-' + axis).textContent = numeric(value?.positionValid ? value.ecefMeters?.[i] : null);
    $('pvt-v' + axis).textContent = numeric(value?.velocityValid ? value.velocityMetersPerSecond?.[i] : null);
  }
  $('dtn-gnss-time').textContent = value ? 'Week ' + value.week + ' / TOW ' + numeric(value.towSeconds) + ' s' : '—';
  $('pvt-satellites').textContent = value?.satellitesUsed ?? '—';
  $('pvt-clock').textContent = numeric(value?.positionValid ? value.receiverClockBiasSeconds : null, 9);
  pill('pvt-validity', !value ? '계산 대기' : '위치 ' + (value.positionValid ? '유효' : '무효') + ' · 속도 ' + (value.velocityValid ? '유효' : '무효'),
    !value ? '' : value.positionValid && value.velocityValid ? 'online' : 'warning');
  $('pvt-message').textContent = value?.message || '지구 ECEF · GPS L1 C/A · 전송시험 시작 시 계산';
  const delta = comparison?.epochs?.[epochIndex];
  $('dtn-comparison').textContent = '위치 차이 ' + numeric(delta?.positionDifferenceMeters, 6) + ' m · 속도 차이 ' +
    numeric(delta?.velocityDifferenceMetersPerSecond, 6) + ' m/s · 시계오차 차이 ' + numeric(delta?.clockDifferenceSeconds, 12) + ' s';
}
function updateControls() {
  const tx = agents.find(a => a.agentId === $('dtn-sender').value);
  const rx = agents.find(a => a.agentId === $('dtn-receiver').value);
  $('dtn-start').disabled = true; // One-shot capture is not connected yet.
  $('dtn-send').disabled = locked() || selectedType !== 'AFS_METADATA' || !inputId || !urlValid() || tx?.state !== 'READY' || rx?.state !== 'READY';
  for (const id of ['dtn-upload', 'dtn-graw-file', 'dtn-example', 'dtn-send-url']) $(id).disabled = locked();
  $('dtn-refresh').disabled = locked() || tx?.state !== 'READY';
  for (const button of document.querySelectorAll('.test-type-button,.transport-mode-button,.input-mode')) button.disabled = locked();
  for (const id of ['dtn-connection-test', 'dtn-connection-save', 'dtn-receiver-ip', 'dtn-receiver-port']) $(id).disabled = locked() || !peerConfig?.editable;
  $('dtn-apply-mode').disabled = locked() || !config.adapterControlConfigured;
  $('dtn-message').textContent = selectedType === 'AFS_METADATA'
    ? active() ? '전송·수신 결과를 기다리는 중입니다.' : !inputId ? 'GNSS 입력을 준비하세요.' : !urlValid() ? '어댑터 전송 URL을 입력하세요.' : ''
    : selectedType === 'GNSS_RAW' ? 'GNSS RAW 직접 전송은 준비 중입니다.' : 'I/Q 공유 파일 전달은 준비 중입니다.';
}
function resetResult() {
  job = null; reportKey = ''; lastEvent = ''; pvt = []; comparison = null; epochIndex = 0;
  payload.setJob(null); renderPvt(); $('dtn-report').hidden = true; $('dtn-report').removeAttribute('href');
  $('dtn-result').textContent = '시험 대기'; pill('dtn-test-status', '시험 대기');
}
async function upload(file) {
  if (locked()) return;
  if (!file || file.size === 0 || file.size > config.maximumInputBytes) throw new Error('1 MiB 이하의 GRAW 파일을 선택하세요.');
  busy = true; inputId = null; resetResult(); view.setData(null); updateControls();
  try {
    $('dtn-input-state').textContent = '입력 확인 중'; $('dtn-upload-progress').value = 0;
    const input = await post('/inputs', {fileName: file.name, size: file.size, kind: 'GRAW_UPLOAD'});
    await request('/inputs/' + input.inputId + '/chunks/0', {
      method: 'PUT', headers: {'Content-Type': 'application/octet-stream'}, body: new Uint8Array(await file.arrayBuffer())});
    const complete = await post('/inputs/' + input.inputId + '/complete');
    const observations = await request('/dtn/inputs/' + input.inputId + '/observations');
    if (!observations.epochs?.length) throw new Error('RAWX 관측값이 없는 입력입니다.');
    view.setData(observations); inputId = input.inputId;
    $('dtn-upload-progress').value = 100; $('dtn-input-state').textContent = file.name + ' · ' + complete.recordCount + '건';
    log('입력 완료 · ' + file.name);
  } catch (error) {
    inputId = null; view.setData(null); $('dtn-input-state').textContent = '입력 실패'; throw error;
  } finally { busy = false; updateControls(); }
}
$('dtn-upload').onclick = () => upload($('dtn-graw-file').files[0]).catch(e => log(e.message, 'ERROR'));
$('dtn-example').onclick = async () => {
  if (locked()) return;
  busy = true; updateControls();
  try {
    const module = await import('./dtn-example.js?v=20260913');
    const file = await module.exampleFile();
    busy = false;
    inputMode = 'upload'; updateInputPanels();
    await upload(file);
    log('F9T 공개 관측값 예제 · 항법정보 없음: 전달 검증용이며 PVT 비교는 불가', 'WARN');
  } catch (e) { log(e.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
};
function updateInputPanels() {
  const iq = selectedType === 'IQ_SAMPLE';
  $('dtn-iq-panel').classList.toggle('hidden', !iq);
  $('dtn-capture-panel').classList.toggle('hidden', iq || inputMode !== 'capture');
  $('dtn-upload-panel').classList.toggle('hidden', iq || inputMode !== 'upload');
  for (const button of document.querySelectorAll('.input-mode')) button.classList.toggle('active', button.dataset.inputMode === inputMode);
}
for (const button of document.querySelectorAll('.input-mode')) button.onclick = () => { inputMode = button.dataset.inputMode; updateInputPanels(); };
for (const button of document.querySelectorAll('.test-type-button')) button.onclick = () => {
  selectedType = button.dataset.testType;
  for (const other of document.querySelectorAll('.test-type-button')) {
    other.classList.toggle('active', other === button); other.setAttribute('aria-pressed', String(other === button));
  }
  updateInputPanels(); updateControls(); log('시험 유형 선택 · ' + button.textContent.trim());
};
for (const button of document.querySelectorAll('.transport-mode-button')) button.onclick = () => {
  senderMode = button.dataset.senderMode; receiverMode = button.dataset.receiverMode;
  for (const other of document.querySelectorAll('.transport-mode-button')) {
    other.classList.toggle('active', other === button); other.setAttribute('aria-pressed', String(other === button));
  }
  $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 적용 전';
};
$('dtn-apply-mode').onclick = async () => {
  busy = true; updateControls();
  try {
    await post('/dtn/adapter-mode', {senderMode, receiverMode});
    $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 적용됨';
    log('어댑터 경로 설정 완료');
  } catch (e) {
    $('dtn-transport-mode-state').textContent = '적용 실패 · 양쪽 어댑터 설정 확인 필요';
    log(e.message, 'ERROR');
  } finally { busy = false; updateControls(); }
};
async function connectPeer(save) {
  if (!$('dtn-receiver-ip').reportValidity() || !$('dtn-receiver-port').reportValidity()) return;
  busy = true; updateControls(); destination('확인 중');
  try {
    const body = {ip: $('dtn-receiver-ip').value.trim(), port: Number($('dtn-receiver-port').value), scheme: peerConfig.scheme || 'http'};
    const result = await request('/node/connection' + (save ? '' : '/test'), {method: save ? 'PUT' : 'POST',
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    if (save) { peerConfig = result; destination('저장 완료 · 재확인 필요'); }
    else destination(result.connected ? '응답 확인' : '연결 실패', result.connected ? 'online' : 'offline');
    $('dtn-connection-message').textContent = save ? '상대 서비스 주소를 저장했습니다.' : result.message;
    log($('dtn-connection-message').textContent, !save && !result.connected ? 'ERROR' : 'INFO');
  } catch (e) { destination('연결 실패', 'offline'); $('dtn-connection-message').textContent = e.message; log(e.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
}
$('dtn-connection-test').onclick = () => connectPeer(false);
$('dtn-connection-save').onclick = () => connectPeer(true);
for (const id of ['dtn-receiver-ip', 'dtn-receiver-port']) $(id).oninput = () => { destination('주소 변경 · 미확인'); $('dtn-connection-message').textContent = ''; };
$('dtn-send-url').oninput = updateControls;
$('dtn-log-clear').onclick = () => { $('dtn-log').textContent = ''; };
$('dtn-refresh').onclick = async () => {
  try { await post('/agents/' + encodeURIComponent($('dtn-sender').value) + '/serial-ports/refresh'); log('COM 포트 조회 요청'); }
  catch (e) { log(e.message, 'ERROR'); }
};
$('dtn-send').onclick = async () => {
  if (locked() || $('dtn-send').disabled) return;
  busy = true; resetResult(); updateControls();
  try {
    job = await post('/dtn/tests', {inputId, senderAgentId: $('dtn-sender').value,
      receiverAgentId: $('dtn-receiver').value, sendUrl: $('dtn-send-url').value.trim()});
    log('전송시험 시작 · ' + job.testId);
    renderSummary();
  } catch (e) { log('시험 시작 실패 · ' + e.message, 'ERROR'); pill('dtn-test-status', '시작 실패', 'error'); }
  finally { busy = false; updateControls(); }
};
function renderSummary() {
  if (!job) return;
  const names = {PREPARING: 'AFS 생성·기준 PVT 계산 중', WAITING_DTN: '외부 전달·수신 대기', WAITING_RECEIVER: '수신 처리 대기',
    CALCULATING: '복원·PVT 계산 중', COMPLETED: '처리 완료', FAILED: '시험 실패', INCONCLUSIVE: '판정 불가', CANCELLED: '취소'};
  pill('dtn-test-status', names[job.state] || job.state, active() ? 'warning' : job.verdict === 'PASS' ? 'online' : 'warning');
  $('dtn-result').textContent = (job.verdict ? ({PASS: '일치', FAIL: '불일치', INCONCLUSIVE: '판정 불가'}[job.verdict] || job.verdict) : names[job.state] || job.state);
  payload.setJob(job);
  const key = job.testId + ':' + job.state + ':' + job.updatedAt;
  if (key !== lastEvent) { log((names[job.state] || job.state) + ' · ' + (job.message || '')); lastEvent = key; }
}
async function poll() {
  if (polling) return;
  polling = true;
  try {
    agents = await request('/agents');
    pill('dtn-server-status', '서버 연결됨', 'online');
    for (const role of ['SENDER', 'RECEIVER']) {
      const list = agents.filter(a => a.role === role), id = 'dtn-' + role.toLowerCase(), old = $(id).value;
      $(id).replaceChildren(...list.map(a => new Option(a.agentId, a.agentId)));
      if (list.some(a => a.agentId === old)) $(id).value = old;
      const a = list.find(a => a.agentId === $(id).value);
      pill(id + '-status', (role === 'SENDER' ? '송신 처리기 ' : '수신 처리기 ') +
        (!a || a.state === 'OFFLINE' ? '연결 끊김' : a.state === 'READY' ? '준비됨' : '처리 중'),
        !a || a.state === 'OFFLINE' ? 'error' : a.state === 'READY' ? 'online' : 'warning');
    }
    const agentState = agents.map(a => a.role + ':' + a.state).join(',');
    if (agentState !== lastAgentState) { log('송·수신 처리기 상태 갱신'); lastAgentState = agentState; }
    if (job && !busy) {
      const id = job.testId;
      const next = await request('/dtn/tests/' + id);
      if (job?.testId === id && !busy) {
        job = next; renderSummary();
        const key = id + ':' + next.updatedAt;
        if ((next.referenceEpochs || next.verdict) && key !== reportKey) {
          const report = await request('/dtn/tests/' + id + '/report');
          if (job?.testId === id && !busy) {
            pvt = report.referencePvt || []; comparison = report.comparison;
            if (report.observations) view.setData(report.observations, true);
            renderPvt(); reportKey = key;
            $('dtn-report').href = api + '/dtn/tests/' + id + '/report'; $('dtn-report').hidden = false;
          }
        }
      }
    }
  } catch (e) {
    agents = []; pill('dtn-server-status', '서버 확인 실패', 'error');
    destination('미확인'); log(e.message, 'ERROR');
  } finally { polling = false; updateControls(); }
}
function socket() {
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/lnis/ws/status');
  ws.onmessage = event => {
    try {
      const data = JSON.parse(event.data);
      if (data.agentId === $('dtn-sender').value && data.payload?.ports)
        $('dtn-port').replaceChildren(new Option('포트 선택', ''), ...data.payload.ports.map(p => new Option(p.name, p.name)));
    } catch { log('포트 응답을 읽을 수 없습니다.', 'WARN'); }
  };
  ws.onclose = () => setTimeout(socket, 3000);
}
async function initialize() {
  try {
    config = await request('/dtn/config');
    $('dtn-send-url').value = config.defaultSendUrl || '';
    $('dtn-example').hidden = !config.exampleEnabled;
    $('dtn-transport-mode-state').textContent = config.adapterControlConfigured ? '경로 적용 전' : '어댑터 제어 미설정';
    try {
      peerConfig = await request('/node/connection');
      $('dtn-receiver-ip').value = peerConfig.ip || ''; $('dtn-receiver-port').value = peerConfig.port;
    } catch { $('dtn-connection-message').textContent = '상대 서비스 주소 설정을 사용할 수 없습니다.'; }
    const recent = await request('/dtn/tests'); job = recent[0] || null;
    await poll(); socket(); log('DTN 송신 화면 준비 완료');
  } catch (e) { log(e.message, 'ERROR'); }
  setInterval(poll, 2000);
  updateControls();
}
initialize();
