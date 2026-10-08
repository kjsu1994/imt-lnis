import {renderTrialSettings, trialOption, colorTrialSelection, initWaitingCancellation, initTrialSettingsDialog} from './dtn-settings.js?v=20261007-clean-layout';
import {requestJson} from '../common/http.js?v=20261001-review';
import {initGnssControls} from './dtn-gnss.js?v=20261001-review';
import {createDtnLog} from './dtn-log.js?v=20261001-review';
import {initAdapterHealth} from './dtn-adapter-health.js?v=20260922-compact-structure';
import {createPayloadViewer, renderIqFile} from './dtn-payload.js?v=20261001-review';
import {createObservationView, numeric, renderClockBias} from './dtn-observations.js?v=20261006-pvt-filter';

const $ = id => document.getElementById(id);
const payloadViewer = createPayloadViewer($('dtn-payload'), {receivedOnly: true});
const referenceView = createObservationView($('reference-observations'), () => {}, '송신 비교원본');
function renderReference(report = {}) {
  const status = report.referenceStatus;
  $('reference-panel').hidden = !status;
  $('received-observation-card').hidden = $('dtn-observations').hidden && !status;
  $('reference-status').textContent = status === 'COMPLETE' ? '비교자료 수신 완료'
    : status === 'WAITING' ? '수신 계산 완료 · 비교자료 조회 중' : status ? '비교자료 확인 필요' : '';
  $('reference-status').title = report.referenceMessage || '';
  $('reference-retry').hidden = !status || status === 'COMPLETE';
  $('reference-toggle').disabled = !report.referenceObservations;
  if (!report.referenceObservations) {
    $('reference-details').hidden = true;
    $('reference-toggle').setAttribute('aria-expanded', 'false');
  }
  referenceView.setData(report.referenceObservations || null, false, null, report);
}
$('reference-retry').onclick = async () => {
  if (!selectedId) return;
  $('reference-retry').disabled = true;
  try {
    await requestJson('/dtn/tests/' + encodeURIComponent(selectedId) + '/reference/retry',
      {method: 'POST'});
    await poll(true);
  } catch (error) { log(error.message, 'ERROR'); }
  finally { $('reference-retry').disabled = false; }
};
const clearScreen = location.pathname?.endsWith('/clear') === true;
let tests = [], epochs = [], selectedId = '', renderVersion = 0, polling = false;
let reportKey = '';
let receivedIds = null;
let historyPage = 0, selectionPinned = false, cancelling = false;
let referenceEpochs = [], comparisonEpochs = [], delayComparison = false, delayEvidence = null;
function setComparison(report = {}) {
  renderReference(report);
  delayComparison = report.comparisonMode === 'DELAY';
  $('receiver-pvt-title').textContent = delayComparison ? '수신 지연 반영 PVT · Reference 비교' : '수신 지구 PVT · 송신 기준 비교';
  if ($('received-pvt-label')) $('received-pvt-label').textContent = delayComparison ? '수신 지연 반영 지구 PVT' : '수신 복원 지구 PVT';
  delayEvidence = report.delayEvidence ?? null;
  $('dtn-clock-analysis').hidden = !delayComparison;
  $('pvt-delay-details').hidden = !delayComparison;
  renderClockAnalysis(null);
  referenceEpochs = Array.isArray(report.referencePvt) ? report.referencePvt : [];
  comparisonEpochs = report.comparison?.epochs || [];
  const verdict = report.comparison?.verdict;
  pill('pvt-match', verdict === 'MEASURED' ? (delayComparison ? '지연 반영 PVT 측정 완료' : 'I/Q PVT 오차 측정') : verdict === 'PARTIAL' ? '부분 비교 · 속도 비교 불가' : verdict === 'PASS' ? '전체 PVT 일치' : verdict === 'FAIL' ? '전체 PVT 불일치' : 'PVT 비교 불가',
    verdict === 'PASS' ? 'online' : verdict === 'FAIL' ? 'error' : verdict === 'MEASURED' && delayComparison ? '' : 'warning');
}
const observations = createObservationView($('dtn-observations'), index => {
  if (epochs[index]) { $('pvt-epoch').value = String(index); renderEpoch(); }
}, '수신 원본');
observations.setData(null);

function get(path) {
  return requestJson(path, {cache: 'no-store'}, {errorDetails: false});
}

function updateClockSkewWarning(connection) {
  const warning = $('dtn-clock-skew-warning');
  if (!warning) return;
  const skew = connection?.peerClockSkewSeconds;
  if (connection?.peerOnline && typeof skew === 'number' && Math.abs(skew) >= 0.5) {
    warning.hidden = false;
    const skewVal = $('clock-skew-val');
    if (skewVal) skewVal.textContent = (skew > 0 ? '+' : '') + skew.toFixed(2) + 's';
  } else {
    warning.hidden = true;
  }
}

const logView=createDtnLog($('dtn-log'));
const waitingCancellation = initWaitingCancellation({isLocked: () => cancelling, refresh: () => poll(true), log});
function log(message,level='INFO') { logView.write(message,level); }
const gnss = initGnssControls({port: 'gnss-port', baud: 'gnss-baud', refresh: 'gnss-refresh', log});
let peerAddressDirty = false, peerSaving = false;
$('sender-address').oninput = () => {
  peerAddressDirty = true;
  $('reverse-state').textContent = '주소 변경 · 미확인';
  $('reverse-dot').className = 'connection-dot unknown';
  $('sender-address-feedback').textContent = '';
};
async function saveSenderAddress(save) {
  if (peerSaving) return;
  peerSaving = true;
  $('sender-address-save').disabled = $('sender-address-test').disabled = true;
  try {
    const url = new URL($('sender-address').value.trim());
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || url.search || url.hash || url.pathname !== '/') throw new Error('http(s)://IPv4:포트 형식으로 입력하세요.');
    const result = await requestJson('/node/connection' + (save ? '' : '/test'), {
      method: save ? 'PUT' : 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ip: url.hostname, port: Number(url.port || (url.protocol === 'https:' ? 443 : 80)), scheme: url.protocol.slice(0, -1)})});
    $('sender-address-feedback').textContent = save ? '수신 서버에 저장·적용됨' : result.message;
    log(save ? '상대 송신 서비스 주소 저장·적용' : result.message, !save && !result.connected ? 'WARN' : 'INFO');
    if (save) {
      peerAddressDirty = false;
      $('sender-address').value = result.baseUrl;
      reportKey = '';
    }
  } catch (error) {
    $('sender-address-feedback').textContent = error.message;
    log(error.message, 'ERROR');
  } finally {
    peerSaving = false;
    await poll(true);
  }
}
$('reference-toggle').onclick = () => {
  const opened = $('reference-details').hidden;
  $('reference-details').hidden = !opened;
  $('reference-toggle').setAttribute('aria-expanded', String(opened));
};
$('sender-address-save').onclick = () => saveSenderAddress(true);
$('sender-address-test').onclick = () => saveSenderAddress(false);

function pill(id, text, state = '') {
  const el = $(id);
  if (el) {
    el.textContent = text;
    el.className = ('pill ' + state).trim();
  }
}

function number(value, digits = 3) {
  return numeric(value, digits, '-');
}

function time(value) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('ko-KR');
}

function measured(value, digits, unit) {
  if (!Number.isFinite(value)) return '—';
  const rounded = value.toFixed(digits);
  return (Number(rounded) === 0 ? (0).toFixed(digits) : rounded) + ' ' + unit;
}

function positionDifference(value) {
  return Number.isFinite(value) && Math.abs(value) < 1
    ? measured(value * 1000, 3, 'mm') : measured(value, 6, 'm');
}

// --- WGS-84 & ENU Coordinate Calculations (100% Offline) ---
const WGS84_A = 6378137.0;
const WGS84_F = 1.0 / 298.257223563;
const WGS84_E2 = WGS84_F * (2.0 - WGS84_F);

function ecefToGeodetic(x, y, z) {
  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return null;
  const p = Math.hypot(x, y);
  if (p < 1e-6) {
    const lat = z >= 0 ? 90.0 : -90.0;
    return { lat, lon: 0.0, alt: Math.abs(z) - 6356752.3142 };
  }
  const lon = Math.atan2(y, x);
  let lat = Math.atan2(z, p * (1 - WGS84_E2));
  for (let i = 0; i < 5; i++) {
    const sinLat = Math.sin(lat);
    const n = WGS84_A / Math.sqrt(1 - WGS84_E2 * sinLat * sinLat);
    lat = Math.atan2(z + WGS84_E2 * n * sinLat, p);
  }
  const sinLat = Math.sin(lat);
  const n = WGS84_A / Math.sqrt(1 - WGS84_E2 * sinLat * sinLat);
  const alt = p / Math.cos(lat) - n;
  return {
    lat: lat * 180 / Math.PI,
    lon: lon * 180 / Math.PI,
    alt: alt
  };
}

function ecefDeltaToEnu(dx, dy, dz, refLatDeg, refLonDeg) {
  const phi = refLatDeg * Math.PI / 180;
  const lam = refLonDeg * Math.PI / 180;
  const sPhi = Math.sin(phi), cPhi = Math.cos(phi);
  const sLam = Math.sin(lam), cLam = Math.cos(lam);
  const e = -sLam * dx + cLam * dy;
  const n = -sPhi * cLam * dx - sPhi * sLam * dy + cPhi * dz;
  const u =  cPhi * cLam * dx + cPhi * sLam * dy + sPhi * dz;
  return { e, n, u };
}

function formatGeoCoord(deg, isLat) {
  if (!Number.isFinite(deg)) return '—';
  const hemi = isLat ? (deg >= 0 ? 'N' : 'S') : (deg >= 0 ? 'E' : 'W');
  const abs = Math.abs(deg);
  const d = Math.floor(abs);
  const m = Math.floor((abs - d) * 60);
  const s = ((abs - d) * 60 - m) * 60;
  return `${d}° ${m}' ${s.toFixed(4)}" ${hemi} (${abs.toFixed(7)}°)`;
}

function formatGeoDelta(deg) {
  if (!Number.isFinite(deg)) return '—';
  const abs = Math.abs(deg);
  if (abs < 1e-9) return '0.0000000°';
  return (deg >= 0 ? '+' : '-') + abs.toFixed(7) + '°';
}

function renderGeodeticVisualizer(reference, pvt) {
  const container = $('pvt-geodetic-visualizer');
  if (!container) return;
  const refEcef = reference?.positionValid ? reference.ecefMeters : null;
  const recEcef = pvt?.positionValid ? pvt.ecefMeters : null;
  if (!refEcef || !recEcef) {
    container.hidden = true;
    return;
  }
  container.hidden = false;

  const refGeo = ecefToGeodetic(refEcef[0], refEcef[1], refEcef[2]);
  const recGeo = ecefToGeodetic(recEcef[0], recEcef[1], recEcef[2]);
  if (!refGeo || !recGeo) {
    container.hidden = true;
    return;
  }

  $('ref-geo-lat').textContent = formatGeoCoord(refGeo.lat, true);
  $('ref-geo-lon').textContent = formatGeoCoord(refGeo.lon, false);
  $('ref-geo-alt').textContent = measured(refGeo.alt, 4, 'm');

  $('rec-geo-lat').textContent = formatGeoCoord(recGeo.lat, true);
  $('rec-geo-lon').textContent = formatGeoCoord(recGeo.lon, false);
  $('rec-geo-alt').textContent = measured(recGeo.alt, 4, 'm');

  const dLat = recGeo.lat - refGeo.lat;
  const dLon = recGeo.lon - refGeo.lon;
  const dAlt = recGeo.alt - refGeo.alt;

  $('geo-delta-lat').textContent = formatGeoDelta(dLat);
  $('geo-delta-lon').textContent = formatGeoDelta(dLon);
  $('geo-delta-alt').textContent = positionDifference(Math.abs(dAlt));

  const dx = recEcef[0] - refEcef[0];
  const dy = recEcef[1] - refEcef[1];
  const dz = recEcef[2] - refEcef[2];
  const enu = ecefDeltaToEnu(dx, dy, dz, refGeo.lat, refGeo.lon);

  const deltaH = Math.hypot(enu.e, enu.n);
  const deltaU = enu.u;
  const deltaR = Math.sqrt(dx*dx + dy*dy + dz*dz);

  $('enu-delta-h').textContent = positionDifference(deltaH);
  $('enu-delta-u').textContent = (deltaU >= 0 ? '+' : '-') + positionDifference(Math.abs(deltaU));
  if ($('enu-delta-u-card')) $('enu-delta-u-card').textContent = (deltaU >= 0 ? '+' : '-') + positionDifference(Math.abs(deltaU));
  $('enu-delta-e').textContent = (enu.e >= 0 ? '+' : '-') + positionDifference(Math.abs(enu.e));
  $('enu-delta-n').textContent = (enu.n >= 0 ? '+' : '-') + positionDifference(Math.abs(enu.n));
  if ($('enu-delta-r')) $('enu-delta-r').textContent = positionDifference(deltaR);

  let azimuth = (Math.atan2(enu.e, enu.n) * 180 / Math.PI + 360) % 360;
  let dirText = '— (중심 일치)';
  if (deltaH >= 0.00005) {
    const sector = azimuth >= 337.5 || azimuth < 22.5 ? '북 (N)'
      : azimuth < 67.5 ? '북동 (NE)'
      : azimuth < 112.5 ? '동 (E)'
      : azimuth < 157.5 ? '남동 (SE)'
      : azimuth < 202.5 ? '남 (S)'
      : azimuth < 247.5 ? '남서 (SW)'
      : azimuth < 292.5 ? '서 (W)' : '북서 (NW)';
    dirText = azimuth.toFixed(1) + '° (' + sector + ')';
  }
  $('enu-azimuth').textContent = dirText;

  let scaleMeters = 0.001;
  let scaleLabel = '반경 1.0 mm (초정밀)';
  if (deltaH > 0.05) {
    scaleMeters = Math.max(0.1, Math.ceil(deltaH * 1.5 * 10) / 10);
    scaleLabel = '반경 ' + measured(scaleMeters, 2, 'm');
  } else if (deltaH > 0.01) {
    scaleMeters = 0.05;
    scaleLabel = '반경 50 mm';
  } else if (deltaH > 0.002) {
    scaleMeters = 0.01;
    scaleLabel = '반경 10 mm';
  } else if (deltaH > 0.001) {
    scaleMeters = 0.002;
    scaleLabel = '반경 2.0 mm';
  }
  if ($('enu-target-scale')) $('enu-target-scale').textContent = scaleLabel;

  const formatRingDist = (m) => {
    if (m < 0.01) return (m * 1000).toFixed(m * 1000 >= 1 ? 1 : 2) + ' mm';
    if (m < 1) return (m * 100).toFixed(1) + ' cm';
    return m.toFixed(2) + ' m';
  };
  if ($('enu-ring-outer-label')) $('enu-ring-outer-label').textContent = formatRingDist(scaleMeters);
  if ($('enu-ring-mid-label')) $('enu-ring-mid-label').textContent = formatRingDist(scaleMeters * (75/110));
  if ($('enu-ring-inner-label')) $('enu-ring-inner-label').textContent = formatRingDist(scaleMeters * (40/110));

  const pxX = Math.max(-110, Math.min(110, (enu.e / scaleMeters) * 110));
  const pxY = Math.max(-110, Math.min(110, (-enu.n / scaleMeters) * 110));

  const vectorLine = $('enu-vector-line');
  const targetPoint = $('enu-target-point');
  const targetPulse = $('enu-target-pulse');
  const verdictBadge = $('geo-match-verdict');

  if (vectorLine && targetPoint) {
    vectorLine.setAttribute('x1', '0');
    vectorLine.setAttribute('y1', '0');
    vectorLine.setAttribute('x2', String(pxX));
    vectorLine.setAttribute('y2', String(pxY));
    targetPoint.setAttribute('cx', String(pxX));
    targetPoint.setAttribute('cy', String(pxY));
    if (targetPulse) {
      targetPulse.setAttribute('cx', String(pxX));
      targetPulse.setAttribute('cy', String(pxY));
    }

    if (deltaH < 0.00005) {
      vectorLine.style.opacity = '0';
      if (targetPulse) targetPulse.style.opacity = '0';
      targetPoint.setAttribute('class', 'enu-target-point');
      if (verdictBadge) {
        verdictBadge.className = 'pvt-gauge-badge badge-perfect';
        verdictBadge.textContent = '🟢 좌표 완벽 일치';
      }
    } else if (deltaR <= 0.05) {
      vectorLine.style.opacity = '1';
      vectorLine.style.stroke = '#10b981';
      vectorLine.setAttribute('marker-end', 'url(#arrow)');
      if (targetPulse) targetPulse.style.opacity = '1';
      targetPoint.setAttribute('class', 'enu-target-point');
      if (verdictBadge) {
        verdictBadge.className = 'pvt-gauge-badge badge-perfect';
        verdictBadge.textContent = '🟢 mm급 정밀 일치 (ΔR: ' + positionDifference(deltaR) + ')';
      }
    } else if (deltaR <= 1.0) {
      vectorLine.style.opacity = '1';
      vectorLine.style.stroke = '#f59e0b';
      vectorLine.setAttribute('marker-end', 'url(#arrow)');
      if (targetPulse) targetPulse.style.opacity = '1';
      targetPoint.setAttribute('class', 'enu-target-point');
      if (verdictBadge) {
        verdictBadge.className = 'pvt-gauge-badge badge-acceptable';
        verdictBadge.textContent = '🟡 양호 (ΔR: ' + positionDifference(deltaR) + ')';
      }
    } else {
      vectorLine.style.opacity = '1';
      vectorLine.style.stroke = '#ef4444';
      vectorLine.setAttribute('marker-end', 'url(#arrow-warn)');
      if (targetPulse) targetPulse.style.opacity = '1';
      targetPoint.setAttribute('class', 'enu-target-point level-warning');
      if (verdictBadge) {
        verdictBadge.className = 'pvt-gauge-badge badge-warning';
        verdictBadge.textContent = '🔴 편차 발생 (ΔR: ' + positionDifference(deltaR) + ')';
      }
    }
  }
}

function renderClockAnalysis(delta, reference, pvt) {
  const available = delayComparison && !delayEvidence?.error;
  const seconds = available ? delta?.clockResidualSeconds : null;
  const residual = Number.isFinite(seconds) && seconds !== 0 && Math.abs(seconds) < 1e-12
    ? (seconds < 0 ? '음수 · ' : '양수 · ') + '크기 < 0.001 ns'
    : measured(seconds * 1e9, 3, 'ns');
  $('pvt-delay-value').textContent = delayComparison ? measured(delayEvidence?.delaySeconds, 9, 's') : '—';
  $('pvt-clock-change').textContent = measured(available ? delta?.clockDifferenceSeconds : null, 9, 's');
  $('pvt-delay-residual').textContent = Number.isFinite(seconds) ? residual : '—';
  $('pvt-delay-reason').textContent = !delayComparison ? '' : delayEvidence?.error
    || (!delta ? '비교 가능한 PVT 결과가 없습니다.' : !Number.isFinite(seconds) ? '시계오차 비교 근거가 없습니다.' : '');
  const raw = value => Number.isFinite(value) ? String(value) : '—';
  const epoch = value => value && Number.isFinite(value.week) && Number.isFinite(value.towSeconds)
    ? 'Week ' + value.week + ' / TOW ' + value.towSeconds + ' s' : '—';
  const vector = values => Array.isArray(values) && values.length === 3 ? values.map(raw).join(' / ') : '—';
  $('pvt-delay-evidence').textContent = !delayComparison ? '' : [
    '시작 접수 (UTC): ' + (delayEvidence?.timing?.startedAt ?? '—'),
    '본문 수신 완료 (UTC): ' + (delayEvidence?.timing?.receivedAt ?? '—'),
    '송신 기준 GNSS 시각: ' + epoch(delayEvidence?.originalTime),
    '지연 반영 GNSS 시각: ' + epoch(delayEvidence?.shiftedTime),
    '시험 전달 지연: ' + raw(delayEvidence?.delaySeconds) + ' s',
    '송신 기준 Clock Bias: ' + raw(reference?.positionValid ? reference.receiverClockBiasSeconds : null) + ' s',
    '수신 Clock Bias: ' + raw(pvt?.positionValid ? pvt.receiverClockBiasSeconds : null) + ' s',
    '시계오차 변화: ' + raw(available ? delta?.clockDifferenceSeconds : null) + ' s',
    '지연 반영 잔차: ' + raw(seconds) + ' s',
    '잔차 = (수신 Bias − 송신 기준 Bias) − 시험 전달 지연',
    '위치 변화 X / Y / Z (m): ' + vector(delta?.positionDeltaMeters),
    '속도 변화 X / Y / Z (m/s): ' + vector(delta?.velocityDeltaMetersPerSecond),
    '사용 위성 (송신 / 수신): ' + raw(reference?.satellitesUsed) + ' / ' + raw(pvt?.satellitesUsed)
  ].join('\n');
}

function formatCoordDiff(diff) {
  if (!Number.isFinite(diff)) return '—';
  const abs = Math.abs(diff);
  if (abs < 1e-6) return '0.0 mm';
  const sign = diff >= 0 ? '+' : '-';
  if (abs < 1.0) {
    return `${sign}${(abs * 1000).toFixed(1)} mm`;
  }
  return `${sign}${abs.toFixed(3)} m`;
}

function renderEpoch() {
  observations.select(Number($('pvt-epoch').value));
  const pvt = epochs[Number($('pvt-epoch').value)];
  const delta = pvt && (delayComparison ? comparisonEpochs[0] : comparisonEpochs.find(e => e.week === pvt.week && e.towSeconds === pvt.towSeconds));
  const deltaR = delta?.positionDifferenceMeters;
  const deltaV = delta?.velocityDifferenceMetersPerSecond;
  $('pvt-differences').textContent = '위치 차이 '+positionDifference(deltaR)+' · 속도 차이 '+measured(deltaV,6,'m/s')
    + (delayComparison ? '' : ' · 시계오차 차이 '+number(delta?.clockDifferenceSeconds,12)+' s');

  const reference = pvt && (delayComparison ? referenceEpochs[0] : referenceEpochs.find(value => value.week === pvt.week && value.towSeconds === pvt.towSeconds));
  renderClockAnalysis(delta, reference, pvt);
  renderGeodeticVisualizer(reference, pvt);

  const position = pvt?.positionValid === true;
  const velocity = pvt?.velocityValid === true;

  // ECEF X, Y, Z 및 편차 계산
  ['x', 'y', 'z'].forEach((axis, index) => {
    const refVal = reference?.positionValid ? reference.ecefMeters?.[index] : null;
    const recVal = position ? pvt.ecefMeters?.[index] : null;
    $('reference-' + axis).textContent = number(refVal);
    $('pvt-' + axis).textContent = number(recVal);

    const diffEl = $('pvt-diff-' + axis);
    const statusEl = $('pvt-status-' + axis);
    if (diffEl && statusEl) {
      if (refVal != null && recVal != null) {
        const diff = recVal - refVal;
        diffEl.textContent = formatCoordDiff(diff);
        const absDiff = Math.abs(diff);
        if (absDiff <= 0.005) {
          statusEl.className = 'pvt-table-badge badge-perfect';
          statusEl.textContent = '🟢 일치';
        } else if (absDiff <= 0.05) {
          statusEl.className = 'pvt-table-badge badge-acceptable';
          statusEl.textContent = '🟡 허용치';
        } else {
          statusEl.className = 'pvt-table-badge badge-warning';
          statusEl.textContent = '🔴 편차';
        }
      } else {
        diffEl.textContent = '—';
        statusEl.className = 'pvt-table-badge';
        statusEl.textContent = '—';
      }
    }
  });

  // 수신기 시계 오차 (Clock Bias) 및 지연 흡수 판정
  const refClock = reference?.positionValid ? reference.receiverClockBiasSeconds : null;
  const recClock = position ? pvt.receiverClockBiasSeconds : null;
  renderClockBias($('reference-clock'), refClock);
  renderClockBias($('pvt-clock'), recClock);
  const diffClockEl = $('pvt-diff-clock');
  const statusClockEl = $('pvt-status-clock');
  if (diffClockEl && statusClockEl) {
    if (refClock != null && recClock != null) {
      const diff = recClock - refClock;
      const sign = diff >= 0 ? '+' : '-';
      if (delayComparison) {
        diffClockEl.textContent = `${sign}${Math.abs(diff).toFixed(9)} s (지연 흡수)`;
        statusClockEl.className = 'pvt-table-badge badge-perfect';
        statusClockEl.textContent = '🟢 지연 정상 흡수';
      } else {
        diffClockEl.textContent = `${sign}${Math.abs(diff).toFixed(9)} s`;
        if (Math.abs(diff) < 1e-7) {
          statusClockEl.className = 'pvt-table-badge badge-perfect';
          statusClockEl.textContent = '🟢 동기 일치';
        } else {
          statusClockEl.className = 'pvt-table-badge badge-acceptable';
          statusClockEl.textContent = '🟡 클록 편차';
        }
      }
    } else {
      diffClockEl.textContent = '—';
      statusClockEl.className = 'pvt-table-badge';
      statusClockEl.textContent = '—';
    }
  }

  // 속도 (Velocity) X, Y, Z 및 3D 합성치
  ['x', 'y', 'z'].forEach((axis, index) => {
    $('reference-v' + axis).textContent = number(reference?.velocityValid ? reference.velocityMetersPerSecond?.[index] : null);
    $('pvt-v' + axis).textContent = number(velocity ? pvt.velocityMetersPerSecond?.[index] : null);
  });
  const refV3D = reference?.velocityValid ? Math.hypot(...reference.velocityMetersPerSecond) : null;
  const recV3D = velocity ? Math.hypot(...pvt.velocityMetersPerSecond) : null;
  if ($('reference-v-total')) $('reference-v-total').textContent = number(refV3D, 3);
  if ($('pvt-v-total')) $('pvt-v-total').textContent = number(recV3D, 3);
  const diffVEl = $('pvt-diff-v');
  const statusVEl = $('pvt-status-v');
  if (diffVEl && statusVEl) {
    if (refV3D != null && recV3D != null) {
      const vDiff = delta?.velocityDifferenceMetersPerSecond ?? Math.abs(recV3D - refV3D);
      diffVEl.textContent = measured(vDiff, 6, 'm/s');
      if (vDiff < 0.001) {
        statusVEl.className = 'pvt-table-badge badge-perfect';
        statusVEl.textContent = '🟢 일치';
      } else if (vDiff < 0.1) {
        statusVEl.className = 'pvt-table-badge badge-acceptable';
        statusVEl.textContent = '🟡 양호';
      } else {
        statusVEl.className = 'pvt-table-badge badge-warning';
        statusVEl.textContent = '🔴 편차';
      }
    } else {
      diffVEl.textContent = '—';
      statusVEl.className = 'pvt-table-badge';
      statusVEl.textContent = '—';
    }
  }

  // 사용 위성수
  const refSats = reference?.satellitesUsed;
  const recSats = pvt?.satellitesUsed;
  $('reference-satellites').textContent = refSats ?? '-';
  $('pvt-satellites').textContent = recSats ?? '-';
  const diffSatsEl = $('pvt-diff-satellites');
  const statusSatsEl = $('pvt-status-satellites');
  if (diffSatsEl && statusSatsEl) {
    if (refSats != null && recSats != null) {
      const satDiff = recSats - refSats;
      diffSatsEl.textContent = satDiff === 0 ? '동일 (0개)' : (satDiff > 0 ? `+${satDiff}개` : `${satDiff}개`);
      if (satDiff === 0) {
        statusSatsEl.className = 'pvt-table-badge badge-perfect';
        statusSatsEl.textContent = `🟢 ${refSats}기 동일`;
      } else {
        statusSatsEl.className = 'pvt-table-badge badge-acceptable';
        statusSatsEl.textContent = `🟡 ${Math.abs(satDiff)}기 차이`;
      }
    } else {
      diffSatsEl.textContent = '—';
      statusSatsEl.className = 'pvt-table-badge';
      statusSatsEl.textContent = '—';
    }
  }

  pill('pvt-validity', !pvt ? '결과 대기' : '위치 ' + (position ? '유효' : '무효') + ' · 속도 ' + (velocity ? '유효' : '무효'),
    !pvt ? '' : position && velocity ? 'online' : 'warning');
  if ($('pvt-message')) $('pvt-message').textContent = pvt?.message || '지구 ECEF · GPS L1 C/A';
}

function setEpochs(values, preserve = false) {
  const selected = preserve ? $('pvt-epoch').value : '0';
  epochs = Array.isArray(values) ? values : [];
  $('pvt-epoch').replaceChildren(...(epochs.length
    ? epochs.map((pvt, index) => new Option((index + 1) + ' · Week ' + pvt.week + ' / TOW ' + number(pvt.towSeconds) + ' s', String(index)))
    : [new Option('계산 결과 없음', '')]));
  $('pvt-epoch').disabled = !epochs.length;
  if (epochs.length) $('pvt-epoch').value = Number(selected) < epochs.length ? selected : '0';
  $('pvt-count').textContent = epochs.length + '개 관측';
  renderEpoch();
}

function formatElapsed(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '';
  if (ms < 1000) return Math.round(ms) + 'ms';
  return (ms / 1000).toFixed(2) + 's';
}

function updateReceiverFlow(job) {
  const regEl = $('step-register'), recvEl = $('step-receive'), procEl = $('step-process');
  if (!regEl || !recvEl || !procEl) return;

  if (!job) {
    regEl.className = '';
    regEl.querySelector('.step-icon').textContent = '①';
    regEl.querySelector('.step-text').textContent = '시험 등록';
    $('step-register-time').textContent = '';

    recvEl.className = '';
    recvEl.querySelector('.step-icon').textContent = '②';
    recvEl.querySelector('.step-text').textContent = '번들 수신';
    $('step-receive-time').textContent = '';

    procEl.className = '';
    procEl.querySelector('.step-icon').textContent = '③';
    procEl.querySelector('.step-text').textContent = '복원·PVT 계산';
    $('step-process-time').textContent = '';
    return;
  }

  const failed = ['FAILED', 'CANCELLED'].includes(job.state);
  const completed = ['COMPLETED', 'INCONCLUSIVE'].includes(job.state);
  const received = !!job.dtnReceived;
  const calculating = job.state === 'CALCULATING' || job.state === 'WAITING_RECEIVER';
  const iq = job.testType === 'IQ_SAMPLE';

  const tReg = Date.parse(job.createdAt);
  const tRecv = Date.parse(job.receivedAt || job.stageStartedAt);
  const tDone = Date.parse(job.updatedAt);

  // Step 1: 등록
  regEl.className = 'done';
  regEl.querySelector('.step-icon').textContent = '✔';
  regEl.querySelector('.step-text').textContent = '① 시험 등록';
  $('step-register-time').textContent = '';

  // Step 2: 번들 수신
  if (received) {
    recvEl.className = 'done';
    recvEl.querySelector('.step-icon').textContent = '✔';
    recvEl.querySelector('.step-text').textContent = '② 번들 수신';
    const recvMs = Number.isFinite(tRecv) && Number.isFinite(tReg) ? Math.max(0, tRecv - tReg) : null;
    $('step-receive-time').textContent = recvMs != null ? '(' + formatElapsed(recvMs) + ')' : '';
  } else if (failed) {
    recvEl.className = 'failed';
    recvEl.querySelector('.step-icon').textContent = '✕';
    recvEl.querySelector('.step-text').textContent = '② 수신 실패';
    $('step-receive-time').textContent = '';
  } else {
    recvEl.className = 'active';
    recvEl.querySelector('.step-icon').textContent = '⟳';
    recvEl.querySelector('.step-text').textContent = '② 번들 수신 대기';
    $('step-receive-time').textContent = '';
  }

  // Step 3: 계산
  const procTitle = iq ? 'I/Q 추적·PVT' : '복원·PVT 계산';
  if (completed) {
    procEl.className = 'done';
    procEl.querySelector('.step-icon').textContent = '✔';
    procEl.querySelector('.step-text').textContent = '③ ' + procTitle;
    const procMs = Number.isFinite(tDone) && Number.isFinite(tRecv) ? Math.max(0, tDone - tRecv) : null;
    $('step-process-time').textContent = procMs != null ? '(' + formatElapsed(procMs) + ')' : '';
  } else if (failed && received) {
    procEl.className = 'failed';
    procEl.querySelector('.step-icon').textContent = '✕';
    procEl.querySelector('.step-text').textContent = '③ 계산 실패';
    $('step-process-time').textContent = '';
  } else if (calculating) {
    procEl.className = 'active';
    procEl.querySelector('.step-icon').textContent = '⟳';
    procEl.querySelector('.step-text').textContent = '③ 계산 중...';
    $('step-process-time').textContent = '';
  } else {
    procEl.className = '';
    procEl.querySelector('.step-icon').textContent = '③';
    procEl.querySelector('.step-text').textContent = procTitle;
    $('step-process-time').textContent = '';
  }
}

let trialDialogHandler = null;
function renderSummary(job) {
  waitingCancellation.update();
  $('dtn-cancel').disabled = cancelling || !['PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING'].includes(job?.state);
  $('dtn-cancel').textContent = job?.state === 'CALCULATING' ? '계산 중지' : job?.state === 'WAITING_DTN' ? '대기 종료' : '시험 중지';
  if ($('trial-settings')) renderTrialSettings($('trial-settings'), job);
  trialDialogHandler?.updateDialog(job);
  $('dtn-observations').hidden = job?.testType === 'IQ_SAMPLE' && !job?.receivedEpochs;
  $('received-observation-card').hidden = $('dtn-observations').hidden && $('reference-panel').hidden;
  const types = {GNSS_RAW: 'GNSS RAW', AFS_METADATA: 'AFS Frame', IQ_SAMPLE: 'I/Q Sample'};
  $('receiver-type').textContent = types[job?.testType] || '시험 선택 대기';
  $('receiver-mode').textContent = job?.senderMode && job?.receiverMode ? job.senderMode + ' → ' + job.receiverMode : '경로 정보 없음';
  if ($('receiver-constellation')) $('receiver-constellation').textContent = job?.pvtConstellation || 'GPS';
  $('receiver-iq').hidden = job?.testType !== 'IQ_SAMPLE';
  renderIqFile($('receiver-iq-result'), job?.fileResult, job?.state === 'FAILED' ? 'I/Q 파일 검증 실패 · 로그를 확인하세요.' : 'I/Q 파일 수신·검증 대기');
  const failed = ['FAILED', 'CANCELLED'].includes(job?.state);
  const completed = ['COMPLETED', 'INCONCLUSIVE'].includes(job?.state);
  const states = {
    PREPARING: '시험 준비 중', WAITING_DTN: '외부 번들 수신 대기',
    WAITING_RECEIVER: '수신 실행기 대기', CALCULATING: job?.testType === 'IQ_SAMPLE' ? 'I/Q 추적·PVT 처리 중' : '복원·PVT 계산 중',
    COMPLETED: '수신 계산 완료', FAILED: '처리 실패', CANCELLED: '시험 취소',
    INCONCLUSIVE: '수신·복원 완료 (PVT 비교 불가)'
  };
  const statusText = job ? (job.lateReceivedAt ? '대기 종료 후 도착' : states[job.state] || job.state) : '수신 대기';
  $('receive-state').textContent = statusText;
  $('receive-state').className = failed ? 'receiver-error' : completed ? 'receiver-success' : '';

  // 중복 문구 제거 (예: '수신 계산 완료'인데 '지연 반영 PVT 측정 완료'가 중복 노출되는 현상 방지)
  const msg = job?.message || '';
  const isRedundant = completed && (msg.includes('PVT 측정 완료') || msg.includes('수신 완료') || msg.includes('계산 완료') || msg === statusText);
  $('receive-message').textContent = isRedundant ? '' : msg;
  $('test-id').textContent = job?.testId || '-';
  if ($('test-id')) $('test-id').title = job?.testId || '';
  const updatedStr = time(job?.updatedAt);
  $('test-updated').textContent = updatedStr;
  if ($('test-updated')) $('test-updated').title = updatedStr;

  updateReceiverFlow(job);
}

async function renderTest(force = false) {
  const version = ++renderVersion;
  const job = tests.find(item => item.testId === $('dtn-tests').value);
  const changed = selectedId !== (job?.testId || '');
  selectedId = job?.testId || '';
  logView.setContext(selectedId);
  payloadViewer.setJob(job);
  renderSummary(job);
  if (changed || !job) {
    $('pvt-delay-details').open = false;
    reportKey = '';
    setComparison();
    setEpochs([]);
    observations.setData(null);
  }
  if (!job) return;
  const event = job.testId + ':' + job.state + ':' + job.updatedAt;
  if (!job.receivedEpochs) return;
  if (!force && reportKey === event) return;
  try {
    const report = await get('/dtn/tests/' + encodeURIComponent(job.testId) + '/report');
    // 시험을 바꾼 뒤 늦게 도착한 이전 응답이 새 시험의 PVT를 덮어쓰지 않는다.
    if (version !== renderVersion || selectedId !== job.testId) return;
    setComparison(report);
    setEpochs(report.receivedPvt, !changed);
    observations.setData(report.observations, !changed, delayComparison ? report.delayEvidence : null, report);
    reportKey = event;
  } catch (error) {
    if (version !== renderVersion) return;
    setComparison();
    setEpochs([]);
    if ($('pvt-message')) $('pvt-message').textContent = 'PVT 조회 실패 · ' + error.message;
    log('PVT 조회 실패 · ' + error.message);
  }
}

function renderAgents(agents) {
  for (const role of ['SENDER', 'RECEIVER']) {
    const agent = agents.find(item => item.role === role);
    const online = !!agent && !['OFFLINE','ERROR'].includes(agent.state);
    const text = role === 'SENDER' ? '송신 처리기' : '수신 처리기';
    pill('dtn-' + role.toLowerCase() + '-status', text + ' ' +
      (online ? (agent.state === 'READY' ? '준비됨' : '처리 중') : '연결 안 됨'),
      online ? (agent.state === 'READY' ? 'online' : 'warning') : 'error');
  }
}

async function poll(force = false) {
  if (polling || (!force && document.visibilityState === 'hidden')) return;
  polling = true;
  $('dtn-refresh').disabled = true;
  try {
    await gnss.poll();
    const query = new URLSearchParams({page: historyPage});
    if ($('dtn-test-filter').value) query.set('state', $('dtn-test-filter').value);
    const [agents, nextTests] = await Promise.all([get('/agents'), get('/dtn/tests?' + query)]);
    $('dtn-tests-prev').disabled = historyPage === 0;
    $('dtn-tests-next').disabled = nextTests.length < 50;
    $('dtn-tests-page').textContent = String(historyPage + 1);
    if (selectionPinned && selectedId && !nextTests.some(item => item.testId === selectedId)) {
      const selectedJob = await get('/dtn/tests/' + encodeURIComponent(selectedId)).catch(() => null);
      if (selectedJob) nextTests.push(selectedJob);
    }
    const newlyReceived = nextTests.filter(job => job.dtnReceived &&
      (receivedIds === null ? !clearScreen : !receivedIds.has(job.testId)))
      .sort((a, b) => (Date.parse(b.receivedAt) || 0) - (Date.parse(a.receivedAt) || 0))[0];
    receivedIds = new Set(nextTests.filter(job => job.dtnReceived).map(job => job.testId));
    tests = nextTests;
    const connection = await get('/node/connection').catch(() => ({}));
    gnss.setPeerTime(connection.peerGnssTimeState);
    updateClockSkewWarning(connection);
    if (!peerAddressDirty) {
      $('sender-address').value = connection.baseUrl || '';
      $('reverse-state').textContent = connection.peerOnline == null ? '미확인' : connection.peerOnline ? '연결됨' : '연결 끊김';
      $('reverse-dot').className = 'connection-dot ' + (connection.peerOnline == null ? 'unknown' : connection.peerOnline ? 'online' : 'offline');
    }
    $('sender-address-save').disabled = peerSaving || !!connection.busy || !connection.editable;
    $('sender-address-test').disabled = peerSaving || !connection.editable;
    $('sender-address').disabled = peerSaving || !!connection.busy || !connection.editable;
    renderAgents(agents);
    const selected = $('dtn-tests').value;
    $('dtn-tests').replaceChildren(...(clearScreen ? [new Option('시험 선택 · 화면 초기화됨', '')] : []), ...(tests.length ? tests.map(job =>
      trialOption(job, time(job.createdAt)))
      : [new Option('등록된 시험 없음', '')]));
    if (newlyReceived && !selectionPinned && historyPage === 0) $('dtn-tests').value = newlyReceived.testId;
    else if (tests.some(job => job.testId === selected)) $('dtn-tests').value = selected;
    else if (!selectionPinned && tests.length) $('dtn-tests').value = tests[0].testId;
    colorTrialSelection($('dtn-tests'), tests.find(job => job.testId === $('dtn-tests').value));
    await renderTest(force);
    const receiptTestId = selectedId;
    const selectedJob = tests.find(item => item.testId === receiptTestId);
    if (receiptTestId && !selectedJob?.receivedPayloadAvailable) {
      const receipts = await get('/dtn/receipts?testId=' + encodeURIComponent(receiptTestId)).catch(() => null);
      if (receipts && selectedId === receiptTestId) payloadViewer.setReceipts(receipts);
    }
    $('last-updated').textContent = '최근 확인 ' + new Date().toLocaleTimeString('ko-KR') + ' · 자동 갱신';
  } catch (error) {
    pill('dtn-receiver-status', '수신 서버 연결 끊김', 'error');
    pill('dtn-sender-status', '송신 노드 확인 불가', 'warning');
    log('조회 실패 · ' + error.message);
  } finally {
    polling = false;
    $('dtn-refresh').disabled = false;
  }
}

function updateAdapterStripPill(text, tone, className) {
  const pillEl = $('dtn-adapter-strip-status');
  if (!pillEl) return;
  const stateClass = tone || (className?.includes('online') ? 'online' : className?.includes('offline') ? 'error' : 'warning');
  const label = text.startsWith('어댑터') ? text : '어댑터 ' + text;
  pill('dtn-adapter-strip-status', label, stateClass);
}

async function initialize() {
  trialDialogHandler = initTrialSettingsDialog(() => tests.find(item => item.testId === $('dtn-tests').value));
  if ($('dtn-adapter-strip-status')) {
    $('dtn-adapter-strip-status').style.cursor = 'pointer';
    $('dtn-adapter-strip-status').onclick = () => {
      $('dtn-send-url')?.focus();
      $('dtn-send-url')?.scrollIntoView({behavior: 'smooth', block: 'center'});
    };
  }
  await gnss.poll();
  void gnss.listPorts();
  try {
    const config = await get('/dtn/config');
    initAdapterHealth(config.adapterUrl || '', log, (text, className, tone) => {
      updateAdapterStripPill(text, tone, className);
    });
  } catch (error) {
    initAdapterHealth('', log, (text, className, tone) => {
      updateAdapterStripPill(text, tone, className);
    });
    log(error.message);
  }
  try {
    const connection = await get('/node/connection');
    $('sender-address').value = connection.baseUrl || '';
  } catch { /* Optional in central mode. */ }
  await poll();
  const repeat = async () => { await poll(); setTimeout(repeat, 2000); };
  setTimeout(repeat, 2000);
}

$('dtn-tests').onchange = () => {
  colorTrialSelection($('dtn-tests'), tests.find(job => job.testId === $('dtn-tests').value));
  selectionPinned = true;
  renderTest();
};
$('dtn-test-filter').onchange = () => { historyPage = 0; selectionPinned = false; poll(true); };
for (const [id, step] of [['dtn-tests-prev', -1], ['dtn-tests-next', 1]]) $(id).onclick = () => {
  historyPage = Math.max(0, historyPage + step); selectionPinned = false; poll(true);
};
$('dtn-cancel').onclick = async () => {
  if (!selectedId || $('dtn-cancel').disabled) return;
  const id = selectedId;
  cancelling = true; $('dtn-cancel').disabled = true;
  try {
    await requestJson('/dtn/tests/' + encodeURIComponent(id) + '/cancel', {method:'POST'});
    log('선택 시험 종료 · ' + id); await poll(true);
  } catch (error) { log(error.message, 'ERROR'); }
  finally { cancelling = false; renderSummary(tests.find(item => item.testId === selectedId)); }
};
$('pvt-epoch').onchange = renderEpoch;
$('dtn-refresh').onclick = () => poll(true);

initialize();
