import {requestJson} from '../common/http.js?v=20260915-structure';

/** 화면이 닫혀도 백엔드 연결은 유지한다. GNSS 시간 수신을 PC 동기화로 표시하지 않는다. */
export function initGnssControls({port, baud, refresh, changed = () => {}, log = () => {}}) {
  const $ = id => document.getElementById(id);
  let state = {state: 'DISCONNECTED', timeState: 'UNAVAILABLE'}, working = false, refreshing = false;
  let lastTransition = '', peerTime = null;
  const request = (path, options = {}) => requestJson('/node/gnss' + path, {cache: 'no-store', ...options});
  const attached = () => ['CONNECTED', 'CONNECTING', 'RECONNECTING'].includes(state.state);
  const timeLabel = value => ({VALID: '시간 수신', ACQUIRING: '시간 확보 중',
    STALE: '시간 갱신 끊김', UNAVAILABLE: '시간 미확인'}[value] || '시간 미확인');
  function render() {
    const labels = {DISCONNECTED: '미연결', CONNECTING: '연결 중', CONNECTED: '연결됨',
      RECONNECTING: '재연결 중', ERROR: '연결 오류'};
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
    $('gnss-time-state').textContent = timeLabel(state.timeState);
    $('gnss-summary').textContent = 'GNSS ' + (labels[state.state] || '미확인') + ' · ' + timeLabel(state.timeState)
      + ' / 상대 GNSS ' + timeLabel(peerTime);
    $('gnss-time-detail').textContent = 'GNSS UTC: ' + (state.timeState === 'VALID' && state.utc ? state.utc : '—')
      + '\n최근 메시지 수신: ' + (state.updatedAt ? new Date(state.updatedAt).toLocaleTimeString('ko-KR') : '—')
      + '\n수신기 시간 정확도 추정: ' + (state.accuracyNanos == null ? '—' : state.accuracyNanos + ' ns (PC 정확도 아님)')
      + '\n' + (state.message || '') + '\nPC 시계는 조정하지 않습니다. 시험은 기존 시스템 시간을 사용합니다.';
    if ($('gnss-dtr')) {
      $('gnss-dtr').disabled = $('gnss-rts').disabled = attached() || working;
      if (attached()) {
        $('gnss-dtr').checked = !!state.dtrEnabled;
        $('gnss-rts').checked = !!state.rtsEnabled;
      }
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
    const transition = state.state + ':' + state.timeState;
    if (transition !== lastTransition) {
      log('GNSS · ' + (state.message || '') + ' · ' + timeLabel(state.timeState), state.state === 'ERROR' ? 'WARN' : 'INFO');
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
          protocolId: 'UBX', dtrEnabled: $('gnss-dtr')?.checked || false, rtsEnabled: $('gnss-rts')?.checked || false})});
    } catch (error) {
      log(error.message, 'ERROR');
      $('gnss-time-detail').textContent = error.message;
    } finally {
      working = false;
      await poll();
    }
  };
  return {poll, listPorts, render, connected: () => state.state === 'CONNECTED',
    setPeerTime(value) { peerTime = value; render(); }};
}
