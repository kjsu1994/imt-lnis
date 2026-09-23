import {requestJson} from '../common/http.js?v=20260915-structure';

const types = {GNSS_RAW: 'RAW', AFS_METADATA: 'AFS', IQ_SAMPLE: 'I/Q'};
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
const policies = {DELETE_AFTER_FORWARDING: '어댑터 기본', on_expiration: '수명 만료 시', on_storage_full: '저장소 부족 시', never: '자동 삭제 안 함'};
function valueText(key, value, compact = false) {
  if (value == null) return '기록 없음';
  if (key === 'enforceBundlePriority') return value ? '사용' : '사용 안 함';
  if (key === 'storageDeletionPolicy') return policies[value] || String(value);
  const unit = fields[key][1];
  const exact = Number(value).toLocaleString('ko-KR') + ' ' + unit;
  if (unit !== 'Bytes') return exact;
  const scale = value >= 1e9 ? 1e9 : value >= 1e6 ? 1e6 : value >= 1e3 ? 1e3 : 1;
  const label = {1: 'Bytes', 1000: 'KB', 1000000: 'MB', 1000000000: 'GB'}[scale];
  const readable = Number((value / scale).toFixed(3)).toLocaleString('ko-KR') + ' ' + label;
  return compact ? readable : exact + (scale > 1 ? ' · ' + readable : '');
}

export function renderTrialSettings(target, job) {
  if (!target) return;
  const config = job?.hdtnConfig;
  const signature = JSON.stringify([job?.testId, job?.senderMode, job?.receiverMode, config]);
  if (target.dataset.signature === signature) return;
  target.dataset.signature = signature;
  const open = target.querySelector('details')?.open || false;
  target.replaceChildren();
  const heading = document.createElement('strong');
  heading.textContent = '이 시험의 어댑터 요청 설정';
  heading.title = '시험에 기록된 요청값입니다. 어댑터 실제 적용값은 미확인입니다.';
  target.append(heading);
  if (!job || !config || (job.senderMode === 'DTN' && job.receiverMode === 'DTN')) {
    const empty = document.createElement('span');
    empty.textContent = !job ? '시험 선택 대기' : job.senderMode === 'DTN' && job.receiverMode === 'DTN' ? 'HDTN 설정 미전달' : '기록된 설정 없음';
    target.append(empty);
    return;
  }
  const chips = document.createElement('div'); chips.className = 'trial-config-chips';
  for (const key of ['maxNumberOfBundlesInPipeline', 'maxSumOfBundleBytesInPipeline', 'tcpclMaxSegmentSizeBytes']) {
    const chip = document.createElement('span');
    chip.textContent = fields[key][0] + ' ' + valueText(key, config[key], true);
    chip.title = valueText(key, config[key]); chips.append(chip);
  }
  const detail = document.createElement('details'); detail.open = open;
  const summary = document.createElement('summary'); summary.textContent = '전체 설정';
  const list = document.createElement('dl'); list.className = 'trial-config-values';
  for (const [key, [label]] of Object.entries(fields)) {
    const item = document.createElement('div');
    const dt = document.createElement('dt'); dt.textContent = label; dt.title = key;
    const dd = document.createElement('dd'); dd.textContent = valueText(key, config[key]);
    item.append(dt, dd); list.append(item);
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
  const describe = row => row.name + ' · ' + types[row.settings.testType] + ' · ' + row.settings.senderMode + '→' + row.settings.receiverMode;
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
