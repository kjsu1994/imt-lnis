import {requestJson} from '../common/http.js?v=20260915-structure';
import {createDtnLog} from './dtn-log.js?v=20260915-structure';
import {initAdapterHealth} from './dtn-adapter-health.js?v=20260915-structure';
import {createPayloadViewer, renderIqFile} from './dtn-payload.js?v=20260915-structure';
import {createObservationView, numeric} from './dtn-observations.js?v=20260915-input-cleanup';

const api = '/lnis/api/v1';
const $ = id => document.getElementById(id);
const payloadViewer = createPayloadViewer($('dtn-payload'), {receivedOnly: true});
const clearScreen = location.pathname?.endsWith('/clear') === true;
let tests = [], epochs = [], selectedId = '', renderVersion = 0, polling = false;
let reportKey = '', lastEvent = '';
let referenceEpochs = [], comparisonEpochs = [];
function setComparison(report = {}) {
  referenceEpochs = Array.isArray(report.referencePvt) ? report.referencePvt : [];
  comparisonEpochs = report.comparison?.epochs || [];
  const verdict = report.comparison?.verdict;
  pill('pvt-match', verdict === 'PASS' ? '전체 PVT 일치' : verdict === 'FAIL' ? '전체 PVT 불일치' : 'PVT 비교 불가',
    verdict === 'PASS' ? 'online' : verdict === 'FAIL' ? 'error' : 'warning');
}
const observations = createObservationView($('dtn-observations'), index => {
  if (epochs[index]) { $('pvt-epoch').value = String(index); renderEpoch(); }
});
observations.setData(null);

function get(path) {
  return requestJson(path, {cache: 'no-store'}, {errorDetails: false});
}

const logView=createDtnLog($('dtn-log'));
function log(message,level='INFO') { logView.write(message,level); }

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

function renderEpoch() {
  observations.select(Number($('pvt-epoch').value));
  const pvt = epochs[Number($('pvt-epoch').value)];
  const delta = pvt && comparisonEpochs.find(e=>e.week===pvt.week && e.towSeconds===pvt.towSeconds);
  $('pvt-differences').textContent = '위치 차이 '+number(delta?.positionDifferenceMeters,6)+' m · 속도 차이 '+number(delta?.velocityDifferenceMetersPerSecond,6)+' m/s · 시계오차 차이 '+number(delta?.clockDifferenceSeconds,12)+' s';
  const reference = pvt && referenceEpochs.find(value => value.week === pvt.week && value.towSeconds === pvt.towSeconds);
  ['x', 'y', 'z'].forEach((axis, index) => {
    $('reference-' + axis).textContent = number(reference?.positionValid ? reference.ecefMeters?.[index] : null);
    $('reference-v' + axis).textContent = number(reference?.velocityValid ? reference.velocityMetersPerSecond?.[index] : null);
  });
  $('reference-clock').textContent = number(reference?.positionValid ? reference.receiverClockBiasSeconds : null, 9);
  $('reference-satellites').textContent = reference?.satellitesUsed ?? '-';
  const position = pvt?.positionValid === true;
  const velocity = pvt?.velocityValid === true;
  ['x', 'y', 'z'].forEach((axis, index) => {
    $('pvt-' + axis).textContent = number(position ? pvt.ecefMeters?.[index] : null);
    $('pvt-v' + axis).textContent = number(velocity ? pvt.velocityMetersPerSecond?.[index] : null);
  });
  $('pvt-satellites').textContent = pvt?.satellitesUsed ?? '-';
  $('pvt-clock').textContent = number(position ? pvt.receiverClockBiasSeconds : null, 9);
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
  $('dtn-observations').hidden = job?.testType === 'IQ_SAMPLE';
  const types = {GNSS_RAW: 'GNSS RAW', AFS_METADATA: 'AFS Frame + Metadata', IQ_SAMPLE: 'I/Q Sample'};
  $('receiver-type').textContent = types[job?.testType] || '시험 선택 대기';
  const selectReadOnly = (id, selected) => {
    const button = $(id);
    button.className = selected ? 'active' : '';
    button.disabled = true;
    button.setAttribute('aria-pressed', String(selected));
  };
  for (const type of Object.keys(types)) selectReadOnly('receiver-' + type, job?.testType === type);
  for (const sender of ['DTN', 'HDTN']) for (const receiver of ['DTN', 'HDTN'])
    selectReadOnly('receiver-mode-' + sender + '-' + receiver,
      job?.senderMode === sender && job?.receiverMode === receiver);
  $('receiver-mode').textContent = job?.senderMode && job?.receiverMode ? job.senderMode + ' → ' + job.receiverMode : '경로 정보 없음';
  $('receiver-iq').hidden = job?.testType !== 'IQ_SAMPLE';
  renderIqFile($('receiver-iq-result'), job?.fileResult, job?.state === 'FAILED' ? 'I/Q 파일 검증 실패 · 로그를 확인하세요.' : 'I/Q 파일 수신·검증 대기');
  const failed = ['FAILED', 'CANCELLED', 'INCONCLUSIVE'].includes(job?.state);
  const completed = job?.state === 'COMPLETED';
  const received = !!job?.dtnReceived;
  const iq = job?.testType === 'IQ_SAMPLE';
  $('step-process').textContent = iq ? '③ I/Q 파일 검증' : '③ 복원·PVT 계산';
  const states = {
    PREPARING: '시험 준비 중', WAITING_DTN: '외부 JSON 수신 대기',
    WAITING_RECEIVER: '수신 실행기 대기', CALCULATING: iq ? 'I/Q 파일 검증 중' : '복원·PVT 계산 중',
    COMPLETED: iq ? 'I/Q 파일 검증 완료' : '수신 계산 완료', FAILED: '처리 실패', CANCELLED: '시험 취소',
    INCONCLUSIVE: '판정 불가'
  };
  $('receive-state').textContent = job ? (states[job.state] || job.state) : '수신 대기';
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
    reportKey = '';
    setComparison();
    setEpochs([]);
    observations.setData(null);
    $('dtn-report').hidden = true;
    $('dtn-report').removeAttribute('href');
  }
  if (!job) return;
  const event = job.testId + ':' + job.state + ':' + job.updatedAt;
  lastEvent = event;
  if (!job.receivedEpochs) return;
  $('dtn-report').href = api + '/dtn/tests/' + encodeURIComponent(job.testId) + '/report';
  $('dtn-report').hidden = false;
  if (!force && reportKey === event) return;
  try {
    const report = await get('/dtn/tests/' + encodeURIComponent(job.testId) + '/report');
    // 시험을 바꾼 뒤 늦게 도착한 이전 응답이 새 시험의 PVT를 덮어쓰지 않는다.
    if (version !== renderVersion || selectedId !== job.testId) return;
    setComparison(report);
    setEpochs(report.receivedPvt, !changed);
    observations.setData(report.observations, !changed);
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
  if (polling) return;
  polling = true;
  $('dtn-refresh').disabled = true;
  try {
    const [agents, nextTests, config] = await Promise.all([get('/agents'), get('/dtn/tests'), get('/dtn/config')]);
    tests = nextTests;
    const connection = await get('/node/connection').catch(() => ({}));
    $('reverse-state').textContent = connection.peerOnline == null ? '미확인' : connection.peerOnline ? '연결됨' : '연결 끊김';
    $('reverse-dot').className = 'connection-dot ' + (connection.peerOnline == null ? 'unknown' : connection.peerOnline ? 'online' : 'offline');
    pill('dtn-server-status', '서버 연결됨', 'online');
    renderAgents(agents);
    pill('receive-auth', config.receiveConfigured ? '수신 인증 설정됨' : '수신 인증 미설정',
      config.receiveConfigured ? 'online' : 'warning');
    $('receive-auth').title = '외부 어댑터는 LNIS_DTN_RECEIVE_TOKEN과 같은 Bearer 토큰을 사용해야 합니다. 관리 토큰과 별개입니다.';
    const selected = $('dtn-tests').value;
    $('dtn-tests').replaceChildren(...(clearScreen ? [new Option('시험 선택 · 화면 초기화됨', '')] : []), ...(tests.length ? tests.map(job =>
      new Option(time(job.createdAt) + ' · ' + job.state + ' · ' + job.testId.slice(0, 8), job.testId))
      : [new Option('등록된 시험 없음', '')]));
    if (tests.some(job => job.testId === selected)) $('dtn-tests').value = selected;
    await renderTest(force);
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
  try { const config = await get('/dtn/config'); initAdapterHealth(config.adapterUrl || '', log); }
  catch (error) { initAdapterHealth('', log); log(error.message); }
  $('receive-url').value = location.origin + api + '/dtn/receive';
  try {
    const response = await fetch(api + '/node', {cache: 'no-store'});
    if (response.ok) {
      const node = await response.json();
      // 컨테이너 내부 IP가 아니라 외부에서 접근하도록 설정한 공개 주소를 표시한다.
      if (node.baseUrl) $('receive-url').value = node.baseUrl.replace(/\/$/, '') + api + '/dtn/receive';
    }
  } catch { /* 중앙 서버 모드에서는 현재 브라우저 주소를 사용한다. */ }
  try {
    const connection = await get('/node/connection');
    $('sender-address').value = connection.baseUrl || '';
  } catch { /* Optional in central mode. */ }
  await poll();
  const repeat = async () => { await poll(); setTimeout(repeat, 2000); };
  setTimeout(repeat, 2000);
}

$('dtn-tests').onchange = () => renderTest();
$('pvt-epoch').onchange = renderEpoch;
$('dtn-refresh').onclick = () => poll(true);

$('copy-receive-url').onclick = async () => {
  try {
    await navigator.clipboard.writeText($('receive-url').value);
    $('receive-help').textContent = '수신 API 주소를 복사했습니다.';
  } catch {
    // 일반 LAN HTTP에서는 클립보드 API가 차단될 수 있으므로 수동 복사를 돕는다.
    $('receive-url').focus();
    $('receive-url').select();
    $('receive-help').textContent = '선택된 주소를 Ctrl+C로 복사하세요.';
  }
};
initialize();
