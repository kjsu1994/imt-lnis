import {renderTrialSettings, trialOption, colorTrialSelection, initWaitingCancellation, initTrialSettingsDialog} from './dtn-settings.js?v=20261007-clean-layout';
import {requestJson} from '../common/http.js?v=20261001-review';
import {initGnssControls} from './dtn-gnss.js?v=20261001-review';
import {createDtnLog} from './dtn-log.js?v=20261001-review';
import {initAdapterHealth} from './dtn-adapter-health.js?v=20260922-compact-structure';
import {createPayloadViewer, renderIqFile} from './dtn-payload.js?v=20261001-review';
import {createObservationView, numeric, renderClockBias} from './dtn-observations.js?v=20261013-nav-params';

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

// --- 3D 및 HUD 뷰 상태 변수 ---
let targetViewMode = 'hud'; // 'hud' | '3d'
let cubeYaw = 0.65; // 약 37도
let cubePitch = 0.42; // 약 24도
let cubeZoom = 1.0; // 줌 배율 (0.5x ~ 4.0x)
let lastEnuData = null; // { e, n, u, deltaH, deltaU, deltaR, dx, dy, dz }

function setTargetViewMode(mode) {
  targetViewMode = mode;
  const isHud = mode === 'hud';
  const btnHud = $('btn-view-hud');
  const btn3d = $('btn-view-3d');
  const paneHud = $('target-view-hud');
  const pane3d = $('target-view-3d');

  if (btnHud) {
    btnHud.classList.toggle('active', isHud);
    btnHud.setAttribute('aria-selected', isHud ? 'true' : 'false');
  }
  if (btn3d) {
    btn3d.classList.toggle('active', !isHud);
    btn3d.setAttribute('aria-selected', !isHud ? 'true' : 'false');
  }
  if (paneHud) paneHud.hidden = !isHud;
  if (pane3d) pane3d.hidden = isHud;

  if (!isHud && lastEnuData) {
    render3DCube(lastEnuData);
  }
}

function project3D(xn, yn, zn, yaw, pitch, cx, cy, rPx) {
  const x1 = xn * Math.cos(yaw) - yn * Math.sin(yaw);
  const y1 = xn * Math.sin(yaw) + yn * Math.cos(yaw);
  const z1 = zn;
  const sx = cx + x1 * rPx;
  const sy = cy - (z1 * Math.cos(pitch) + y1 * Math.sin(pitch)) * rPx;
  return { x: sx, y: sy };
}

function buildTooltipHtml(data) {
  if (!data) return '';
  return `
    <div class="tooltip-title">
      <span>🎯 타깃 정밀 오차 인스펙터</span>
      <span class="highlight-r">ΔR ${positionDifference(data.deltaR)}</span>
    </div>
    <div class="tooltip-row"><span class="tooltip-label">국소 동서 (E):</span><span class="tooltip-val">${(data.e >= 0 ? '+' : '-') + positionDifference(Math.abs(data.e))}</span></div>
    <div class="tooltip-row"><span class="tooltip-label">국소 남북 (N):</span><span class="tooltip-val">${(data.n >= 0 ? '+' : '-') + positionDifference(Math.abs(data.n))}</span></div>
    <div class="tooltip-row"><span class="tooltip-label">국소 고도 (U):</span><span class="tooltip-val highlight-u">${(data.u >= 0 ? '+' : '-') + positionDifference(Math.abs(data.u))}</span></div>
    <div class="tooltip-row"><span class="tooltip-label">수평 편차 (ΔH):</span><span class="tooltip-val">${positionDifference(data.deltaH)}</span></div>
    ${data.dx != null ? `<div class="tooltip-row" style="margin-top:3px;border-top:1px dashed #334155;padding-top:3px;"><span class="tooltip-label">ECEF ΔX/Y/Z:</span><span class="tooltip-val">${(data.dx*1000).toFixed(1)} / ${(data.dy*1000).toFixed(1)} / ${(data.dz*1000).toFixed(1)} mm</span></div>` : ''}
  `;
}

function showTargetTooltip(e, data) {
  const tooltip = $('pvt-target-tooltip');
  if (!tooltip || !data) return;
  tooltip.innerHTML = buildTooltipHtml(data);
  tooltip.hidden = false;
  const container = $('pvt-geodetic-visualizer') || document.body;
  const rect = container.getBoundingClientRect();
  tooltip.style.left = (e.clientX - rect.left) + 'px';
  tooltip.style.top = (e.clientY - rect.top - 12) + 'px';
}

function hideTargetTooltip() {
  const tooltip = $('pvt-target-tooltip');
  if (tooltip) tooltip.hidden = true;
}

function render3DCube(data) {
  const svg = $('cube-3d-svg');
  if (!svg || !data) return;

  const cx = 195, cy = 155;
  const rPx = 110 * cubeZoom;

  const maxC = Math.max(Math.abs(data.e), Math.abs(data.n), Math.abs(data.u), 0.0001);
  let boxRange = 0.001;
  if (maxC > 0.05) boxRange = Math.max(0.1, Math.ceil(maxC * 1.3 * 10) / 10);
  else if (maxC > 0.01) boxRange = 0.05;
  else if (maxC > 0.005) boxRange = 0.01;
  else if (maxC > 0.002) boxRange = 0.005;
  else if (maxC > 0.001) boxRange = 0.002;

  const formatRingDist = (m) => {
    if (m < 0.01) return (m * 1000).toFixed(m * 1000 >= 1 ? 1 : 2) + ' mm';
    if (m < 1) return (m * 100).toFixed(1) + ' cm';
    return m.toFixed(2) + ' m';
  };

  const project = (xn, yn, zn) => project3D(xn, yn, zn, cubeYaw, cubePitch, cx, cy, rPx);

  const en = Math.max(-1.18, Math.min(1.18, data.e / boxRange));
  const nn = Math.max(-1.18, Math.min(1.18, data.n / boxRange));
  const un = Math.max(-1.18, Math.min(1.18, data.u / boxRange));

  const p0 = project(0, 0, 0);
  const pE = project(1.18, 0, 0);
  const pN = project(0, 1.18, 0);
  const pU = project(0, 0, 1.18);

  // Wireframe box 8 corners
  const c000 = project(-1, -1, -1);
  const c100 = project(1, -1, -1);
  const c110 = project(1, 1, -1);
  const c010 = project(-1, 1, -1);
  const c001 = project(-1, -1, 1);
  const c101 = project(1, -1, 1);
  const c111 = project(1, 1, 1);
  const c011 = project(-1, 1, 1);

  // Ground plane 4 corners (Z = 0)
  const g00 = project(-1, -1, 0);
  const g10 = project(1, -1, 0);
  const g11 = project(1, 1, 0);
  const g01 = project(-1, 1, 0);

  // Ground sub-grid lines
  const gridLines = [];
  for (const t of [-0.5, 0.5]) {
    const l1a = project(t, -1, 0);
    const l1b = project(t, 1, 0);
    gridLines.push(`<line x1="${l1a.x.toFixed(1)}" y1="${l1a.y.toFixed(1)}" x2="${l1b.x.toFixed(1)}" y2="${l1b.y.toFixed(1)}" stroke="#cbd5e1" stroke-width="0.8" stroke-dasharray="2,2"/>`);
    const l2a = project(-1, t, 0);
    const l2b = project(1, t, 0);
    gridLines.push(`<line x1="${l2a.x.toFixed(1)}" y1="${l2a.y.toFixed(1)}" x2="${l2b.x.toFixed(1)}" y2="${l2b.y.toFixed(1)}" stroke="#cbd5e1" stroke-width="0.8" stroke-dasharray="2,2"/>`);
  }

  // Target points
  const pTarget = project(en, nn, un);
  const pGround = project(en, nn, 0);

  const isWarn = data.deltaR > 0.05;
  const isErr = data.deltaR > 1.0;
  const targetColor = isErr ? '#ef4444' : isWarn ? '#f59e0b' : '#10b981';

  svg.innerHTML = `
    <defs>
      <marker id="arrow-3d-e" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
        <path d="M 0 1.5 L 10 5 L 0 8.5 z" fill="#2563eb"/>
      </marker>
      <marker id="arrow-3d-n" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
        <path d="M 0 1.5 L 10 5 L 0 8.5 z" fill="#059669"/>
      </marker>
      <marker id="arrow-3d-u" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
        <path d="M 0 1.5 L 10 5 L 0 8.5 z" fill="#d97706"/>
      </marker>
      <marker id="arrow-3d-vec" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
        <path d="M 0 1.5 L 10 5 L 0 8.5 z" fill="${targetColor}"/>
      </marker>
    </defs>

    <!-- Bounding Wireframe Box -->
    <g stroke="#cbd5e1" stroke-width="1" stroke-dasharray="3,3" opacity="0.6">
      <line x1="${c000.x.toFixed(1)}" y1="${c000.y.toFixed(1)}" x2="${c100.x.toFixed(1)}" y2="${c100.y.toFixed(1)}" />
      <line x1="${c100.x.toFixed(1)}" y1="${c100.y.toFixed(1)}" x2="${c110.x.toFixed(1)}" y2="${c110.y.toFixed(1)}" />
      <line x1="${c110.x.toFixed(1)}" y1="${c110.y.toFixed(1)}" x2="${c010.x.toFixed(1)}" y2="${c010.y.toFixed(1)}" />
      <line x1="${c010.x.toFixed(1)}" y1="${c010.y.toFixed(1)}" x2="${c000.x.toFixed(1)}" y2="${c000.y.toFixed(1)}" />
      <line x1="${c000.x.toFixed(1)}" y1="${c000.y.toFixed(1)}" x2="${c001.x.toFixed(1)}" y2="${c001.y.toFixed(1)}" />
      <line x1="${c100.x.toFixed(1)}" y1="${c100.y.toFixed(1)}" x2="${c101.x.toFixed(1)}" y2="${c101.y.toFixed(1)}" />
      <line x1="${c110.x.toFixed(1)}" y1="${c110.y.toFixed(1)}" x2="${c111.x.toFixed(1)}" y2="${c111.y.toFixed(1)}" />
      <line x1="${c010.x.toFixed(1)}" y1="${c010.y.toFixed(1)}" x2="${c011.x.toFixed(1)}" y2="${c011.y.toFixed(1)}" />
      <line x1="${c001.x.toFixed(1)}" y1="${c001.y.toFixed(1)}" x2="${c101.x.toFixed(1)}" y2="${c101.y.toFixed(1)}" />
      <line x1="${c101.x.toFixed(1)}" y1="${c101.y.toFixed(1)}" x2="${c111.x.toFixed(1)}" y2="${c111.y.toFixed(1)}" />
      <line x1="${c111.x.toFixed(1)}" y1="${c111.y.toFixed(1)}" x2="${c011.x.toFixed(1)}" y2="${c011.y.toFixed(1)}" />
      <line x1="${c011.x.toFixed(1)}" y1="${c011.y.toFixed(1)}" x2="${c001.x.toFixed(1)}" y2="${c001.y.toFixed(1)}" />
    </g>

    <!-- Ground Plane (Z = 0) -->
    <polygon points="${g00.x.toFixed(1)},${g00.y.toFixed(1)} ${g10.x.toFixed(1)},${g10.y.toFixed(1)} ${g11.x.toFixed(1)},${g11.y.toFixed(1)} ${g01.x.toFixed(1)},${g01.y.toFixed(1)}" fill="rgba(241, 245, 249, 0.75)" stroke="#94a3b8" stroke-width="1.2" />
    ${gridLines.join('\n')}

    <!-- Principal Axes at Origin -->
    <line x1="${p0.x.toFixed(1)}" y1="${p0.y.toFixed(1)}" x2="${pE.x.toFixed(1)}" y2="${pE.y.toFixed(1)}" stroke="#2563eb" stroke-width="2" marker-end="url(#arrow-3d-e)" />
    <text x="${(pE.x + 6).toFixed(1)}" y="${(pE.y + 3).toFixed(1)}" fill="#2563eb" font-size="11" font-weight="800" font-family="sans-serif">+E</text>

    <line x1="${p0.x.toFixed(1)}" y1="${p0.y.toFixed(1)}" x2="${pN.x.toFixed(1)}" y2="${pN.y.toFixed(1)}" stroke="#059669" stroke-width="2" marker-end="url(#arrow-3d-n)" />
    <text x="${(pN.x - 6).toFixed(1)}" y="${(pN.y - 4).toFixed(1)}" fill="#059669" font-size="11" font-weight="800" font-family="sans-serif">+N</text>

    <line x1="${p0.x.toFixed(1)}" y1="${p0.y.toFixed(1)}" x2="${pU.x.toFixed(1)}" y2="${pU.y.toFixed(1)}" stroke="#d97706" stroke-width="2" marker-end="url(#arrow-3d-u)" />
    <text x="${pU.x.toFixed(1)}" y="${(pU.y - 6).toFixed(1)}" fill="#d97706" font-size="11" font-weight="800" font-family="sans-serif" text-anchor="middle">+U</text>

    <!-- Reference Origin Point (0,0,0) -->
    <circle cx="${p0.x.toFixed(1)}" cy="${p0.y.toFixed(1)}" r="3.5" fill="#2563eb" stroke="#ffffff" stroke-width="1.5" />
    <text x="${(p0.x - 6).toFixed(1)}" y="${(p0.y + 12).toFixed(1)}" fill="#475569" font-size="9" font-weight="700" font-family="sans-serif">기준(0,0,0)</text>

    <!-- Target Ground Footprint & Projection Shadow -->
    <line x1="${p0.x.toFixed(1)}" y1="${p0.y.toFixed(1)}" x2="${pGround.x.toFixed(1)}" y2="${pGround.y.toFixed(1)}" stroke="#94a3b8" stroke-width="1.2" stroke-dasharray="3,2" />
    <ellipse cx="${pGround.x.toFixed(1)}" cy="${pGround.y.toFixed(1)}" rx="4.5" ry="3" fill="rgba(16, 185, 129, 0.35)" stroke="${targetColor}" stroke-width="1" stroke-dasharray="2,2" />

    <!-- Vertical Drop Line from Ground (en, nn, 0) to Target (en, nn, un) -->
    <line x1="${pGround.x.toFixed(1)}" y1="${pGround.y.toFixed(1)}" x2="${pTarget.x.toFixed(1)}" y2="${pTarget.y.toFixed(1)}" stroke="${targetColor}" stroke-width="1.8" stroke-dasharray="3,2" />

    <!-- 3D Direct Deviation Vector (0,0,0) -> (en, nn, un) -->
    <line x1="${p0.x.toFixed(1)}" y1="${p0.y.toFixed(1)}" x2="${pTarget.x.toFixed(1)}" y2="${pTarget.y.toFixed(1)}" stroke="${targetColor}" stroke-width="2.2" marker-end="url(#arrow-3d-vec)" />

    <!-- Target Point Dot -->
    <circle cx="${pTarget.x.toFixed(1)}" cy="${pTarget.y.toFixed(1)}" r="10" fill="${targetColor}" fill-opacity="0.2" />
    <circle id="cube-target-dot" cx="${pTarget.x.toFixed(1)}" cy="${pTarget.y.toFixed(1)}" r="5.5" fill="${targetColor}" stroke="#ffffff" stroke-width="1.8" style="cursor: pointer;" />

    <!-- Floating Target Badge -->
    <g transform="translate(${(pTarget.x + 8).toFixed(1)}, ${(pTarget.y - 18).toFixed(1)})">
      <rect x="0" y="0" width="86" height="26" rx="4" fill="rgba(15, 23, 42, 0.88)" />
      <text x="6" y="11" fill="#f8fafc" font-size="8.5" font-family="monospace" font-weight="700">ΔR ${positionDifference(data.deltaR)}</text>
      <text x="6" y="21" fill="#38bdf8" font-size="8" font-family="monospace">ΔU ${(data.deltaU >= 0 ? '+' : '')}${positionDifference(Math.abs(data.deltaU))}</text>
    </g>

    <!-- Top Left Range Marker -->
    <text x="10" y="18" fill="#64748b" font-size="10" font-family="monospace" font-weight="600">격자: ±${formatRingDist(boxRange)} (${cubeZoom.toFixed(1)}x)</text>
  `;

  // Attach hover inspector on cube target dot
  const dot = svg.querySelector('#cube-target-dot');
  if (dot) {
    dot.addEventListener('mouseenter', (e) => showTargetTooltip(e, data));
    dot.addEventListener('mousemove', (e) => showTargetTooltip(e, data));
    dot.addEventListener('mouseleave', hideTargetTooltip);
  }
}

let cubeDragInitialized = false;
function initCubeInteraction() {
  if (cubeDragInitialized) return;
  cubeDragInitialized = true;

  const btnHud = $('btn-view-hud');
  const btn3d = $('btn-view-3d');
  const btnReset = $('btn-reset-3d');
  const btnIso = $('btn-preset-iso');
  const btnTop = $('btn-preset-top');
  const btnSide = $('btn-preset-side');
  const zoomBadge = $('cube-zoom-container');
  const wrap = $('cube-3d-wrap');

  if (btnHud) btnHud.onclick = () => setTargetViewMode('hud');
  if (btn3d) btn3d.onclick = () => setTargetViewMode('3d');

  const updatePresetButtons = (activePreset) => {
    [btnIso, btnTop, btnSide].forEach(btn => btn?.classList.remove('active'));
    if (activePreset === 'iso') btnIso?.classList.add('active');
    else if (activePreset === 'top') btnTop?.classList.add('active');
    else if (activePreset === 'side') btnSide?.classList.add('active');
  };

  if (btnIso) btnIso.onclick = () => {
    cubeYaw = 0.65; cubePitch = 0.42; updatePresetButtons('iso');
    if (lastEnuData) render3DCube(lastEnuData);
  };
  if (btnTop) btnTop.onclick = () => {
    cubeYaw = 0.0; cubePitch = 1.55; updatePresetButtons('top');
    if (lastEnuData) render3DCube(lastEnuData);
  };
  if (btnSide) btnSide.onclick = () => {
    cubeYaw = 0.0; cubePitch = 0.04; updatePresetButtons('side');
    if (lastEnuData) render3DCube(lastEnuData);
  };

  const resetView = () => {
    cubeYaw = 0.65; cubePitch = 0.42; cubeZoom = 1.0;
    if ($('cube-zoom-val')) $('cube-zoom-val').textContent = '1.0x';
    updatePresetButtons('iso');
    if (lastEnuData) render3DCube(lastEnuData);
  };

  if (btnReset) btnReset.onclick = resetView;
  if (zoomBadge) zoomBadge.ondblclick = resetView;

  if (wrap) {
    let isDragging = false;
    let startX = 0;
    let startY = 0;

    wrap.addEventListener('pointerdown', (e) => {
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;
      try { wrap.setPointerCapture?.(e.pointerId); } catch {}
    });

    wrap.addEventListener('pointermove', (e) => {
      if (!isDragging) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;
      startX = e.clientX;
      startY = e.clientY;

      cubeYaw += dx * 0.015;
      cubePitch = Math.max(0.04, Math.min(1.55, cubePitch + dy * 0.015));
      updatePresetButtons(null);

      if (lastEnuData) render3DCube(lastEnuData);
    });

    const stopDrag = (e) => {
      if (!isDragging) return;
      isDragging = false;
      try { wrap.releasePointerCapture?.(e.pointerId); } catch {}
    };

    wrap.addEventListener('pointerup', stopDrag);
    wrap.addEventListener('pointercancel', stopDrag);

    // Mouse wheel zoom
    wrap.addEventListener('wheel', (e) => {
      e.preventDefault();
      const factor = e.deltaY < 0 ? 1.15 : 0.87;
      cubeZoom = Math.max(0.5, Math.min(4.0, cubeZoom * factor));
      if ($('cube-zoom-val')) $('cube-zoom-val').textContent = cubeZoom.toFixed(1) + 'x';
      if (lastEnuData) render3DCube(lastEnuData);
    }, { passive: false });
  }

  // Hover inspector on 2D radar point
  const radarPoint = $('enu-target-point');
  if (radarPoint) {
    radarPoint.addEventListener('mouseenter', (e) => {
      if (lastEnuData) showTargetTooltip(e, lastEnuData);
    });
    radarPoint.addEventListener('mousemove', (e) => {
      if (lastEnuData) showTargetTooltip(e, lastEnuData);
    });
    radarPoint.addEventListener('mouseleave', hideTargetTooltip);
  }
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
  const outerRadius = 140;
  if ($('enu-ring-outer-label')) $('enu-ring-outer-label').textContent = formatRingDist(scaleMeters);
  if ($('enu-ring-mid-label')) $('enu-ring-mid-label').textContent = formatRingDist(scaleMeters * (95/140));
  if ($('enu-ring-inner-label')) $('enu-ring-inner-label').textContent = formatRingDist(scaleMeters * (50/140));

  const pxX = Math.max(-outerRadius, Math.min(outerRadius, (enu.e / scaleMeters) * outerRadius));
  const pxY = Math.max(-outerRadius, Math.min(outerRadius, (-enu.n / scaleMeters) * outerRadius));

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

  // 수직 고도(ΔU) 슬림 게이지 업데이트 - 5단계 눈금 틱
  let altRange = 0.005;
  if (Math.abs(deltaU) > 0.05) {
    altRange = Math.max(0.1, Math.ceil(Math.abs(deltaU) * 1.3 * 10) / 10);
  } else if (Math.abs(deltaU) > 0.01) {
    altRange = 0.05;
  } else if (Math.abs(deltaU) > 0.005) {
    altRange = 0.01;
  } else if (Math.abs(deltaU) > 0.002) {
    altRange = 0.005;
  } else if (Math.abs(deltaU) > 0.001) {
    altRange = 0.002;
  }

  if ($('alt-tick-top')) $('alt-tick-top').textContent = '+' + formatRingDist(altRange);
  if ($('alt-tick-mid-top')) $('alt-tick-mid-top').textContent = '+' + formatRingDist(altRange * 0.5);
  if ($('alt-tick-mid-bottom')) $('alt-tick-mid-bottom').textContent = '-' + formatRingDist(altRange * 0.5);
  if ($('alt-tick-bottom')) $('alt-tick-bottom').textContent = '-' + formatRingDist(altRange);

  const altBar = $('alt-gauge-bar');
  const altThumb = $('alt-gauge-thumb');
  const altThumbVal = $('alt-gauge-thumb-val');

  if (altThumbVal) {
    altThumbVal.textContent = (deltaU >= 0 ? '+' : '-') + positionDifference(Math.abs(deltaU));
  }

  const uNorm = Math.max(-1, Math.min(1, deltaU / altRange));
  const thumbTopPct = 50 - uNorm * 46;
  if (altThumb) {
    altThumb.style.top = thumbTopPct.toFixed(1) + '%';
  }

  if (altBar) {
    if (deltaU >= 0) {
      altBar.style.top = thumbTopPct.toFixed(1) + '%';
      altBar.style.height = (50 - thumbTopPct).toFixed(1) + '%';
      altBar.style.background = deltaR <= 0.05 ? '#10b981' : deltaR <= 1.0 ? '#f59e0b' : '#ef4444';
    } else {
      altBar.style.top = '50%';
      altBar.style.height = (thumbTopPct - 50).toFixed(1) + '%';
      altBar.style.background = deltaR <= 0.05 ? '#3b82f6' : deltaR <= 1.0 ? '#f59e0b' : '#ef4444';
    }
  }

  // 3D 큐브 데이터 갱신 및 렌더링
  lastEnuData = { e: enu.e, n: enu.n, u: deltaU, deltaH, deltaU, deltaR, dx, dy, dz };
  if (targetViewMode === '3d') {
    render3DCube(lastEnuData);
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
  const delta = pvt && comparisonEpochs.find(e => e.week === pvt.week && Math.abs(e.towSeconds - pvt.towSeconds) < 0.001);
  const deltaR = delta?.positionDifferenceMeters;
  const deltaV = delta?.velocityDifferenceMetersPerSecond;
  $('pvt-differences').textContent = '위치 차이 '+positionDifference(deltaR)+' · 속도 차이 '+measured(deltaV,6,'m/s')
    + (delayComparison ? '' : ' · 시계오차 차이 '+number(delta?.clockDifferenceSeconds,12)+' s');

  const reference = pvt && (delayComparison ? (delta ? referenceEpochs[0] : null) : referenceEpochs.find(value => value.week === pvt.week && value.towSeconds === pvt.towSeconds));
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
      (receivedIds !== null && !receivedIds.has(job.testId)))
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
    $('dtn-tests').replaceChildren(new Option(tests.length ? '시험 선택' : '등록된 시험 없음', ''), ...(tests.length ? tests.map(job =>
      trialOption(job, time(job.createdAt))) : []));
    if (newlyReceived && !selectionPinned && historyPage === 0) $('dtn-tests').value = newlyReceived.testId;
    else if (tests.some(job => job.testId === selected)) $('dtn-tests').value = selected;
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
  initCubeInteraction();
  trialDialogHandler = initTrialSettingsDialog(() => tests.find(item => item.testId === $('dtn-tests').value));
  if ($('dtn-adapter-strip-status')) {
    if ($('dtn-adapter-strip-status').style) $('dtn-adapter-strip-status').style.cursor = 'pointer';
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
