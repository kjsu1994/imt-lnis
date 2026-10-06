import {renderTrialSettings, trialOption, colorTrialSelection, initWaitingCancellation} from './dtn-settings.js?v=20261001-review';
import {requestJson} from '../common/http.js?v=20261001-review';
import {initGnssControls} from './dtn-gnss.js?v=20261001-review';
import {createDtnLog} from './dtn-log.js?v=20261001-review';
import {initAdapterHealth} from './dtn-adapter-health.js?v=20260922-compact-structure';
import {createPayloadViewer, renderIqFile} from './dtn-payload.js?v=20261001-review';
import {createObservationView, numeric, renderClockBias} from './dtn-observations.js?v=20261006-pvt-filter';

const $ = id => document.getElementById(id);
const payloadViewer = createPayloadViewer($('dtn-payload'), {receivedOnly: true});
const referenceView = createObservationView($('reference-observations'), () => {}, '송신 비교원본');
function renderReference(report = {}) {
  const status = report.referenceStatus;
  $('reference-panel').hidden = !status;
  $('received-observation-card').hidden = $('dtn-observations').hidden && !status;
  $('reference-status').textContent = status === 'COMPLETE' ? '비교자료 수신 완료'
    : status === 'WAITING' ? '수신 계산 완료 · 비교자료 조회 중' : status ? '비교자료 확인 필요' : '';
  $('reference-status').title = report.referenceMessage || '';
  $('reference-retry').hidden = !status || status === 'COMPLETE';
  $('reference-toggle').disabled = !report.referenceObservations;
  if (!report.referenceObservations) {
    $('reference-details').hidden = true;
    $('reference-toggle').setAttribute('aria-expanded', 'false');
  }
  referenceView.setData(report.referenceObservations || null, false, null, report);
}
$('reference-retry').onclick = async () => {
  if (!selectedId) return;
  $('reference-retry').disabled = true;
  try {
    await requestJson('/dtn/tests/' + encodeURIComponent(selectedId) + '/reference/retry',
      {method: 'POST'});
    await poll(true);
  } catch (error) { log(error.message, 'ERROR'); }
  finally { $('reference-retry').disabled = false; }
};
const clearScreen = location.pathname?.endsWith('/clear') === true;
let tests = [], epochs = [], selectedId = '', renderVersion = 0, polling = false;
let reportKey = '';
let receivedIds = null;
let historyPage = 0, selectionPinned = false, cancelling = false;
let referenceEpochs = [], comparisonEpochs = [], delayComparison = false, delayEvidence = null;
function setComparison(report = {}) {
  renderReference(report);
  delayComparison = report.comparisonMode === 'DELAY';
  $('receiver-pvt-title').textContent = delayComparison ? '수신 지연 반영 PVT · Reference 비교' : '수신 지구 PVT · 송신 기준 비교';
  $('received-pvt-label').textContent = delayComparison ? '수신 지연 반영 지구 PVT' : '수신 복원 지구 PVT';
  delayEvidence = report.delayEvidence ?? null;
  $('dtn-clock-analysis').hidden = !delayComparison;
  $('pvt-sync-note').hidden = !delayComparison;
  const timingKnown = report.senderClock?.source && report.receiverClock?.source
    && report.senderClock.source !== 'SYSTEM' && report.receiverClock.source !== 'SYSTEM';
  $('pvt-sync-note').textContent = report.clockWarning || (timingKnown ? '내부 시각 근사 보정 적용' : '시험 시각 보정 미확인');
  $('pvt-sync-note').title = report.senderClock && report.receiverClock
    ? '송신: ' + report.senderClock.source + ' / 수신: ' + report.receiverClock.source
      + '\n송신 보정량 ' + report.senderClock.offsetSeconds + ' s / 수신 보정량 ' + report.receiverClock.offsetSeconds + ' s'
    : '과거 시험 또는 미보정 시각';
  $('pvt-delay-details').hidden = !delayComparison;
  renderClockAnalysis(null);
  referenceEpochs = Array.isArray(report.referencePvt) ? report.referencePvt : [];
  comparisonEpochs = report.comparison?.epochs || [];
  const verdict = report.comparison?.verdict;
  pill('pvt-match', verdict === 'MEASURED' ? (delayComparison ? '지연 반영 PVT 측정 완료' : 'I/Q PVT 오차 측정') : verdict === 'PARTIAL' ? '부분 비교 · 속도 비교 불가' : verdict === 'PASS' ? '전체 PVT 일치' : verdict === 'FAIL' ? '전체 PVT 불일치' : 'PVT 비교 불가',
    verdict === 'PASS' ? 'online' : verdict === 'FAIL' ? 'error' : verdict === 'MEASURED' && delayComparison ? '' : 'warning');
}
const observations = createObservationView($('dtn-observations'), index => {
  if (epochs[index]) { $('pvt-epoch').value = String(index); renderEpoch(); }
}, '수신 원본');
observations.setData(null);

function get(path) {
  return requestJson(path, {cache: 'no-store'}, {errorDetails: false});
}

function updateClockSkewWarning(connection) {
  const warning = $('dtn-clock-skew-warning');
  if (!warning) return;
  const skew = connection?.peerClockSkewSeconds;
  if (connection?.peerOnline && typeof skew === 'number' && Math.abs(skew) >= 0.5) {
    warning.hidden = false;
    const skewVal = $('clock-skew-val');
    if (skewVal) skewVal.textContent = (skew > 0 ? '+' : '') + skew.toFixed(2) + 's';
  } else {
    warning.hidden = true;
  }
}

const logView=createDtnLog($('dtn-log'));
const waitingCancellation = initWaitingCancellation({isLocked: () => cancelling, refresh: () => poll(true), log});
function log(message,level='INFO') { logView.write(message,level); }
const gnss = initGnssControls({port: 'gnss-port', baud: 'gnss-baud', refresh: 'gnss-refresh', log});
let peerAddressDirty = false, peerSaving = false;
$('sender-address').oninput = () => {
  peerAddressDirty = true;
  $('reverse-state').textContent = '주소 변경 · 미확인';
  $('reverse-dot').className = 'connection-dot unknown';
  $('sender-address-feedback').textContent = '';
};
async function saveSenderAddress(save) {
  if (peerSaving) return;
  peerSaving = true;
  $('sender-address-save').disabled = $('sender-address-test').disabled = true;
  try {
    const url = new URL($('sender-address').value.trim());
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || url.search || url.hash || url.pathname !== '/') throw new Error('http(s)://IPv4:포트 형식으로 입력하세요.');
    const result = await requestJson('/node/connection' + (save ? '' : '/test'), {
      method: save ? 'PUT' : 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ip: url.hostname, port: Number(url.port || (url.protocol === 'https:' ? 443 : 80)), scheme: url.protocol.slice(0, -1)})});
    $('sender-address-feedback').textContent = save ? '수신 서버에 저장·적용됨' : result.message;
    log(save ? '상대 송신 서비스 주소 저장·적용' : result.message, !save && !result.connected ? 'WARN' : 'INFO');
    if (save) {
      peerAddressDirty = false;
      $('sender-address').value = result.baseUrl;
      reportKey = '';
    }
  } catch (error) {
    $('sender-address-feedback').textContent = error.message;
    log(error.message, 'ERROR');
  } finally {
    peerSaving = false;
    await poll(true);
  }
}
$('reference-toggle').onclick = () => {
  const opened = $('reference-details').hidden;
  $('reference-details').hidden = !opened;
  $('reference-toggle').setAttribute('aria-expanded', String(opened));
};
$('sender-address-save').onclick = () => saveSenderAddress(true);
$('sender-address-test').onclick = () => saveSenderAddress(false);

function pill(id, text, state = '') {
  $(id).textContent = text;
  $(id).className = ('pill ' + state).trim();
}

function number(value, digits = 3) {
  return numeric(value, digits, '-');
}

function time(value) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('ko-KR');
}

function measured(value, digits, unit) {
  if (!Number.isFinite(value)) return '—';
  const rounded = value.toFixed(digits);
  return (Number(rounded) === 0 ? (0).toFixed(digits) : rounded) + ' ' + unit;
}

function positionDifference(value) {
  return Number.isFinite(value) && Math.abs(value) < 1
    ? measured(value * 1000, 3, 'mm') : measured(value, 6, 'm');
}

function renderClockAnalysis(delta, reference, pvt) {
  const available = delayComparison && !delayEvidence?.error;
  const seconds = available ? delta?.clockResidualSeconds : null;
  const residual = Number.isFinite(seconds) && seconds !== 0 && Math.abs(seconds) < 1e-12
    ? (seconds < 0 ? '음수 · ' : '양수 · ') + '크기 < 0.001 ns'
    : measured(seconds * 1e9, 3, 'ns');
  $('pvt-delay-value').textContent = delayComparison ? measured(delayEvidence?.delaySeconds, 9, 's') : '—';
  $('pvt-clock-change').textContent = measured(available ? delta?.clockDifferenceSeconds : null, 9, 's');
  $('pvt-delay-residual').textContent = Number.isFinite(seconds) ? residual : '—';
  $('pvt-delay-reason').textContent = !delayComparison ? '' : delayEvidence?.error
    || (!delta ? '비교 가능한 PVT 결과가 없습니다.' : !Number.isFinite(seconds) ? '시계오차 비교 근거가 없습니다.' : '');
  const raw = value => Number.isFinite(value) ? String(value) : '—';
  const epoch = value => value && Number.isFinite(value.week) && Number.isFinite(value.towSeconds)
    ? 'Week ' + value.week + ' / TOW ' + value.towSeconds + ' s' : '—';
  const vector = values => Array.isArray(values) && values.length === 3 ? values.map(raw).join(' / ') : '—';
  $('pvt-delay-evidence').textContent = !delayComparison ? '' : [
    '시작 접수 (UTC): ' + (delayEvidence?.timing?.startedAt ?? '—'),
    '본문 수신 완료 (UTC): ' + (delayEvidence?.timing?.receivedAt ?? '—'),
    '송신 기준 GNSS 시각: ' + epoch(delayEvidence?.originalTime),
    '지연 반영 GNSS 시각: ' + epoch(delayEvidence?.shiftedTime),
    '시험 전달 지연: ' + raw(delayEvidence?.delaySeconds) + ' s',
    '송신 기준 Clock Bias: ' + raw(reference?.positionValid ? reference.receiverClockBiasSeconds : null) + ' s',
    '수신 Clock Bias: ' + raw(pvt?.positionValid ? pvt.receiverClockBiasSeconds : null) + ' s',
    '시계오차 변화: ' + raw(available ? delta?.clockDifferenceSeconds : null) + ' s',
    '지연 반영 잔차: ' + raw(seconds) + ' s',
    '잔차 = (수신 Bias − 송신 기준 Bias) − 시험 전달 지연',
    '위치 변화 X / Y / Z (m): ' + vector(delta?.positionDeltaMeters),
    '속도 변화 X / Y / Z (m/s): ' + vector(delta?.velocityDeltaMetersPerSecond),
    '사용 위성 (송신 / 수신): ' + raw(reference?.satellitesUsed) + ' / ' + raw(pvt?.satellitesUsed)
  ].join('\n');
}

function renderEpoch() {
  observations.select(Number($('pvt-epoch').value));
  const pvt = epochs[Number($('pvt-epoch').value)];
  const delta = pvt && comparisonEpochs.find(e=>e.week===pvt.week && e.towSeconds===pvt.towSeconds);
  $('pvt-differences').textContent = '위치 차이 '+positionDifference(delta?.positionDifferenceMeters)+' · 속도 차이 '+measured(delta?.velocityDifferenceMetersPerSecond,6,'m/s')
    + (delayComparison ? '' : ' · 시계오차 차이 '+number(delta?.clockDifferenceSeconds,12)+' s');
  const reference = pvt && (delayComparison ? referenceEpochs[0] : referenceEpochs.find(value => value.week === pvt.week && value.towSeconds === pvt.towSeconds));
  renderClockAnalysis(delta, reference, pvt);
  ['x', 'y', 'z'].forEach((axis, index) => {
    $('reference-' + axis).textContent = number(reference?.positionValid ? reference.ecefMeters?.[index] : null);
    $('reference-v' + axis).textContent = number(reference?.velocityValid ? reference.velocityMetersPerSecond?.[index] : null);
  });
  renderClockBias($('reference-clock'), reference?.positionValid ? reference.receiverClockBiasSeconds : null);
  $('reference-satellites').textContent = reference?.satellitesUsed ?? '-';
  const position = pvt?.positionValid === true;
  const velocity = pvt?.velocityValid === true;
  ['x', 'y', 'z'].forEach((axis, index) => {
    $('pvt-' + axis).textContent = number(position ? pvt.ecefMeters?.[index] : null);
    $('pvt-v' + axis).textContent = number(velocity ? pvt.velocityMetersPerSecond?.[index] : null);
  });
  $('pvt-satellites').textContent = pvt?.satellitesUsed ?? '-';
  renderClockBias($('pvt-clock'), position ? pvt.receiverClockBiasSeconds : null);
  pill('pvt-validity', !pvt ? '결과 대기' : '위치 ' + (position ? '유효' : '무효') + ' · 속도 ' + (velocity ? '유효' : '무효'),
    !pvt ? '' : position && velocity ? 'online' : 'warning');
  $('pvt-message').textContent = pvt?.message || '지구 ECEF · GPS L1 C/A';
}

function setEpochs(values, preserve = false) {
  const selected = preserve ? $('pvt-epoch').value : '0';
  epochs = Array.isArray(values) ? values : [];
  $('pvt-epoch').replaceChildren(...(epochs.length
    ? epochs.map((pvt, index) => new Option((index + 1) + ' · Week ' + pvt.week + ' / TOW ' + number(pvt.towSeconds) + ' s', String(index)))
    : [new Option('계산 결과 없음', '')]));
  $('pvt-epoch').disabled = !epochs.length;
  if (epochs.length) $('pvt-epoch').value = Number(selected) < epochs.length ? selected : '0';
  $('pvt-count').textContent = epochs.length + '개 관측';
  renderEpoch();
}

function renderSummary(job) {
  waitingCancellation.update();
  $('dtn-cancel').disabled = cancelling || !['PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING'].includes(job?.state);
  $('dtn-cancel').textContent = job?.state === 'CALCULATING' ? '계산 중지' : job?.state === 'WAITING_DTN' ? '대기 종료' : '시험 중지';
  renderTrialSettings($('trial-settings'), job);
  $('dtn-observations').hidden = job?.testType === 'IQ_SAMPLE' && !job?.receivedEpochs;
  $('received-observation-card').hidden = $('dtn-observations').hidden && $('reference-panel').hidden;
  const types = {GNSS_RAW: 'GNSS RAW', AFS_METADATA: 'AFS Frame', IQ_SAMPLE: 'I/Q Sample'};
  $('receiver-type').textContent = types[job?.testType] || '시험 선택 대기';
  $('receiver-mode').textContent = job?.senderMode && job?.receiverMode ? job.senderMode + ' → ' + job.receiverMode : '경로 정보 없음';
  $('receiver-iq').hidden = job?.testType !== 'IQ_SAMPLE';
  renderIqFile($('receiver-iq-result'), job?.fileResult, job?.state === 'FAILED' ? 'I/Q 파일 검증 실패 · 로그를 확인하세요.' : 'I/Q 파일 수신·검증 대기');
  const failed = ['FAILED', 'CANCELLED'].includes(job?.state);
  const completed = ['COMPLETED', 'INCONCLUSIVE'].includes(job?.state);
  const received = !!job?.dtnReceived;
  const iq = job?.testType === 'IQ_SAMPLE';
  $('step-process').textContent = iq ? '③ I/Q 검증·추적·PVT' : '③ 복원·PVT 계산';
  const states = {
    PREPARING: '시험 준비 중', WAITING_DTN: '외부 JSON 수신 대기',
    WAITING_RECEIVER: '수신 실행기 대기', CALCULATING: iq ? 'I/Q 검증·추적·PVT 처리 중' : '복원·PVT 계산 중',
    COMPLETED: iq ? 'I/Q 처리 완료' : '수신 계산 완료', FAILED: '처리 실패', CANCELLED: '시험 취소',
    INCONCLUSIVE: '수신·복원 완료 · PVT 비교 불가'
  };
  $('receive-state').textContent = job ? (job.lateReceivedAt ? '대기 종료 · 이후 수신됨' : job.state === 'WAITING_DTN' && job.message?.startsWith('수신 검증 실패') ? '검증 실패 · 재수신 대기' : states[job.state] || job.state) : '수신 대기';
  $('receive-state').className = failed ? 'receiver-error' : '';
  $('receive-message').textContent = job?.message || '송신 측 시험 시작을 기다립니다.';
  $('test-id').textContent = job?.testId || '-';
  $('test-updated').textContent = time(job?.updatedAt);
  // 서버가 복호화/계산의 개별 진척률을 제공하지 않으므로 하나의 처리 단계로 표시한다.
  const classes = [job ? 'done' : '', received ? 'done' : job && !failed ? 'active' : '',
    completed ? 'done' : received && !failed ? 'active' : ''];
  if (failed) classes[received ? 2 : 1] = 'failed';
  ['step-register', 'step-receive', 'step-process'].forEach((id, index) => $(id).className = classes[index]);
}

async function renderTest(force = false) {
  const version = ++renderVersion;
  const job = tests.find(item => item.testId === $('dtn-tests').value);
  const changed = selectedId !== (job?.testId || '');
  selectedId = job?.testId || '';
  logView.setContext(selectedId);
  payloadViewer.setJob(job);
  renderSummary(job);
  if (changed || !job) {
    $('pvt-delay-details').open = false;
    reportKey = '';
    setComparison();
    setEpochs([]);
    observations.setData(null);
  }
  if (!job) return;
  const event = job.testId + ':' + job.state + ':' + job.updatedAt;
  if (!job.receivedEpochs) return;
  if (!force && reportKey === event) return;
  try {
    const report = await get('/dtn/tests/' + encodeURIComponent(job.testId) + '/report');
    // 시험을 바꾼 뒤 늦게 도착한 이전 응답이 새 시험의 PVT를 덮어쓰지 않는다.
    if (version !== renderVersion || selectedId !== job.testId) return;
    setComparison(report);
    setEpochs(report.receivedPvt, !changed);
    observations.setData(report.observations, !changed, delayComparison ? report.delayEvidence : null, report);
    reportKey = event;
  } catch (error) {
    if (version !== renderVersion) return;
    setComparison();
    setEpochs([]);
    $('pvt-message').textContent = 'PVT 조회 실패 · ' + error.message;
    log('PVT 조회 실패 · ' + error.message);
  }
}

function renderAgents(agents) {
  for (const role of ['SENDER', 'RECEIVER']) {
    const agent = agents.find(item => item.role === role);
    const online = !!agent && !['OFFLINE','ERROR'].includes(agent.state);
    const text = role === 'SENDER' ? '송신 처리기' : '수신 처리기';
    pill('dtn-' + role.toLowerCase() + '-status', text + ' ' +
      (online ? (agent.state === 'READY' ? '준비됨' : '처리 중') : '연결 안 됨'),
      online ? (agent.state === 'READY' ? 'online' : 'warning') : 'error');
  }
}

async function poll(force = false) {
  if (polling || (!force && document.visibilityState === 'hidden')) return;
  polling = true;
  $('dtn-refresh').disabled = true;
  try {
    await gnss.poll();
    const query = new URLSearchParams({page: historyPage});
    if ($('dtn-test-filter').value) query.set('state', $('dtn-test-filter').value);
    const [agents, nextTests] = await Promise.all([get('/agents'), get('/dtn/tests?' + query)]);
    $('dtn-tests-prev').disabled = historyPage === 0;
    $('dtn-tests-next').disabled = nextTests.length < 50;
    $('dtn-tests-page').textContent = String(historyPage + 1);
    if (selectionPinned && selectedId && !nextTests.some(item => item.testId === selectedId)) {
      const selectedJob = await get('/dtn/tests/' + encodeURIComponent(selectedId)).catch(() => null);
      if (selectedJob) nextTests.push(selectedJob);
    }
    const newlyReceived = nextTests.filter(job => job.dtnReceived &&
      (receivedIds === null ? !clearScreen : !receivedIds.has(job.testId)))
      .sort((a, b) => (Date.parse(b.receivedAt) || 0) - (Date.parse(a.receivedAt) || 0))[0];
    receivedIds = new Set(nextTests.filter(job => job.dtnReceived).map(job => job.testId));
    tests = nextTests;
    const connection = await get('/node/connection').catch(() => ({}));
    gnss.setPeerTime(connection.peerGnssTimeState);
    updateClockSkewWarning(connection);
    if (!peerAddressDirty) {
      $('sender-address').value = connection.baseUrl || '';
      $('reverse-state').textContent = connection.peerOnline == null ? '미확인' : connection.peerOnline ? '연결됨' : '연결 끊김';
      $('reverse-dot').className = 'connection-dot ' + (connection.peerOnline == null ? 'unknown' : connection.peerOnline ? 'online' : 'offline');
    }
    $('sender-address-save').disabled = peerSaving || !!connection.busy || !connection.editable;
    $('sender-address-test').disabled = peerSaving || !connection.editable;
    $('sender-address').disabled = peerSaving || !!connection.busy || !connection.editable;
    pill('dtn-server-status', '서버 연결됨', 'online');
    renderAgents(agents);
    const selected = $('dtn-tests').value;
    $('dtn-tests').replaceChildren(...(clearScreen ? [new Option('시험 선택 · 화면 초기화됨', '')] : []), ...(tests.length ? tests.map(job =>
      trialOption(job, time(job.createdAt)))
      : [new Option('등록된 시험 없음', '')]));
    if (newlyReceived && !selectionPinned && historyPage === 0) $('dtn-tests').value = newlyReceived.testId;
    else if (tests.some(job => job.testId === selected)) $('dtn-tests').value = selected;
    colorTrialSelection($('dtn-tests'), tests.find(job => job.testId === $('dtn-tests').value));
    await renderTest(force);
    const receiptTestId = selectedId;
    const selectedJob = tests.find(item => item.testId === receiptTestId);
    if (receiptTestId && !selectedJob?.receivedPayloadAvailable) {
      const receipts = await get('/dtn/receipts?testId=' + encodeURIComponent(receiptTestId)).catch(() => null);
      if (receipts && selectedId === receiptTestId) payloadViewer.setReceipts(receipts);
    }
    $('last-updated').textContent = '최근 확인 ' + new Date().toLocaleTimeString('ko-KR') + ' · 자동 갱신';
  } catch (error) {
    pill('dtn-server-status', '갱신 실패 · 재시도 중', 'error');
    pill('dtn-sender-status', '송신 노드 확인 불가', 'warning');
    pill('dtn-receiver-status', '수신 실행기 확인 불가', 'warning');
    log('조회 실패 · ' + error.message);
  } finally {
    polling = false;
    $('dtn-refresh').disabled = false;
  }
}

async function initialize() {
  await gnss.poll();
  void gnss.listPorts();
  try { const config = await get('/dtn/config'); initAdapterHealth(config.adapterUrl || '', log); }
  catch (error) { initAdapterHealth('', log); log(error.message); }
  try {
    const connection = await get('/node/connection');
    $('sender-address').value = connection.baseUrl || '';
  } catch { /* Optional in central mode. */ }
  await poll();
  const repeat = async () => { await poll(); setTimeout(repeat, 2000); };
  setTimeout(repeat, 2000);
}

$('dtn-tests').onchange = () => {
  colorTrialSelection($('dtn-tests'), tests.find(job => job.testId === $('dtn-tests').value));
  selectionPinned = true;
  renderTest();
};
$('dtn-test-filter').onchange = () => { historyPage = 0; selectionPinned = false; poll(true); };
for (const [id, step] of [['dtn-tests-prev', -1], ['dtn-tests-next', 1]]) $(id).onclick = () => {
  historyPage = Math.max(0, historyPage + step); selectionPinned = false; poll(true);
};
$('dtn-cancel').onclick = async () => {
  if (!selectedId || $('dtn-cancel').disabled) return;
  const id = selectedId;
  cancelling = true; $('dtn-cancel').disabled = true;
  try {
    await requestJson('/dtn/tests/' + encodeURIComponent(id) + '/cancel', {method:'POST'});
    log('선택 시험 종료 · ' + id); await poll(true);
  } catch (error) { log(error.message, 'ERROR'); }
  finally { cancelling = false; renderSummary(tests.find(item => item.testId === selectedId)); }
};
$('pvt-epoch').onchange = renderEpoch;
$('dtn-refresh').onclick = () => poll(true);

initialize();
