import {requestJson} from '../common/http.js?v=20261001-review';

const types = {GNSS_RAW: 'RAW', AFS_METADATA: 'AFS', IQ_SAMPLE: 'I/Q'};
const trialStates = {PREPARING: '준비 중', WAITING_DTN: '수신 대기', WAITING_RECEIVER: '계산 대기',
  CALCULATING: '계산 중', COMPLETED: '완료', INCONCLUSIVE: '비교 불가', FAILED: '실패', CANCELLED: '종료'};

export function trialStatus(job) {
  if (!job) return {label: '시험 선택', tone: 'neutral'};
  const failed = job.state === 'FAILED' || job.verdict === 'FAIL';
  const completed = job.state === 'COMPLETED';
  return {
    label: failed && completed ? '완료 · 불일치' : trialStates[job.state] || '상태 확인 중',
    tone: failed ? 'failure' : completed ? 'success' : job.state === 'CANCELLED' ? 'neutral' : 'waiting'
  };
}

export function trialOption(job, date, suffix = '') {
  const status = trialStatus(job);
  const option = new Option(date + ' · ' + status.label + suffix + ' · ' + job.testId.slice(0, 8), job.testId);
  option.className = 'trial-' + status.tone;
  return option;
}

export function colorTrialSelection(select, job) {
  select.setAttribute('data-trial-tone', trialStatus(job).tone);
}

const fields = {
  maxNumberOfBundlesInPipeline: ['동시 번들', '개'],
  maxSumOfBundleBytesInPipeline: ['동시 용량', 'Bytes'],
  maxBundleSizeBytes: ['번들 크기', 'Bytes'],
  tcpclMaxSegmentSizeBytes: ['TCPCL 크기', 'Bytes'],
  neighborDepletedStorageDelaySeconds: ['혼잡 대기', '초'],
  enforceBundlePriority: ['우선순위', ''],
  storageDeletionPolicy: ['삭제 정책', ''],
  totalStorageCapacityBytes: ['저장 용량', 'Bytes'],
  maxLtpReceiveUdpPacketSizeBytes: ['LTP 수신 크기', 'Bytes'],
  acsSendPeriodMilliseconds: ['ACS 주기', 'ms']
};
const dtnFields = {
  sdrHeapSizeBytes: ['SDR 힙', 'Bytes'],
  sdrWorkingMemorySizeBytes: ['SDR 작업 메모리', 'Bytes'],
  sdrTransientMode: ['SDR 모드', ''],
  maxBundleSizeBytes: ['번들 크기', 'Bytes'],
  contactRateBytesPerSec: ['접촉 전송 속도', 'Bytes/s'],
  maxProductionRateBytesPerSec: ['생성 속도', 'Bytes/s'],
  maxConsumptionRateBytesPerSec: ['소비 속도', 'Bytes/s'],
  tcpclMaxSegmentSizeBytes: ['TCPCL 크기', 'Bytes'],
  stcpMaxSegmentSizeBytes: ['STCP 크기', 'Bytes'],
  routingMode: ['라우팅 모드', '']
};
const policies = {never: '자동 삭제 안 함 (never)', on_forward: '전달 완료 시 삭제', on_delivery: '인도 완료 시 삭제', DELETE_AFTER_FORWARDING: '어댑터 기본', on_expiration: '수명 만료 시', on_storage_full: '저장소 부족 시'};
function valueText(key, value, compact = false) {
  if (value == null) return '기록 없음';
  if (key === 'enforceBundlePriority') return value ? '사용' : '사용 안 함';
  if (key === 'sdrTransientMode') return value ? '순수 RAM (Transient)' : '디스크 기반';
  if (key === 'storageDeletionPolicy') return policies[value] || String(value);
  if (key === 'routingMode') return String(value);
  const def = fields[key] || dtnFields[key] || ['', ''];
  const unit = def[1];
  const exact = Number(value).toLocaleString('ko-KR') + (unit ? ' ' + unit : '');
  if (unit !== 'Bytes' && unit !== 'Bytes/s') return exact;
  const baseUnit = unit === 'Bytes/s' ? 'B/s' : 'Bytes';
  const scale = value >= 1e9 ? 1e9 : value >= 1e6 ? 1e6 : value >= 1e3 ? 1e3 : 1;
  const label = {1: baseUnit, 1000: 'K' + baseUnit, 1000000: 'M' + baseUnit, 1000000000: 'G' + baseUnit}[scale];
  const readable = Number((value / scale).toFixed(3)).toLocaleString('ko-KR') + ' ' + label;
  return compact ? readable : exact + (scale > 1 ? ' · ' + readable : '');
}

export function renderTrialSettings(target, job) {
  if (!target) return;
  const hdtn = job?.hdtnConfig;
  const dtn = job?.dtnConfig;
  const signature = JSON.stringify([job?.testId, job?.senderMode, job?.receiverMode, hdtn, dtn]);
  if (target.dataset.signature === signature) return;
  target.dataset.signature = signature;
  const open = target.querySelector('details')?.open || false;
  target.replaceChildren();
  const heading = document.createElement('strong');
  heading.textContent = '이 시험의 어댑터 요청 설정';
  heading.title = '시험에 기록된 요청값입니다. 어댑터 실제 적용값은 미확인입니다.';
  target.append(heading);
  if (!job || (!hdtn && !dtn)) {
    const empty = document.createElement('span');
    empty.textContent = !job ? '시험 선택 대기' : '기록된 설정 없음';
    target.append(empty);
    return;
  }
  const chips = document.createElement('div'); chips.className = 'trial-config-chips';
  if (dtn) {
    const chip = document.createElement('span');
    chip.textContent = 'DTN 힙 ' + valueText('sdrHeapSizeBytes', dtn.sdrHeapSizeBytes, true);
    chip.title = 'DTN SDR 힙: ' + valueText('sdrHeapSizeBytes', dtn.sdrHeapSizeBytes);
    chips.append(chip);
  }
  if (hdtn) {
    for (const key of ['maxNumberOfBundlesInPipeline', 'maxSumOfBundleBytesInPipeline', 'tcpclMaxSegmentSizeBytes']) {
      const chip = document.createElement('span');
      chip.textContent = 'HDTN ' + fields[key][0] + ' ' + valueText(key, hdtn[key], true);
      chip.title = valueText(key, hdtn[key]); chips.append(chip);
    }
  }
  const detail = document.createElement('details'); detail.open = open;
  const summary = document.createElement('summary'); summary.textContent = '전체 설정';
  const list = document.createElement('dl'); list.className = 'trial-config-values';
  if (dtn) {
    for (const [key, [label]] of Object.entries(dtnFields)) {
      const item = document.createElement('div');
      const dt = document.createElement('dt'); dt.textContent = '[DTN] ' + label; dt.title = key;
      const dd = document.createElement('dd'); dd.textContent = valueText(key, dtn[key]);
      item.append(dt, dd); list.append(item);
    }
  }
  if (hdtn) {
    for (const [key, [label]] of Object.entries(fields)) {
      const item = document.createElement('div');
      const dt = document.createElement('dt'); dt.textContent = '[HDTN] ' + label; dt.title = key;
      const dd = document.createElement('dd'); dd.textContent = valueText(key, hdtn[key]);
      item.append(dt, dd); list.append(item);
    }
  }
  const note = document.createElement('small'); note.textContent = '요청값 · 실제 적용 여부 미확인';
  detail.append(summary, list, note); target.append(chips, detail);
}

export function initPresetControls({read, apply, isLocked}) {
  const $ = id => document.getElementById(id);
  const select = $('preset-select'), dialog = $('preset-dialog'), name = $('preset-name');
  let rows = [], loaded = null, pending = false, operation = '', base = null, opener = null;
  const message = text => { $('preset-status').textContent = text; };
  const chosen = () => rows.find(row => row.id === select.value);
  const describe = row => row.name + ' · ' + types[row.settings.testType] + (row.settings.pvtConstellation && row.settings.pvtConstellation !== 'GPS' ? ' (' + row.settings.pvtConstellation + ')' : '') + ' · ' + row.settings.senderMode + '→' + row.settings.receiverMode;
  const api = (path = '', method = 'GET', body) => requestJson('/dtn/presets' + path, {
    method, cache: 'no-store', headers: {'Content-Type': 'application/json'},
    ...(body ? {body: JSON.stringify(body)} : {})
  }, {allowEmpty: true});
  function update() {
    const locked = pending || isLocked();
    select.disabled = pending;
    $('preset-load').disabled = locked || !chosen();
    $('preset-save').disabled = locked || rows.length >= 5;
    $('preset-update').disabled = locked || !loaded || loaded.id !== select.value;
    $('preset-manage').disabled = locked || !chosen();
    $('preset-confirm').disabled = locked;
    $('preset-delete').disabled = locked;
    $('preset-cancel').disabled = pending;
    $('preset-save').title = rows.length >= 5 ? '최대 5개입니다. 기존 항목을 수정하거나 삭제하세요.' : '현재 시험 조건 저장';
    $('preset-count').textContent = rows.length + ' / 5';
  }
  async function refresh() {
    if (pending) return;
    pending = true; update();
    try {
      const id = select.value; rows = await api();
      select.replaceChildren(new Option('프리셋 선택', ''), ...rows.map(row => new Option(describe(row), row.id)));
      if (rows.some(row => row.id === id)) select.value = id;
      message('');
    } catch (error) { message(error.message); }
    finally { pending = false; update(); }
  }
  function close() { dialog.close(); opener?.focus(); }
  function show(mode) {
    if (pending || isLocked()) return;
    operation = mode; base = mode === 'update' ? loaded : chosen();
    if (mode !== 'new' && !base) return;
    opener = document.activeElement;
    name.value = mode === 'new' ? '' : base.name;
    $('preset-dialog-title').textContent = mode === 'new' ? '설정 저장하기' : mode === 'update' ? '변경 저장' : '프리셋 관리';
    $('preset-delete').hidden = mode !== 'manage';
    $('preset-dialog-error').textContent = '';
    $('preset-dialog-note').textContent = mode === 'update' ? '기존 프리셋을 현재 설정으로 덮어씁니다.' : '';
    dialog.showModal(); name.focus();
  }
  async function save(remove = false) {
    if (pending || isLocked() || (!remove && !name.reportValidity())) return;
    let settings;
    try { settings = operation === 'manage' ? base.settings : read(); }
    catch (error) { $('preset-dialog-error').textContent = error.message; return; }
    if (remove && !window.confirm('“' + base.name + '” 프리셋을 삭제할까요?')) return;
    pending = true; update();
    try {
      const result = remove
        ? await api('/' + base.id + '?version=' + base.version, 'DELETE')
        : await api(operation === 'new' ? '' : '/' + base.id, operation === 'new' ? 'POST' : 'PUT', {
            name: name.value.trim(), version: operation === 'new' ? null : base.version, settings
          });
      if (remove) { if (loaded?.id === base.id) loaded = null; }
      else if (operation !== 'manage' || loaded?.id === base.id) loaded = result;
      close(); pending = false; await refresh();
      if (!remove) select.value = result.id;
      message(remove ? '삭제했습니다.' : '저장했습니다.');
    } catch (error) { $('preset-dialog-error').textContent = error.message; }
    finally { pending = false; update(); }
  }
  select.onchange = update;
  $('preset-refresh').onclick = refresh;
  $('preset-load').onclick = () => {
    if (pending || isLocked() || !chosen()) return;
    try { const row = chosen(); apply(row.settings); loaded = row; message('불러왔습니다.'); update(); }
    catch (error) { message(error.message); }
  };
  $('preset-save').onclick = () => show('new');
  $('preset-update').onclick = () => show('update');
  $('preset-manage').onclick = () => show('manage');
  $('preset-cancel').onclick = close;
  $('preset-form').onsubmit = event => { event.preventDefault(); void save(); };
  $('preset-delete').onclick = () => void save(true);
  dialog.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
  return {refresh, update};
}

// 확인창을 열 때 서버가 확정한 ID만 전송한다. 그 뒤 대기 상태가 된 시험은 포함하지 않는다.
export function initWaitingCancellation({isLocked = () => false, refresh, log}) {
  const button = document.getElementById('dtn-cancel-waiting');
  let working = false;
  const update = () => {
    button.disabled = working || isLocked();
    button.setAttribute('aria-busy', String(working));
  };
  button.onclick = async () => {
    if (working || isLocked()) return;
    working = true;
    update();
    try {
      const summary = await requestJson('/dtn/tests/waiting-summary', {cache: 'no-store'});
      if (!summary.count) {
        log('종료할 수신 대기 시험이 없습니다.');
        return;
      }
      if (!Array.isArray(summary.testIds) || summary.testIds.length !== summary.count) {
        throw new Error('종료 대상 목록을 확인할 수 없습니다. 다시 시도하세요.');
      }
      if (!confirm('수신 대기 ' + summary.count + '건을 모두 종료할까요?\n'
          + '전송·계산 중인 시험은 제외하며 기록은 보존합니다.\n'
          + '이미 전달된 번들은 회수되지 않으며, 이후 도착한 데이터는 종료 시험의 수신 기록으로 남습니다.')) return;
      const result = await requestJson('/dtn/tests/cancel-waiting', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({asOf: summary.asOf, testIds: summary.testIds})
      });
      log('수신 대기 전체 종료 요청 · 즉시 종료 ' + result.cancelled + '건 · 상태 변경으로 제외 ' + result.skipped + '건'
        + (result.pending ? ' · 상대 대기 상태 확인·종료 전달 중 ' + result.pending + '건' : ''), 'INFO');
      await refresh();
    } catch (error) {
      log('수신 대기 전체 종료 실패 · ' + error.message, 'ERROR');
    } finally {
      working = false;
      update();
    }
  };
  return {update};
}
