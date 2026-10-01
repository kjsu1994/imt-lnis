import {requestJson} from '../common/http.js?v=20261001-review';

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
      + '\n' + (state.message || '') + '\nWindows 시간은 변경하지 않습니다. 시험 시각 보정은 시간 맞추기에서 적용합니다.';
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
    await pollClock();
  }
  const tickNow = () => globalThis.performance?.now() ?? Date.now();
  let clockSample = null, clockTick = 0, clockWorking = false;
  const sourceLabel = value => ({SYSTEM: 'PC 시각 · 미보정', GNSS_USB: 'GNSS · 근사 보정',
    PEER_GNSS: '상대 GNSS · 근사 보정', NTP: '공통 NTP · 근사 보정'}[value] || '미확인');
  async function pollClock() {
    if (!$('service-clock-value')) return;
    try {
      const start = tickNow();
      const response = await requestJson('/node/clock', {cache: 'no-store'});
      if (!response?.clock?.trialAt) throw new Error('시험 시각 응답 없음');
      clockTick = tickNow();
      clockSample = {response, milliseconds: Date.parse(response.clock.trialAt) + (clockTick - start) / 2};
      const value = response.clock;
      $('service-clock-state').textContent = sourceLabel(value.source) + (value.ageSeconds > 300 ? ' · 재확인 권장' : '');
      $('service-clock-sync').disabled = clockWorking || response.busy;
      $('service-clock-detail').textContent = 'PC UTC: ' + value.rawAt + '\n시험 UTC: ' + value.trialAt
        + '\n보정량: ' + Number(value.offsetSeconds).toFixed(9) + ' s'
        + '\n최근 보정: ' + (value.calibratedAt || '없음')
        + '\n시간원: ' + sourceLabel(value.source)
        + '\n조회 왕복 시간: ' + (value.roundTripSeconds == null ? '—' : (value.roundTripSeconds * 1000).toFixed(3) + ' ms')
        + '\nPC 시각 변경 감지: ' + value.clockChanges + '회'
        + '\n표시는 서버 시각을 기준으로 갱신합니다. 소수점 자릿수는 측정 정확도가 아닙니다.';
    } catch {
      clockSample = null;
      $('service-clock-state').textContent = '시험 시각 조회 불가';
      $('service-clock-sync').disabled = clockWorking;
    }
    drawClock();
  }
  function drawClock() {
    if (!$('service-clock-value')) return;
    const elapsed = tickNow() - clockTick;
    $('service-clock-value').textContent = clockSample && elapsed < 6000
      ? new Date(clockSample.milliseconds + elapsed + 9 * 3600000).toISOString().replace('T', ' ').slice(0, 23)
      : '—';
    if (clockSample && elapsed >= 6000) $('service-clock-state').textContent = '시각 갱신 끊김';
  }
  if ($('service-clock-sync')) {
    $('service-clock-sync').onclick = async () => {
      if (clockWorking) return;
      clockWorking = true;
      $('service-clock-sync').disabled = true;
      try {
        const proposal = await requestJson('/node/clock/prepare', {method: 'POST'});
        if (!confirm(sourceLabel(proposal.source) + '\n내부 시험 시각 보정량: '
            + Number(proposal.offsetSeconds).toFixed(6) + ' s\nWindows 시간은 변경하지 않습니다. 적용할까요?')) return;
        await requestJson('/node/clock/apply', {method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({ticket: proposal.ticket})});
        log('시험 기준시각 보정 완료 · ' + sourceLabel(proposal.source), 'INFO');
      } catch (error) {
        log('시각 보정 미적용 · ' + error.message, 'WARN');
        alert(error.message);
      } finally {
        clockWorking = false;
        await pollClock();
      }
    };
    const animateClock = () => { drawClock(); setTimeout(animateClock, 100); };
    animateClock();
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
