import { requestJson } from '../common/http.js?v=20261103-management-reset';

const $ = (id) => document.getElementById(id);
const api = '/data-management';
const names = {
  DTN: 'DTN 시험',
  AFS: 'AFS 시험',
  RECEIPT: '수신 원문',
  INPUT: '입력 자료',
  IQ: 'I/Q 파일'
};
const labels = {
  COMPLETED: '완료',
  FAILED: '실패',
  CANCELLED: '중지',
  INCONCLUSIVE: '판정 불가',
  ACCEPTED: '정상 접수',
  REJECTED: '검증 거절',
  RECEIVED: '검증 전',
  COMPLETE: '준비 완료',
  INCOMPLETE: '수집·업로드 중',
  READY: '준비 완료',
  GENERATING: '생성 중',
  PREPARING: '시험 준비',
  CREATED: '시험 생성',
  TRANSMITTING: '전송 중',
  EVALUATING: '검증 중',
  WAITING_DTN: '외부 수신 대기',
  WAITING_RECEIVER: '수신 처리 대기',
  CALCULATING: '계산 중'
};
let page = 0,
  current = [],
  selected = new Set(),
  busy = false,
  confirmAction = null,
  savedPolicies = null,
  allSelected = false,
  totalRows = 0,
  resetPreview = null,
  resetJob = null,
  resetRetry = false,
  resetPoll = null;
const node = (tag, text) => {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  return e;
};
const when = (value) => (value ? new Date(value).toLocaleString('ko-KR') : '—');
const size = (value) => {
  if (value == null) return '확인 불가';
  if (value < 1000) return value + ' B';
  for (const [scale, unit] of [[1e12,'TB'], [1e9,'GB'], [1e6,'MB'], [1e3,'KB']])
    if (value >= scale) return (value / scale).toFixed(scale >= 1e9 ? 2 : 1) + ' ' + unit;
};
const code = (key) => key.kind + ':' + key.id;
function request(path, body) {
  return requestJson(
    api + path,
    {
      cache: 'no-store',
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    },
    { allowEmpty: true }
  );
}
function message(text, error = false) {
  $('message').textContent = text;
  $('message').className = error ? 'error' : '';
}
async function run(work) {
  if (busy) return;
  busy = true;
  try {
    await work();
  } catch (e) {
    message(e.message, true);
  } finally {
    busy = false;
  }
}
async function summary() {
  const data = await request('/summary');
  $('node').textContent =
    (data.role === 'receiver' ? '수신 PC' : data.role === 'sender' ? '송신 PC' : '현재 서버') +
    ' · 이 PC의 데이터만 관리합니다';
  $('back').href = '/lnis/dtntest/' + (data.role === 'receiver' ? 'receiver' : 'sender');
  $('brand-home').href = $('back').href;
  $('reset-home').href = $('back').href;
  $('summary').replaceChildren();
  for (const [label, value, detail] of [
    ['시험 기록', data.counts.DTN + '건', '결과·송수신 원문은 DB에 저장'],
    ['수신 원문', data.counts.RECEIPT + '건', size(data.receiptBytes) + ' · 본문 용량'],
    ['입력 파일', size(data.inputBytes), (data.counts.INPUT ?? 0) + '개'],
    ['I/Q 파일', size(data.iqBytes), (data.counts.IQ ?? 0) + '개 · 공유 자료 포함'],
    ['DB 파일', size(data.databaseBytes), '삭제 후 크기가 바로 줄어들지 않을 수 있음'],
    ['저장소 여유', size(data.freeBytes), 'DB·입력 파일 저장소']
  ]) {
    const box = node('div');
    box.className = 'metric';
    box.append(node('span', label), node('strong', value), node('small', detail));
    $('summary').append(box);
  }
  return data;
}
function states() {
  const kind = $('kind').value;
  const list =
    kind === 'RECEIPT'
      ? ['ACCEPTED', 'REJECTED', 'RECEIVED']
      : kind === 'INPUT'
        ? ['COMPLETE', 'INCOMPLETE']
        : kind === 'IQ'
          ? ['READY', 'FAILED', 'GENERATING']
          : [
              'COMPLETED',
              'FAILED',
              'CANCELLED',
              'INCONCLUSIVE',
              'PREPARING',
              'WAITING_DTN',
              'WAITING_RECEIVER',
              'CALCULATING'
            ];
  $('state').replaceChildren(
    new Option('전체', ''),
    ...list.map((s) => new Option(labels[s] || s, s))
  );
}
function selectionQuery() {
  const query = {kind: $('kind').value, search: $('search').value, state: $('state').value};
  if ($('from').value) query.from = new Date($('from').value + 'T00:00:00').toISOString();
  if ($('to').value) {
    const end = new Date($('to').value + 'T00:00:00');
    end.setDate(end.getDate() + 1);
    query.to = end.toISOString();
  }
  return query;
}
function selectionStatus() {
  $('selection-count').textContent = allSelected
    ? '검색 결과 전체 ' + totalRows + '건 선택'
    : selected.size ? selected.size + '건 선택' : '';
  $('delete-selected').disabled = !allSelected && !selected.size;
  $('clear-selection').disabled = !allSelected && !selected.size;
  $('select-results').disabled = !totalRows || allSelected;
  $('select-page').disabled = !totalRows || allSelected;
}
async function load(preserveSelection = false) {
  if (!preserveSelection) {
    selected.clear();
    allSelected = false;
  }
  $('detail').hidden = true;
  const params = new URLSearchParams({...selectionQuery(), page});
  const data = await request('/items?' + params);
  current = data.items;
  $('rows').replaceChildren();
  for (const row of current) {
    const tr = node('tr'),
      cell = node('td'),
      check = node('input');
    check.type = 'checkbox';
    check.disabled = !!row.blocked || allSelected;
    check.checked = !row.blocked && (allSelected || selected.has(code(row.key)));
    check.setAttribute('aria-label', row.key.id + ' 선택');
    check.onchange = () => {
      if (check.checked) selected.add(code(row.key));
      else selected.delete(code(row.key));
      selectionStatus();
    };
    cell.append(check);
    tr.append(cell);
    const title = node('td', row.name);
    const id = node('small', row.key.id);
    id.className = 'id';
    title.append(id);
    if (row.blocked) title.append(node('small', row.blocked));
    tr.append(
      title,
      node('td', labels[row.state] || row.state),
      node('td', when(row.createdAt)),
      node('td', row.bytes ? size(row.bytes) : '—')
    );
    const actions = node('td');
    const detail = node('button', '상세');
    detail.onclick = () => run(() => showDetail(row));
    const pin = node('button', row.pinned ? '보관 고정 해제' : '보관 고정');
    pin.onclick = () =>
      run(async () => {
        await request('/pin', { item: row.key, pinned: !row.pinned });
        await load();
        message('보관 고정 설정을 저장했습니다.');
      });
    actions.append(detail, pin);
    tr.append(actions);
    $('rows').append(tr);
  }
  totalRows = data.total;
  selectionStatus();
  $('count').textContent = '총 ' + data.total + '건';
  $('page').textContent = page + 1 + ' / ' + Math.max(1, Math.ceil(data.total / 50));
  $('previous').disabled = page === 0;
  $('next').disabled = (page + 1) * 50 >= data.total;
}
function link(label, url) {
  const a = node('a', label);
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener';
  return a;
}
async function showDetail(row) {
  const data = await request('/items/' + row.key.kind + '/' + row.key.id);
  $('detail').hidden = false;
  $('detail-content').textContent = [
    names[row.key.kind] + ' · ' + row.key.id,
    '이름: ' + row.name,
    '상태: ' + (labels[row.state] || row.state),
    '생성: ' + when(row.createdAt),
    '최근 변경: ' + when(row.updatedAt),
    '용량: ' + size(row.bytes),
    '보관 고정: ' + (row.pinned ? '사용' : '사용 안 함'),
    row.blocked ? '삭제 제한: ' + row.blocked : '',
    data.message || ''
  ]
    .filter(Boolean)
    .join('\n');
  const links = $('detail-links');
  links.replaceChildren(
    link(
      row.key.kind === 'INPUT' || row.key.kind === 'IQ' ? '파일 다운로드' : '상세 원문 열기',
      data.detailUrl
    )
  );
  if (row.key.kind === 'RECEIPT')
    links.append(link('원문 다운로드', data.detailUrl + '?download=true'));
  if (row.key.kind === 'DTN') {
    const base = '/lnis/api/v1/dtn/tests/' + row.key.id;
    for (const [available, direction, label] of [
      [data.sentAvailable, 'sent', '송신 원문'],
      [data.receivedAvailable, 'received', '수신 원문']
    ])
      if (available)
        links.append(link(label + ' 다운로드', base + '/payload/' + direction + '?download=true'));
    links.append(
      link('시험 로그 다운로드', '/lnis/api/v1/dtn/logs?scopeId=' + row.key.id + '&download=true')
    );
  }
  $('detail').scrollIntoView({ behavior: 'smooth', block: 'start' });
}
function confirm(preview, title, action, apply = '삭제 실행') {
  $('confirm-title').textContent = title;
  $('confirm-note').textContent =
    (preview.role === 'receiver' ? '수신 PC' : '현재 PC') +
    ' · 대상 ' +
    preview.items.length +
    '건 · 미리보기 유효 10분';
  $('preview-items').replaceChildren();
  const summary = node('div');
  summary.className = 'preview-summary';
  for (const [kind, count] of Object.entries(preview.counts || {}))
    if (count) summary.append(node('span', (names[kind] || kind) + ' ' + count + '건'));
  summary.append(node('span', '삭제 파일 ' + size(preview.fileBytes || 0)));
  $('preview-items').append(summary);
  const excluded = preview.excluded || [];
  if (excluded.length) {
    const details = node('details');
    details.className = 'preview-excluded';
    details.append(node('summary', '삭제 제외 ' + excluded.length + '건 · 사유 확인'));
    for (const row of excluded.slice(0, 50))
      details.append(node('p', row.key.id + ' · ' + row.blocked));
    $('preview-items').append(details);
  }
  for (const item of preview.items.slice(0, 50)) {
    const p = node(
      'p',
      names[item.row.key.kind] +
        ' · ' +
        item.row.key.id +
        ' · ' +
        (item.blocked
          ? '제외: ' + item.blocked
          : '관련 파일 ' +
            item.related.length +
            '건 · 원문 ' +
            item.receipts +
            '건 · 로그 ' +
            item.logs +
            '건 · 프레임 증거 ' +
            item.evidence +
            '건')
    );
    $('preview-items').append(p);
  }
  if (preview.items.length > 50) $('preview-items').append(node('p', '외 ' + (preview.items.length - 50) + '건도 포함됩니다.'));
  $('confirm-apply').textContent = apply;
  const saving = apply === '설정 저장';
  $('confirm-apply').className = saving ? 'primary' : 'danger';
  $('confirm-warning').textContent = saving
    ? '저장 후 다음 자동 정리 주기부터 적용됩니다. 이 확인 단계에서는 자료를 삭제하지 않습니다.'
    : '진행 중·보관 고정·공유 자료는 서버에서 다시 확인합니다. 삭제한 자료는 이 화면에서 복원할 수 없습니다.';
  confirmAction = action;
  $('confirm').showModal();
}
async function execute(preview) {
  const result = await request('/delete', { token: preview.token });
  const failed = result.results.filter((r) => r.status === 'FAILED');
  message(
    '삭제 ' +
      (result.results.length - failed.length) +
      '건 · 실패 ' +
      failed.length +
      '건' +
      (failed.length ? ' · 보관 설정의 정리 내역에서 재시도할 수 있습니다.' : ''),
    !!failed.length
  );
  await load();
  await summary();
  await history();
}
$('confirm-cancel').onclick = () => {
  $('confirm').close();
  confirmAction = null;
};
$('confirm-apply').onclick = () =>
  run(async () => {
    const action = confirmAction;
    confirmAction = null;
    $('confirm').close();
    if (action) await action();
  });
$('search-form').onsubmit = (e) => {
  e.preventDefault();
  page = 0;
  run(load);
};
$('kind').onchange = () => {
  states();
  page = 0;
  run(load);
};
$('previous').onclick = () => {
  page--;
  run(() => load(true));
};
$('next').onclick = () => {
  page++;
  run(() => load(true));
};
$('close-detail').onclick = () => ($('detail').hidden = true);
$('select-page').onclick = () => {
  const boxes = [...$('rows').querySelectorAll('input:not(:disabled)')];
  const checked = boxes.some((e) => !e.checked);
  for (const e of boxes) {
    e.checked = checked;
    e.onchange();
  }
};
$('delete-selected').onclick = () =>
  run(async () => {
    const items = [...selected].map((value) => {
      const [kind, id] = value.split(':');
      return {kind, id};
    });
    if (!allSelected && !items.length) throw Error('삭제할 자료를 선택하세요.');
    const preview = allSelected
      ? await request('/selection/preview', selectionQuery())
      : await request('/preview', {items});
    if (!preview.items.length) {
      message('삭제 가능한 자료가 없습니다. 진행 중·보관 고정·공유 여부를 확인하세요.');
      return;
    }
    confirm(preview, '선택 자료 삭제', () => execute(preview));
  });
$('select-results').onclick = () => {
  allSelected = true;
  selected.clear();
  run(() => load(true));
};
$('clear-selection').onclick = () => run(() => load());
for (const button of document.querySelectorAll('[data-tab]'))
  button.onclick = () =>
    run(async () => {
      const tab = button.dataset.tab;
      for (const b of document.querySelectorAll('[data-tab]'))
        b.classList.toggle('primary', b === button);
      $('settings').hidden = tab !== 'settings';
      $('reset').hidden = tab !== 'reset';
      $('records').hidden = ['settings', 'reset'].includes(tab);
      $('detail').hidden = true;
      if (tab === 'reset') {
        await loadResetStatus();
        return;
      }
      if (tab === 'settings') {
        await policies();
        await history();
        return;
      }
      $('kind').replaceChildren(
        ...(tab === 'records' ? ['DTN'] : ['RECEIPT', 'INPUT', 'IQ']).map(
          (k) => new Option(names[k], k)
        )
      );
      states();
      page = 0;
      await load();
    });
function readPolicies() {
  const value = {};
  for (const key of ['tests', 'receipts', 'files'])
    value[key] = { enabled: $(key + '-enabled').checked, days: Number($(key + '-days').value) };
  return value;
}
function validRetentionDays(days) {
  return Number.isInteger(days) && days >= 1 && days <= 3650;
}
function updatePolicyState() {
  const value = readPolicies();
  const changed = JSON.stringify(value) !== JSON.stringify(savedPolicies);
  const valid = Object.values(value).every((policy) => validRetentionDays(policy.days));
  $('settings-save').disabled = !changed || !valid;
  $('cleanup-now').disabled = changed || !Object.values(value).some((policy) => policy.enabled);
  $('policy-status').textContent = !valid
    ? '보관 기간은 1~3,650일의 정수로 입력하세요.'
    : changed ? '변경사항 있음 · 저장 후 적용' : '변경사항 없음';
}
async function policies() {
  const settings = await request('/settings');
  savedPolicies = settings;
  $('policies').replaceChildren();
  for (const [key, label, basis] of [
    ['tests', '종료된 DTN 시험', '시험 최종 갱신 기준'],
    ['receipts', '시험에 연결되지 않은 수신 원문', '수신 시점 기준'],
    ['files', '미사용 GNSS 입력·I/Q 파일', '생성 시점 기준']
  ]) {
    const box = node('div');
    box.className = 'policy';
    const target = node('div');
    target.className = 'policy-target';
    target.append(node('strong', label), node('small', basis));
    const labelOn = node('label');
    labelOn.className = 'policy-toggle';
    const enabled = node('input');
    enabled.type = 'checkbox';
    enabled.id = key + '-enabled';
    enabled.checked = settings[key].enabled;
    enabled.setAttribute('role', 'switch');
    enabled.setAttribute('aria-label', label + ' 자동 삭제');
    const state = node('span');
    labelOn.append(enabled, state);
    const period = node('div');
    period.className = 'policy-period';
    const select = node('select');
    select.id = key + '-period';
    select.setAttribute('aria-label', label + ' 보관 기간');
    for (const days of [7, 30, 90]) select.append(new Option(days + '일', days));
    select.append(new Option('직접 입력', 'custom'));
    select.value = [7, 30, 90].includes(settings[key].days) ? String(settings[key].days) : 'custom';
    const custom = node('label');
    custom.className = 'policy-custom';
    const days = node('input');
    days.id = key + '-days';
    days.type = 'number';
    days.min = 1;
    days.max = 3650;
    days.step = 1;
    days.required = true;
    days.value = settings[key].days;
    days.setAttribute('aria-label', label + ' 보관 일수');
    custom.append(days, node('span', '일'));
    function updateControls() {
      state.textContent = enabled.checked ? '켜짐' : '꺼짐';
      box.classList.toggle('policy-enabled', enabled.checked);
      select.disabled = !enabled.checked;
      custom.hidden = select.value !== 'custom';
      days.disabled = !enabled.checked || custom.hidden;
    }
    enabled.onchange = () => {
      if (!enabled.checked && !validRetentionDays(Number(days.value)))
        days.value = savedPolicies[key].days;
      updateControls();
      updatePolicyState();
    };
    select.onchange = () => {
      if (select.value !== 'custom') days.value = select.value;
      updateControls();
      updatePolicyState();
    };
    days.oninput = updatePolicyState;
    updateControls();
    period.append(select, custom);
    box.append(target, labelOn, period);
    $('policies').append(box);
  }
  updatePolicyState();
}
$('settings-form').onsubmit = (e) => {
  e.preventDefault();
  return run(async () => {
    const value = readPolicies();
    if (JSON.stringify(value) === JSON.stringify(savedPolicies)) return;
    if (!Object.values(value).every((policy) => validRetentionDays(policy.days)))
      throw Error('보관 기간은 1~3,650일의 정수로 입력하세요.');
    const result = await request('/settings/preview', value);
    confirm(
      result.preview,
      '보관 설정 확인',
      async () => {
        await request('/settings', { token: result.preview.token });
        savedPolicies = value;
        message('보관 설정을 저장했습니다. 다음 정리 주기부터 적용합니다.');
        try {
          await summary();
        } finally {
          updatePolicyState();
        }
      },
      '설정 저장'
    );
  });
};
$('cleanup-now').onclick = () =>
  run(async () => {
    if (JSON.stringify(readPolicies()) !== JSON.stringify(savedPolicies))
      throw Error('변경한 설정을 먼저 저장하세요.');
    const preview = await request('/cleanup/preview', {});
    if (!preview.items.length) {
      message('현재 설정으로 삭제할 자료가 없습니다.');
      return;
    }
    confirm(preview, '저장된 보관 설정으로 지금 정리', () => execute(preview));
  });
async function history() {
  const list = await request('/history');
  $('history').replaceChildren();
  for (const item of list) {
    const entry = node('div');
    entry.className = 'history-entry';
    entry.append(
      node(
        'strong',
        when(item.at) + ' · ' + (item.source === 'AUTOMATIC' ? '자동 정리' : '수동 정리')
      )
    );
    const failed = item.results.filter((r) => r.status === 'FAILED');
    entry.append(
      node(
        'p',
        '삭제 ' +
          item.results.filter((r) => r.status === 'DELETED').length +
          '건 · 실패 ' +
          failed.length +
          '건'
      )
    );
    if (failed.length) {
      const detail = node('details');
      detail.append(node('summary', '실패 자료 확인'));
      for (const f of failed) detail.append(node('p', f.key.id + ' · ' + f.message));
      const retry = node('button', '실패 자료 재시도');
      retry.onclick = () =>
        run(async () => {
          const preview = await request('/history/' + item.id + '/retry', {});
          confirm(preview, '실패 자료 재시도', () => execute(preview));
        });
      entry.append(detail, retry);
    }
    $('history').append(entry);
  }
  if (!list.length) $('history').append(node('p', '아직 정리한 기록이 없습니다.'));
}
const resetTitle = (mode) => mode === 'SERVICE' ? '서비스 초기화' : '시험 데이터 초기화';
const resetLabels = {DTN:'시험', RECEIPT:'수신 원문', LOG:'로그', INPUT:'입력', IQ:'I/Q', PRESET:'프리셋', PIN:'보관 고정', ARCHIVE:'이전 시험 이력'};
function resetSummaryText(value) {
  return Object.entries(value.counts || {}).filter(([, count]) => count)
    .map(([kind, count]) => (resetLabels[kind] || kind) + ' ' + count + (['INPUT','IQ','PRESET'].includes(kind) ? '개' : '건')).join(' · ') || '삭제할 자료 없음';
}
function renderReset() {
  const value = resetPreview || resetJob;
  const active = resetJob && ['RUNNING', 'FAILED'].includes(resetJob.state);
  $('storage-overview').hidden = !!active;
  $('reset-preview').disabled = !!active;
  $('reset-mode-trials').disabled = $('reset-mode-service').disabled = !!active;
  for (const button of document.querySelectorAll('[data-tab]')) button.disabled = !!active && button.dataset.tab !== 'reset';
  $('reset-summary').hidden = !value;
  $('reset-summary').replaceChildren();
  if (value) {
    $('reset-summary').append(node('strong', resetTitle(value.mode)),
      node('span', resetSummaryText(value)), node('span', '삭제 파일 ' + size(value.fileBytes)));
    $('reset-summary').append(node('small', value.mode === 'SERVICE'
      ? '사용자 설정은 배포 기본값으로 복원하며 자동 삭제는 꺼집니다.'
      : 'GNSS 입력·I/Q 파일·프리셋·연결 설정·보관 설정은 유지합니다.'));
    if (value.retainedFiles)
      $('reset-summary').append(node('small', '공유·소유 미확인 I/Q ' + value.retainedFiles + '개는 유지합니다.'));
  }
  const blocked = resetPreview?.blocked || [];
  $('reset-blocked').hidden = !blocked.length;
  $('reset-blocked').replaceChildren(...blocked.map((text) => node('p', text)));
  const waiting = resetPreview?.waiting;
  if (waiting?.count) {
    const details = node('details');
    details.append(node('summary', '수신 대기 시험 ' + waiting.count + '건'));
    for (const id of waiting.testIds.slice(0, 10)) details.append(node('p', id));
    if (waiting.count > 10) details.append(node('p', '외 ' + (waiting.count - 10) + '건'));
    $('reset-blocked').append(details);
  }
  $('reset-stop-waiting').hidden = !waiting?.count || !!active;
  $('reset-confirm-open').hidden = !resetPreview || !!active;
  $('reset-confirm-open').disabled = !!blocked.length;
  $('reset-retry').hidden = resetJob?.state !== 'FAILED';
  $('reset-progress-panel').hidden = !resetJob;
  if (resetJob) {
    $('reset-progress-panel').dataset.state = resetJob.state;
    $('reset-state').textContent = {RUNNING:'초기화 진행 중', FAILED:'초기화 미완료', COMPLETED:'초기화 완료'}[resetJob.state] || '';
    $('reset-progress-count').textContent = resetJob.completed + ' / ' + resetJob.total;
    $('reset-progress').value = Math.floor(resetJob.completed * 100 / Math.max(1, resetJob.total));
    $('reset-message').textContent = resetJob.message;
    $('reset-home').hidden = resetJob.state !== 'COMPLETED';
  }
}
function showResetTab() {
  $('reset').hidden = false;
  $('records').hidden = $('settings').hidden = $('detail').hidden = true;
  for (const button of document.querySelectorAll('[data-tab]'))
    button.classList.toggle('primary', button.dataset.tab === 'reset');
}
async function loadResetStatus() {
  const value = await request('/reset/status');
  if (!value?.state) return;
  resetJob = value;
  if (['RUNNING','FAILED'].includes(value.state)) {
    resetPreview = null;
    $('node').textContent = (value.role === 'receiver' ? '수신 PC' : value.role === 'sender' ? '송신 PC' : '현재 서버') + ' · 이 PC의 데이터만 관리합니다';
    showResetTab();
    $('reset-mode-service').checked = value.mode === 'SERVICE';
    $('reset-mode-trials').checked = value.mode !== 'SERVICE';
  }
  renderReset();
  if (value.state === 'RUNNING' && !resetPoll) scheduleResetPoll();
}
function scheduleResetPoll() {
  resetPoll = setTimeout(async () => {
    resetPoll = null;
    try {
      await loadResetStatus();
      if (resetJob?.state === 'COMPLETED') {
        await summary();
        await history();
      }
    } catch (error) {
      message('초기화 상태 조회 실패 · 다시 확인합니다. ' + error.message, true);
      scheduleResetPoll();
    }
  }, 1000);
}
for (const id of ['reset-mode-trials', 'reset-mode-service'])
  $(id).onchange = () => { resetPreview = null; resetJob = null; renderReset(); };
$('reset-preview').onclick = () => run(async () => {
  const mode = $('reset-mode-service').checked ? 'SERVICE' : 'TRIALS';
  resetPreview = await request('/reset/preview', {mode});
  resetJob = null;
  renderReset();
});
function openResetConfirmation(retry) {
  resetRetry = retry;
  const value = retry ? resetJob : resetPreview;
  if (!value) return;
  const title = resetTitle(value.mode);
  $('reset-confirm-title').textContent = retry ? title + ' · 남은 항목 다시 실행' : title;
  $('reset-confirm-summary').textContent = (value.role === 'receiver' ? '수신 서비스' : value.role === 'sender' ? '송신 서비스' : '현재 서비스')
    + ' · ' + resetSummaryText(value) + ' · 삭제 파일 ' + size(value.fileBytes);
  $('reset-phrase-label').textContent = '확인을 위해 ‘' + title + '’를 입력하세요.';
  $('reset-phrase').placeholder = title;
  $('reset-phrase').value = '';
  $('reset-acknowledge').checked = false;
  $('reset-confirm-run').disabled = true;
  $('reset-confirm').showModal();
}
function validateResetConfirmation() {
  const value = resetRetry ? resetJob : resetPreview;
  $('reset-confirm-run').disabled = !value || !$('reset-acknowledge').checked
    || $('reset-phrase').value !== resetTitle(value.mode);
}
$('reset-confirm-open').onclick = () => openResetConfirmation(false);
$('reset-retry').onclick = () => openResetConfirmation(true);
$('reset-phrase').oninput = $('reset-acknowledge').onchange = validateResetConfirmation;
$('reset-confirm-cancel').onclick = () => $('reset-confirm').close();
$('reset-confirm-run').onclick = () => run(async () => {
  const value = resetRetry ? resetJob : resetPreview;
  if (!value || !$('reset-acknowledge').checked || $('reset-phrase').value !== resetTitle(value.mode))
    throw Error('초기화 범위와 확인 문구를 확인하세요.');
  resetJob = await request(resetRetry ? '/reset/retry' : '/reset/execute',
    {token:value.token, confirmation:$('reset-phrase').value});
  $('reset-confirm').close();
  resetPreview = null;
  renderReset();
  if (resetJob.state === 'RUNNING') scheduleResetPoll();
});
$('reset-stop-waiting').onclick = () => run(async () => {
  const waiting = resetPreview?.waiting;
  if (!waiting?.count || !globalThis.confirm('현재 서비스의 수신 대기 시험 ' + waiting.count
      + '건을 종료하시겠습니까? 상대 서비스와 어댑터의 자료는 유지됩니다.')) return;
  const result = await request('/reset/waiting/close', {token:resetPreview.token});
  message('현재 서비스 대기 종료 ' + result.cancelled + '건 · 제외 ' + result.skipped + '건');
  resetPreview = await request('/reset/preview', {mode:resetPreview.mode});
  renderReset();
});
run(async () => {
  await loadResetStatus();
  if (resetJob && ['RUNNING','FAILED'].includes(resetJob.state)) return;
  await summary();
  states();
  await load();
});
