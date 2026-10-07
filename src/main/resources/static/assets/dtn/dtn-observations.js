// Shared DTN-only observation display. Device values are never inserted as HTML.
export const numeric = (value, digits = 3, missing = '—') =>
  typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : missing;
// Preserve tiny nonzero clock biases that fixed decimal places would hide.
export function clockBias(value, missing = '-') {
  if (!Number.isFinite(value)) return missing;
  if (value !== 0 && Math.abs(value) < 1e-9) {
    const [mantissa, exponent] = value.toExponential(6).split('e');
    return mantissa + ' × 10^' + Number(exponent);
  }
  return value.toFixed(9);
}

export function renderClockBias(element, value) {
  element.textContent = clockBias(value);
  element.title = Number.isFinite(value) ? String(value) + ' s' : '';
}

export function renderCnoCell(cell, cno) {
  cell.replaceChildren();
  if (!Number.isFinite(cno) || cno <= 0) {
    cell.textContent = '—';
    return;
  }
  const container = document.createElement('div');
  container.className = 'cno-indicator';
  const num = document.createElement('span');
  num.className = 'cno-num';
  num.textContent = String(cno);

  const meter = document.createElement('span');
  meter.className = 'cno-meter';
  const level = cno >= 40 ? 4 : cno >= 33 ? 3 : cno >= 26 ? 2 : 1;
  meter.classList.add('level-' + level);
  meter.title = 'C/N₀ ' + cno + ' dB-Hz (' + (level === 4 ? '최우수' : level === 3 ? '양호' : level === 2 ? '보통' : '불량') + ')';

  for (let i = 1; i <= 4; i++) {
    const bar = document.createElement('i');
    bar.className = 'bar b' + i;
    meter.append(bar);
  }
  container.append(num, meter);
  cell.append(container);
}

export const constellation = id => ['GPS', 'SBAS', 'Galileo', 'BeiDou', 'IMES', 'QZSS', 'GLONASS', 'NavIC'][id] || ('GNSS ' + id);

export function satelliteBadgeText(constellationId, satelliteId) {
  const pad = String(satelliteId).padStart(2, '0');
  switch (constellationId) {
    case 0: return 'GPS ' + pad;
    case 1: return 'SBAS ' + pad;
    case 2: return 'GAL ' + pad;
    case 3: return 'BDS ' + pad;
    case 5: return 'QZS ' + pad;
    case 6: return 'GLO ' + pad;
    default: return 'SAT ' + pad;
  }
}

export function constellationClass(constellationId) {
  switch (constellationId) {
    case 0: return 'constellation-badge-gps';
    case 1: return 'constellation-badge-sbas';
    case 2: return 'constellation-badge-gal';
    case 3: return 'constellation-badge-bds';
    case 5: return 'constellation-badge-qzss';
    case 6: return 'constellation-badge-glo';
    default: return 'constellation-badge-other';
  }
}
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

// RAWX lock counter saturates at 64500 ms; preserve missing values as missing.
export function lockTime(milliseconds) {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '—';
  if (milliseconds === 64500) return '64.5 이상 (64500 ms)';
  return ((milliseconds / 1000).toFixed(3).replace(/\.?0+$/, '') || '0') + ' (' + milliseconds + ' ms)';
}

export function unclassifiedNavigation(message) {
  if (!message) return '메시지 종류 확인 불가';
  if ([1, 2, 3, 4, 5, 6, 7].includes(message.constellationId)) {
    const name = constellation(message.constellationId);
    return '항법정보 수신 · ' + name;
  }
  if (message.constellationId === 0 && Number.isInteger(message.signalId) && message.signalId !== 0) {
    return '항법정보 수신 · GPS L1 C/A 전용 계산';
  }
  return '메시지 종류 확인 불가';
}

export function navigationLabel(item) {
  const header = item?.display;
  const msg = item?.message || item || {};
  const cid = msg.constellationId;
  const words = msg.words;
  const sig = msg.signalId;

  if (cid === 0) {
    if (header?.commonCorrection) return 'SF4 · 전리층·UTC 공통 보정';
    const sf = header?.subframeId;
    const labels = {1: '위성 시계·상태', 2: '궤도정보 ①', 3: '궤도정보 ②', 4: '보정·위성군 정보', 5: '위성군 정보'};
    if (labels[sf]) {
      return 'SF' + sf + ' · ' + labels[sf] + (header.pageId == null ? '' : ' · Page ' + header.pageId);
    }
    if (words && words.length === 10) {
      const parsedSf = (Number(words[1]) >>> 8) & 0x7;
      if (labels[parsedSf]) return 'SF' + parsedSf + ' · ' + labels[parsedSf];
    }
    if (Number.isInteger(sig) && sig !== 0) {
      return '항법정보 수신 · GPS L1 C/A 전용 계산';
    }
    return 'GPS LNAV 항법 메시지';
  }

  if (cid === 3) {
    if (words && words.length === 10) {
      const w0 = Number(words[0]);
      const frn = (w0 >>> 12) & 0x7;
      const bdsLabels = {
        1: 'SF1 · 위성 시계·상태 (BDS B1I)',
        2: 'SF2 · 궤도정보 ① (BDS B1I)',
        3: 'SF3 · 궤도정보 ② (BDS B1I)',
        4: 'SF4 · 전리층·보정·알마낙 (BDS B1I)',
        5: 'SF5 · 위성군 정보 (BDS B1I)'
      };
      if (bdsLabels[frn]) return bdsLabels[frn];
    }
    if (sig === 6) return 'BDS B1C CNAV1 · 보조 항법';
    if (sig === 8) return 'BDS B2a CNAV2 · 보조 항법';
    if (sig === 1) return 'BDS B1I D2 · GEO 항법';
    return 'BeiDou 항법 메시지' + (Number.isInteger(sig) ? ' (신호 ' + sig + ')' : '');
  }

  if (cid === 2) {
    if (words && words.length === 8) {
      const w0 = Number(words[0]);
      const type = (w0 >>> 24) & 0x3f;
      const galLabels = {
        0: 'Word 0 · 예비/동기 (GAL I/NAV)',
        1: 'Word 1 · 궤도정보 ① (GAL I/NAV)',
        2: 'Word 2 · 궤도정보 ② (GAL I/NAV)',
        3: 'Word 3 · 궤도정보 ③ & SVID (GAL I/NAV)',
        4: 'Word 4 · 궤도정보 ④ & 시계 (GAL I/NAV)',
        5: 'Word 5 · 전리층·UTC 보정 (GAL I/NAV)',
        6: 'Word 6 · GST-GPS 변환 (GAL I/NAV)',
        7: 'Word 7 · 알마낙 ① (GAL I/NAV)',
        8: 'Word 8 · 알마낙 ② (GAL I/NAV)',
        9: 'Word 9 · 알마낙 ③ (GAL I/NAV)',
        10: 'Word 10 · 알마낙 ④ (GAL I/NAV)'
      };
      if (galLabels[type]) return galLabels[type];
      return 'Word ' + type + ' · I/NAV 메시지 (GAL)';
    }
    if (sig === 5) return 'Galileo E5a F/NAV 항법 메시지';
    return 'Galileo 항법 메시지' + (Number.isInteger(sig) ? ' (신호 ' + sig + ')' : '');
  }

  if (cid === 5) {
    if (words && words.length === 10) {
      const qzssSf = (Number(words[1]) >>> 8) & 0x7;
      const labels = {1: '위성 시계·상태', 2: '궤도정보 ①', 3: '궤도정보 ②', 4: '보정·알마낙', 5: '위성군 정보'};
      if (labels[qzssSf]) return 'SF' + qzssSf + ' · ' + labels[qzssSf] + ' (QZSS)';
    }
    if (sig === 8) return 'QZSS L5 CNAV 항법 메시지';
    return 'QZSS 항법 메시지' + (Number.isInteger(sig) ? ' (신호 ' + sig + ')' : '');
  }

  if (cid === 6) return 'GLONASS 항법 스트링 (G1/G2)';
  if (cid === 1) return 'SBAS 보정 메시지 (L1 C/A)';

  return unclassifiedNavigation(msg);
}

export function isObservationEligible(o, targetConstellation = 'GPS') {
  const prValid = (o.trackingStatus & 1) !== 0;
  if (!prValid || !Number.isFinite(o.pseudorangeMeters) || o.pseudorangeMeters <= 0 || !Number.isFinite(o.dopplerHz)) {
    return false;
  }
  const target = String(targetConstellation || 'GPS').toUpperCase();
  if (target === 'GPS') {
    return o.constellationId === 0 && o.signalId === 0;
  }
  if (target === 'BEIDOU') {
    return o.constellationId === 3 && (o.signalId === 0 || o.signalId === 1);
  }
  if (target === 'GALILEO') {
    return o.constellationId === 2 && (o.signalId === 0 || o.signalId === 1);
  }
  if (target === 'ALL') {
    return (o.constellationId === 0 && o.signalId === 0)
      || (o.constellationId === 3 && (o.signalId === 0 || o.signalId === 1))
      || (o.constellationId === 2 && (o.signalId === 0 || o.signalId === 1));
  }
  return false;
}

export function observationCells(o, receiverTowSeconds, targetConstellation = 'GPS') {
  const iq = o.source === 'IQ_TRACKING';
  const gnss = constellation(o.constellationId);
  const prValid = (o.trackingStatus & 1) !== 0;
  const cpValid = (o.trackingStatus & 2) !== 0;
  const eligible = isObservationEligible(o, targetConstellation);
  return [gnss, o.satelliteId, iq ? 'AFS Data · L1' : o.constellationId === 0 && o.signalId === 0 ? 'L1 C/A (0)' : 'ID ' + o.signalId,
    numeric(o.pseudorangeMeters), numeric(o.carrierPhaseCycles), numeric(o.dopplerHz),
    o.carrierToNoiseDbHz, lockTime(o.lockTimeMilliseconds),
    iq ? '—' : ['PR ' + (o.pseudorangeStdDev ?? '—'), 'CP ' + (o.carrierPhaseStdDev ?? '—'), 'D ' + (o.dopplerStdDev ?? '—')].join(' / '),
    iq ? 'PR 유효 · 위상 상대값' : 'PR ' + (prValid ? '유효' : '무효') + ' · CP ' + (cpValid ? '유효' : '무효'),
    eligible ? '입력 대상' : '제외',
    transmitTime(o, receiverTowSeconds)];
}


// Basic input checks are separate from the solver's actual result.
export function pvtInputStatus(data, index = 0, results = [], evidence = null) {
  const epoch = data?.epochs?.[index]?.observation;
  if (!epoch) return {label: '입력 대기', level: '', reasons: []};
  if (evidence?.error) {
    return {label: '계산 불가', level: 'error', reasons: [evidence.error]};
  }
  const result = results[index];
  if (result?.positionValid) {
    return {label: result.velocityValid ? 'PVT 계산 완료' : '위치 계산 완료 · 속도 미확정',
      level: result.velocityValid ? 'online' : 'warning',
      reasons: ['실제 계산 결과 · 사용 위성 ' + (result.satellitesUsed ?? '—') + '개',
        ...(!result.velocityValid ? [result.message || '속도 해가 유효하지 않습니다.'] : [])]};
  }
  const reasons = [];
  const gps = (epoch.observations || []).filter(o => o.constellationId === 0 && o.signalId === 0);
  const valid = gps.filter(o => (o.trackingStatus & 1) && Number.isFinite(o.pseudorangeMeters) && o.pseudorangeMeters > 0);
  const satellites = [...new Set(valid.map(o => o.satelliteId))];
  if (satellites.length < 4) reasons.push('유효 GPS L1 의사거리 ' + satellites.length + '위성 · 위치 계산에는 최소 4위성이 필요합니다.');
  if (valid.some(o => !Number.isFinite(o.dopplerHz))) reasons.push('Doppler가 없는 관측이 있습니다. 속도 계산 입력을 확인하세요.');

  const iq = data.source === 'IQ_TRACKING';
  const frame = !!data.receivedValues && !data.receivedValues.records;
  const records = data.records || [];
  const epochPositions = records.flatMap((r, i) => r.type === 'OBSERVATION_EPOCH' ? [i] : []);
  const navPositions = records.flatMap((r, i) => r.type === 'NAVIGATION_UPDATE' ? [i] : []);
  const epochPosition = epochPositions[index];
  const nav = (data.navigation || []).filter((item, i) => {
    if (iq || frame) return true;
    return epochPosition != null && navPositions[i] != null && navPositions[i] < epochPosition;
  });
  if (!iq && !frame && epochPosition == null) {
    reasons.push('항법정보의 관측 이전 수신 여부를 확인할 수 없습니다.');
  } else {
    for (const satellite of satellites) {
      const messages = nav.filter(n => n.message.constellationId === 0 && n.message.satelliteId === satellite);
      const missing = [1, 2, 3].filter(sf => !messages.some(n => n.display?.subframeId === sf));
      if (missing.length) reasons.push('GPS G' + String(satellite).padStart(2, '0') + ': '
        + missing.map(sf => 'SF' + sf).join('·') + ' 미확인'
        + (iq || frame ? '' : ' (관측 이전 기준)'));
    }
  }
  if (result && !result.positionValid) {
    reasons.unshift(result.message || '계산기가 유효한 위치 해를 구하지 못했습니다.');
    return {label: 'PVT 계산 불가', level: 'warning', reasons};
  }
  return {label: reasons.length ? '입력 확인 필요' : '계산 결과 대기',
    level: reasons.length ? 'warning' : '', reasons: reasons.length ? reasons
      : ['기본 관측·항법 메시지를 확인했습니다. 궤도 유효성·정합성과 실제 사용 위성은 계산 결과로 확인합니다.']};
}


export function receiverInformation(data) {
  if (!data) return '';
  const info = data.receiverInfo || {};
  const epochs = data.epochs || [];
  const times = epochs.map(e => e.observation).filter(e => Number.isInteger(e.week)
    && Number.isFinite(e.receiverTowSeconds) && e.receiverTowSeconds >= 0 && e.receiverTowSeconds < 604800)
    .map(e => e.week * 604800 + e.receiverTowSeconds);
  const span = times.length === epochs.length && times.length
    ? numeric(Math.max(...times) - Math.min(...times), 3) + '초 (첫 관측~마지막 관측)' : '확인 불가';
  return [
    '모델  ' + (info.model || data.receiver?.receiverModel || '미기록'),
    '펌웨어  ' + (info.firmware || data.receiver?.firmwareVersion || '미기록'),
    '프로토콜  ' + (info.protocol || '미기록'),
    '지원 위성군  ' + (info.supportedConstellations?.join(' · ') || '미기록'),
    '관측 Epoch  ' + epochs.length + '개',
    '관측 구간  ' + span,
    '지원 위성군은 장비 정보이며 현재 관측·PVT 계산 대상과 다릅니다.'
  ].join('\n');
}

function precedingNavigation(data, index) {
  const restored = data?.source === 'IQ_TRACKING' || !!data?.receivedValues && !data.receivedValues.records;
  const records = data?.records || [];
  const epochs = records.flatMap((r, i) => r.type === 'OBSERVATION_EPOCH' ? [i] : []);
  const nav = records.flatMap((r, i) => r.type === 'NAVIGATION_UPDATE' ? [i] : []);
  if (!restored && epochs[index] == null) return null;
  return (data.navigation || []).filter((_, i) => restored || nav[i] != null && nav[i] < epochs[index]);
}

export function observationCounts(data, index = 0, results = [], evidence = null, targetConstellation = 'GPS') {
  const epoch = data?.epochs?.[index]?.observation;
  const wire = data?.receivedValues;
  const displayed = wire?.records?.find(r => r.observation)?.observation?.observations
    || wire?.observations || epoch?.observations || [];
  const eligible = evidence?.error ? [] : (epoch?.observations || []).filter(o =>
    isObservationEligible(o, targetConstellation));
  const target = String(targetConstellation || 'GPS').toUpperCase();
  const matching = new Set(displayed.filter(o => isObservationEligible(o, targetConstellation)).map(o => o.constellationId + ':' + o.satelliteId));
  const nav = precedingNavigation(data, index);
  const complete = (nav == null || target !== 'GPS') ? null : [...matching].filter(sv => {
    const prn = Number(sv.split(':')[1]);
    return [1, 2, 3].every(sf =>
      nav.some(n => n.message.constellationId === 0 && n.message.satelliteId === prn && n.display?.subframeId === sf));
  }).length;
  const result = results[index];
  return {
    satellites: new Set(displayed.map(o => o.constellationId + ':' + o.satelliteId)).size,
    signals: displayed.length,
    gps: matching.size,
    eligible: new Set(eligible.map(o => o.constellationId + ':' + o.satelliteId)).size,

    complete,
    used: result?.positionValid && Number.isInteger(result.satellitesUsed) ? result.satellitesUsed : null
  };
}

export function createObservationView(container, onSelect = () => {}, role = '') {
  if (!container) return {setData() {}, select() {}, setPvt() {}};
  container.innerHTML = `
    <div class="gnss-data-header"><h2 data-title>GNSS 수집 데이터</h2>
      <label>GNSS 기준시간 <select data-epoch aria-label="관측 시점"></select></label>
      <span data-integrity class="pill" role="status" hidden></span>

      <details class="pvt-input-status" data-pvt-status>
        <summary class="pill" data-pvt-status-label>계산 상태 · 입력 대기</summary>
        <ul data-pvt-status-reasons></ul>
      </details>
    <div class="observation-summary"><span data-source>데이터 없음</span><span data-nav>항법정보 —</span>
      <span data-count>관측 신호 —</span><span data-pvt-counts title="입력 조건·메시지 보유·계산기 채택은 서로 다른 지표입니다. SF1~3 확인은 유효한 Ephemeris 보장이 아닙니다."></span><span data-status></span></div>
    ${role === '송신 원본' ? `
      <details id="dtn-receiver-info" class="receiver-file-info" hidden>
        <summary>수신기 정보</summary>
        <pre id="dtn-receiver-info-body"></pre>
      </details>` : ''}</div>
    <div class="observation-header-row">
      <h3 data-observation-title>관측값 · RAWX</h3>
      <div class="multi-gnss-summary-bar" data-constellation-summary hidden></div>
      <label class="pvt-filter-toggle"><input type="checkbox" data-pvt-filter> <span>PVT 계산 위성만 보기</span></label>
    </div>
    <p data-delay-summary hidden></p>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 관측값 표">
      <table class="epoch-observation-table"><caption>위성·신호별 관측값</caption><thead><tr>
        <th>GNSS</th><th>위성</th><th>신호</th><th data-range-heading>의사거리 <small>m</small></th>
        <th data-range-after hidden>변환 후 의사거리 <small>m</small></th>
        <th data-range-added hidden>증가량 <small>m</small></th>
        <th>반송파 위상 <small>cycle</small></th><th>도플러 <small>Hz</small></th>
        <th>C/N₀ <small>dB-Hz</small></th><th title="반송파 위상 추적 유지시간. RAWX 카운터가 64500 ms이면 64.5초 이상입니다.">추적 유지 <small>초</small></th>
        <th title="PR: 의사거리, CP: 반송파 위상, D: Doppler. 단위·환산 방식이 서로 다른 수신기 코드이며 공통 오차등급이 아닙니다.">표준편차 코드 <small>PR / CP / D</small></th>
        <th>측정 유효성</th><th title="GPS L1·유효 의사거리·Doppler의 입력 조건입니다. 실제 계산 채택 여부는 아니며, 최종 사용 위성 수는 PVT 결과에 표시됩니다.">PVT 입력</th>
        <th data-transmit-heading title="관측 수신 시각 − 의사거리 / 299,792,458. 위성 시계·시스템 간 시간 보정 전 추정값이며 실제 정확도를 의미하지 않습니다. 주 경계를 넘으면 이전 주로 표시합니다.">위성 송신 시각 추정 <small>TOW(s) · 보정 전</small></th>
      </tr></thead><tbody data-observations></tbody></table></div>
    <details data-frame-input hidden>
      <summary>AFS 프레임에서 복원한 PVT 계산 입력 · 지연 적용 전</summary>
      <p data-frame-summary></p>
      <div class="epoch-table-viewport" tabindex="0" aria-label="AFS 계산 입력 표">
        <table class="epoch-observation-table"><thead><tr>
          <th>위성</th><th>GNSS Week</th><th>원본 TOW (s)</th><th>원본 의사거리 (m)</th>
          <th>Doppler (Hz)</th><th>C/N₀ (dB-Hz)</th>
        </tr></thead><tbody data-frame-values></tbody></table>
      </div>
    </details>
    <div class="navigation-heading"><h3 data-navigation-title>항법정보 · SFRBX</h3>
      <div data-association hidden><button type="button" data-navigation-all>전체 보기</button>
        <span data-navigation-summary></span></div></div>
    <div class="epoch-table-viewport" tabindex="0" aria-label="GNSS 항법정보 표">
      <table class="epoch-observation-table" data-navigation-table><caption data-navigation-caption>항법정보 · SFRBX · 수집된 전체 메시지</caption><thead><tr data-navigation-head>
        <th title="원본 레코드의 수집 순번이며 총 건수가 아닙니다. 0~95는 96건입니다.">수집 순번</th><th>수집 시각 <small>UTC</small></th><th>GNSS</th><th>위성</th>
        <th>신호 ID</th><th>주파수 ID</th><th>버전</th><th>워드 수</th><th>수신 워드 <small data-word-width>HEX · 32 bit</small></th>
      </tr></thead><tbody data-navigation></tbody></table></div>
    <details data-common-navigation hidden><summary data-common-title></summary>
      <div class="epoch-table-viewport"><table class="epoch-observation-table navigation-linked">
        <thead><tr><th>방송 위성</th><th>내용</th><th>관측 기준</th><th>원문</th></tr></thead>
        <tbody data-common-body></tbody></table></div></details>
    <details data-record-details><summary>저장된 전체 필드 보기 · JSON</summary><pre data-records class="log"></pre></details>
    `;
  const hideTransmitEstimate = role === '송신 원본' || role === '송신 비교원본';
  container.querySelector('[data-transmit-heading]').hidden = hideTransmitEstimate;
  const select = container.querySelector('[data-epoch]');
  const body = container.querySelector('[data-observations]');
  const filterCheckbox = container.querySelector('[data-pvt-filter]');
  const observationTable = container.querySelector('.epoch-observation-table');
  function applyPvtFilter() {
    const isFiltered = !!filterCheckbox?.checked;
    if (observationTable) {
      if (observationTable.classList?.toggle) {
        observationTable.classList.toggle('pvt-filter-active', isFiltered);
      }
    }
    const rows = body?.children ? [...body.children] : [];
    for (const tr of rows) {
      const isExcluded = tr.classList?.contains ? tr.classList.contains('pvt-excluded-row')
        : (tr.className || '').includes('pvt-excluded-row');
      if (isExcluded) {
        tr.hidden = isFiltered;
        if (tr.style) tr.style.display = isFiltered ? 'none' : '';
      } else {
        tr.hidden = false;
        if (tr.style) tr.style.display = '';
      }
    }
  }
  if (filterCheckbox) {
    filterCheckbox.onchange = applyPvtFilter;
    filterCheckbox.oninput = applyPvtFilter;
  }
  let data = null, delayEvidence = null, report = null, pvtResults = [];

  const linkedNavigation = role === '수신 원본' || hideTransmitEstimate;
  let selectedSatellite = null, showAllNavigation = true;
  const satelliteKey = observation => observation.constellationId + ':' + observation.satelliteId;
  const satelliteName = observation => constellation(observation.constellationId) + ' '
    + (observation.constellationId === 0 ? 'G' + String(observation.satelliteId).padStart(2, '0') : observation.satelliteId);
  const ordered = observations => {
    const rows = (observations || []).map((observation, index) => ({observation, index}));
    return linkedNavigation ? rows.sort((a, b) =>
      a.observation.constellationId - b.observation.constellationId
      || a.observation.satelliteId - b.observation.satelliteId
      || a.observation.signalId - b.observation.signalId) : rows;
  };
  const association = container.querySelector('[data-association]');
  association.hidden = !linkedNavigation;
  const allNavigation = container.querySelector('[data-navigation-all]');
  allNavigation.onclick = () => {
    showAllNavigation = true;
    render(false);
  };

  function selectSatellite(observations) {
    if (!linkedNavigation) return;
    if (!observations.some(o => satelliteKey(o) === selectedSatellite)) {
      const first = observations.find(o => o.constellationId === 0 && o.signalId === 0) || observations[0];
      selectedSatellite = first ? satelliteKey(first) : null;
    }
  }

  function linkSatellite(row, observation) {
    const cell = row.children[1];
    const cid = observation.constellationId;
    const sid = observation.satelliteId;
    const badgeText = satelliteBadgeText(cid, sid);
    const badgeClass = constellationClass(cid);

    if (!linkedNavigation) {
      const badge = document.createElement('span');
      badge.className = 'satellite-badge constellation-badge ' + badgeClass;
      badge.textContent = badgeText;
      badge.title = satelliteName(observation);
      cell.replaceChildren(badge);
      return;
    }

    const selected = !showAllNavigation && satelliteKey(observation) === selectedSatellite;
    if (row.classList?.toggle) {
      row.classList.toggle('satellite-selected', selected);
    } else {
      row.className = (row.className || '').replace(/\bsatellite-selected\b/g, '').trim();
      if (selected) row.className = (row.className ? row.className + ' ' : '') + 'satellite-selected';
    }
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'satellite-select constellation-badge ' + badgeClass;
    button.textContent = badgeText;
    button.title = satelliteName(observation) + ' · 클릭하면 이 위성의 항법정보 표시';
    button.setAttribute('aria-pressed', String(selected));
    button.setAttribute('aria-label', satelliteName(observation) + ' 항법정보 보기');
    button.onclick = () => {
      selectedSatellite = satelliteKey(observation);
      showAllNavigation = false;
      const rowIndex = [...body.children].indexOf(row);
      render(false);
      // Keep focus on the same signal row when a satellite has multiple signals.
      body.children[rowIndex]?.querySelector('.satellite-select')?.focus();
    };
    cell.replaceChildren(button);
  }

  function renderAssociation(observations) {
    renderPvtStatus();
    if (!linkedNavigation) return;
    const navigation = data?.navigation || [];
    const iq = data?.source === 'IQ_TRACKING';
    const frame = !!data?.receivedValues && !data.receivedValues.records;
    const selected = observations.find(o => satelliteKey(o) === selectedSatellite);
    const matching = navigation.filter(item => satelliteKey(item.message) === selectedSatellite);
    const shown = !selected || showAllNavigation ? navigation : matching;
    container.querySelector('[data-navigation-title]').textContent =
      selected && !showAllNavigation ? satelliteName(selected) + '에서 받은 항법정보 · ' + shown.length + '건'
        : '전체 항법정보 · ' + shown.length + '건';
    allNavigation.textContent = '전체 보기';
    allNavigation.hidden = showAllNavigation || !selected;
    allNavigation.disabled = !selected;
    allNavigation.setAttribute('aria-pressed', String(showAllNavigation));
    const summary = container.querySelector('[data-navigation-summary]');
    if (selected && !showAllNavigation) {
      if (selected.constellationId === 0) {
        const counts = [1, 2, 3].map(sf => 'SF' + sf + ' ' + matching.filter(n => n.display?.subframeId === sf).length + '건');
        summary.textContent = counts.join(' · ') + ' · GPS LNAV 메시지 보유 현황';
        summary.title = 'SF1~3이 모두 있어도 시각·궤도 유효성과 PVT 채택은 별도입니다.';
      } else if (selected.constellationId === 3) {
        const bdsSf = sf => matching.filter(n => {
          const w = n.message?.words;
          return w && w.length === 10 && ((Number(w[0]) >>> 12) & 0x7) === sf;
        }).length;
        const counts = [1, 2, 3, 4, 5].map(sf => 'SF' + sf + ' ' + bdsSf(sf) + '건');
        summary.textContent = counts.join(' · ') + ' · BDS B1I 메시지 보유 현황';
        summary.title = 'BDS B1I SF1~3(시계 및 궤도) 수신 현황입니다. 실제 계산 성공 여부는 PVT 상태를 확인하세요.';
      } else if (selected.constellationId === 2) {
        const galType = type => matching.filter(n => {
          const w = n.message?.words;
          return w && w.length === 8 && ((Number(w[0]) >>> 24) & 0x3f) === type;
        }).length;
        const counts = [1, 2, 3, 4, 5].map(t => 'W' + t + ' ' + galType(t) + '건');
        summary.textContent = counts.join(' · ') + ' · GAL I/NAV 메시지 보유 현황';
        summary.title = 'Galileo Word 1~4(궤도·시계) 및 Word 5(보정) 수신 현황입니다. Word 1~4가 모두 있어야 궤도가 복원됩니다.';
      } else if (selected.constellationId === 5) {
        const qzssSf = sf => matching.filter(n => {
          const w = n.message?.words;
          return w && w.length === 10 && ((Number(w[1]) >>> 8) & 0x7) === sf;
        }).length;
        const counts = [1, 2, 3].map(sf => 'SF' + sf + ' ' + qzssSf(sf) + '건');
        summary.textContent = counts.join(' · ') + ' · QZSS L1 메시지 보유 현황';
        summary.title = 'QZSS L1 SF1~3 수신 현황입니다.';
      } else {
        summary.textContent = '수신 항법 메시지 ' + matching.length + '건';
        summary.title = '';
      }
    } else {
      summary.textContent = '';
      summary.title = '';
    }
    container.querySelector('[data-navigation-caption]').textContent = iq ? (data.assistance || 'I/Q 복호 항법정보')
      : frame ? 'AFS 프레임 복원 · 원본 수집 순번·시각 없음' : '원본 레코드 순서 기준 · 관측 이후 메시지는 해당 시점 계산과 구분';

    // Raw processing order, not UTC or a sorted sequence number, determines availability.
    const records = data?.records || [];
    const epochPositions = [], navigationPositions = [];
    records.forEach((record, index) => {
      if (record.type === 'OBSERVATION_EPOCH') epochPositions.push(index);
      if (record.type === 'NAVIGATION_UPDATE') navigationPositions.push(index);
    });
    const epochPosition = epochPositions[Number(select.value)];
    const navigationBody = container.querySelector('[data-navigation]');
    const headings = container.querySelector('[data-navigation-head]');
    headings.innerHTML = '<th>방송 위성</th><th>내용</th><th>관측 기준</th><th>원문</th>';
    container.querySelector('[data-navigation-table]').classList.add('navigation-linked');
    navigationBody.replaceChildren();

    function appendMessage(target, item) {
      const row = document.createElement('tr');
      const label = navigationLabel(item);
      const position = navigationPositions[navigation.indexOf(item)];
      const timing = frame ? '프레임 복원' : iq ? '복호·보조 정보'
        : position == null || epochPosition == null ? '순서 확인 불가'
        : position < epochPosition ? '선택 Epoch 이전 수신' : '선택 Epoch 이후 수신';
      const cellSat = document.createElement('td');
      const badge = document.createElement('span');
      const cid = item.message.constellationId;
      const sid = item.message.satelliteId;
      badge.className = 'satellite-badge constellation-badge ' + constellationClass(cid);
      badge.textContent = satelliteBadgeText(cid, sid);
      badge.title = satelliteName(item.message);
      cellSat.append(badge);
      row.append(cellSat);
      for (const text of [label, timing]) {
        const cell = document.createElement('td');
        cell.textContent = text;
        row.append(cell);
      }
      const cell = document.createElement('td');
      const content = document.createElement('pre');
      const values = navigationCells(item, iq);
      content.textContent = (frame || iq ? '' : '수집 순번 ' + (item.sequence ?? '—')
        + ' · ' + (item.capturedAt ?? '시각 없음') + ' · 신호 ID ' + (item.message.signalId ?? '—')
        + ' · 주파수 ID ' + (item.message.frequencyId ?? '—') + ' · 버전 ' + (item.message.sfrbxVersion ?? '—') + ' · ')
        + values[7] + ' words · HEX ' + (iq ? '24 bit (패리티 제외)' : '32 bit') + '\n' + values[8];
      cell.append(content); row.append(cell); target.append(row);
    }
    shown.forEach(item => appendMessage(navigationBody, item));
    if (!shown.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      cell.colSpan = 4;
      cell.textContent = selected ? '이 위성에서 받은 항법 메시지가 없습니다.' : '표시할 항법정보가 없습니다.';
      row.append(cell); navigationBody.append(row);
    }
    const common = navigation.filter(item => item.display?.commonCorrection && !shown.includes(item));
    const commonDetails = container.querySelector('[data-common-navigation]');
    commonDetails.hidden = !common.length;
    container.querySelector('[data-common-title]').textContent = '다른 위성에서 받은 공통 보정 · ' + common.length + '건';
    const commonBody = container.querySelector('[data-common-body]');
    commonBody.replaceChildren();
    common.forEach(item => appendMessage(commonBody, item));
  }

  function activeConstellation() {
    const el = typeof document !== 'undefined' && typeof document?.getElementById === 'function'
      ? document.getElementById('dtn-pvt-constellation') : null;
    return report?.pvtConstellation || el?.value || 'GPS';
  }


  function renderPvtStatus() {
    const activeSys = activeConstellation();
    const state = pvtInputStatus(data, Number(select.value) || 0, pvtResults,
      role === '수신 원본' ? delayEvidence : null);
    const counts = observationCounts(data, Number(select.value) || 0, pvtResults,
      role === '수신 원본' ? delayEvidence : null, activeSys);
    container.querySelector('[data-count]').textContent = data
      ? '관측 ' + counts.satellites + '위성 · ' + counts.signals + '신호' : '관측 신호 —';
    const activeLabel = {GPS: 'GPS L1', BEIDOU: 'BeiDou B1I', GALILEO: 'Galileo E1', ALL: 'Multi-GNSS'}[activeSys] || activeSys;
    container.querySelector('[data-pvt-counts]').textContent = data
      ? activeLabel + ' ' + counts.gps + ' · 입력 대상 ' + counts.eligible

        + (counts.complete != null ? ' · SF1~3 확인 ' + counts.complete : '')
        + ' · 실제 사용 ' + (counts.used ?? '—') : '';
    const label = container.querySelector('[data-pvt-status-label]');
    label.textContent = '계산 상태 · ' + state.label;
    label.className = 'pill ' + state.level;
    const reasons = container.querySelector('[data-pvt-status-reasons]');
    reasons.replaceChildren();
    const messages = [...state.reasons];
    if (role === '수신 원본' && report?.clockWarning) messages.push(report.clockWarning);
    for (const reason of messages) {
      const item = document.createElement('li');
      item.textContent = reason;
      reasons.append(item);
    }
    container.querySelector('[data-pvt-status]').hidden = !data;
  }

  function render(notify = true) {
    if (data?.receivedValues) { renderWire(notify); return; }
    container.querySelector('[data-integrity]').hidden = true;
    const item = data?.epochs?.[Number(select.value)];
    const epoch = item?.observation;
    const comparison = !data?.receivedValues && !!delayEvidence && role === '수신 원본' && data?.source !== 'IQ_TRACKING';
    const epochMatches = !!epoch && !!delayEvidence?.shiftedTime && !delayEvidence?.error && epoch.week === delayEvidence?.originalTime?.week
      && epoch?.receiverTowSeconds === delayEvidence?.originalTime?.towSeconds;
    container.querySelector('[data-range-heading]').textContent = comparison ? '원본 의사거리 (m)' : '의사거리 (m)';
    container.querySelector('[data-range-after]').hidden = !comparison;
    container.querySelector('[data-range-added]').hidden = !comparison;
    const summary = container.querySelector('[data-delay-summary]');
    summary.hidden = !comparison;
    summary.textContent = comparison ? (epochMatches
      ? 'GNSS 관측 시각: Week ' + epoch.week + ' / TOW ' + numeric(epoch.receiverTowSeconds, 9)
        + ' → Week ' + (delayEvidence.shiftedTime?.week ?? '—') + ' / TOW ' + numeric(delayEvidence.shiftedTime?.towSeconds, 9)
        + ' s · Doppler·C/N₀·반송파·항법정보 원본 유지'
      : '변환 후 값 표시 불가 · ' + (delayEvidence.error || '원본 Epoch와 계산 근거 불일치')) : '';
    summary.title = '변환 후 의사거리 = 원본 의사거리 + 299,792,458 × 측정 지연(초). 표시값은 저장된 계산 근거이며 원문과 JSON 다운로드는 실제 수신 원본 그대로 유지됩니다. 최종 채택 위성 수는 PVT 결과에서 확인하세요.';
    selectSatellite(epoch?.observations || []);
    const constellationSummaryEl = container.querySelector('[data-constellation-summary]');
    if (constellationSummaryEl) {
      if (!epoch || !epoch.observations?.length) {
        constellationSummaryEl.hidden = true;
      } else {
        const counts = {};
        for (const obs of epoch.observations) {
          const cid = obs.constellationId;
          if (!counts[cid]) counts[cid] = new Set();
          counts[cid].add(obs.satelliteId);
        }
        constellationSummaryEl.replaceChildren();
        const configMap = [
          { id: 0, key: 'gps', label: 'GPS' },
          { id: 3, key: 'bds', label: 'BDS' },
          { id: 2, key: 'gal', label: 'GAL' },
          { id: 5, key: 'qzss', label: 'QZS' },
          { id: 6, key: 'glo', label: 'GLO' }
        ];
        let hasAny = false;
        for (const conf of configMap) {
          if (counts[conf.id] && counts[conf.id].size > 0) {
            hasAny = true;
            const chip = document.createElement('span');
            chip.className = 'constellation-chip chip-' + conf.key;
            chip.textContent = conf.label + ': ' + counts[conf.id].size;
            chip.title = conf.label + ' 위성 ' + counts[conf.id].size + '개 수신됨';
            constellationSummaryEl.append(chip);
          }
        }
        for (const [cidStr, set] of Object.entries(counts)) {
          const cid = Number(cidStr);
          if (!configMap.some(c => c.id === cid) && set.size > 0) {
            hasAny = true;
            const chip = document.createElement('span');
            chip.className = 'constellation-chip chip-other';
            chip.textContent = constellation(cid) + ': ' + set.size;
            constellationSummaryEl.append(chip);
          }
        }
        constellationSummaryEl.hidden = !hasAny;
      }
    }
    body.replaceChildren();
    if (!epoch || !epoch.observations?.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      row.className = 'epoch-empty-row'; cell.colSpan = (comparison ? 14 : 12) - (hideTransmitEstimate ? 1 : 0); cell.textContent = epoch ? 'RAWX 메시지는 수신했지만 관측 신호가 0개입니다. 안테나와 위성 추적 상태를 확인하세요.' : '표시할 GNSS 관측값이 없습니다.';
      row.append(cell); body.append(row);
    } else {
      for (const {index, observation} of ordered(epoch.observations)) {
        const row = document.createElement('tr');
        const isEligible = isObservationEligible(observation, activeConstellation());
        if (row.classList?.toggle) {
          row.classList.toggle('pvt-eligible-row', isEligible);
          row.classList.toggle('pvt-excluded-row', !isEligible);
        } else {
          row.className = isEligible ? 'pvt-eligible-row' : 'pvt-excluded-row';
        }
        const values = observationCells(observation, epoch.receiverTowSeconds, activeConstellation());
        if (hideTransmitEstimate) values.pop();
        if (comparison) {
          // 순서·위성·신호·원본 값을 대조하여 다른 관측의 변환값을 표시하지 않는다.
          const satellite = epochMatches ? delayEvidence.satellites?.[index] : null;
          const matches = satellite && satellite.constellationId === observation.constellationId
            && satellite.satelliteId === observation.satelliteId && satellite.signalId === observation.signalId
            && satellite.originalMeters === observation.pseudorangeMeters;
          const converted = matches ? satellite.recalculatedMeters : null;
          values.splice(4, 0, numeric(converted), numeric(Number.isFinite(converted) ? delayEvidence.addedMeters : null));
        }
        const cnoColIndex = comparison ? 8 : 6;
        const pvtColIndex = comparison ? 12 : 10;
        values.forEach((value, colIndex) => {
          const cell = document.createElement('td');
          if (colIndex === cnoColIndex) {
            renderCnoCell(cell, observation.carrierToNoiseDbHz);
          } else if (colIndex === pvtColIndex) {
            if (value === '입력 대상') {
              cell.innerHTML = '<span class="pill online pvt-input-badge">입력 대상</span>';
            } else {
              cell.innerHTML = '<span class="pvt-excluded-badge">' + value + '</span>';
            }
          } else {
            cell.textContent = String(value ?? '—');
          }
          row.append(cell);
        });
        linkSatellite(row, observation);
        body.append(row);
      }
    }
    const frameInput = data?.frameInput;
    container.querySelector('[data-frame-input]').hidden = !frameInput;
    const frameBody = container.querySelector('[data-frame-values]');
    frameBody.replaceChildren();
    if (frameInput) {
      container.querySelector('[data-frame-summary]').textContent = frameInput.frameCount
        + ' frames · SB2·SB3 항법정보 + SB4 관측값 · 원본 메타데이터 대조 통과 · 위 RAWX 표는 원본 보존값';
      const frameEpoch = frameInput.epochs?.[Number(select.value)]?.observation;
      for (const observation of frameEpoch?.observations || []) {
        const row = document.createElement('tr');
        for (const value of [observation.satelliteId, frameEpoch.week, numeric(frameEpoch.receiverTowSeconds, 9),
          numeric(observation.pseudorangeMeters, 6), numeric(observation.dopplerHz, 6), observation.carrierToNoiseDbHz]) {
          const cell = document.createElement('td'); cell.textContent = String(value); row.append(cell);
        }
        frameBody.append(row);
      }
    }
    const iq = data?.source === 'IQ_TRACKING';
    const frameNavigation = iq && data?.assistance?.startsWith('AFS SB2');
    container.querySelector('[data-title]').textContent = iq ? (frameNavigation ? 'I/Q 복원 관측값 · 프레임 항법정보' : 'I/Q 복원 관측값 · 보조 항법정보') : role ? role+' GNSS 관측값' : 'GNSS 수집 데이터';
    container.querySelector('[data-observation-title]').textContent = comparison ? '수신 관측값 · RAWX 원본 / 지연 변환 후' : iq ? '관측값 · I/Q 추적 (RAWX 원본 아님)' : role ? role+' 관측값 · RAWX (변환 전)' : '관측값 · RAWX';
    container.querySelector('[data-navigation-title]').textContent = iq ? (frameNavigation ? '프레임 복원 항법정보 · GPS LNAV' : '보조 항법정보 · GPS LNAV') : '항법정보 · SFRBX';
    container.querySelector('[data-navigation-caption]').textContent = iq ? data.assistance : '항법정보 · SFRBX · 수집된 전체 메시지';
    const wordWidth = container.querySelector('[data-word-width]');
    if (wordWidth) wordWidth.textContent = iq ? 'HEX · 24 bit (패리티 제외)' : 'HEX · 32 bit';
    container.querySelector('[data-source]').textContent = iq ? 'PocketSDR AFS 추적' : data?.receiver?.receiverModel || (epoch ? 'GRAW 관측값' : '데이터 없음');
    container.querySelector('[data-nav]').textContent = '항법정보 ' + (data?.navigationCount ?? '—') + '건' +
      (epoch && data?.navigationCount === 0 ? ' · PVT 계산 불가' : '');
    container.querySelector('[data-count]').textContent = '관측 신호 ' + (epoch?.observations?.length ?? '—');
    container.querySelector('[data-status]').textContent = iq ? '위상은 상대 누적값 · F9T 편차·상태 정보 없음' : epoch ? 'RAWX v' + (epoch.rawxVersion ?? '—') + ' · 윤초 ' + ((epoch.receiverStatus & 1) ? epoch.leapSeconds + ' s' : '미확정') + ((epoch.receiverStatus & 2) ? ' · 수신기 시계 재설정' : '') + ' · 수신기 상태 0x' + epoch.receiverStatus.toString(16) : '';

    renderAssociation(epoch?.observations || []);
    applyPvtFilter();
    if (notify) onSelect(Number(select.value), epoch);
  }

  function renderWire(notify) {
    const wire = data.receivedValues;
    const rawEpoch = wire.records?.find(record => record.observation)?.observation;
    const values = rawEpoch?.observations || wire.observations || [];
    const week = rawEpoch?.week ?? values[0]?.week;
    const tow = rawEpoch?.receiverTowSeconds ?? values[0]?.towSeconds;
    const reference = report?.referenceObservations?.epochs?.find(item =>
      item.observation.week === week && item.observation.receiverTowSeconds === tow)?.observation;
    const calculated = data.epochs?.[Number(select.value)]?.observation;
    const matchSignal = (a, b) => a.constellationId === b.constellationId
      && a.satelliteId === b.satelliteId && a.signalId === b.signalId;
    const status = report?.referenceStatus;
    const integrity = container.querySelector('[data-integrity]');
    integrity.hidden = false;
    integrity.textContent = status === 'COMPLETE' ? '데이터 일치'
      : status === 'MISMATCH' ? '데이터 불일치'
      : status === 'UNAVAILABLE' ? '비교 불가' : '비교 대기';
    integrity.className = 'pill ' + (status === 'COMPLETE' ? 'online' : status === 'MISMATCH' ? 'error' : 'warning');
    integrity.title = '송신 시 의도한 변환·제외를 적용한 뒤 전달 대상 데이터만 대조합니다. 원본 의사거리와 Reference는 별도 조회하며 수신 PVT 계산에 사용하지 않습니다.';
    container.querySelector('[data-title]').textContent = '수신 원본 GNSS 관측값';
    container.querySelector('[data-observation-title]').textContent = '관측값 · RAWX';
    container.querySelector('[data-range-heading]').textContent = '원본 의사거리 (m)';
    container.querySelector('[data-range-heading]').title = '계산 후 송신 서비스에서 별도로 조회한 원본입니다. 조회 전에는 표시하지 않습니다.';
    container.querySelector('[data-range-after]').hidden = false;
    container.querySelector('[data-range-after]').classList.add('converted-range');
    container.querySelector('[data-range-added]').hidden = true;
    container.querySelector('[data-transmit-heading]').textContent = '보정 송신 시각 (Unix s)';
    container.querySelector('[data-transmit-heading]').classList.add('converted-value');
    container.querySelector('[data-transmit-heading]').title = '송신부가 원본 의사거리에서 역산하고 시험 시작 시각에 맞춰 보정한 가상 송신 시각입니다.';
    container.querySelector('[data-delay-summary]').hidden = true;
    container.querySelector('[data-frame-input]').hidden = true;
    container.querySelector('[data-source]').textContent = rawEpoch ? '수신 RAW JSON' : 'AFS 프레임 복원';
    container.querySelector('[data-nav]').textContent = '항법정보 ' + (data.navigationCount ?? 0) + '건';
    container.querySelector('[data-count]').textContent = '관측 신호 ' + values.length;
    container.querySelector('[data-status]').textContent = '';
    container.querySelector('[data-navigation-title]').textContent = '수신 항법정보 · SFRBX';
    container.querySelector('[data-navigation-caption]').textContent = rawEpoch ? '전달된 항법 메시지' : 'SB2·SB3·SB4에서 복원한 항법 메시지';
    select.replaceChildren(new Option('Week ' + (week ?? '—') + ' / TOW ' + numeric(tow) + ' s', '0'));
    select.disabled = true;
    body.replaceChildren();
    selectSatellite(values);
    for (const {observation} of ordered(values)) {
      const originals = reference?.observations?.filter(o => matchSignal(o, observation)) || [];
      const original = originals.length === 1 ? originals[0] : null;
      const conversions = calculated?.observations?.filter(o => matchSignal(o, observation)) || [];
      const converted = conversions.length === 1 ? conversions[0] : null;
      const cells = observationCells(observation, tow);
      cells[3] = numeric(original?.pseudorangeMeters);
      // Eligibility uses the calculated received observation, never Reference pseudorange.
      cells[10] = delayEvidence?.error ? '계산 불가' : conversions.length > 1 ? '확인 불가'
        : converted ? observationCells(converted, tow)[10] : '계산값 없음';
      if (!rawEpoch) cells[8] = '—';
      const stamp = observation.transmitAt;
      cells[11] = stamp ? String(stamp.seconds) + '.' + String(stamp.femtoseconds).padStart(15, '0') : '—';
      cells.splice(4, 0, numeric(delayEvidence?.error ? null : converted?.pseudorangeMeters));
      const row = document.createElement('tr');
      const isEligible = cells[11] === '입력 대상';
      if (row.classList?.toggle) {
        row.classList.toggle('pvt-eligible-row', isEligible);
        row.classList.toggle('pvt-excluded-row', !isEligible);
      } else {
        row.className = isEligible ? 'pvt-eligible-row' : 'pvt-excluded-row';
      }
      cells.forEach((value, index) => {
        const cell = document.createElement('td');
        if (index === 4) cell.className = 'converted-range';
        if (index === cells.length - 1 && stamp) cell.className = 'converted-value';
        if (index === 7) {
          renderCnoCell(cell, observation.carrierToNoiseDbHz);
        } else if (index === 11) {
          if (value === '입력 대상') {
            cell.innerHTML = '<span class="pill online pvt-input-badge">입력 대상</span>';
          } else {
            cell.innerHTML = '<span class="pvt-excluded-badge">' + value + '</span>';
          }
        } else {
          cell.textContent = String(value ?? '—');
        }
        row.append(cell);
      });
      linkSatellite(row, observation);
      body.append(row);
    }
    renderAssociation(values);
    applyPvtFilter();
    if (notify) onSelect(0, calculated);
  }

  select.onchange = () => render();
  return {
    setData(next, preserve = false, evidence = null, comparisonReport = null) {
      report = comparisonReport;
      pvtResults = (role === '수신 원본' ? report?.receivedPvt : report?.referencePvt) || [];
      if (!preserve) {
        selectedSatellite = null;
        showAllNavigation = true;
      }
      const selected = preserve ? Number(select.value) : 0;
      data = next;
      delayEvidence = evidence;
      const iq = data?.source === 'IQ_TRACKING';
      const navigationBody = container.querySelector('[data-navigation]');
      navigationBody.replaceChildren();
      for (const item of data?.navigation || []) {
        const row = document.createElement('tr');
        const navigation = navigationCells(item, iq);
        if (data?.receivedValues && !data.receivedValues.records) {
          // 프레임에 없는 수집 순번·수집 시각·수신기 헤더를 실제 수신값처럼 표시하지 않는다.
          for (const index of [0, 1, 4, 5, 6]) navigation[index] = '—';
        }
        for (const value of navigation) {
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
          ? JSON.stringify(data?.receivedValues || data?.records || [], null, 2) : '';
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
    render() { render(false); },
    setPvt(values) { pvtResults = values || []; renderPvtStatus(); },
    select(index) { if (data?.epochs?.[index]) { select.value = String(index); render(false); } }
  };
}
