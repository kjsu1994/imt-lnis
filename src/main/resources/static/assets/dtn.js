import {initAdapterHealth} from './dtn-adapter-health.js?v=20260914-transfer-v2';
import {createPayloadViewer} from './dtn-payload.js?v=20260914-auto-pretty';
import {createObservationView, numeric} from './dtn-observations.js?v=20260914-navigation';

const api = '/lnis/api/v1', $ = id => document.getElementById(id);
const payload = createPayloadViewer($('dtn-payload'), {sentOnly: true});
let inputId = null, agents = [], busy = false, job = null, config = {}, peerConfig = null;
let selectedType = 'AFS_METADATA', senderMode = 'DTN', receiverMode = 'HDTN';
let pvt = [], comparison = null, epochIndex = 0, reportKey = '', lastEvent = '', lastAgentState = '';
let polling = false, inputMode = 'upload';
let captureId = null, captureError = '';
let iqJob = null;
let iqFiles = [];
const generatingIq = () => iqJob?.state === 'GENERATING';
const active = () => ['PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING'].includes(job?.state);
const locked = () => busy || active() || generatingIq();
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
function buildAdapterUrl(id) {
  const value = $(id).value.trim();
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? value : null;
  } catch { return null; }
}
const buildSendUrl = () => buildAdapterUrl('dtn-send-url');
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
  $('dtn-start').disabled = locked() || ! $('dtn-port').value || tx?.state !== 'READY';
  $('dtn-port').disabled = $('dtn-baud').disabled = locked();
  $('dtn-send').disabled = locked() || (selectedType === 'IQ_SAMPLE' ? iqJob?.state !== 'READY' : !inputId) || !urlValid() || tx?.state !== 'READY' || rx?.state !== 'READY';
  $('iq-generate').disabled = locked() || !config.iqEnabled || !inputId;
  $('iq-cancel').disabled = !generatingIq();
  $('iq-saved').disabled = locked();
  $('iq-delete').disabled = locked() || iqJob?.state !== 'READY';
  for (const id of ['dtn-upload', 'dtn-graw-file', 'dtn-send-url']) $(id).disabled = locked();
  if ($('dtn-replay')) $('dtn-replay').disabled = locked();
  $('dtn-refresh').disabled = locked() || tx?.state !== 'READY';
  for (const button of document.querySelectorAll('.test-type-button,.transport-mode-button,.input-mode')) button.disabled = locked();
  for (const id of ['dtn-connection-test', 'dtn-connection-save', 'dtn-receiver-ip', 'dtn-receiver-port']) $(id).disabled = locked() || !peerConfig?.editable;
  $('dtn-message').textContent = selectedType !== 'IQ_SAMPLE'
    ? active() ? '전송·수신 결과를 기다리는 중입니다.' : !inputId ? 'GNSS 입력을 준비하세요.' : !urlValid() ? '어댑터 전송 URL을 입력하세요.' : ''
    : active() ? '전송·수신 결과를 기다리는 중입니다.' : generatingIq() ? '90초 I/Q 생성 중입니다.'
      : iqJob?.state === 'READY' ? '선택한 I/Q 파일을 전송합니다.' : 'GNSS 입력 적용 후 90초 I/Q를 생성하세요.';
}
function renderIq() {
  if (!iqJob) { $('iq-state').textContent = '파일 선택 또는 생성 대기'; $('iq-file').textContent = ''; $('iq-progress').value = 0; return; }
  $('iq-state').textContent = iqJob.message;
  $('iq-progress').value = Math.min(100, 100 * (iqJob.generatedBytes || 0) / (iqJob.expectedBytes || 1));
  $('iq-file').textContent = iqJob.file ? JSON.stringify({file:iqJob.file, preview:iqJob.preview}, null, 2) : '';
}
async function loadIqFiles() {
  iqFiles = await request('/dtn/iq');
  $('iq-saved').replaceChildren(new Option('파일 선택', ''), ...iqFiles.filter(item => item.state === 'READY').map(item => new Option(item.id + ' · 90초', item.id)));
  if (!iqJob) iqJob = iqFiles.find(item => item.state === 'GENERATING') || null;
  if (iqJob) $('iq-saved').value = iqJob.id;
  renderIq(); updateInputPanels(); updateControls();
}
$('iq-saved').onchange = () => { iqJob = iqFiles.find(item => item.id === $('iq-saved').value) || null; renderIq(); updateControls(); };
$('iq-delete').onclick = async () => {
  if (locked() || iqJob?.state !== 'READY' || !confirm('선택한 I/Q BIN 파일을 영구 삭제합니다. 시험 기록은 유지됩니다. 삭제할까요?')) return;
  try { await request('/dtn/iq/' + iqJob.id, {method:'DELETE'}); iqJob = null; $('iq-file').textContent = ''; await loadIqFiles(); log('선택 I/Q 파일 삭제 완료'); }
  catch (error) { log(error.message, 'ERROR'); }
};
$('iq-generate').onclick = async () => {
  if (locked() || !inputId) return;
  busy = true; updateControls();
  try { iqJob = await post('/dtn/iq', {inputId}); renderIq(); log('GNSS 기반 90초 I/Q 생성 시작 · PRN별 SB2 반복'); }
  catch (error) { log(error.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
};
$('iq-cancel').onclick = async () => {
  try { iqJob = await post('/dtn/iq/' + iqJob.id + '/cancel'); renderIq(); }
  catch (error) { log(error.message, 'ERROR'); }
  updateControls();
};
function resetResult() {
  job = null; reportKey = ''; lastEvent = ''; pvt = []; comparison = null; epochIndex = 0;
  payload.setJob(null); renderPvt(); $('dtn-report').hidden = true; $('dtn-report').removeAttribute('href');
  $('dtn-result').textContent = '시험 대기'; pill('dtn-test-status', '시험 대기');
}
function clearIqSelection() {
  iqJob = null; $('iq-saved').value = ''; renderIq();
}
async function upload(file) {
  if (locked()) return;
  if (!file || file.size === 0 || file.size > config.maximumInputBytes) throw new Error('1 MiB 이하의 GRAW 파일을 선택하세요.');
  busy = true; inputId = null; clearIqSelection(); resetResult(); view.setData(null); updateControls();
  try {
    $('dtn-input-state').textContent = '입력 확인 중'; $('dtn-upload-progress').value = 0;
    const input = await post('/inputs', {fileName: file.name, size: file.size, kind: 'GRAW_UPLOAD'});
    await request('/inputs/' + input.inputId + '/chunks/0', {
      method: 'PUT', headers: {'Content-Type': 'application/octet-stream'}, body: new Uint8Array(await file.arrayBuffer())});
    const complete = await post('/inputs/' + input.inputId + '/complete');
    const observations = await request('/dtn/inputs/' + input.inputId + '/observations');
    if (!observations.epochs?.length) throw new Error('RAWX 관측값이 없는 입력입니다.');
    inputId = input.inputId;
    try { pvt = await request('/dtn/inputs/' + inputId + '/pvt'); }
    catch (error) { pvt = []; log('PVT 미리보기 불가 · ' + error.message, 'WARN'); }
    view.setData(observations); renderPvt();
    $('dtn-upload-progress').value = 100; $('dtn-input-state').textContent = file.name + ' · ' + complete.recordCount + '건';
    log('입력 완료 · ' + file.name);
  } catch (error) {
    inputId = null; view.setData(null); $('dtn-input-state').textContent = '입력 실패'; throw error;
  } finally { busy = false; updateControls(); }
}
$('dtn-upload').onclick = () => upload($('dtn-graw-file').files[0]).catch(e => log(e.message, 'ERROR'));
$('dtn-port').onchange = updateControls;
$('dtn-start').onclick = async () => {
  if (locked() || !$('dtn-port').value) return;
  busy = true; inputId = null; clearIqSelection(); captureError = ''; resetResult(); view.setData(null); updateControls();
  $('dtn-input-state').textContent = '항법정보·관측값 수집 중 · 최대 120초';
  log('한 시점 수집 시작 · ' + $('dtn-port').value);
  try {
    const input = await post('/captures', {senderAgentId: $('dtn-sender').value,
      portName: $('dtn-port').value, baudRate: Number($('dtn-baud').value), protocolId: 'UBX',
      receiverModel: 'u-blox EVK-F9T', sessionName: 'DTN single epoch', singleEpoch: true});
    captureId = input.inputId;
    const deadline = Date.now() + 140000;
    while (true) {
      if (captureError) throw new Error(captureError);
      const state = await request('/inputs/' + captureId);
      if (state.complete) break;
      if (Date.now() >= deadline) throw new Error('수집 완료 응답이 없습니다. 장치와 서버 상태를 확인하세요.');
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    const observations = await request('/dtn/inputs/' + captureId + '/observations');
    pvt = await request('/dtn/inputs/' + captureId + '/pvt');
    if (observations.epochs?.length !== 1 || !pvt[0]?.positionValid || !pvt[0]?.velocityValid)
      throw new Error('유효한 한 시점 PVT 입력이 아닙니다.');
    view.setData(observations); renderPvt(); inputId = captureId;
    $('dtn-input-state').textContent = '한 시점 수집 완료 · 지구 PVT 계산 완료';
    log('수집 완료 · 관측값 1시점 · 지구 PVT 계산 완료');
  } catch (e) {
    inputId = null; pvt = []; view.setData(null); renderPvt();
    $('dtn-input-state').textContent = '수집 실패'; log(e.message, 'ERROR');
  } finally { captureId = null; busy = false; updateControls(); }
};
if ($('dtn-replay')) $('dtn-replay').onclick = async () => {
  if (locked()) return;
  busy = true; inputId = null; clearIqSelection(); resetResult(); view.setData(null); updateControls();
  $('dtn-input-state').textContent = '합성 레코드 수집 재생 중 · 실장비 아님';
  try {
    const input = await post('/dtn/example/replay');
    const observations = await request('/dtn/inputs/' + input.inputId + '/observations');
    pvt = await request('/dtn/inputs/' + input.inputId + '/pvt');
    inputId = input.inputId; view.setData(observations); renderPvt();
    $('dtn-input-state').textContent = '합성 수집 완료 · 지구 PVT 계산 완료 · 실측 아님';
    log('합성 GRAW 수집 재생 완료 · COM/UBX 장치 시험이 아닙니다.', 'WARN');
  } catch (error) { inputId = null; $('dtn-input-state').textContent = '재생 실패'; log(error.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
};
function updateInputPanels() {
  const iq = selectedType === 'IQ_SAMPLE';
  $('dtn-iq-panel').classList.toggle('hidden', !iq && !generatingIq());
  $('dtn-capture-panel').classList.toggle('hidden', inputMode !== 'capture');
  $('dtn-upload-panel').classList.toggle('hidden', inputMode !== 'upload');
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
  $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 전송 요청에 포함';
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
    job = await post('/dtn/tests', {inputId: selectedType === 'IQ_SAMPLE' ? null : inputId, iqFileId: iqJob?.id, senderAgentId: $('dtn-sender').value,
      receiverAgentId: $('dtn-receiver').value, sendUrl: $('dtn-send-url').value.trim(), testType: selectedType, senderMode, receiverMode});
    log('전송시험 시작 · ' + job.testId);
    renderSummary();
  } catch (e) { log('시험 시작 실패 · ' + e.message, 'ERROR'); pill('dtn-test-status', '시작 실패', 'error'); }
  finally { busy = false; updateControls(); }
};
function renderSummary() {
  if (!job) return;
  const names = {PREPARING: job.testType === 'IQ_SAMPLE' ? 'I/Q 파일 확인 중' : '입력 준비·기준 PVT 계산 중', WAITING_DTN: '외부 전달·수신 대기', WAITING_RECEIVER: '수신 처리 대기',
    CALCULATING: '복원·PVT 계산 중', COMPLETED: '처리 완료', FAILED: '시험 실패', INCONCLUSIVE: '판정 불가', CANCELLED: '취소'};
  pill('dtn-test-status', names[job.state] || job.state, active() ? 'warning' : job.verdict === 'PASS' ? 'online' : 'warning');
  $('dtn-result').textContent = (job.verdict ? ({PASS: '일치', FAIL: '불일치', INCONCLUSIVE: '판정 불가'}[job.verdict] || job.verdict) : names[job.state] || job.state);
  payload.setJob(job);
  if (job.fileResult) {
    $('dtn-comparison').textContent = 'I/Q 파일 ' + job.fileResult.verdict + ' · ' + job.fileResult.sizeBytes + ' bytes · SHA-256 ' + job.fileResult.sha256;
  }
  const key = job.testId + ':' + job.state + ':' + job.updatedAt;
  if (key !== lastEvent) { log((names[job.state] || job.state) + ' · ' + (job.message || '')); lastEvent = key; }
}
async function poll() {
  if (polling) return;
  polling = true;
  try {
    if (generatingIq()) {
      iqJob = await request('/dtn/iq/' + iqJob.id); renderIq();
      if (!generatingIq()) { log('I/Q 생성 결과 · ' + iqJob.state + ' · ' + iqJob.message); await loadIqFiles(); }
    }
    agents = await request('/agents');
    if (peerConfig) {
      const connection = await request('/node/connection');
      if ($('dtn-receiver-ip').value === connection.ip && Number($('dtn-receiver-port').value) === connection.port)
        destination(connection.peerOnline ? '연결됨' : '연결 끊김', connection.peerOnline ? 'online' : 'offline');
    }
    pill('dtn-server-status', '서버 연결됨', 'online');
    for (const role of ['SENDER', 'RECEIVER']) {
      const list = agents.filter(a => a.role === role), id = 'dtn-' + role.toLowerCase(), old = $(id).value;
      $(id).replaceChildren(...list.map(a => new Option(a.agentId, a.agentId)));
      if (list.some(a => a.agentId === old)) $(id).value = old;
      const a = list.find(a => a.agentId === $(id).value);
      pill(id + '-status', (role === 'SENDER' ? '송신 처리기 ' : '수신 처리기 ') +
        (!a || a.state === 'OFFLINE' ? '연결 끊김' : a.state === 'ERROR' ? '오류' : a.state === 'READY' ? '준비됨' : '처리 중'),
        !a || ['OFFLINE','ERROR'].includes(a.state) ? 'error' : a.state === 'READY' ? 'online' : 'warning');
    }
    const agentState = agents.map(a => a.role + ':' + a.state).join(',');
    if (agentState !== lastAgentState) { log('송·수신 처리기 상태 갱신'); lastAgentState = agentState; }
    if (job && !busy) {
      const id = job.testId;
      const next = await request('/dtn/tests/' + id);
      if (job?.testId === id && !busy) {
        job = next; renderSummary();
        const key = id + ':' + next.updatedAt;
        if (next.testType !== 'IQ_SAMPLE' && (next.referenceEpochs || next.verdict) && key !== reportKey) {
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
      if (data.agentId === $('dtn-sender').value && data.sessionId === captureId && data.type === 'ERROR')
        captureError = data.payload?.message || 'GNSS 수집 실패';
      if (data.agentId === $('dtn-sender').value && data.payload?.ports)
        $('dtn-port').replaceChildren(new Option('포트 선택', ''), ...data.payload.ports.map(p => new Option(p.name, p.name)));
    } catch { log('포트 응답을 읽을 수 없습니다.', 'WARN'); }
  };
  ws.onclose = () => setTimeout(socket, 3000);
}
async function initialize() {
  try {
    config = await request('/dtn/config');
    if (config.iqEnabled) await loadIqFiles();
    $('dtn-send-url').value = config.defaultSendUrl || '';
    initAdapterHealth(config.adapterUrl || config.defaultSendUrl || '', log);
      $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 전송 요청에 포함';
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
