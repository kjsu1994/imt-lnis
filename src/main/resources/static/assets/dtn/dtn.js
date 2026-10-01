import {initPresetControls, renderTrialSettings, trialOption, colorTrialSelection, initWaitingCancellation} from './dtn-settings.js?v=20261001-review';
import {requestJson} from '../common/http.js?v=20261001-review';
import {initGnssControls} from './dtn-gnss.js?v=20261001-review';
import {createDtnLog} from './dtn-log.js?v=20261001-review';
import {initAdapterHealth, validAdapterUrl} from './dtn-adapter-health.js?v=20260922-compact-settings';
import {createPayloadViewer, renderIqFile} from './dtn-payload.js?v=20261001-review';
import {createObservationView, numeric, renderClockBias} from './dtn-observations.js?v=20260929-real-gnss';

const $ = id => document.getElementById(id);
const payload = createPayloadViewer($('dtn-payload'), {sentOnly: true});
let inputId = null, agents = [], busy = false, job = null, config = {}, peerConfig = null;
let selectedType = 'AFS_METADATA', senderMode = 'DTN', receiverMode = 'HDTN';
let pvt = [], inputPvt = [], epochIndex = 0, reportKey = '', lastAgentState = '';
let polling = false, inputMode = 'upload';
let delayChoices = [], preparedEpoch = null, inputView = false;
let captureId = null, captureError = '';
let pendingCapture = null, acceptedCapture = false;
let gnssState = {state: 'DISCONNECTED'};
let iqJob = null;
let iqFiles = [];
let jobVersion = 0;
const generatingIq = () => iqJob?.state === 'GENERATING';
const active = () => ['PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING'].includes(job?.state);
const locked = () => !!pendingCapture || busy || config.sendBusy || job?.sendBusy
  || job?.state === 'PREPARING' || job?.sendStatus === 'REQUESTING' || generatingIq();
const view = createObservationView($('dtn-observations'), index => {
  epochIndex = index;
  if (inputView && inputId && delayChoices[index]) {
    preparedEpoch = {inputId, choice: delayChoices[index]};
  }
  renderPvt();
  if (config.delaySupported) updateControls();
}, '송신 원본');
view.setData(null);

function request(path, options = {}) {
  return requestJson(path, {cache: 'no-store', ...options}, {allowEmpty: true});
}
const post = (path, body) => request(path, {method: 'POST', headers: {'Content-Type': 'application/json'},
  body: body === undefined ? undefined : JSON.stringify(body)});
const logView=createDtnLog($('dtn-log'));
const gnss = initGnssControls({port: 'dtn-port', baud: 'dtn-baud', refresh: 'dtn-refresh', log,
  changed(state) { gnssState = state; updateControls(); }});
const waitingCancellation = initWaitingCancellation({isLocked: () => busy || !!pendingCapture, refresh: () => poll(), log});
function log(message,level='INFO') {
  logView.write(message,level);
  if (!$('dtn-settings-view').hidden && (busy || level === 'ERROR')) {
    $('dtn-settings-feedback').hidden = false;
    $('dtn-settings-feedback').textContent = message;
  }
}
const hdtnRules = {
  "maxNumberOfBundlesInPipeline": {
    "label": "최대 동시 번들 수",
    "default": 50,
    "min": 10,
    "max": 10000,
    "advanced": false
  },
  "maxSumOfBundleBytesInPipeline": {
    "label": "최대 동시 번들 용량",
    "default": 50000000,
    "min": 1048576,
    "max": 2147483648,
    "advanced": false
  },
  "maxBundleSizeBytes": {
    "label": "최대 번들 크기",
    "default": 10485760,
    "min": 1048576,
    "max": 104857600,
    "advanced": false
  },
  "tcpclMaxSegmentSizeBytes": {
    "label": "TCPCL 세그먼트 크기",
    "default": 20000,
    "min": 20000,
    "max": 200000,
    "advanced": false
  },
  "neighborDepletedStorageDelaySeconds": {
    "label": "저장 공간 부족 시 대기",
    "default": 10,
    "min": 0,
    "max": 3600,
    "advanced": false
  },
  "enforceBundlePriority": {
    "label": "번들 우선순위 준수",
    "default": false,
    "advanced": false
  },
  "storageDeletionPolicy": {
    "label": "스토리지 삭제 정책",
    "default": "DELETE_AFTER_FORWARDING",
    "advanced": false
  },
  "totalStorageCapacityBytes": {
    "label": "전체 저장 용량",
    "default": 8589934592,
    "min": 1,
    "max": 9007199254740991,
    "advanced": true
  },
  "maxLtpReceiveUdpPacketSizeBytes": {
    "label": "LTP 최대 수신 패킷 크기",
    "default": 65536,
    "min": 1,
    "max": 2147483647,
    "advanced": true
  },
  "acsSendPeriodMilliseconds": {
    "label": "ACS 전송 주기",
    "default": 1000,
    "min": 1,
    "max": 2147483647,
    "advanced": true
  }
};
const hdtnDefaults = Object.fromEntries(Object.entries(hdtnRules).map(([key, rule]) => [key, rule.default]));
const hdtnPolicies = ['DELETE_AFTER_FORWARDING', 'on_expiration', 'on_storage_full', 'never'];
const hdtnStorageKey = 'lnis.hdtnConfig.v1';
const usesHdtn = () => senderMode === 'HDTN' || receiverMode === 'HDTN';

function hdtnValue(key, raw) {
  const rule = hdtnRules[key];
  if (typeof rule.default === 'number') {
    const value = Number(raw);
    if (!raw || !Number.isSafeInteger(value) || value < rule.min || value > rule.max)
      throw new Error(rule.label + ': ' + rule.min.toLocaleString() + '~' + rule.max.toLocaleString() + ' 사이의 정수를 입력하세요.');
    return value;
  }
  if (typeof rule.default === 'boolean') {
    if (!['true', 'false'].includes(raw)) throw new Error('우선순위 사용 여부를 선택하세요.');
    return raw === 'true';
  }
  if (!hdtnPolicies.includes(raw)) throw new Error('지원하는 삭제 정책을 선택하세요.');
  return raw;
}

function readHdtnConfig() {
  const result = {};
  let firstError = null;
  for (const key of Object.keys(hdtnRules)) {
    const input = $('hdtn-' + key), error = $('hdtn-' + key + '-error');
    try {
      result[key] = hdtnValue(key, input.value.trim());
      input.removeAttribute('aria-invalid'); error.hidden = true; error.textContent = '';
    } catch (failure) {
      input.setAttribute('aria-invalid', 'true'); error.hidden = false; error.textContent = failure.message;
      if (!firstError) { failure.field = key; firstError = failure; }
    }
  }
  if (firstError) throw firstError;
  return result;
}

function saveHdtnConfig(settings) {
  try { localStorage.setItem(hdtnStorageKey, JSON.stringify(settings)); }
  catch { /* Current values still apply when browser storage is unavailable. */ }
}

function initializeHdtnConfig() {
  let saved = {}, restored = [];
  try {
    if (location.pathname?.endsWith('/clear')) localStorage.removeItem(hdtnStorageKey);
    saved = JSON.parse(localStorage.getItem(hdtnStorageKey) || '{}') || {};
  } catch { /* Use defaults for unavailable or malformed storage. */ }
  for (const [key, rule] of Object.entries(hdtnRules)) {
    let value = rule.default;
    if (Object.hasOwn(saved, key)) {
      try { value = hdtnValue(key, String(saved[key])); }
      catch { restored.push(rule.label); }
    }
    const input = $('hdtn-' + key);
    input.value = String(value);
    input.oninput = input.onchange = () => {
      try { saveHdtnConfig(readHdtnConfig()); } catch { /* Keep invalid edits visible, never persist them. */ }
      updateHdtnControls();
    };
  }
  const values = readHdtnConfig();
  if (restored.length) saveHdtnConfig(values);
  $('hdtn-config-notice').hidden = !restored.length;
  $('hdtn-config-notice').textContent = restored.length ? '새 허용 범위에 맞춰 기본값 복구: ' + restored.join(', ') : '';
  $('hdtn-reset').onclick = () => {
    if (locked() || !usesHdtn()) return;
    for (const [key, value] of Object.entries(hdtnDefaults)) $('hdtn-' + key).value = String(value);
    saveHdtnConfig(readHdtnConfig());
    $('hdtn-config-notice').hidden = false;
    $('hdtn-config-notice').textContent = '고급 설정을 포함한 전체 값을 기본값으로 복원했습니다.';
    updateHdtnControls();
  };
}

function updateHdtnControls() {
  for (const key of Object.keys(hdtnDefaults)) $('hdtn-' + key).disabled = locked() || !usesHdtn();
  $('hdtn-reset').disabled = locked() || !usesHdtn();
  let message = 'DTN → DTN 경로에서는 전송하지 않습니다.';
  if (usesHdtn()) {
    try { readHdtnConfig(); message = '다음 시험 적용 · 자동 저장'; }
    catch (error) { message = error.message; }
  }
  $('hdtn-config-state').textContent = message;
}
const presets = initPresetControls({read: () => ({
  testType: selectedType, senderMode, receiverMode,
  hdtnConfig: readHdtnConfig()
}), isLocked: locked, apply: settings => {
  if (locked()) throw new Error('처리 중에는 불러올 수 없습니다.');
  if (!['GNSS_RAW', 'AFS_METADATA', 'IQ_SAMPLE'].includes(settings.testType)
      || !['DTN', 'HDTN'].includes(settings.senderMode) || !['DTN', 'HDTN'].includes(settings.receiverMode)) throw new Error('프리셋 설정을 확인하세요.');
  const configValues = Object.fromEntries(Object.keys(hdtnRules).map(key => [key, hdtnValue(key, String(settings.hdtnConfig?.[key] ?? ''))]));
  selectedType = settings.testType; senderMode = settings.senderMode; receiverMode = settings.receiverMode;
  for (const [key, value] of Object.entries(configValues)) $('hdtn-' + key).value = String(value);
  for (const button of document.querySelectorAll('.test-type-button')) {
    const selected = button.dataset.testType === selectedType;
    button.classList.toggle('active', selected); button.setAttribute('aria-pressed', String(selected));
  }
  for (const button of document.querySelectorAll('.transport-mode-button')) {
    const selected = button.dataset.senderMode === senderMode && button.dataset.receiverMode === receiverMode;
    button.classList.toggle('active', selected); button.setAttribute('aria-pressed', String(selected));
  }
  $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 전송 요청에 포함';
  saveHdtnConfig(configValues); updateInputPanels(); updateControls();
}});
let mainScrollY = 0;
function showSettings(open) {
  if (open && $('dtn-settings-view').hidden) { mainScrollY = window.scrollY; void presets.refresh(); }
  $('dtn-settings-view').hidden = !open;
  $('dtn-main-view').hidden = open;
  $('dtn-settings-open').setAttribute('aria-expanded', String(open));
  (open ? $('dtn-settings-title') : $('dtn-settings-open')).focus({preventScroll: true});
  window.scrollTo({top: open ? 0 : mainScrollY, behavior: 'instant'});
}
$('dtn-settings-open').onclick = () => showSettings($('dtn-settings-view').hidden);
$('dtn-settings-close').onclick = () => showSettings(false);
function updateInputSummary() {
  const type = {GNSS_RAW: 'GNSS RAW', AFS_METADATA: 'AFS Frame', IQ_SAMPLE: 'I/Q Sample'}[selectedType];
  $('dtn-condition-summary').textContent = type + ' · ' + senderMode + ' → ' + receiverMode + (delayMode() ? ' · 지연 반영 1 Epoch' : '');
  const source = inputMode === 'capture' ? 'COM ' + ($('dtn-port').value || '미선택') : 'GNSS 파일';
  const epoch = delayMode() ? selectedDelayEpoch()?.epoch : null;
  $('dtn-input-summary').textContent = source + ' · ' + $('dtn-input-state').textContent
    + (epoch ? ' · 시험 TOW ' + numeric(epoch.towSeconds) + ' s' : '');
  $('dtn-input-summary').title = epoch ? '다음 전송: Week ' + epoch.week + ' / TOW ' + epoch.towSeconds + ' s' : '';
}
function pill(id, text, state = '') { $(id).textContent = text; $(id).className = 'pill ' + state; }
function destination(text, state = 'unknown') {
  $('destination-state').textContent = text; $('destination-dot').className = 'connection-dot ' + state;
  $('dtn-peer-summary').textContent = text;
  $('dtn-peer-summary-dot').className = 'connection-dot ' + state;
}
const buildSendUrl = () => validAdapterUrl($('dtn-send-url').value);
const urlValid = () => !!buildSendUrl();
function renderPvt() {
  const value = pvt[epochIndex];
  for (const [i, axis] of ['x', 'y', 'z'].entries()) {
    $('pvt-' + axis).textContent = numeric(value?.positionValid ? value.ecefMeters?.[i] : null);
    $('pvt-v' + axis).textContent = numeric(value?.velocityValid ? value.velocityMetersPerSecond?.[i] : null);
  }
  $('dtn-gnss-time').textContent = value ? 'Week ' + value.week + ' / TOW ' + numeric(value.towSeconds) + ' s' : '—';
  $('pvt-satellites').textContent = value?.satellitesUsed ?? '—';
  renderClockBias($('pvt-clock'), value?.positionValid ? value.receiverClockBiasSeconds : null);
  pill('pvt-validity', !value ? '계산 대기' : '위치 ' + (value.positionValid ? '유효' : '무효') + ' · 속도 ' + (value.velocityValid ? '유효' : '무효'),
    !value ? '' : value.positionValid && value.velocityValid ? 'online' : 'warning');
  $('pvt-message').textContent = value?.message || '지구 ECEF · GPS L1 C/A · 전송시험 시작 시 계산';

}
function delayMode() { return selectedType !== 'IQ_SAMPLE'; }
function selectedDelayEpoch() {
  return preparedEpoch?.inputId === inputId ? preparedEpoch.choice : null;
}
function showInputObservations(data) {
  inputView = true;
  inputPvt = pvt;
  view.setData(data);
  renderPvt();
}
async function loadDelayEpochs() {
  const selected = selectedDelayEpoch()?.epoch;
  delayChoices = [];
  if (!config.delaySupported || !inputId) return;
  try {
    delayChoices = await request('/dtn/inputs/' + inputId + '/delay-epochs');
    const sameEpoch = choice => selected && choice.epoch.recordIndex === selected.recordIndex
      && choice.epoch.week === selected.week && choice.epoch.towSeconds === selected.towSeconds;
    let index = delayChoices.findIndex(sameEpoch);
    if (index < 0) index = delayChoices.findIndex(choice => choice.reference.positionValid);
    if (index < 0 && acceptedCapture && delayChoices.length) index = 0;
    preparedEpoch = index < 0 ? null : {inputId, choice: delayChoices[index]};
    if (index >= 0 && inputView) {
      epochIndex = index;
      view.select(index);
      renderPvt();
    } else if (index < 0 && !acceptedCapture && !pendingCapture) {
      log('지연 반영 시험에 사용할 유효한 Epoch가 없습니다. 입력·항법정보를 확인하세요.', 'WARN');
    }
  } catch (error) {
    preparedEpoch = null;
    log('시험 Epoch 확인 실패 · ' + error.message, 'WARN');
  }
}
function updateControls() {
  waitingCancellation.update();
  presets.update();
  updateInputSummary();
  $('dtn-settings-lock').hidden = !locked();
  updateHdtnControls();
  const tx = agents.find(a => a.agentId === $('dtn-sender').value);
  const rx = agents.find(a => a.agentId === $('dtn-receiver').value);
  $('dtn-start').disabled = locked() || gnssState.state !== 'CONNECTED' || gnssState.capturing || ! $('dtn-port').value || tx?.state !== 'READY';
  $('dtn-port').disabled = $('dtn-baud').disabled = locked() || ['CONNECTED', 'CONNECTING', 'RECONNECTING'].includes(gnssState.state);
  $('dtn-tests').disabled = busy || !!pendingCapture;
  $('dtn-cancel').disabled = busy || !(active() || job?.state === 'FAILED' || job?.cancelPending);
  $('dtn-cancel').textContent = job?.cancelPending ? '종료 전달 중' : job?.state === 'WAITING_DTN' ? '대기 종료' : job?.state === 'CALCULATING' ? '계산 중지' : '시험 중지';
  const noAfs = selectedType === 'AFS_METADATA' && selectedDelayEpoch()?.afsReady === false;
  $('dtn-send').disabled = locked() || (selectedType === 'IQ_SAMPLE' ? iqJob?.state !== 'READY' : !inputId) || (delayMode() && !acceptedCapture && !selectedDelayEpoch()?.reference?.positionValid) || noAfs || !urlValid() || tx?.state !== 'READY' || !rx || ['OFFLINE', 'ERROR'].includes(rx.state);
  $('iq-generate').disabled = locked() || !config.iqEnabled || !inputId || (acceptedCapture && !inputPvt.some(v => v.positionValid && v.velocityValid));
  for (const id of ['capture-use', 'capture-retry', 'capture-discard']) $(id).disabled = busy;
  $('iq-cancel').disabled = !generatingIq();
  $('iq-saved').disabled = locked();
  $('iq-delete').disabled = locked() || iqJob?.state !== 'READY';
  for (const id of ['dtn-upload', 'dtn-graw-file', 'dtn-send-url', 'dtn-adapter-save']) $(id).disabled = locked();
  if ($('dtn-replay')) $('dtn-replay').disabled = locked();
  $('dtn-refresh').disabled = locked() || tx?.state !== 'READY';
  for (const button of document.querySelectorAll('.test-type-button,.transport-mode-button,.input-mode')) button.disabled = locked();
  for (const id of ['dtn-connection-test', 'dtn-connection-save', 'dtn-receiver-ip', 'dtn-receiver-port']) $(id).disabled = busy || (!pendingCapture && locked()) || !peerConfig?.editable;
  $('dtn-message').textContent = selectedType !== 'IQ_SAMPLE'
    ? active() ? (locked() ? '송신 준비·어댑터 요청 중입니다.' : '이전 시험 수신 대기 · 다음 시험을 전송할 수 있습니다.') : !inputId ? 'GNSS 입력을 준비하세요.' : !urlValid() ? '어댑터 전송 URL을 입력하세요.' : ''
    : active() ? (locked() ? '송신 준비·어댑터 요청 중입니다.' : '이전 시험 수신 대기 · 다음 시험을 전송할 수 있습니다.') : generatingIq() ? '90초 I/Q 생성 중입니다.'
      : iqJob?.state === 'READY' ? '선택한 I/Q 파일을 전송합니다.' : 'GNSS 입력 적용 후 90초 I/Q를 생성하세요.';
  if (pendingCapture) $('dtn-message').textContent = '확보한 데이터의 사용 여부를 선택하세요.';
  else if (acceptedCapture && noAfs) $('dtn-message').textContent = 'AFS 생성에 필요한 GPS LNAV 항법정보 부족 · GNSS RAW로 전송 가능';
  else if (acceptedCapture && selectedType === 'IQ_SAMPLE' && !inputPvt.some(v => v.positionValid && v.velocityValid)) $('dtn-message').textContent = 'I/Q 생성에는 유효한 위치·속도 PVT가 필요합니다.';
}
function renderIq() {
  if(iqJob?.id && !job?.testId) logView.setContext(iqJob.id,'IQ');
  if (!iqJob) { $('iq-state').textContent = '파일 선택 또는 생성 대기'; renderIqFile($('iq-file'), null, ''); $('iq-progress').value = 0; return; }
  $('iq-state').textContent = iqJob.message;
  $('iq-progress').value = Math.min(100, 100 * (iqJob.generatedBytes || 0) / (iqJob.expectedBytes || 1));
  renderIqFile($('iq-file'), iqJob.file ? {...iqJob.file, preview:iqJob.preview} : null, '송신 파일 준비 완료');
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
function resetInputSelection() {
  preparedEpoch = null;
  delayChoices = [];
  inputPvt = [];
}
function resetResult() {
  job = null; reportKey = ''; pvt = []; epochIndex = 0; inputView = false;
  $('dtn-iq-result').hidden = true;
  renderIqFile($('dtn-iq-result'), null, '');
  payload.setJob(null); renderPvt(); pill('dtn-test-status', '시험 대기');
}
function clearIqSelection() {
  iqJob = null; $('iq-saved').value = ''; renderIq();
}
async function upload(file) {
  if (locked()) return;
  const ubx = /\.ubx$/i.test(file?.name || '');
  const maximum = ubx ? 64 * 1024 * 1024 : config.maximumInputBytes;
  if (!file || file.size === 0 || file.size > maximum) throw new Error('UBX는 64 MiB 이하, GRAW는 1 MiB 이하의 파일을 선택하세요.');
  busy = true; resetInputSelection(); inputId = null; acceptedCapture = false; clearIqSelection(); resetResult(); view.setData(null); updateControls();
  try {
    $('dtn-input-state').textContent = '입력 확인 중'; $('dtn-upload-progress').value = 0;
    let input, complete;
    if (ubx) {
      $('dtn-input-state').textContent = 'UBX 업로드·관측값 해석 중';
      const query = new URLSearchParams({fileName: file.name});
      if (file.lastModified) query.set('archiveTime', new Date(file.lastModified).toISOString());
      input = complete = await request('/inputs/ubx?' + query, {
        method: 'POST', headers: {'Content-Type': 'application/octet-stream'}, body: file});
    } else {
      input = await post('/inputs?dtn=true', {fileName: file.name, size: file.size, kind: 'GRAW_UPLOAD'});
      logView.setContext(input.inputId, 'INPUT');
      await request('/inputs/' + input.inputId + '/chunks/0', {
        method: 'PUT', headers: {'Content-Type': 'application/octet-stream'}, body: new Uint8Array(await file.arrayBuffer())});
      complete = await post('/inputs/' + input.inputId + '/complete');
    }
    logView.setContext(input.inputId,'INPUT');
    const observations = await request('/dtn/inputs/' + input.inputId + '/observations');
    if (!observations.epochs?.length) throw new Error('RAWX 관측값이 없는 입력입니다.');
    inputId = input.inputId;
    try { pvt = await request('/dtn/inputs/' + inputId + '/pvt'); }
    catch (error) { pvt = []; log('PVT 미리보기 불가 · ' + error.message, 'WARN'); }
    showInputObservations(observations); await loadDelayEpochs();
    $('dtn-upload-progress').value = 100; $('dtn-input-state').textContent = file.name + ' · ' + complete.recordCount + '건';
    log('입력 완료 · ' + file.name); void logView.refresh();
  } catch (error) {
    inputId = null; view.setData(null); $('dtn-input-state').textContent = '입력 실패'; throw error;
  } finally { busy = false; updateControls(); }
}
$('dtn-upload').onclick = () => upload($('dtn-graw-file').files[0]).catch(e => log(e.message, 'ERROR'));
$('dtn-port').onchange = updateControls;
$('dtn-start').onclick = async () => {
  if (locked() || gnssState.state !== 'CONNECTED' || !$('dtn-port').value) return;
  busy = true; resetInputSelection(); inputId = null; acceptedCapture = false; clearIqSelection(); captureError = ''; $('dtn-capture-status').textContent = ''; resetResult(); view.setData(null); updateControls();
  $('dtn-input-state').textContent = '항법정보·관측값 수집 중 · 최대 120초';
  log('한 시점 수집 시작 · ' + $('dtn-port').value);
  try {
    const input = await post('/captures', {senderAgentId: $('dtn-sender').value,
      portName: $('dtn-port').value, baudRate: Number($('dtn-baud').value), protocolId: 'UBX',
      receiverModel: '', sessionName: 'DTN single epoch', singleEpoch: true,
      dtrEnabled: gnssState.dtrEnabled || false, rtsEnabled: gnssState.rtsEnabled || false});
    captureId = input.inputId;
    logView.setContext(captureId,'INPUT');
    const deadline = Date.now() + 140000;
    while (true) {
      if (captureError) throw new Error(captureError);
      const state = await request('/inputs/' + captureId);
      if (state.complete) {
        if (state.captureDecision === 'AWAITING_DECISION') {
          await showCaptureDecision(state);
          return;
        }
        break;
      }
      if (Date.now() >= deadline) throw new Error('수집 완료 응답이 없습니다. 장치와 서버 상태를 확인하세요.');
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    const observations = await request('/dtn/inputs/' + captureId + '/observations');
    pvt = await request('/dtn/inputs/' + captureId + '/pvt');
    if (observations.epochs?.length !== 1 || !pvt[0]?.positionValid || !pvt[0]?.velocityValid)
      throw new Error('유효한 한 시점 PVT 입력이 아닙니다.');
    inputId = captureId; showInputObservations(observations); await loadDelayEpochs();
    $('dtn-input-state').textContent = '한 시점 수집 완료 · 지구 PVT 계산 완료';
    log('수집 완료 · 관측값 1시점 · 지구 PVT 계산 완료');
  } catch (e) {
    inputId = null; pvt = []; view.setData(null); renderPvt();
    $('dtn-input-state').textContent = '수집 실패'; log(e.message, 'ERROR');
  } finally { captureId = null; busy = false; updateControls(); }
};

async function showCaptureDecision(input) {
  resetInputSelection();
  resetResult();
  clearIqSelection();
  pendingCapture = input;
  inputId = null;
  acceptedCapture = false;
  epochIndex = 0;
  $('capture-decision').hidden = false;
  $('dtn-input-state').textContent = '사용 여부 선택 대기';
  const data = await request('/dtn/inputs/' + input.inputId + '/observations');
  pendingCapture.receiver = data.receiver;
  pvt = await request('/dtn/inputs/' + input.inputId + '/pvt');
  showInputObservations(data);
  const epoch = data.epochs?.[0]?.observation;
  $('capture-decision-summary').textContent = (epoch ? 'Week ' + epoch.week + ' / TOW ' + epoch.receiverTowSeconds + ' s · 관측 신호 ' + epoch.observations.length : '관측 데이터 확보')
    + ' · 항법정보 ' + data.navigationCount + '건 · ' + (pvt[0]?.message || 'PVT 계산 조건 미충족');
  logView.setContext(input.inputId, 'INPUT');
  updateControls();
}

async function decideCapture(action) {
  if (!pendingCapture || busy) return;
  busy = true; updateControls();
  const id = pendingCapture.inputId;
  let retry = false;
  const receiver = pendingCapture.receiver;
  try {
    if (action === 'accept') {
      await post('/captures/' + id + '/accept');
      inputId = id; acceptedCapture = true;
      pendingCapture = null;
      await loadDelayEpochs();
      $('dtn-input-state').textContent = '수집 완료 · PVT 조건 미충족 데이터 사용';
      log('사용자 승인 · 관측 데이터 사용 · 계산 가능한 항목만 비교', 'WARN');
    } else {
      await post('/captures/' + id + '/discard');
      pendingCapture = null; inputId = null; acceptedCapture = false;
      pvt = []; delayChoices = []; view.setData(null); renderPvt();
      $('dtn-input-state').textContent = 'GNSS 입력 대기';
      log(action === 'retry' ? '기존 후보 폐기 · 새 1 Epoch 수집 요청' : '사용자 결정 · 확보한 관측 데이터 사용 안 함');
      retry = action === 'retry';
    }
    $('capture-decision').hidden = true;
  } catch (error) {
    log(error.message, 'ERROR');
  } finally {
    busy = false; updateControls();
  }
  if (retry) {
    inputMode = 'capture'; updateInputPanels();
    if (receiver?.portName) {
      if (![...$('dtn-port').options].some(option => option.value === receiver.portName)) {
        $('dtn-port').add(new Option(receiver.portName, receiver.portName));
      }
      $('dtn-port').value = receiver.portName;
      $('dtn-baud').value = String(receiver.baudRate);
    }
    await $('dtn-start').onclick();
  }
}
$('capture-use').onclick = () => decideCapture('accept');
$('capture-retry').onclick = () => decideCapture('retry');
$('capture-discard').onclick = () => decideCapture('discard');
if ($('dtn-replay')) $('dtn-replay').onclick = async () => {
  if (locked()) return;
  busy = true; resetInputSelection(); inputId = null; acceptedCapture = false; clearIqSelection(); resetResult(); view.setData(null); updateControls();
  $('dtn-input-state').textContent = '저장된 실제 GNSS 데이터 불러오는 중';
  try {
    const input = await post('/dtn/example/replay');
    logView.setContext(input.inputId,'INPUT');
    const observations = await request('/dtn/inputs/' + input.inputId + '/observations');
    pvt = await request('/dtn/inputs/' + input.inputId + '/pvt');
    inputId = input.inputId; showInputObservations(observations); await loadDelayEpochs();
    $('dtn-input-state').textContent = '실측 GRAW 불러오기 완료 · GNSS 기준시간에서 1에폭 선택';
    log('저장된 실측 GRAW ' + observations.epochs.length + '에폭 로드 · 새 실시간 수집은 COM 포트에서 실행');
  } catch (error) { inputId = null; $('dtn-input-state').textContent = '재생 실패'; log(error.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
};
function updateInputPanels() {
  const iq = selectedType === 'IQ_SAMPLE';
  $('dtn-iq-panel').classList.toggle('hidden', !iq && !generatingIq());
  $('dtn-iq-settings').classList.toggle('hidden', !iq && !generatingIq());
  $('dtn-start').hidden = inputMode !== 'capture';
  $('dtn-capture-panel').classList.toggle('hidden', inputMode !== 'capture');
  $('dtn-upload-panel').classList.toggle('hidden', inputMode !== 'upload');
  for (const button of document.querySelectorAll('.input-mode')) button.classList.toggle('active', button.dataset.inputMode === inputMode);
  updateInputSummary();
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
  updateInputSummary(); updateHdtnControls();
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

async function refreshPorts() {
  await gnss.listPorts();
}
function showPorts(ports) {
  const select = $('dtn-port'), previous = select.value;
  select.replaceChildren(new Option(ports.length ? '포트 선택' : '연결된 포트 없음', ''),
    ...ports.map(p => new Option(p.name + (p.description && p.description !== p.name ? ' · ' + p.description : ''), p.name)));
  if (ports.some(p => p.name === previous)) select.value = previous;
  $('dtn-port-status').textContent = ports.length + '개 포트 확인 · ' + new Date().toLocaleTimeString()
    + ' · 포트 사용 가능 여부는 수집 시작 시 확인';
  updateControls();
}
$('dtn-refresh').onclick = refreshPorts;
$('dtn-send').onclick = async () => {
  if (locked() || $('dtn-send').disabled) return;
  let hdtnConfig;
  try { if (usesHdtn()) hdtnConfig = readHdtnConfig(); }
  catch (error) {
    showSettings(true);
    if (hdtnRules[error.field]?.advanced) $('hdtn-advanced').open = true;
    if (error.field) $('hdtn-' + error.field).focus();
    $('hdtn-config-state').textContent = error.message;
    log(error.message, 'ERROR'); return;
  }
  jobVersion++;
  const delayRequest = delayMode() ? {comparisonMode:'DELAY',selectedEpoch:selectedDelayEpoch()?.epoch} : {};
  busy = true; resetResult(); updateControls();
  try {
    job = await post('/dtn/tests', {inputId: selectedType === 'IQ_SAMPLE' ? null : inputId, iqFileId: iqJob?.id, senderAgentId: $('dtn-sender').value,
      receiverAgentId: $('dtn-receiver').value, sendUrl: $('dtn-send-url').value.trim(), testType: selectedType, senderMode, receiverMode, ...delayRequest, ...(hdtnConfig ? {hdtnConfig} : {})});
    log('전송시험 시작 · ' + job.testId);
    renderSummary();
    historyPage = 0; $('dtn-test-filter').value = ''; await refreshHistory();
  } catch (e) { log('시험 시작 실패 · ' + e.message, 'ERROR'); pill('dtn-test-status', '시작 실패', 'error'); }
  finally { busy = false; updateControls(); }
};
$('dtn-cancel').onclick = async () => {
  if ($('dtn-cancel').disabled || !job?.testId) return;
  const id = job.testId;
  jobVersion++; busy = true; updateControls();
  try {
    job = await post('/dtn/tests/' + encodeURIComponent(id) + '/cancel');
    log(job.cancelPending ? '시험 중지 · 상대 노드에 중지 요청 재시도 중' : '시험 중지 처리 완료');
    renderSummary();
  } catch (error) { log('시험 중지 요청 실패 · ' + error.message, 'ERROR'); }
  finally { busy = false; updateControls(); }
};
function renderSummary() {
  renderTrialSettings($('trial-settings'), job);
  $('dtn-iq-result').hidden = job?.testType !== 'IQ_SAMPLE';
  renderIqFile($('dtn-iq-result'), job?.testType === 'IQ_SAMPLE' ? job.fileResult : null,
    job?.state === 'FAILED' ? '수신 파일 검증 실패 · 로그를 확인하세요.' : '수신 파일 검증 대기');
  if(job?.testId) logView.setContext(job.testId);
  if (!job) return;
  const names = {PREPARING: job.testType === 'IQ_SAMPLE' ? 'I/Q 파일 확인 중' : '입력 준비·기준 PVT 계산 중', WAITING_DTN: '외부 전달·수신 대기', WAITING_RECEIVER: '수신 처리 대기',
    CALCULATING: '복원·PVT 계산 중', COMPLETED: '처리 완료', FAILED: '시험 실패', INCONCLUSIVE: '수신 완료 · PVT 비교 불가', CANCELLED: '취소'};
  pill('dtn-test-status', job.state === 'WAITING_DTN' && job.message?.startsWith('수신 검증 실패') ? '검증 실패 · 재수신 대기' : names[job.state] || job.state, active() ? 'warning' : job.verdict === 'PASS' ? 'online' : 'warning');

  $('dtn-test-detail').textContent = job.testId + ' · ' + (job.message || '');
  payload.setJob(job);
}
let historyPage = 0, historyVersion = 0;
async function refreshHistory() {
  const version = ++historyVersion;
  const query = new URLSearchParams({page: historyPage});
  if ($('dtn-test-filter').value) query.set('state', $('dtn-test-filter').value);
  const rows = await request('/dtn/tests?' + query);
  if (version !== historyVersion) return;
  $('dtn-tests').replaceChildren(new Option('시험 선택', ''), ...rows.map(item => {
    const wait = item.state === 'WAITING_DTN' ? ' · ' + Math.max(0, Math.floor((Date.now() - Date.parse(item.createdAt)) / 60000)) + '분 대기' : '';
    return trialOption(item, new Date(item.createdAt).toLocaleString('ko-KR'), wait);
  }));
  if (job && rows.some(item => item.testId === job.testId)) $('dtn-tests').value = job.testId;
  colorTrialSelection($('dtn-tests'), rows.find(item => item.testId === $('dtn-tests').value));
  $('dtn-tests-prev').disabled = historyPage === 0;
  $('dtn-tests-next').disabled = rows.length < 50;
  $('dtn-tests-page').textContent = String(historyPage + 1);
}
$('dtn-test-filter').onchange = () => { historyPage = 0; refreshHistory().catch(error => log(error.message, 'ERROR')); };
for (const [id, step] of [['dtn-tests-prev', -1], ['dtn-tests-next', 1]]) $(id).onclick = () => {
  historyPage = Math.max(0, historyPage + step); refreshHistory().catch(error => log(error.message, 'ERROR'));
};
$('dtn-tests').onchange = async () => {
  colorTrialSelection($('dtn-tests'), null);
  if (busy || pendingCapture || !$('dtn-tests').value) return;
  const id = $('dtn-tests').value, version = ++jobVersion;
  try {
    const selected = await request('/dtn/tests/' + encodeURIComponent(id));
    if (version !== jobVersion || busy || pendingCapture) return;
    job = selected; colorTrialSelection($('dtn-tests'), job); reportKey = ''; renderSummary(); updateControls();
  } catch (error) { log(error.message, 'ERROR'); }
};

async function poll() {
  if (polling || document.visibilityState === 'hidden') return;
  polling = true;
  try {
    await gnss.poll();
    if (generatingIq()) {
      iqJob = await request('/dtn/iq/' + iqJob.id); renderIq();
      if (!generatingIq()) { log('I/Q 생성 결과 · ' + iqJob.state + ' · ' + iqJob.message); await loadIqFiles(); }
    }
    const status = await request('/dtn/config');
    config.sendBusy = status.sendBusy === true;
    if (!busy && !captureId) {
      const pending = await request('/captures/pending');
      if (Array.isArray(pending) && pending.length && pending[0].inputId !== pendingCapture?.inputId) {
        await showCaptureDecision(pending[0]);
      } else if (Array.isArray(pending) && !pending.length && pendingCapture) {
        const id = pendingCapture.inputId;
        pendingCapture = null;
        $('capture-decision').hidden = true;
        try {
          const input = await request('/inputs/' + id);
          if (input.captureDecision === 'ACCEPTED') {
            inputId = id; acceptedCapture = true;
            await loadDelayEpochs();
            $('dtn-input-state').textContent = '수집 완료 · 다른 화면에서 데이터 사용 승인';
          }
        } catch {
          inputId = null; acceptedCapture = false; pvt = []; delayChoices = [];
          view.setData(null); renderPvt();
          $('dtn-input-state').textContent = '선택 대기 종료 · 입력 상태를 확인하세요.';
        }
      }
    }
    agents = await request('/agents');
    if (peerConfig) {
      const connection = await request('/node/connection');
      gnss.setPeerTime(connection.peerGnssTimeState);
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
    await refreshHistory();
    if (job && !busy) {
      const id = job.testId, version = jobVersion;
      const next = await request('/dtn/tests/' + id);
      if (job?.testId === id && !busy && version === jobVersion) {
        job = next; renderSummary();
        const key = id + ':' + next.updatedAt;
        if (next.testType !== 'IQ_SAMPLE' && (next.referenceEpochs || next.verdict) && key !== reportKey) {
          const report = await request('/dtn/tests/' + id + '/report');
          if (job?.testId === id && !busy && version === jobVersion) {
            pvt = report.referencePvt || [];
            if (report.observations) { inputView = false; view.setData(report.observations, true); }
            renderPvt(); reportKey = key;

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
  ws.onopen = () => { void refreshPorts(); };
  ws.onmessage = event => {
    try {
      const data = JSON.parse(event.data);
      if (data.agentId === $('dtn-sender').value && data.sessionId === captureId && data.type === 'ERROR')
        captureError = data.payload?.message || 'GNSS 수집 실패';
      if (data.agentId === $('dtn-sender').value && data.payload?.ports) showPorts(data.payload.ports);
      if (data.agentId === $('dtn-sender').value && data.sessionId === captureId
          && data.type === 'GNSS_STATUS' && busy) {
        const c = data.payload?.counters || {};
        $('dtn-input-state').textContent = data.payload?.message || '1에폭 수집 중';
        $('dtn-capture-status').textContent = ['bytes', 'rawxEpochs', 'observations', 'gpsL1Satellites', 'navigationMessages']
          .filter(key => c[key] !== undefined).map(key => ({bytes: '수신 바이트', rawxEpochs: '수신 RAWX',
            observations: '최근 관측 신호', gpsL1Satellites: '유효 GPS L1 위성', navigationMessages: '항법 메시지'}[key]) + ': ' + c[key]).join(' · ');
      }
    } catch { log('포트 응답을 읽을 수 없습니다.', 'WARN'); }
  };
  ws.onclose = () => setTimeout(socket, 3000);
}
async function initialize() {
  initializeHdtnConfig();
  try {
    config = await request('/dtn/config');
    if (config.iqEnabled) await loadIqFiles();
    $('dtn-send-url').value = config.defaultSendUrl || '';
    initAdapterHealth(config.adapterUrl || config.defaultSendUrl || '', log, (text, className) => {
      $('dtn-adapter-summary').textContent = text;
      $('dtn-adapter-summary-dot').className = className;
    });
      $('dtn-transport-mode-state').textContent = senderMode + ' → ' + receiverMode + ' · 전송 요청에 포함';
    try {
      peerConfig = await request('/node/connection');
      $('dtn-receiver-ip').value = peerConfig.ip || ''; $('dtn-receiver-port').value = peerConfig.port;
    } catch { $('dtn-connection-message').textContent = '상대 서비스 주소 설정을 사용할 수 없습니다.'; }
    if (!location.pathname?.endsWith('/clear')) {
      const recent = await request('/dtn/tests'); job = recent[0] || null;
    }
    await poll(); socket();
    log('DTN 송신 화면 준비 완료');
  } catch (e) { log(e.message, 'ERROR'); }
  setInterval(poll, 2000);
  updateControls();
}
initialize();
