// Shared DTN-only observation display. Device values are never inserted as HTML.
export const numeric = (value, digits = 3) =>
  typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : '—';
const constellation = id => ['GPS', 'SBAS', 'Galileo', 'BeiDou', 'IMES', 'QZSS', 'GLONASS', 'NavIC'][id] || ('GNSS ' + id);
export function navigationCells(item) {
  const n = item.message;
  return [item.sequence, item.capturedAt, constellation(n.constellationId), n.satelliteId,
    n.signalId, n.frequencyId, n.sfrbxVersion, n.words.length,
    n.words.map(word => Number(word).toString(16).toUpperCase().padStart(8, '0')).join(' ')];
}

export function observationCells(o) {
  const gnss = ['GPS', 'SBAS', 'Galileo', 'BeiDou', 'IMES', 'QZSS', 'GLONASS', 'NavIC'][o.constellationId] || ('GNSS ' + o.constellationId);
  const prValid = (o.trackingStatus & 1) !== 0;
  const cpValid = (o.trackingStatus & 2) !== 0;
  return [gnss, o.satelliteId, o.constellationId === 0 && o.signalId === 0 ? 'L1 C/A (0)' : 'ID ' + o.signalId,
    numeric(o.pseudorangeMeters), numeric(o.carrierPhaseCycles), numeric(o.dopplerHz),
    o.carrierToNoiseDbHz, o.lockTimeMilliseconds,
    [o.pseudorangeStdDev, o.carrierPhaseStdDev, o.dopplerStdDev].join(' / '),
    'PR ' + (prValid ? '유효' : '무효') + ' · CP ' + (cpValid ? '유효' : '무효'),
    o.constellationId === 0 && o.signalId === 0 && prValid ? '계산 대상' : '제외'];
}

export function createObservationView(container, onSelect = () => {}) {
  if (!container) return {setData() {}, select() {}};
  container.innerHTML = `
    <div class="gnss-data-header"><h2>GNSS 수집 데이터</h2>
      <label>GNSS 기준시간 <select data-epoch aria-label="관측 시점"></select></label>
    <div class="observation-summary"><span data-source>데이터 없음</span><span data-nav>항법정보 —</span>
      <span data-count>관측 신호 —</span><span data-status></span></div></div>
    <h3>관측값 · RAWX</h3>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 관측값 표">
      <table class="epoch-observation-table"><caption>위성·신호별 관측값</caption><thead><tr>
        <th>GNSS</th><th>위성</th><th>신호</th><th>의사거리 <small>m</small></th>
        <th>반송파 위상 <small>cycle</small></th><th>도플러 <small>Hz</small></th>
        <th>C/N₀ <small>dB-Hz</small></th><th>추적시간 <small>ms</small></th>
        <th title="수신기가 출력한 표준편차 코드. SI 단위의 표준편차가 아닙니다.">편차 코드 <small>PR / CP / DO</small></th>
        <th>측정 유효성</th><th title="GPS L1·의사거리 유효 조건. 최종 계산에서 사용한 위성 수는 PVT 결과에 표시됩니다.">PVT 입력</th>
      </tr></thead><tbody></tbody></table></div>
    <h3>항법정보 · SFRBX</h3>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 항법정보 표">
      <table class="epoch-observation-table"><caption>항법정보 · SFRBX · 수집된 전체 메시지</caption><thead><tr>
        <th>수집 순번</th><th>수집 시각 <small>UTC</small></th><th>GNSS</th><th>위성</th>
        <th>신호 ID</th><th>주파수 ID</th><th>버전</th><th>워드 수</th><th>수신 워드 <small>HEX · 32 bit</small></th>
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
      row.className = 'epoch-empty-row'; cell.colSpan = 11; cell.textContent = '표시할 GNSS 관측값이 없습니다.';
      row.append(cell); body.append(row);
    } else {
      for (const observation of epoch.observations) {
        const row = document.createElement('tr');
        for (const value of observationCells(observation)) {
          const cell = document.createElement('td'); cell.textContent = String(value ?? '—'); row.append(cell);
        }
        body.append(row);
      }
    }
    container.querySelector('[data-source]').textContent = data?.receiver?.receiverModel || (epoch ? 'GRAW 관측값' : '데이터 없음');
    container.querySelector('[data-nav]').textContent = '항법정보 ' + (data?.navigationCount ?? '—') + '건' +
      (epoch && data?.navigationCount === 0 ? ' · PVT 계산 불가' : '');
    container.querySelector('[data-count]').textContent = '관측 신호 ' + (epoch?.observations?.length ?? '—');
    container.querySelector('[data-status]').textContent = epoch ? '윤초 ' + epoch.leapSeconds + ' s · 수신기 상태 0x' + epoch.receiverStatus.toString(16) : '';
    if (notify) onSelect(Number(select.value), epoch);
  }
  select.onchange = () => render();
  return {
    setData(next, preserve = false) {
      const selected = preserve ? Number(select.value) : 0;
      data = next;
      const navigationBody = container.querySelector('[data-navigation]');
      navigationBody.replaceChildren();
      for (const item of data?.navigation || []) {
        const row = document.createElement('tr');
        for (const value of navigationCells(item)) {
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
