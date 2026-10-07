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

const hdtnStandardFields = {
  maxNumberOfBundlesInPipeline: ['최대 동시 번들 수', '개'],
  maxSumOfBundleBytesInPipeline: ['최대 동시 번들 용량', 'Bytes'],
  maxBundleSizeBytes: ['최대 번들 크기', 'Bytes'],
  tcpclMaxSegmentSizeBytes: ['TCPCL 세그먼트 크기', 'Bytes'],
  neighborDepletedStorageDelaySeconds: ['저장 공간 부족 시 대기', '초'],
  enforceBundlePriority: ['번들 우선순위 준수', ''],
  storageDeletionPolicy: ['스토리지 삭제 정책', '']
};

const hdtnAdvancedFields = {
  totalStorageCapacityBytes: ['전체 저장 용량', 'Bytes'],
  acsSendPeriodMilliseconds: ['ACS 전송 주기', 'ms']
};

const fields = {
  ...hdtnStandardFields,
  ...hdtnAdvancedFields
};

const dtnFields = {
  sdrHeapSizeBytes: ['SDR 힙 크기', 'Bytes'],
  sdrWorkingMemorySizeBytes: ['SDR 작업 메모리', 'Bytes'],
  sdrTransientMode: ['SDR 임시(Transient) 모드', ''],
  maxBundleSizeBytes: ['최대 번들 크기', 'Bytes'],
  contactRateBytesPerSec: ['접촉 전송 속도', 'Bytes/s'],
  maxProductionRateBytesPerSec: ['최대 생성 속도', 'Bytes/s'],
  maxConsumptionRateBytesPerSec: ['최대 소비 속도', 'Bytes/s'],
  tcpclMaxSegmentSizeBytes: ['TCPCL 세그먼트 크기', 'Bytes'],
  routingMode: ['라우팅 모드', '']
};

const policies = {
  never: '자동 삭제 안 함 (never)',
  on_forward: '전달 완료 시 삭제 (on_forward)',
  on_delivery: '인도 완료 시 삭제 (on_delivery)',
  DELETE_AFTER_FORWARDING: '어댑터 기본 (DELETE_AFTER_FORWARDING)',
  on_expiration: '수명 만료 시 삭제',
  on_storage_full: '저장소 부족 시 삭제'
};

function formatParam(key, value) {
  if (value == null) return { primary: '기록 없음', secondary: '' };
  if (key === 'enforceBundlePriority') {
    return { primary: value ? '사용' : '사용 안 함 (속도 최적화)', secondary: '' };
  }
  if (key === 'sdrTransientMode') {
    return { primary: value ? '순수 RAM (고속)' : '디스크 기반 (권장)', secondary: '' };
  }
  if (key === 'storageDeletionPolicy') {
    return { primary: policies[value] || String(value), secondary: '' };
  }
  if (key === 'routingMode') {
    return { primary: String(value), secondary: '' };
  }
  const def = fields[key] || dtnFields[key] || ['', ''];
  const unit = def[1];
  const num = Number(value);
  if (isNaN(num)) return { primary: String(value), secondary: '' };

  if (unit === 'Bytes' || unit === 'Bytes/s') {
    const isRate = unit === 'Bytes/s';
    const baseUnit = isRate ? 'B/s' : 'Bytes';
    const scale = num >= 1e9 ? 1e9 : num >= 1e6 ? 1e6 : num >= 1e3 ? 1e3 : 1;
    const label = { 1: baseUnit, 1000: 'KB' + (isRate ? '/s' : ''), 1000000: 'MB' + (isRate ? '/s' : ''), 1000000000: 'GB' + (isRate ? '/s' : '') }[scale];
    const readable = scale > 1 ? Number((num / scale).toFixed(3)).toLocaleString('ko-KR') + ' ' + label : '';
    const exact = num.toLocaleString('ko-KR') + ' ' + (isRate ? 'Bytes/s' : 'Bytes');
    return {
      primary: readable || exact,
      secondary: scale > 1 ? exact : ''
    };
  }
  return {
    primary: num.toLocaleString('ko-KR') + (unit ? ' ' + unit : ''),
    secondary: ''
  };
}

function getRoles(job) {
  const sMode = job?.senderMode || '';
  const rMode = job?.receiverMode || '';
  let dtnRole = 'DTN';
  let hdtnRole = 'HDTN';
  let dtnBadge = 'badge-both';
  let hdtnBadge = 'badge-both';

  if (sMode === 'DTN' && rMode === 'HDTN') {
    dtnRole = '송신 DTN';
    hdtnRole = '수신 HDTN';
    dtnBadge = 'badge-sender';
    hdtnBadge = 'badge-receiver';
  } else if (sMode === 'HDTN' && rMode === 'DTN') {
    hdtnRole = '송신 HDTN';
    dtnRole = '수신 DTN';
    hdtnBadge = 'badge-sender';
    dtnBadge = 'badge-receiver';
  } else if (sMode === 'DTN' && rMode === 'DTN') {
    dtnRole = '송·수신 DTN';
    dtnBadge = 'badge-both';
  } else if (sMode === 'HDTN' && rMode === 'HDTN') {
    hdtnRole = '송·수신 HDTN';
    hdtnBadge = 'badge-both';
  }
  return { dtnRole, hdtnRole, dtnBadge, hdtnBadge };
}

function createChip(typeClass, roleText, badgeClass, text) {
  const chip = document.createElement('span');
  chip.className = 'trial-chip ' + typeClass;
  const roleBadge = document.createElement('span');
  roleBadge.className = 'adapter-tab-badge ' + badgeClass;
  roleBadge.textContent = roleText;
  const textSpan = document.createElement('span');
  textSpan.textContent = text;
  chip.append(roleBadge, textSpan);
  return chip;
}

export function renderTrialSettingsInDialog(target, job) {
  if (!target) return;
  const hdtn = job?.hdtnConfig;
  const dtn = job?.dtnConfig;
  target.replaceChildren();

  if (!job || (!hdtn && !dtn)) {
    const empty = document.createElement('div');
    empty.className = 'trial-dialog-empty';
    empty.textContent = !job ? '선택된 시험이 없습니다. 시험 기록을 먼저 선택하세요.' : '이 시험에는 기록된 어댑터 요청 설정이 없습니다.';
    target.append(empty);
    return;
  }

  const { dtnRole, hdtnRole, dtnBadge, hdtnBadge } = getRoles(job);

  const groups = document.createElement('div');
  groups.className = 'trial-groups';

  if (dtn) {
    const dtnGroup = document.createElement('div');
    dtnGroup.className = 'trial-group trial-group-dtn';
    dtnGroup.innerHTML = `
      <div class="trial-group-header">
        <span class="adapter-tab-badge ${dtnBadge}">${dtnRole}</span>
        <strong>DTN (NASA JPL ION) 설정</strong>
        <small>SDR 공유 메모리 · 대역폭 · 라우팅</small>
      </div>
    `;
    const dtnGrid = document.createElement('div');
    dtnGrid.className = 'trial-param-grid';
    for (const [key, [label]] of Object.entries(dtnFields)) {
      const { primary, secondary } = formatParam(key, dtn[key]);
      const card = document.createElement('div');
      card.className = 'trial-param-card';
      card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
      const labelSpan = document.createElement('span');
      labelSpan.className = 'trial-param-label';
      labelSpan.textContent = label;
      const valStrong = document.createElement('strong');
      valStrong.className = 'trial-param-val';
      valStrong.textContent = primary;
      card.append(labelSpan, valStrong);
      if (secondary) {
        const subSmall = document.createElement('small');
        subSmall.className = 'trial-param-sub';
        subSmall.textContent = secondary;
        card.append(subSmall);
      }
      dtnGrid.append(card);
    }
    dtnGroup.append(dtnGrid);
    groups.append(dtnGroup);
  }

  if (hdtn) {
    const hdtnGroup = document.createElement('div');
    hdtnGroup.className = 'trial-group trial-group-hdtn';
    hdtnGroup.innerHTML = `
      <div class="trial-group-header">
        <span class="adapter-tab-badge ${hdtnBadge}">${hdtnRole}</span>
        <strong>HDTN (NASA High-Speed DTN) 설정</strong>
        <small>파이프라인 · 큐 · 세그먼트 MTU</small>
      </div>
    `;
    const hdtnGrid = document.createElement('div');
    hdtnGrid.className = 'trial-param-grid';
    for (const [key, [label]] of Object.entries(hdtnStandardFields)) {
      const { primary, secondary } = formatParam(key, hdtn[key]);
      const card = document.createElement('div');
      card.className = 'trial-param-card';
      card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
      const labelSpan = document.createElement('span');
      labelSpan.className = 'trial-param-label';
      labelSpan.textContent = label;
      const valStrong = document.createElement('strong');
      valStrong.className = 'trial-param-val';
      valStrong.textContent = primary;
      card.append(labelSpan, valStrong);
      if (secondary) {
        const subSmall = document.createElement('small');
        subSmall.className = 'trial-param-sub';
        subSmall.textContent = secondary;
        card.append(subSmall);
      }
      hdtnGrid.append(card);
    }
    hdtnGroup.append(hdtnGrid);

    const hasAdvanced = Object.keys(hdtnAdvancedFields).some(k => hdtn[k] != null);
    if (hasAdvanced) {
      const advDetails = document.createElement('details');
      advDetails.className = 'trial-sub-details';
      advDetails.open = true;
      advDetails.innerHTML = '<summary>HDTN 고급 설정 (저장 용량 · ACS)</summary>';
      const advGrid = document.createElement('div');
      advGrid.className = 'trial-param-grid';
      for (const [key, [label]] of Object.entries(hdtnAdvancedFields)) {
        const { primary, secondary } = formatParam(key, hdtn[key]);
        const card = document.createElement('div');
        card.className = 'trial-param-card';
        card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
        const labelSpan = document.createElement('span');
        labelSpan.className = 'trial-param-label';
        labelSpan.textContent = label;
        const valStrong = document.createElement('strong');
        valStrong.className = 'trial-param-val';
        valStrong.textContent = primary;
        card.append(labelSpan, valStrong);
        if (secondary) {
          const subSmall = document.createElement('small');
          subSmall.className = 'trial-param-sub';
          subSmall.textContent = secondary;
          card.append(subSmall);
        }
        advGrid.append(card);
      }
      advDetails.append(advGrid);
      hdtnGroup.append(advDetails);
    }

    groups.append(hdtnGroup);
  }

  const note = document.createElement('small');
  note.className = 'trial-config-note';
  note.textContent = '시험 시작 시 어댑터에 요청된 파라미터 값입니다.';

  target.append(groups, note);
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

  const headerRow = document.createElement('div');
  headerRow.className = 'trial-config-header';
  const heading = document.createElement('strong');
  heading.textContent = '이 시험의 어댑터 요청 설정';
  heading.title = '시험 시작 시 어댑터에 전달된 요청 파라미터입니다.';
  headerRow.append(heading);

  if (!job || (!hdtn && !dtn)) {
    const empty = document.createElement('span');
    empty.className = 'trial-config-empty';
    empty.textContent = !job ? '시험 선택 대기' : '기록된 설정 없음';
    headerRow.append(empty);
    target.append(headerRow);
    return;
  }

  const { dtnRole, hdtnRole, dtnBadge, hdtnBadge } = getRoles(job);
  const chips = document.createElement('div');
  chips.className = 'trial-config-chips';

  if (dtn) {
    if (dtn.sdrHeapSizeBytes != null) {
      chips.append(createChip('chip-dtn', dtnRole, dtnBadge, 'SDR 힙 ' + formatParam('sdrHeapSizeBytes', dtn.sdrHeapSizeBytes).primary));
    }
    if (dtn.maxBundleSizeBytes != null) {
      chips.append(createChip('chip-dtn', dtnRole, dtnBadge, '최대 번들 ' + formatParam('maxBundleSizeBytes', dtn.maxBundleSizeBytes).primary));
    }
    if (dtn.contactRateBytesPerSec != null) {
      chips.append(createChip('chip-dtn', dtnRole, dtnBadge, '접촉 속도 ' + formatParam('contactRateBytesPerSec', dtn.contactRateBytesPerSec).primary));
    }
  }

  if (hdtn) {
    if (hdtn.maxNumberOfBundlesInPipeline != null) {
      chips.append(createChip('chip-hdtn', hdtnRole, hdtnBadge, '동시 ' + formatParam('maxNumberOfBundlesInPipeline', hdtn.maxNumberOfBundlesInPipeline).primary));
    }
    if (hdtn.maxSumOfBundleBytesInPipeline != null) {
      chips.append(createChip('chip-hdtn', hdtnRole, hdtnBadge, '용량 ' + formatParam('maxSumOfBundleBytesInPipeline', hdtn.maxSumOfBundleBytesInPipeline).primary));
    }
    if (hdtn.maxBundleSizeBytes != null) {
      chips.append(createChip('chip-hdtn', hdtnRole, hdtnBadge, '번들 ' + formatParam('maxBundleSizeBytes', hdtn.maxBundleSizeBytes).primary));
    }
    if (hdtn.tcpclMaxSegmentSizeBytes != null) {
      chips.append(createChip('chip-hdtn', hdtnRole, hdtnBadge, 'TCPCL ' + formatParam('tcpclMaxSegmentSizeBytes', hdtn.tcpclMaxSegmentSizeBytes).primary));
    }
  }

  headerRow.append(chips);

  const detail = document.createElement('details');
  detail.className = 'trial-config-details';
  detail.open = open;

  const summary = document.createElement('summary');
  summary.className = 'trial-config-summary';
  summary.innerHTML = '<span>전체 어댑터 설정 상세</span><small>클릭하여 펼치기/접기</small>';

  const groups = document.createElement('div');
  groups.className = 'trial-groups';

  if (dtn) {
    const dtnGroup = document.createElement('div');
    dtnGroup.className = 'trial-group trial-group-dtn';
    dtnGroup.innerHTML = `
      <div class="trial-group-header">
        <span class="adapter-tab-badge ${dtnBadge}">${dtnRole}</span>
        <strong>DTN (NASA JPL ION) 설정</strong>
        <small>SDR 공유 메모리 · 대역폭 · 라우팅</small>
      </div>
    `;
    const dtnGrid = document.createElement('div');
    dtnGrid.className = 'trial-param-grid';
    for (const [key, [label]] of Object.entries(dtnFields)) {
      const { primary, secondary } = formatParam(key, dtn[key]);
      const card = document.createElement('div');
      card.className = 'trial-param-card';
      card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
      const labelSpan = document.createElement('span');
      labelSpan.className = 'trial-param-label';
      labelSpan.textContent = label;
      const valStrong = document.createElement('strong');
      valStrong.className = 'trial-param-val';
      valStrong.textContent = primary;
      card.append(labelSpan, valStrong);
      if (secondary) {
        const subSmall = document.createElement('small');
        subSmall.className = 'trial-param-sub';
        subSmall.textContent = secondary;
        card.append(subSmall);
      }
      dtnGrid.append(card);
    }
    dtnGroup.append(dtnGrid);
    groups.append(dtnGroup);
  }

  if (hdtn) {
    const hdtnGroup = document.createElement('div');
    hdtnGroup.className = 'trial-group trial-group-hdtn';
    hdtnGroup.innerHTML = `
      <div class="trial-group-header">
        <span class="adapter-tab-badge ${hdtnBadge}">${hdtnRole}</span>
        <strong>HDTN (NASA High-Speed DTN) 설정</strong>
        <small>파이프라인 · 큐 · 세그먼트 MTU</small>
      </div>
    `;
    const hdtnGrid = document.createElement('div');
    hdtnGrid.className = 'trial-param-grid';
    for (const [key, [label]] of Object.entries(hdtnStandardFields)) {
      const { primary, secondary } = formatParam(key, hdtn[key]);
      const card = document.createElement('div');
      card.className = 'trial-param-card';
      card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
      const labelSpan = document.createElement('span');
      labelSpan.className = 'trial-param-label';
      labelSpan.textContent = label;
      const valStrong = document.createElement('strong');
      valStrong.className = 'trial-param-val';
      valStrong.textContent = primary;
      card.append(labelSpan, valStrong);
      if (secondary) {
        const subSmall = document.createElement('small');
        subSmall.className = 'trial-param-sub';
        subSmall.textContent = secondary;
        card.append(subSmall);
      }
      hdtnGrid.append(card);
    }
    hdtnGroup.append(hdtnGrid);

    const hasAdvanced = Object.keys(hdtnAdvancedFields).some(k => hdtn[k] != null);
    if (hasAdvanced) {
      const advDetails = document.createElement('details');
      advDetails.className = 'trial-sub-details';
      advDetails.innerHTML = '<summary>HDTN 고급 설정 (저장 용량 · ACS)</summary>';
      const advGrid = document.createElement('div');
      advGrid.className = 'trial-param-grid';
      for (const [key, [label]] of Object.entries(hdtnAdvancedFields)) {
        const { primary, secondary } = formatParam(key, hdtn[key]);
        const card = document.createElement('div');
        card.className = 'trial-param-card';
        card.title = key + ': ' + primary + (secondary ? ' (' + secondary + ')' : '');
        const labelSpan = document.createElement('span');
        labelSpan.className = 'trial-param-label';
        labelSpan.textContent = label;
        const valStrong = document.createElement('strong');
        valStrong.className = 'trial-param-val';
        valStrong.textContent = primary;
        card.append(labelSpan, valStrong);
        if (secondary) {
          const subSmall = document.createElement('small');
          subSmall.className = 'trial-param-sub';
          subSmall.textContent = secondary;
          card.append(subSmall);
        }
        advGrid.append(card);
      }
      advDetails.append(advGrid);
      hdtnGroup.append(advDetails);
    }

    groups.append(hdtnGroup);
  }

  const note = document.createElement('small');
  note.className = 'trial-config-note';
  note.textContent = '시험 시작 시 어댑터에 요청된 파라미터 값입니다.';

  detail.append(summary, groups, note);
  target.append(headerRow, detail);
}

export function initTrialSettingsDialog(getJob) {
  const btn = document.getElementById('dtn-trial-config-btn');
  const dialog = document.getElementById('trial-settings-dialog');
  const body = document.getElementById('trial-settings-dialog-body');
  const closeBtn = document.getElementById('trial-settings-dialog-close');

  const updateDialog = (job) => {
    if (dialog && dialog.open && body) {
      renderTrialSettingsInDialog(body, job);
    }
  };

  if (btn && dialog && body) {
    btn.onclick = () => {
      const job = typeof getJob === 'function' ? getJob() : null;
      renderTrialSettingsInDialog(body, job);
      dialog.showModal();
    };
  }

  if (closeBtn && dialog) {
    closeBtn.onclick = () => dialog.close();
  }

  if (dialog) {
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) dialog.close();
    });
  }

  return { updateDialog };
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
