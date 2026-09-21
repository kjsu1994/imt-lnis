// Shared DTN-only observation display. Device values are never inserted as HTML.
export const numeric = (value, digits = 3, missing = '—') =>
  typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : missing;
const constellation = id => ['GPS', 'SBAS', 'Galileo', 'BeiDou', 'IMES', 'QZSS', 'GLONASS', 'NavIC'][id] || ('GNSS ' + id);
export function navigationCells(item, iq = false) {
  const n = item.message;
  return [item.sequence, item.capturedAt, constellation(n.constellationId), n.satelliteId,
    n.signalId, n.frequencyId, n.sfrbxVersion, n.words.length,
    n.words.map(word => Number(word).toString(16).toUpperCase().padStart(iq ? 6 : 8, '0')).join(' ')];
}

// Display only: retain the measured pseudorange and epoch used by the PVT pipeline.
export function transmitTime(o, receiverTowSeconds) {
  if (!(o.trackingStatus & 1) || !Number.isFinite(receiverTowSeconds) ||
      receiverTowSeconds < 0 || receiverTowSeconds >= 604800 ||
      !Number.isFinite(o.pseudorangeMeters) || o.pseudorangeMeters <= 0) return '—';
  const seconds = receiverTowSeconds - o.pseudorangeMeters / 299792458;
  const weekOffset = Math.floor(seconds / 604800);
  return numeric(seconds - weekOffset * 604800, 9) + (weekOffset ? ' (이전 주)' : '');
}

export function observationCells(o, receiverTowSeconds) {
  const iq = o.source === 'IQ_TRACKING';
  const gnss = constellation(o.constellationId);
  const prValid = (o.trackingStatus & 1) !== 0;
  const cpValid = (o.trackingStatus & 2) !== 0;
  return [gnss, o.satelliteId, iq ? 'AFS Data · L1' : o.constellationId === 0 && o.signalId === 0 ? 'L1 C/A (0)' : 'ID ' + o.signalId,
    numeric(o.pseudorangeMeters), numeric(o.carrierPhaseCycles), numeric(o.dopplerHz),
    o.carrierToNoiseDbHz, o.lockTimeMilliseconds,
    iq ? '—' : [o.pseudorangeStdDev, o.carrierPhaseStdDev, o.dopplerStdDev].join(' / '),
    iq ? 'PR 유효 · 위상 상대값' : 'PR ' + (prValid ? '유효' : '무효') + ' · CP ' + (cpValid ? '유효' : '무효'),
    o.constellationId === 0 && o.signalId === 0 && prValid ? '계산 대상' : '제외',
    transmitTime(o, receiverTowSeconds)];
}

export function createObservationView(container, onSelect = () => {}, role = '') {
  if (!container) return {setData() {}, select() {}};
  container.innerHTML = `
    <div class="gnss-data-header"><h2 data-title>GNSS 수집 데이터</h2>
      <label>GNSS 기준시간 <select data-epoch aria-label="관측 시점"></select></label>
    <div class="observation-summary"><span data-source>데이터 없음</span><span data-nav>항법정보 —</span>
      <span data-count>관측 신호 —</span><span data-status></span></div></div>
    <h3 data-observation-title>관측값 · RAWX</h3>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 관측값 표">
      <table class="epoch-observation-table"><caption>위성·신호별 관측값</caption><thead><tr>
        <th>GNSS</th><th>위성</th><th>신호</th><th>의사거리 <small>m</small></th>
        <th>반송파 위상 <small>cycle</small></th><th>도플러 <small>Hz</small></th>
        <th>C/N₀ <small>dB-Hz</small></th><th>추적시간 <small>ms</small></th>
        <th title="수신기가 출력한 표준편차 코드. SI 단위의 표준편차가 아닙니다.">편차 코드 <small>PR / CP / DO</small></th>
        <th>측정 유효성</th><th title="GPS L1·의사거리 유효 조건. 최종 계산에서 사용한 위성 수는 PVT 결과에 표시됩니다.">PVT 입력</th>
        <th title="관측 수신 시각 − 의사거리 / 299,792,458. 위성 시계·시스템 간 시간 보정 전 추정값이며 실제 정확도를 의미하지 않습니다. 주 경계를 넘으면 이전 주로 표시합니다.">위성 송신 시각 추정 <small>TOW(s) · 보정 전</small></th>
      </tr></thead><tbody></tbody></table></div>
    <h3 data-navigation-title>항법정보 · SFRBX</h3>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 항법정보 표">
      <table class="epoch-observation-table"><caption data-navigation-caption>항법정보 · SFRBX · 수집된 전체 메시지</caption><thead><tr>
        <th title="원본 레코드의 수집 순번이며 총 건수가 아닙니다. 0~95는 96건입니다.">수집 순번</th><th>수집 시각 <small>UTC</small></th><th>GNSS</th><th>위성</th>
        <th>신호 ID</th><th>주파수 ID</th><th>버전</th><th>워드 수</th><th>수신 워드 <small data-word-width>HEX · 32 bit</small></th>
      </tr></thead><tbody data-navigation></tbody></table></div>
    <details data-record-details><summary>저장된 전체 필드 보기 · JSON</summary><pre data-records class="log"></pre></details>
    `;
  const select = container.querySelector('[data-epoch]');
  const body = container.querySelector('tbody');
  let data = null;
  function render(notify = true) {
    const item = data?.epochs?.[Number(select.value)];
    const epoch = item?.observation;
    body.replaceChildren();
    if (!epoch) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      row.className = 'epoch-empty-row'; cell.colSpan = 12; cell.textContent = '표시할 GNSS 관측값이 없습니다.';
      row.append(cell); body.append(row);
    } else {
      for (const observation of epoch.observations) {
        const row = document.createElement('tr');
        for (const value of observationCells(observation, epoch.receiverTowSeconds)) {
          const cell = document.createElement('td'); cell.textContent = String(value ?? '—'); row.append(cell);
        }
        body.append(row);
      }
    }
    const iq = data?.source === 'IQ_TRACKING';
    container.querySelector('[data-title]').textContent = iq ? 'I/Q 복원 관측값 · 보조 항법정보' : role ? role+' GNSS 관측값' : 'GNSS 수집 데이터';
    container.querySelector('[data-observation-title]').textContent = iq ? '관측값 · I/Q 추적 (RAWX 원본 아님)' : role ? role+' 관측값 · RAWX (변환 전)' : '관측값 · RAWX';
    container.querySelector('[data-navigation-title]').textContent = iq ? '보조 항법정보 · GPS LNAV' : '항법정보 · SFRBX';
    container.querySelector('[data-navigation-caption]').textContent = iq ? data.assistance : '항법정보 · SFRBX · 수집된 전체 메시지';
    container.querySelector('[data-word-width]').textContent = iq ? 'HEX · 24 bit (패리티 제외)' : 'HEX · 32 bit';
    container.querySelector('[data-source]').textContent = iq ? 'PocketSDR AFS 추적' : data?.receiver?.receiverModel || (epoch ? 'GRAW 관측값' : '데이터 없음');
    container.querySelector('[data-nav]').textContent = '항법정보 ' + (data?.navigationCount ?? '—') + '건' +
      (epoch && data?.navigationCount === 0 ? ' · PVT 계산 불가' : '');
    container.querySelector('[data-count]').textContent = '관측 신호 ' + (epoch?.observations?.length ?? '—');
    container.querySelector('[data-status]').textContent = iq ? '위상은 상대 누적값 · F9T 편차·상태 정보 없음' : epoch ? '윤초 ' + epoch.leapSeconds + ' s · 수신기 상태 0x' + epoch.receiverStatus.toString(16) : '';
    if (notify) onSelect(Number(select.value), epoch);
  }
  select.onchange = () => render();
  return {
    setData(next, preserve = false) {
      const selected = preserve ? Number(select.value) : 0;
      data = next;
      const iq = data?.source === 'IQ_TRACKING';
      const navigationBody = container.querySelector('[data-navigation]');
      navigationBody.replaceChildren();
      for (const item of data?.navigation || []) {
        const row = document.createElement('tr');
        for (const value of navigationCells(item, iq)) {
          const cell = document.createElement('td'); cell.textContent = String(value ?? '—'); row.append(cell);
        }
        navigationBody.append(row);
      }
      if (!data?.navigation?.length) {
        const row = document.createElement('tr'), cell = document.createElement('td');
        row.className = 'epoch-empty-row'; cell.colSpan = 9; cell.textContent = '표시할 항법정보가 없습니다.';
        row.append(cell); navigationBody.append(row);
      }
      const details = container.querySelector('[data-record-details]');
      const renderRecords = () => {
        container.querySelector('[data-records]').textContent = details.open
          ? JSON.stringify(data?.records || [], null, 2) : '';
      };
      details.ontoggle = renderRecords;
      renderRecords();
      select.replaceChildren(...(data?.epochs?.length
        ? data.epochs.map((item, i) => new Option('Week ' + item.observation.week + ' / TOW ' + numeric(item.observation.receiverTowSeconds) + ' s', String(i)))
        : [new Option('GNSS 시간 없음', '')]));
      select.disabled = !data?.epochs?.length;
      if (data?.epochs?.length) select.value = String(Math.min(selected || 0, data.epochs.length - 1));
      render();
    },
    select(index) { if (data?.epochs?.[index]) { select.value = String(index); render(false); } }
  };
}
