export function initAdapterHealth(defaultUrl, log = () => {}) {
  const $ = id => document.getElementById(id);
  const input = $('dtn-send-url'), button = $('dtn-adapter-health');
  const save = $('dtn-adapter-save');
  const storageKey = 'lnis.adapter-url.' + (document.body?.dataset?.page || 'dtn');
  input.value = defaultUrl;
  try { input.value = localStorage.getItem(storageKey) || defaultUrl; } catch { /* Storage may be disabled. */ }
  let checking = false, revision = 0, lastState = '';
  const reportState = (state, automatic) => {
    if (!automatic || state !== lastState) log('어댑터 연결 확인 · ' + state);
    lastState = state;
  };
  const status = (text, tone = '') => {
    $('dtn-adapter-status').textContent = text;
    $('dtn-adapter-dot').className = 'connection-dot ' + (tone === 'online' ? 'online' : tone === 'error' ? 'offline' : 'unknown');
  };
  const previousInput = input.oninput;
  input.oninput = event => {
    revision++;
    previousInput?.(event);
    status('확인 대기');
    $('dtn-adapter-detail').textContent = '변경된 주소로 다시 확인합니다.';
    $('dtn-adapter-health-time').textContent = '주소 변경 · 다음 확인 대기';
    $('dtn-adapter-health-json').textContent = '아직 변경된 주소의 확인 결과가 없습니다.';
  };
  async function check(automatic = false) {
    if (checking) return;
    checking = true;
    const currentRevision = revision, address = input.value.trim();
    const current = () => currentRevision === revision && address === input.value.trim();
    button.disabled = true;
    $('dtn-adapter-health-results').setAttribute('aria-busy', 'true');
    status('확인 중', 'warning');
    try {
      const url = new URL(address);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash)
        throw new Error('어댑터 서버 주소를 확인하세요.');
      const response = await fetch('/lnis/api/v1/dtn/adapter-health?adapterUrl=' + encodeURIComponent(address),
        {cache: 'no-store', signal: AbortSignal.timeout(8000)});
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const report = await response.json();
      if (!report.adapter || !report.checkedAt) throw new Error('헬스체크 응답 형식 오류');
      if (!current()) return;
      const value = report.adapter;
      reportState(value.message, automatic);
      status(value.status === 'ready' ? '연결됨' : value.message, value.status === 'ready' ? 'online' : value.status === 'busy' ? 'warning' : 'error');
      $('dtn-adapter-detail').textContent = (value.httpStatus == null ? '' : 'HTTP ' + value.httpStatus + ' · ')
        + value.elapsedMillis + ' ms · ' + value.url;
      $('dtn-adapter-health-time').textContent = '마지막 확인 ' + new Date(report.checkedAt).toLocaleString('ko-KR', {hour12: false}) + ' · LNIS 서버 기준';
      $('dtn-adapter-health-json').textContent = JSON.stringify(report, null, 2);
    } catch (error) {
      if (!current()) return;
      reportState('연결실패', automatic);
      status('연결실패', 'error');
      $('dtn-adapter-detail').textContent = error.message;
      $('dtn-adapter-health-time').textContent = '마지막 확인 실패 · 다음 자동 확인 또는 수동 재시도';
      $('dtn-adapter-health-json').textContent = JSON.stringify({error: error.message}, null, 2);
    } finally {
      checking = false; button.disabled = false;
      $('dtn-adapter-health-results').setAttribute('aria-busy', 'false');
    }
  }
  button.onclick = () => check(false);
  save.onclick = async () => {
    if (input.disabled) return;
    try {
      const address = input.value.trim(), url = new URL(address);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash)
        throw new Error('어댑터 서버 주소를 확인하세요.');
      localStorage.setItem(storageKey, address);
      log('어댑터 주소 저장·적용 완료 · 이 브라우저에 저장됨');
      await check(false);
    } catch (error) { log('어댑터 주소 저장 실패 · ' + error.message); }
  };
  setInterval(() => { if (document.visibilityState !== 'hidden') check(true); }, 10000);
}
