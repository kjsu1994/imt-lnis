// JSON 본문은 HTML로 해석하지 않는다. 정렬 보기는 화면에만 적용하고 다운로드는 원문 API를 사용한다.
export function payloadUrl(testId, direction, download = false) {
  if (!['sent', 'received'].includes(direction)) throw new Error('지원하지 않는 JSON 방향입니다.');
  return '/lnis/api/v1/dtn/tests/' + encodeURIComponent(testId) + '/payload/' + direction +
    (download ? '?download=true' : '');
}

export function displayedJson(original, pretty) {
  return pretty ? JSON.stringify(JSON.parse(original), null, 2) : original;
}

export function createPayloadViewer(container, {receivedOnly = false} = {}) {
  const create = (tag, text) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    return element;
  };
  const title = create('h2', receivedOnly ? '수신 JSON 원문' : '송수신 JSON 원문');
  const controls = create('div');
  controls.className = 'dtn-payload-controls';
  const sent = create('button', '송신 원문');
  const received = create('button', '수신 원문');
  sent.type = received.type = 'button';
  sent.disabled = received.disabled = true;
  // 수신 노드에는 송신 원문이 없으므로 불필요한 버튼을 노출하지 않는다.
  sent.hidden = receivedOnly;
  controls.append(sent, received);
  const status = create('p', '아직 준비된 JSON 원문이 없습니다.');
  status.setAttribute('role', 'status');
  const panel = create('div');
  panel.hidden = true;
  const caption = create('p');
  const prettyLabel = create('label');
  const pretty = create('input');
  pretty.type = 'checkbox';
  pretty.disabled = true;
  prettyLabel.append(pretty, document.createTextNode(' 정렬 보기'));
  const download = create('a', 'JSON 다운로드');
  download.setAttribute('aria-disabled', 'true');
  const close = create('button', '접기');
  close.type = 'button';
  const toolbar = create('div');
  toolbar.className = 'dtn-payload-controls';
  toolbar.append(prettyLabel, download, close);
  const text = create('textarea');
  text.readOnly = true;
  text.rows = 9;
  text.placeholder = 'JSON 원문이 준비되면 위 버튼으로 확인할 수 있습니다.';
  text.spellcheck = false;
  text.className = 'dtn-payload-text';
  text.setAttribute('aria-label', '선택한 시험의 JSON 본문');
  panel.append(caption, toolbar, text);
  container.append(title, controls, status, panel);
  let job = null, original = '', generation = 0, pending = null;

  function reset() {
    generation++;
    pending?.abort();
    pending = null;
    original = '';
    text.value = '';
    pretty.checked = false;
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
    } catch (error) {
      if (requestGeneration === generation && error.name !== 'AbortError') status.textContent = 'JSON 조회 실패: ' + error.message;
    }
  }

  sent.onclick = () => show('sent');
  received.onclick = () => show('received');
  pretty.onchange = () => {
    if (pretty.disabled || !original) { pretty.checked = false; return; }
    try { text.value = displayedJson(original, pretty.checked); }
    catch { pretty.checked = false; text.value = original; status.textContent = '정렬할 수 없어 원문을 표시합니다.'; }
  };
  close.onclick = () => { reset(); status.textContent = 'JSON 보기를 닫았습니다.'; };

  return {
    setJob(nextJob) {
      if (job?.testId !== nextJob?.testId) {
        reset();
        status.textContent = nextJob
          ? (receivedOnly ? '수신 원문 버튼으로 접수 당시 JSON을 확인하세요.' : '준비된 송신 또는 수신 JSON을 선택하세요.')
          : '시험을 선택하세요.';
      }
      job = nextJob || null;
      sent.disabled = !job?.sentPayloadAvailable;
      received.disabled = !job?.receivedPayloadAvailable;
    }
  };
}
