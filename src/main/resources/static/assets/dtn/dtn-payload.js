// JSON 본문은 HTML로 해석하지 않는다. 정렬 보기는 화면에만 적용하고 다운로드는 원문 API를 사용한다.
const iqViews = new WeakMap();
export function renderIqFile(container, data, label = '파일 준비 완료') {
  const key = JSON.stringify([data, label]);
  if (iqViews.get(container) === key) return;
  iqViews.set(container, key);
  const expanded = container.querySelector?.('details')?.open === true;
  const node = (tag, text, className) => {
    const e = document.createElement(tag);
    if (text !== undefined) e.textContent = text;
    if (className) e.className = className;
    return e;
  };
  container.replaceChildren();
  if (!data) { container.append(node('small', label)); return; }
  const summary = node('div', undefined, 'iq-file-summary');
  summary.append(node('span', data.verdict === 'PASS' ? '파일 검증 일치' : data.verdict === 'FAIL' ? '파일 검증 불일치' : label,
    'pill ' + (data.verdict === 'PASS' ? 'online' : data.verdict === 'FAIL' ? 'error' : '')));
  const size = Number.isFinite(data.sizeBytes) ? (data.sizeBytes / 1e9).toFixed(2) + ' GB' : '크기 정보 없음';
  summary.append(node('strong', size));
  if (data.durationSeconds) summary.append(node('span', data.durationSeconds + '초'));
  if (data.sampleRateHz) summary.append(node('span', data.sampleRateHz / 1e6 + ' MHz'));
  const path = node('div', data.filePath || '경로 정보 없음', 'iq-file-path');
  const details = node('details'); details.open = expanded;
  details.append(node('summary', '해시 · I/Q 샘플 상세'));
  details.append(node('div', '파일 크기 · ' + (Number.isFinite(data.sizeBytes) ? data.sizeBytes.toLocaleString('ko-KR') + ' bytes' : '—')));
  details.append(node('div', 'SHA-256 · ' + (data.sha256 || '—'), 'iq-file-path'));
  if (Array.isArray(data.preview) && data.preview.length) {
    details.append(node('small', '파일 앞부분 ' + data.preview.length + '쌍 · 전체 샘플 아님'));
    const table = node('table', undefined, 'iq-sample-table');
    const head = node('thead'), row = node('tr');
    for (const title of ['번호', 'I', 'Q']) { const th = node('th', title); th.scope = 'col'; row.append(th); }
    head.append(row); table.append(head);
    const body = node('tbody');
    data.preview.forEach((pair, i) => {
      const tr = node('tr');
      for (const value of [i + 1, pair?.[0] ?? '—', pair?.[1] ?? '—']) tr.append(node('td', String(value)));
      body.append(tr);
    });
    table.append(body);
    const scroll = node('div', undefined, 'iq-sample-scroll'); scroll.tabIndex = 0; scroll.append(table); details.append(scroll);
  }
  container.append(summary, path, details);
}
export function payloadUrl(testId, direction, download = false) {
  if (!['sent', 'received'].includes(direction)) throw new Error('지원하지 않는 JSON 방향입니다.');
  return '/lnis/api/v1/dtn/tests/' + encodeURIComponent(testId) + '/payload/' + direction +
    (download ? '?download=true' : '');
}

export function displayedJson(original, pretty) {
  return pretty ? JSON.stringify(JSON.parse(original), null, 2) : original;
}

export function createPayloadViewer(container, {receivedOnly = false, sentOnly = false} = {}) {
  const create = (tag, text) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const title = create('h2', receivedOnly ? '수신 JSON 원문' : sentOnly ? '송신 JSON 원문' : '송수신 JSON 원문');
  const controls = create('div');
  controls.className = 'dtn-payload-controls';
  const sent = create('button', sentOnly ? '송신 JSON 원문' : '송신 원문');
  const received = create('button', receivedOnly ? '수신 JSON 원문' : '수신 원문');
  sent.type = received.type = 'button';
  sent.disabled = received.disabled = true;
  // 수신 노드에는 송신 원문이 없으므로 불필요한 버튼을 노출하지 않는다.
  sent.hidden = receivedOnly;
  received.hidden = sentOnly;
  controls.append(sent, received);
  const status = create('p', '아직 준비된 JSON 원문이 없습니다.');
  status.setAttribute('role', 'status');
  const panel = create('div');
  panel.hidden = true;
  const caption = create('p');
  const prettyLabel = create('label');
  const pretty = create('input');
  pretty.type = 'checkbox';
  pretty.checked = true;
  pretty.disabled = true;
  prettyLabel.append(pretty, document.createTextNode(' 정렬 보기'));
  const download = create('a', 'JSON 다운로드');
  download.setAttribute('aria-disabled', 'true');
  const close = create('button', '접기');
  close.type = 'button';
  const toolbar = create('div');
  toolbar.className = 'dtn-payload-controls';
  if (sentOnly || receivedOnly) {
    controls.append(prettyLabel, download);
    controls.className += ' dtn-payload-header';
    download.className = 'dtn-payload-download';
    toolbar.hidden = true;
  } else toolbar.append(prettyLabel, download, close);
  const text = create('textarea');
  text.readOnly = true;
  text.rows = 9;
  text.placeholder = 'JSON 원문이 준비되면 위 버튼으로 확인할 수 있습니다.';
  text.spellcheck = false;
  text.className = 'dtn-payload-text';
  text.setAttribute('aria-label', '선택한 시험의 JSON 본문');
  panel.append(caption, toolbar, text);
  if (!sentOnly && !receivedOnly) container.append(title);
  container.append(controls, status, panel);
  let job = null, original = '', generation = 0, pending = null;
  let automaticKey = null;

  function reset() {
    generation++;
    pending?.abort();
    pending = null;
    original = '';
    text.value = '';
    pretty.checked = true;
    panel.hidden = true;
    pretty.disabled = true;
    sent.setAttribute('aria-expanded', 'false');
    received.setAttribute('aria-expanded', 'false');
    download.removeAttribute('href');
    download.setAttribute('aria-disabled', 'true');
  }

  async function show(direction) {
    if (!job) return;
    reset();
    const selectedId = job.testId;
    const requestGeneration = generation;
    pending = new AbortController();
    status.textContent = 'JSON 본문을 불러오는 중입니다.';
    try {
      const response = await fetch(payloadUrl(selectedId, direction), {cache: 'no-store', signal: pending.signal});
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.detail || error.message || ('HTTP ' + response.status));
      }
      const body = await response.text();
      // 조회 중 시험을 바꾸거나 닫으면 이전 요청이 새 시험의 본문을 덮어쓰지 않는다.
      if (requestGeneration !== generation) return;
      original = body;
      text.value = original;
      caption.textContent = (direction === 'sent' ? '송신 요청 JSON' : '최초 접수 수신 JSON') + ' · 시험 ' + selectedId;
      download.href = payloadUrl(selectedId, direction, true);
      download.setAttribute('aria-disabled', 'false');
      panel.hidden = false;
      pretty.disabled = false;
      (direction === 'sent' ? sent : received).setAttribute('aria-expanded', 'true');
      const legacy = response.headers.get('X-LNIS-Payload-Representation') === 'legacy-normalized';
      status.textContent = legacy
        ? '과거 시험의 정규화된 저장본입니다.'
        : direction === 'sent'
          ? '외부 DTN/HDTN에 전달할 요청 본문입니다.'
          : '접수 당시 원문 · 다운로드는 원문 그대로 저장합니다.';
      pretty.onchange();
    } catch (error) {
      if (requestGeneration === generation && error.name !== 'AbortError') status.textContent = 'JSON 조회 실패: ' + error.message;
    } finally {
      if (requestGeneration === generation) pending = null;
    }
  }

  function toggle(direction, button, singleDirection) {
    if (singleDirection && (!panel.hidden || pending)) {
      generation++; pending?.abort(); pending=null;
      panel.hidden=true; button.setAttribute('aria-expanded','false');
      status.textContent='JSON 보기를 닫았습니다.';
      return;
    }
    return show(direction);
  }
  sent.onclick = () => toggle('sent', sent, sentOnly);
  received.onclick = () => toggle('received', received, receivedOnly);
  pretty.onchange = () => {
    if (pretty.disabled || !original) return;
    try { text.value = displayedJson(original, pretty.checked); }
    catch { pretty.checked = false; text.value = original; status.textContent = '정렬할 수 없어 원문을 표시합니다.'; }
  };
  close.onclick = () => { reset(); status.textContent = 'JSON 보기를 닫았습니다.'; };

  return {
    setJob(nextJob) {
      if (job?.testId !== nextJob?.testId) {
        automaticKey = null;
        reset();
        status.textContent = nextJob
          ? (receivedOnly ? '수신 JSON 원문 버튼으로 접수 당시 JSON을 확인하세요.' : sentOnly ? '송신 JSON 원문 버튼으로 확인하세요.' : '준비된 송신 또는 수신 JSON을 선택하세요.')
          : '시험을 선택하세요.';
      }
      job = nextJob || null;
      sent.disabled = !job?.sentPayloadAvailable;
      received.disabled = !job?.receivedPayloadAvailable;
      const direction = receivedOnly ? 'received' : sentOnly ? 'sent'
        : job?.sentPayloadAvailable ? 'sent' : 'received';
      const available = direction === 'sent' ? job?.sentPayloadAvailable : job?.receivedPayloadAvailable;
      const key = job?.testId + ':' + direction;
      if (available && automaticKey !== key) {
        automaticKey = key;
        return show(direction);
      }
    }
  };
}
