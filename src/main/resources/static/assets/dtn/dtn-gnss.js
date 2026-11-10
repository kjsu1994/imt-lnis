import {requestJson} from '../common/http.js?v=20261103-management-reset';

/** 화면이 닫혀도 COM 연결과 수집은 유지한다. */
export function initGnssControls({port, baud, refresh, changed = () => {}, log = () => {}}) {
  const $ = id => document.getElementById(id);
  let state = {state: 'DISCONNECTED', timeState: 'UNAVAILABLE'}, working = false, refreshing = false;
  let lastTransition = '';
  const request = (path, options = {}) => requestJson('/node/gnss' + path, {cache: 'no-store', ...options});
  const attached = () => ['CONNECTED', 'CONNECTING', 'RECONNECTING'].includes(state.state);
  const labels = {DISCONNECTED: '미연결', CONNECTING: '연결 중', CONNECTED: '연결됨',
    RECONNECTING: '재연결 중', ERROR: '연결 오류'};
  function render() {
    $('gnss-connection-state').textContent = labels[state.state] || '미확인';
    $('gnss-connection-state').className = 'pill ' + (state.state === 'CONNECTED' ? 'online' : 'warning');
    $('gnss-connect').textContent = attached() ? '연결 해제' : '연결';
    $('gnss-connect').disabled = working || (!attached() && !$(port).value);
    $(port).disabled = $(baud).disabled = working || attached();
    $(refresh).disabled = working || refreshing;
    if (attached() && state.portName) {
      if (![...$(port).options].some(option => option.value === state.portName)) {
        $(port).add(new Option(state.portName, state.portName));
      }
      $(port).value = state.portName;
      $(baud).value = String(state.baudRate);
    }
    changed(state);
  }
  async function listPorts() {
    if (refreshing) return;
    refreshing = true;
    render();
    try {
      const ports = await request('/ports'), previous = $(port).value;
      $(port).replaceChildren(new Option(ports.length ? '포트 선택' : '연결된 포트 없음', ''),
        ...ports.map(p => new Option(p.name + (p.description ? ' · ' + p.description : ''), p.name)));
      if (ports.some(p => p.name === previous)) $(port).value = previous;
    } catch (error) {
      log('GNSS 포트 조회 불가 · ' + error.message, 'WARN');
    } finally {
      refreshing = false;
      render();
    }
  }
  async function poll() {
    try {
      state = await request('');
    } catch {
      state = {state: 'UNAVAILABLE', timeState: 'UNAVAILABLE', message: 'GNSS 상태 조회 불가 · 파일 기반 시험은 계속 사용할 수 있습니다.'};
    }
    const transition = state.state;
    if (transition !== lastTransition) {
      log('GNSS · ' + (state.message || ''), state.state === 'ERROR' ? 'WARN' : 'INFO');
      lastTransition = transition;
    }
    render();

  }
  $(refresh).onclick = listPorts;
  $(port).addEventListener('change', render);
  $('gnss-connect').onclick = async () => {
    if (working) return;
    if (state.capturing && !confirm('진행 중인 GNSS 수집을 중단하고 연결을 해제할까요?')) return;
    working = true;
    render();
    try {
      const disconnect = attached();
      state = await request(disconnect ? '/disconnect?stopCapture=' + !!state.capturing : '/connect', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: disconnect ? undefined : JSON.stringify({portName: $(port).value, baudRate: Number($(baud).value),
          protocolId: 'UBX', dtrEnabled: false, rtsEnabled: false})});
    } catch (error) {
      log(error.message, 'ERROR');
    } finally {
      working = false;
      await poll();
    }
  };
  return {poll, listPorts, render, connected: () => state.state === 'CONNECTED'};
}


// 브라우저 시계 대신 서비스의 시스템 시각을 표시한다.
export function initSystemClock() {
  const element = document.getElementById('service-clock-value');
  let sample = null, sampledAt = 0;
  const tickNow = () => globalThis.performance?.now() ?? Date.now();
  const format = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  });
  function draw() {
    if (!element) return;
    const elapsed = tickNow() - sampledAt;
    element.textContent = sample !== null && elapsed < 6000
      ? format.format(new Date(sample + elapsed)) : '—';
  }
  async function poll() {
    if (!element) return;
    try {
      const response = await requestJson('/node/clock', {cache: 'no-store'});
      const value = Date.parse(response?.clock?.rawAt);
      if (!Number.isFinite(value)) throw new Error('시스템 시각 응답 없음');
      sample = value;
      sampledAt = tickNow();
    } catch {
      sample = null;
    }
    draw();
  }
  setInterval(draw, 1000);
  return {poll};
}
