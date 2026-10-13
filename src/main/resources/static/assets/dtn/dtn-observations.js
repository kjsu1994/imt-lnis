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

export function navigationSubframeKey(item) {
  const msg = item?.message || item || {};
  const cid = msg.constellationId;
  const words = msg.words;
  if (!words || !words.length) return null;

  if (cid === 0 || cid === 5) {
    const sf = item?.display?.subframeId ?? ((Number(words[1]) >>> 8) & 0x7);
    if (sf >= 1 && sf <= 4) return 'SF' + sf;
    return null;
  }
  if (cid === 3) {
    if (words.length === 10) {
      const frn = (Number(words[0]) >>> 12) & 0x7;
      if (frn >= 1 && frn <= 4) return 'SF' + frn;
    }
    return null;
  }
  if (cid === 2) {
    if (words.length === 8) {
      const type = (Number(words[0]) >>> 24) & 0x3f;
      if (type >= 1 && type <= 5) return 'W' + type;
    }
    return null;
  }
  return null;
}

export function getActivePvtNavigationItems(navigation, navigationPositions, epochPosition) {
  const latestByKey = new Map();
  if (!Array.isArray(navigation)) return new Set();
  for (let i = 0; i < navigation.length; i++) {
    const item = navigation[i];
    const pos = navigationPositions ? navigationPositions[i] : null;
    if (epochPosition != null && pos != null && pos >= epochPosition) {
      continue;
    }
    const msg = item?.message || item || {};
    const satKey = msg.constellationId + ':' + msg.satelliteId;
    const sfKey = navigationSubframeKey(item);
    if (!sfKey) continue;
    latestByKey.set(satKey + ':' + sfKey, item);
  }
  return new Set(latestByKey.values());
}

function extractLnavBits(words, startBit, numBits, signed = false, iq = false) {
  let val = 0n;
  for (let i = 0; i < numBits; i++) {
    const b = startBit + i;
    const w = Math.floor(b / 24);
    if (w >= words.length) return null;
    const bitInWord = b % 24;
    const shift = iq ? (23 - bitInWord) : (29 - bitInWord);
    const bit = (BigInt(words[w]) >> BigInt(shift)) & 1n;
    val = (val << 1n) | bit;
  }
  if (signed) {
    const signBit = 1n << BigInt(numBits - 1);
    if ((val & signBit) !== 0n) {
      val = val - (1n << BigInt(numBits));
    }
  }
  return Number(val);
}

function decodeGpsLnavParameters(words, iq, constellationName = 'GPS') {
  const sfId = extractLnavBits(words, 43, 3, false, iq);
  if (![1, 2, 3].includes(sfId)) return null;

  if (sfId === 1) {
    const week = extractLnavBits(words, 48, 10, false, iq);
    const health = extractLnavBits(words, 64, 6, false, iq);
    const iodcMsb = extractLnavBits(words, 70, 2, false, iq);
    const tgdRaw = extractLnavBits(words, 160, 8, true, iq);
    const iodcLsb = extractLnavBits(words, 168, 8, false, iq);
    const tocRaw = extractLnavBits(words, 176, 16, false, iq);
    const af2Raw = extractLnavBits(words, 192, 8, true, iq);
    const af1Raw = extractLnavBits(words, 200, 16, true, iq);
    const af0Raw = extractLnavBits(words, 216, 22, true, iq);

    const iodc = (iodcMsb << 8) | iodcLsb;
    const tgd = tgdRaw * Math.pow(2, -31);
    const toc = tocRaw * 16;
    const af2 = af2Raw * Math.pow(2, -55);
    const af1 = af1Raw * Math.pow(2, -43);
    const af0 = af0Raw * Math.pow(2, -31);

    return {
      constellation: constellationName,
      subframeId: 1,
      title: 'SF1 · 위성 시계·상태 (' + constellationName + ')',
      elements: [
        { target: 'SB2', name: 't_oc', fullName: 't_oc (시계 기준시각)', meaning: '위성 시계 기준시각', wordOrigin: 'Word 8', value: toc, formatted: toc.toLocaleString() + ' s' },
        { target: 'SB2', name: 'a_f0', fullName: 'a_f0 (시계 편차)', meaning: '위성 시계 편차 (Bias)', wordOrigin: 'Word 10', value: af0, formatted: (Math.abs(af0) < 1e-3 ? (af0 * 1e6).toFixed(3) + ' µs (' + af0.toExponential(4) + ' s)' : af0.toFixed(6) + ' s') },
        { target: 'SB2', name: 'a_f1', fullName: 'a_f1 (시계 표류율)', meaning: '위성 시계 표류율 (Drift)', wordOrigin: 'Word 9', value: af1, formatted: af1.toExponential(4) + ' s/s' },
        { target: 'SB3', name: 'a_f2', fullName: 'a_f2 (시계 드리프트율)', meaning: '2차 시계 가속도', wordOrigin: 'Word 9', value: af2, formatted: af2 === 0 ? '0.0 s/s²' : af2.toExponential(4) + ' s/s²' },
        { target: 'SB3', name: 'T_GD', fullName: 'T_GD (전송 지연차)', meaning: 'L1/L2 군지연차', wordOrigin: 'Word 7', value: tgd, formatted: (tgd * 1e9).toFixed(3) + ' ns (' + tgd.toExponential(4) + ' s)' },
        { target: 'SB3', name: 'IODC', fullName: 'IODC (시계 데이터 번호)', meaning: '시계 이슈 번호', wordOrigin: 'Word 3 & 8', value: iodc, formatted: String(iodc) },
        { target: 'SB3', name: 'Week', fullName: 'GPS Week', meaning: '기준 주차 번호', wordOrigin: 'Word 3', value: week, formatted: 'Week ' + week },
        { target: 'SB3', name: 'SV Health', fullName: 'SV Health (위성 건전성)', meaning: '위성 신호 정상 여부', wordOrigin: 'Word 3', value: health, formatted: health === 0 ? '0 (정상)' : '0x' + health.toString(16) + ' (비정상)' }
      ]
    };
  }

  if (sfId === 2) {
    const iode = extractLnavBits(words, 48, 8, false, iq);
    const crsRaw = extractLnavBits(words, 56, 16, true, iq);
    const dnRaw = extractLnavBits(words, 72, 16, true, iq);
    const m0Raw = extractLnavBits(words, 88, 32, true, iq);
    const cucRaw = extractLnavBits(words, 120, 16, true, iq);
    const eRaw = extractLnavBits(words, 136, 32, false, iq);
    const cusRaw = extractLnavBits(words, 168, 16, true, iq);
    const sqrtaRaw = extractLnavBits(words, 184, 32, false, iq);
    const toeRaw = extractLnavBits(words, 216, 16, false, iq);

    const crs = crsRaw * Math.pow(2, -5);
    const dn = dnRaw * Math.pow(2, -43) * Math.PI;
    const m0 = m0Raw * Math.pow(2, -31) * Math.PI;
    const cuc = cucRaw * Math.pow(2, -29);
    const ecc = eRaw * Math.pow(2, -33);
    const cus = cusRaw * Math.pow(2, -29);
    const sqrta = sqrtaRaw * Math.pow(2, -19);
    const toe = toeRaw * 16;
    const aKm = Math.pow(sqrta, 2) / 1000;

    return {
      constellation: constellationName,
      subframeId: 2,
      title: 'SF2 · 궤도정보 ① (' + constellationName + ')',
      elements: [
        { target: 'SB2', name: 't_oe', fullName: 't_oe (궤도 기준시각)', meaning: 'Ephemeris 기준시각', wordOrigin: 'Word 10', value: toe, formatted: toe.toLocaleString() + ' s' },
        { target: 'SB2', name: '√a', fullName: '√a (장반경 제곱근)', meaning: '궤도 장반경 제곱근 (크기)', wordOrigin: 'Word 8~9', value: sqrta, formatted: sqrta.toFixed(4) + ' m^(1/2) (a ≈ ' + aKm.toFixed(1) + ' km)' },
        { target: 'SB2', name: 'e', fullName: 'e (이심률)', meaning: '궤도 이심률 (찌그러짐)', wordOrigin: 'Word 6~7', value: ecc, formatted: ecc.toFixed(8) },
        { target: 'SB2', name: 'M_0', fullName: 'M_0 (평균근점각)', meaning: '기준 평균근점각 (위치)', wordOrigin: 'Word 4~5', value: m0, formatted: (m0 * 180 / Math.PI).toFixed(2) + '° (' + m0.toFixed(4) + ' rad)' },
        { target: 'SB3', name: 'C_rs', fullName: 'C_rs (반경 Sine 섭동)', meaning: '궤도 반경 Sine 조화 섭동', wordOrigin: 'Word 3', value: crs, formatted: (crs >= 0 ? '+' : '') + crs.toFixed(3) + ' m' },
        { target: 'SB3', name: 'C_uc', fullName: 'C_uc (위도 Cos 섭동)', meaning: '궤도 위도 Cosine 조화 섭동', wordOrigin: 'Word 6', value: cuc, formatted: (cuc >= 0 ? '+' : '') + cuc.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_us', fullName: 'C_us (위도 Sin 섭동)', meaning: '궤도 위도 Sine 조화 섭동', wordOrigin: 'Word 8', value: cus, formatted: (cus >= 0 ? '+' : '') + cus.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'Δn', fullName: 'Δn (평균운동량 보정)', meaning: '평균 운동량 섭동 보정치', wordOrigin: 'Word 4', value: dn, formatted: (dn >= 0 ? '+' : '') + dn.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IODE', fullName: 'IODE (궤도 데이터 번호)', meaning: '궤도 이슈 번호', wordOrigin: 'Word 3', value: iode, formatted: String(iode) + ' (0x' + iode.toString(16).toUpperCase() + ')' }
      ]
    };
  }

  if (sfId === 3) {
    const cicRaw = extractLnavBits(words, 48, 16, true, iq);
    const omg0Raw = extractLnavBits(words, 64, 32, true, iq);
    const cisRaw = extractLnavBits(words, 96, 16, true, iq);
    const inc0Raw = extractLnavBits(words, 112, 32, true, iq);
    const crcRaw = extractLnavBits(words, 144, 16, true, iq);
    const aopRaw = extractLnavBits(words, 160, 32, true, iq);
    const omgDotRaw = extractLnavBits(words, 192, 24, true, iq);
    const iode = extractLnavBits(words, 216, 8, false, iq);
    const idotRaw = extractLnavBits(words, 224, 14, true, iq);

    const cic = cicRaw * Math.pow(2, -29);
    const omg0 = omg0Raw * Math.pow(2, -31) * Math.PI;
    const cis = cisRaw * Math.pow(2, -29);
    const inc0 = inc0Raw * Math.pow(2, -31) * Math.PI;
    const crc = crcRaw * Math.pow(2, -5);
    const aop = aopRaw * Math.pow(2, -31) * Math.PI;
    const omgDot = omgDotRaw * Math.pow(2, -43) * Math.PI;
    const idot = idotRaw * Math.pow(2, -43) * Math.PI;

    return {
      constellation: constellationName,
      subframeId: 3,
      title: 'SF3 · 궤도정보 ② (' + constellationName + ')',
      elements: [
        { target: 'SB2', name: 'i_0', fullName: 'i_0 (궤도 경사각)', meaning: '기준 궤도 경사각 (기울기)', wordOrigin: 'Word 5~6', value: inc0, formatted: (inc0 * 180 / Math.PI).toFixed(4) + '° (' + inc0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'Ω_0', fullName: 'Ω_0 (승교점 적경)', meaning: '승교점 적경 (경도 위치)', wordOrigin: 'Word 3~4', value: omg0, formatted: (omg0 * 180 / Math.PI).toFixed(2) + '° (' + omg0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'ω', fullName: 'ω (근지점 인수)', meaning: '근지점 인수 (타원 방향)', wordOrigin: 'Word 7~8', value: aop, formatted: (aop * 180 / Math.PI).toFixed(2) + '° (' + aop.toFixed(4) + ' rad)' },
        { target: 'SB3', name: 'C_rc', fullName: 'C_rc (반경 Cos 섭동)', meaning: '궤도 반경 Cosine 조화 섭동', wordOrigin: 'Word 7', value: crc, formatted: (crc >= 0 ? '+' : '') + crc.toFixed(3) + ' m' },
        { target: 'SB3', name: 'C_ic', fullName: 'C_ic (경사각 Cos 섭동)', meaning: '경사각 Cosine 조화 섭동', wordOrigin: 'Word 3', value: cic, formatted: (cic >= 0 ? '+' : '') + cic.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_is', fullName: 'C_is (경사각 Sin 섭동)', meaning: '경사각 Sine 조화 섭동', wordOrigin: 'Word 5', value: cis, formatted: (cis >= 0 ? '+' : '') + cis.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'Ω̇', fullName: 'Ω̇ (승교점 세차율)', meaning: '승교점 세차율 (궤도면 회전)', wordOrigin: 'Word 9', value: omgDot, formatted: (omgDot >= 0 ? '+' : '') + omgDot.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IDOT', fullName: 'IDOT (경사각 변화율)', meaning: '경사각 시간 변화율', wordOrigin: 'Word 10', value: idot, formatted: (idot >= 0 ? '+' : '') + idot.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IODE', fullName: 'IODE (궤도 데이터 번호)', meaning: '궤도 이슈 번호', wordOrigin: 'Word 10', value: iode, formatted: String(iode) + ' (0x' + iode.toString(16).toUpperCase() + ')' }
      ]
    };
  }

  return null;
}

function decodeBdsD1Parameters(words, iq) {
  if (!words || words.length !== 10) return null;

  const extractBdsBit = (bitPos) => {
    const w = Math.floor(bitPos / 30);
    if (w >= words.length) return 0n;
    const bitInWord = bitPos % 30;
    const shift = 29 - bitInWord;
    return (BigInt(words[w]) >> BigInt(shift)) & 1n;
  };

  const getbitu = (pos, len) => {
    let val = 0n;
    for (let i = 0; i < len; i++) {
      val = (val << 1n) | extractBdsBit(pos + i);
    }
    return Number(val);
  };

  const getbits = (pos, len) => {
    let val = 0n;
    for (let i = 0; i < len; i++) {
      val = (val << 1n) | extractBdsBit(pos + i);
    }
    const signBit = 1n << BigInt(len - 1);
    if ((val & signBit) !== 0n) {
      val = val - (1n << BigInt(len));
    }
    return Number(val);
  };

  const getbitu2 = (p1, l1, p2, l2) => {
    const v1 = BigInt(getbitu(p1, l1));
    const v2 = BigInt(getbitu(p2, l2));
    return Number((v1 << BigInt(l2)) | v2);
  };

  const getbits2 = (p1, l1, p2, l2) => {
    const u1 = getbitu(p1, 1);
    const b1 = BigInt(getbits(p1, l1));
    const u2 = BigInt(getbitu(p2, l2));
    if (u1) {
      return Number((b1 << BigInt(l2)) | u2);
    } else {
      return Number((BigInt(getbitu(p1, l1)) << BigInt(l2)) | u2);
    }
  };

  const frn = getbitu(15, 3);
  if (![1, 2, 3].includes(frn)) return null;

  if (frn === 1) {
    const svh = getbitu(42, 1);
    const iodc = getbitu(43, 5); // AODC
    const week = getbitu(60, 13);
    const toc = getbitu2(73, 9, 90, 8) * 8.0;
    const tgd1 = getbits(98, 10) * 0.1 * 1e-9;
    const tgd2 = getbits2(108, 4, 120, 6) * 0.1 * 1e-9;
    const af2 = getbits(214, 11) * Math.pow(2, -66);
    const af0 = getbits2(225, 7, 240, 17) * Math.pow(2, -33);
    const af1 = getbits2(257, 5, 270, 17) * Math.pow(2, -50);
    const iode = getbitu(287, 5); // AODE

    return {
      constellation: 'BeiDou',
      subframeId: 1,
      title: 'SF1 · 위성 시계·상태 (BDS B1I)',
      elements: [
        { target: 'SB2', name: 't_oc', fullName: 't_oc (시계 기준시각)', meaning: '위성 시계 기준시각', wordOrigin: 'Word 3~4', value: toc, formatted: toc.toLocaleString() + ' s' },
        { target: 'SB2', name: 'a_f0', fullName: 'a_f0 (시계 편차)', meaning: '위성 시계 편차 (Bias)', wordOrigin: 'Word 8~9', value: af0, formatted: (Math.abs(af0) < 1e-3 ? (af0 * 1e6).toFixed(3) + ' µs (' + af0.toExponential(4) + ' s)' : af0.toFixed(6) + ' s') },
        { target: 'SB2', name: 'a_f1', fullName: 'a_f1 (시계 표류율)', meaning: '위성 시계 표류율 (Drift)', wordOrigin: 'Word 9~10', value: af1, formatted: af1.toExponential(4) + ' s/s' },
        { target: 'SB3', name: 'a_f2', fullName: 'a_f2 (시계 드리프트율)', meaning: '2차 시계 가속도', wordOrigin: 'Word 8', value: af2, formatted: af2 === 0 ? '0.0 s/s²' : af2.toExponential(4) + ' s/s²' },
        { target: 'SB3', name: 'T_GD1', fullName: 'T_GD1 (B1/B3 전송지연차)', meaning: 'B1 군지연차', wordOrigin: 'Word 4', value: tgd1, formatted: (tgd1 * 1e9).toFixed(1) + ' ns' },
        { target: 'SB3', name: 'T_GD2', fullName: 'T_GD2 (B2/B3 전송지연차)', meaning: 'B2 군지연차', wordOrigin: 'Word 4~5', value: tgd2, formatted: (tgd2 * 1e9).toFixed(1) + ' ns' },
        { target: 'SB3', name: 'AODC', fullName: 'AODC (시계 데이터 번호)', meaning: '시계 이슈 번호', wordOrigin: 'Word 2', value: iodc, formatted: String(iodc) },
        { target: 'SB3', name: 'AODE', fullName: 'AODE (궤도 데이터 번호)', meaning: '궤도 이슈 번호', wordOrigin: 'Word 10', value: iode, formatted: String(iode) },
        { target: 'SB3', name: 'Week', fullName: 'BDT Week', meaning: '기준 주차 번호', wordOrigin: 'Word 3', value: week, formatted: 'Week ' + week },
        { target: 'SB3', name: 'SV Health', fullName: 'SV Health (위성 건전성)', meaning: '위성 신호 정상 여부', wordOrigin: 'Word 2', value: svh, formatted: svh === 0 ? '0 (정상)' : '0x' + svh.toString(16) + ' (비정상)' }
      ]
    };
  }

  if (frn === 2) {
    const dn = getbits2(42, 10, 60, 6) * Math.pow(2, -43) * Math.PI;
    const cuc = getbits2(66, 16, 90, 2) * Math.pow(2, -31);
    const m0 = getbits2(92, 20, 120, 12) * Math.pow(2, -31) * Math.PI;
    const ecc = getbitu2(132, 10, 150, 22) * Math.pow(2, -33);
    const cus = getbits(180, 18) * Math.pow(2, -31);
    const crc = getbits2(198, 4, 210, 14) * Math.pow(2, -6);
    const crs = getbits2(224, 8, 240, 10) * Math.pow(2, -6);
    const sqrta = getbitu2(250, 12, 270, 20) * Math.pow(2, -19);
    const toe1 = getbitu(290, 2);
    const aKm = Math.pow(sqrta, 2) / 1000;

    return {
      constellation: 'BeiDou',
      subframeId: 2,
      title: 'SF2 · 궤도정보 ① (BDS B1I)',
      elements: [
        { target: 'SB2', name: '√a', fullName: '√a (장반경 제곱근)', meaning: '궤도 장반경 제곱근 (크기)', wordOrigin: 'Word 9~10', value: sqrta, formatted: sqrta.toFixed(4) + ' m^(1/2) (a ≈ ' + aKm.toFixed(1) + ' km)' },
        { target: 'SB2', name: 'e', fullName: 'e (이심률)', meaning: '궤도 이심률 (찌그러짐)', wordOrigin: 'Word 5~6', value: ecc, formatted: ecc.toFixed(8) },
        { target: 'SB2', name: 'M_0', fullName: 'M_0 (평균근점각)', meaning: '기준 평균근점각 (위치)', wordOrigin: 'Word 4~5', value: m0, formatted: (m0 * 180 / Math.PI).toFixed(2) + '° (' + m0.toFixed(4) + ' rad)' },
        { target: 'SB3', name: 't_oe (MSB)', fullName: 't_oe (기준시각 상위 2bit)', meaning: 'Ephemeris 기준시각 상위', wordOrigin: 'Word 10', value: toe1, formatted: '0b' + toe1.toString(2).padStart(2, '0') + ' (SF3에서 완성)' },
        { target: 'SB3', name: 'C_rs', fullName: 'C_rs (반경 Sine 섭동)', meaning: '궤도 반경 Sine 조화 섭동', wordOrigin: 'Word 8~9', value: crs, formatted: (crs >= 0 ? '+' : '') + crs.toFixed(3) + ' m' },
        { target: 'SB3', name: 'C_uc', fullName: 'C_uc (위도 Cos 섭동)', meaning: '궤도 위도 Cosine 조화 섭동', wordOrigin: 'Word 3~4', value: cuc, formatted: (cuc >= 0 ? '+' : '') + cuc.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_us', fullName: 'C_us (위도 Sin 섭동)', meaning: '궤도 위도 Sine 조화 섭동', wordOrigin: 'Word 7', value: cus, formatted: (cus >= 0 ? '+' : '') + cus.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_rc', fullName: 'C_rc (반경 Cos 섭동)', meaning: '궤도 반경 Cosine 조화 섭동', wordOrigin: 'Word 7~8', value: crc, formatted: (crc >= 0 ? '+' : '') + crc.toFixed(3) + ' m' },
        { target: 'SB3', name: 'Δn', fullName: 'Δn (평균운동량 보정)', meaning: '평균 운동량 섭동 보정치', wordOrigin: 'Word 2~3', value: dn, formatted: (dn >= 0 ? '+' : '') + dn.toExponential(4) + ' rad/s' }
      ]
    };
  }

  if (frn === 3) {
    const toe2 = getbitu2(42, 10, 60, 5);
    const inc0 = getbits2(65, 17, 90, 15) * Math.pow(2, -31) * Math.PI;
    const cic = getbits2(105, 7, 120, 11) * Math.pow(2, -31);
    const omgDot = getbits2(131, 11, 150, 13) * Math.pow(2, -43) * Math.PI;
    const cis = getbits2(163, 9, 180, 9) * Math.pow(2, -31);
    const idot = getbits2(189, 13, 210, 1) * Math.pow(2, -43) * Math.PI;
    const omg0 = getbits2(211, 21, 240, 11) * Math.pow(2, -31) * Math.PI;
    const aop = getbits2(251, 11, 270, 21) * Math.pow(2, -31) * Math.PI;
    const toeApprox = toe2 * 8.0;

    return {
      constellation: 'BeiDou',
      subframeId: 3,
      title: 'SF3 · 궤도정보 ② (BDS B1I)',
      elements: [
        { target: 'SB2', name: 'i_0', fullName: 'i_0 (궤도 경사각)', meaning: '기준 궤도 경사각 (기울기)', wordOrigin: 'Word 3~4', value: inc0, formatted: (inc0 * 180 / Math.PI).toFixed(4) + '° (' + inc0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'Ω_0', fullName: 'Ω_0 (승교점 적경)', meaning: '승교점 적경 (경도 위치)', wordOrigin: 'Word 8~9', value: omg0, formatted: (omg0 * 180 / Math.PI).toFixed(2) + '° (' + omg0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'ω', fullName: 'ω (근지점 인수)', meaning: '근지점 인수 (타원 방향)', wordOrigin: 'Word 9~10', value: aop, formatted: (aop * 180 / Math.PI).toFixed(2) + '° (' + aop.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 't_oe', fullName: 't_oe (궤도 기준시각 LSB)', meaning: 'Ephemeris 기준시각', wordOrigin: 'Word 2~3', value: toeApprox, formatted: toeApprox.toLocaleString() + ' s' },
        { target: 'SB3', name: 'C_ic', fullName: 'C_ic (경사각 Cos 섭동)', meaning: '경사각 Cosine 조화 섭동', wordOrigin: 'Word 4~5', value: cic, formatted: (cic >= 0 ? '+' : '') + cic.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_is', fullName: 'C_is (경사각 Sin 섭동)', meaning: '경사각 Sine 조화 섭동', wordOrigin: 'Word 6~7', value: cis, formatted: (cis >= 0 ? '+' : '') + cis.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'Ω̇', fullName: 'Ω̇ (승교점 세차율)', meaning: '승교점 세차율 (궤도면 회전)', wordOrigin: 'Word 5~6', value: omgDot, formatted: (omgDot >= 0 ? '+' : '') + omgDot.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IDOT', fullName: 'IDOT (경사각 변화율)', meaning: '경사각 시간 변화율', wordOrigin: 'Word 7~8', value: idot, formatted: (idot >= 0 ? '+' : '') + idot.toExponential(4) + ' rad/s' }
      ]
    };
  }

  return null;
}

function decodeGalileoInavParameters(words, iq) {
  if (!words || words.length !== 8) return null;

  const extractGalBits = (startBit, numBits, signed = false) => {
    let val = 0n;
    for (let i = 0; i < numBits; i++) {
      const bitIdx = startBit + i;
      const w = Math.floor(bitIdx / 32);
      if (w >= words.length) return null;
      const shift = 31 - (bitIdx % 32);
      const bit = (BigInt(words[w]) >> BigInt(shift)) & 1n;
      val = (val << 1n) | bit;
    }
    if (signed) {
      const signBit = 1n << BigInt(numBits - 1);
      if ((val & signBit) !== 0n) val = val - (1n << BigInt(numBits));
    }
    return Number(val);
  };

  const type = extractGalBits(0, 6, false);
  if (![1, 2, 3, 4].includes(type)) return null;

  if (type === 1) {
    const iodNav = extractGalBits(6, 10, false);
    const toeRaw = extractGalBits(16, 14, false);
    const m0Raw = extractGalBits(30, 32, true);
    const eRaw = extractGalBits(62, 32, false);
    const sqrtaRaw = extractGalBits(94, 32, false);

    const toe = toeRaw * 60;
    const m0 = m0Raw * Math.pow(2, -31) * Math.PI;
    const ecc = eRaw * Math.pow(2, -33);
    const sqrta = sqrtaRaw * Math.pow(2, -19);
    const aKm = Math.pow(sqrta, 2) / 1000;

    return {
      constellation: 'Galileo',
      subframeId: 1,
      title: 'Word 1 · 궤도정보 ① (GAL I/NAV)',
      elements: [
        { target: 'SB2', name: 't_oe', fullName: 't_oe (궤도 기준시각)', meaning: 'Ephemeris 기준시각', wordOrigin: 'Word 1', value: toe, formatted: toe.toLocaleString() + ' s' },
        { target: 'SB2', name: '√a', fullName: '√a (장반경 제곱근)', meaning: '궤도 장반경 제곱근 (크기)', wordOrigin: 'Word 3~4', value: sqrta, formatted: sqrta.toFixed(4) + ' m^(1/2) (a ≈ ' + aKm.toFixed(1) + ' km)' },
        { target: 'SB2', name: 'e', fullName: 'e (이심률)', meaning: '궤도 이심률 (찌그러짐)', wordOrigin: 'Word 2~3', value: ecc, formatted: ecc.toFixed(8) },
        { target: 'SB2', name: 'M_0', fullName: 'M_0 (평균근점각)', meaning: '기준 평균근점각 (위치)', wordOrigin: 'Word 1~2', value: m0, formatted: (m0 * 180 / Math.PI).toFixed(2) + '° (' + m0.toFixed(4) + ' rad)' },
        { target: 'SB3', name: 'IODnav', fullName: 'IODnav (궤도 데이터 번호)', meaning: 'Galileo 궤도/시계 이슈 번호', wordOrigin: 'Word 1', value: iodNav, formatted: String(iodNav) }
      ]
    };
  }

  if (type === 2) {
    const iodNav = extractGalBits(6, 10, false);
    const omg0Raw = extractGalBits(16, 32, true);
    const inc0Raw = extractGalBits(48, 32, true);
    const aopRaw = extractGalBits(80, 32, true);
    const idotRaw = extractGalBits(112, 14, true);

    const omg0 = omg0Raw * Math.pow(2, -31) * Math.PI;
    const inc0 = inc0Raw * Math.pow(2, -31) * Math.PI;
    const aop = aopRaw * Math.pow(2, -31) * Math.PI;
    const idot = idotRaw * Math.pow(2, -43) * Math.PI;

    return {
      constellation: 'Galileo',
      subframeId: 2,
      title: 'Word 2 · 궤도정보 ② (GAL I/NAV)',
      elements: [
        { target: 'SB2', name: 'i_0', fullName: 'i_0 (궤도 경사각)', meaning: '기준 궤도 경사각 (기울기)', wordOrigin: 'Word 2~3', value: inc0, formatted: (inc0 * 180 / Math.PI).toFixed(4) + '° (' + inc0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'Ω_0', fullName: 'Ω_0 (승교점 적경)', meaning: '승교점 적경 (경도 위치)', wordOrigin: 'Word 1~2', value: omg0, formatted: (omg0 * 180 / Math.PI).toFixed(2) + '° (' + omg0.toFixed(4) + ' rad)' },
        { target: 'SB2', name: 'ω', fullName: 'ω (근지점 인수)', meaning: '근지점 인수 (타원 방향)', wordOrigin: 'Word 3~4', value: aop, formatted: (aop * 180 / Math.PI).toFixed(2) + '° (' + aop.toFixed(4) + ' rad)' },
        { target: 'SB3', name: 'IDOT', fullName: 'IDOT (경사각 변화율)', meaning: '경사각 시간 변화율', wordOrigin: 'Word 4', value: idot, formatted: (idot >= 0 ? '+' : '') + idot.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IODnav', fullName: 'IODnav (궤도 데이터 번호)', meaning: 'Galileo 궤도/시계 이슈 번호', wordOrigin: 'Word 1', value: iodNav, formatted: String(iodNav) }
      ]
    };
  }

  if (type === 3) {
    const iodNav = extractGalBits(6, 10, false);
    const omgDotRaw = extractGalBits(16, 24, true);
    const delnRaw = extractGalBits(40, 16, true);
    const cucRaw = extractGalBits(56, 16, true);
    const cusRaw = extractGalBits(72, 16, true);
    const crcRaw = extractGalBits(88, 16, true);
    const crsRaw = extractGalBits(104, 16, true);

    const omgDot = omgDotRaw * Math.pow(2, -43) * Math.PI;
    const deln = delnRaw * Math.pow(2, -43) * Math.PI;
    const cuc = cucRaw * Math.pow(2, -29);
    const cus = cusRaw * Math.pow(2, -29);
    const crc = crcRaw * Math.pow(2, -5);
    const crs = crsRaw * Math.pow(2, -5);

    return {
      constellation: 'Galileo',
      subframeId: 3,
      title: 'Word 3 · 섭동정보 (GAL I/NAV)',
      elements: [
        { target: 'SB3', name: 'C_rs', fullName: 'C_rs (반경 Sine 섭동)', meaning: '궤도 반경 Sine 조화 섭동', wordOrigin: 'Word 4', value: crs, formatted: (crs >= 0 ? '+' : '') + crs.toFixed(3) + ' m' },
        { target: 'SB3', name: 'C_rc', fullName: 'C_rc (반경 Cos 섭동)', meaning: '궤도 반경 Cosine 조화 섭동', wordOrigin: 'Word 3~4', value: crc, formatted: (crc >= 0 ? '+' : '') + crc.toFixed(3) + ' m' },
        { target: 'SB3', name: 'C_uc', fullName: 'C_uc (위도 Cos 섭동)', meaning: '궤도 위도 Cosine 조화 섭동', wordOrigin: 'Word 2~3', value: cuc, formatted: (cuc >= 0 ? '+' : '') + cuc.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_us', fullName: 'C_us (위도 Sin 섭동)', meaning: '궤도 위도 Sine 조화 섭동', wordOrigin: 'Word 3', value: cus, formatted: (cus >= 0 ? '+' : '') + cus.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'Ω̇', fullName: 'Ω̇ (승교점 세차율)', meaning: '승교점 세차율 (궤도면 회전)', wordOrigin: 'Word 1~2', value: omgDot, formatted: (omgDot >= 0 ? '+' : '') + omgDot.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'Δn', fullName: 'Δn (평균운동량 보정)', meaning: '평균 운동량 섭동 보정치', wordOrigin: 'Word 2', value: deln, formatted: (deln >= 0 ? '+' : '') + deln.toExponential(4) + ' rad/s' },
        { target: 'SB3', name: 'IODnav', fullName: 'IODnav (궤도 데이터 번호)', meaning: 'Galileo 궤도/시계 이슈 번호', wordOrigin: 'Word 1', value: iodNav, formatted: String(iodNav) }
      ]
    };
  }

  if (type === 4) {
    const iodNav = extractGalBits(6, 10, false);
    const svid = extractGalBits(16, 6, false);
    const cicRaw = extractGalBits(22, 16, true);
    const cisRaw = extractGalBits(38, 16, true);
    const tocRaw = extractGalBits(54, 14, false);
    const af0Raw = extractGalBits(68, 31, true);
    const af1Raw = extractGalBits(99, 21, true);
    const af2Raw = extractGalBits(120, 6, true);

    const cic = cicRaw * Math.pow(2, -29);
    const cis = cisRaw * Math.pow(2, -29);
    const toc = tocRaw * 60;
    const af0 = af0Raw * Math.pow(2, -34);
    const af1 = af1Raw * Math.pow(2, -46);
    const af2 = af2Raw * Math.pow(2, -59);

    return {
      constellation: 'Galileo',
      subframeId: 4,
      title: 'Word 4 · 시계·경사각섭동 (GAL I/NAV)',
      elements: [
        { target: 'SB2', name: 't_oc', fullName: 't_oc (시계 기준시각)', meaning: '위성 시계 기준시각', wordOrigin: 'Word 2~3', value: toc, formatted: toc.toLocaleString() + ' s' },
        { target: 'SB2', name: 'a_f0', fullName: 'a_f0 (시계 편차)', meaning: '위성 시계 편차 (Bias)', wordOrigin: 'Word 3~4', value: af0, formatted: (Math.abs(af0) < 1e-3 ? (af0 * 1e6).toFixed(3) + ' µs (' + af0.toExponential(4) + ' s)' : af0.toFixed(6) + ' s') },
        { target: 'SB2', name: 'a_f1', fullName: 'a_f1 (시계 표류율)', meaning: '위성 시계 표류율 (Drift)', wordOrigin: 'Word 4', value: af1, formatted: af1.toExponential(4) + ' s/s' },
        { target: 'SB3', name: 'a_f2', fullName: 'a_f2 (시계 드리프트율)', meaning: '2차 시계 가속도', wordOrigin: 'Word 4', value: af2, formatted: af2 === 0 ? '0.0 s/s²' : af2.toExponential(4) + ' s/s²' },
        { target: 'SB3', name: 'C_ic', fullName: 'C_ic (경사각 Cos 섭동)', meaning: '경사각 Cosine 조화 섭동', wordOrigin: 'Word 1~2', value: cic, formatted: (cic >= 0 ? '+' : '') + cic.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'C_is', fullName: 'C_is (경사각 Sin 섭동)', meaning: '경사각 Sine 조화 섭동', wordOrigin: 'Word 2', value: cis, formatted: (cis >= 0 ? '+' : '') + cis.toExponential(4) + ' rad' },
        { target: 'SB3', name: 'IODnav', fullName: 'IODnav (궤도 데이터 번호)', meaning: 'Galileo 궤도/시계 이슈 번호', wordOrigin: 'Word 1', value: iodNav, formatted: String(iodNav) }
      ]
    };
  }

  return null;
}

export function decodeNavigationParameters(item, iq = false) {
  const msg = item?.message || item;
  if (!msg || !Array.isArray(msg.words)) return null;
  const cid = msg.constellationId;
  const words = msg.words;

  if (cid === 0 || cid === 5) {
    if (words.length !== 10) return null;
    return decodeGpsLnavParameters(words, iq, cid === 5 ? 'QZSS' : 'GPS');
  }

  if (cid === 3) {
    if (words.length !== 10) return null;
    return decodeBdsD1Parameters(words, iq);
  }

  if (cid === 2) {
    if (words.length !== 8) return null;
    return decodeGalileoInavParameters(words, iq);
  }

  return null;
}

export function renderNavigationParametersDetails(parsed) {
  if (!parsed || !parsed.elements || !parsed.elements.length) return null;
  const doc = typeof document !== 'undefined' ? document : (typeof globalThis !== 'undefined' ? globalThis.document : null);
  if (!doc || typeof doc.createElement !== 'function') return null;

  const details = doc.createElement('details');
  details.className = 'nav-param-details';

  const summary = doc.createElement('summary');
  summary.className = 'nav-param-summary';

  const icon = doc.createElement('span');
  icon.className = 'nav-param-toggle-icon';
  icon.textContent = '📐';

  const title = doc.createElement('span');
  title.className = 'nav-param-title';
  title.textContent = 'AFS 프레임 매핑 파라미터 (' + (parsed.title || parsed.constellation) + ')';

  summary.append(icon);
  summary.append(title);

  const sb2Count = parsed.elements.filter(e => e.target === 'SB2').length;
  const sb3Count = parsed.elements.filter(e => e.target === 'SB3').length;

  if (sb2Count > 0) {
    const pillSb2 = doc.createElement('span');
    pillSb2.className = 'nav-badge-pill badge-sb2';
    pillSb2.textContent = 'SB2 ' + sb2Count + '항목';
    summary.append(pillSb2);
  }
  if (sb3Count > 0) {
    const pillSb3 = doc.createElement('span');
    pillSb3.className = 'nav-badge-pill badge-sb3';
    pillSb3.textContent = 'SB3 ' + sb3Count + '항목';
    summary.append(pillSb3);
  }

  const arrow = doc.createElement('span');
  arrow.className = 'nav-toggle-arrow';
  arrow.textContent = '▾';
  summary.append(arrow);
  details.append(summary);

  const grid = doc.createElement('div');
  grid.className = 'nav-param-grid';

  for (const el of parsed.elements) {
    const card = doc.createElement('div');
    card.className = 'nav-param-card target-' + (el.target === 'SB2' ? 'sb2' : 'sb3');

    const header = doc.createElement('div');
    header.className = 'nav-param-card-header';

    const nameSpan = doc.createElement('span');
    nameSpan.className = 'nav-param-name';
    nameSpan.textContent = el.name;
    if (el.meaning) {
      const small = doc.createElement('small');
      small.textContent = ' (' + el.meaning + ')';
      nameSpan.append(small);
    }

    const tagSpan = doc.createElement('span');
    tagSpan.className = 'nav-tag nav-tag-' + (el.target === 'SB2' ? 'sb2' : 'sb3');
    tagSpan.textContent = el.target;

    header.append(nameSpan);
    header.append(tagSpan);

    const valDiv = doc.createElement('div');
    valDiv.className = 'nav-param-card-value';
    valDiv.textContent = el.formatted;
    valDiv.title = el.fullName + ': ' + el.formatted + '\n물리적 의미: ' + el.meaning + '\n출처: ' + el.wordOrigin;

    const meta = doc.createElement('div');
    meta.className = 'nav-param-card-meta';

    const origin = doc.createElement('span');
    origin.className = 'nav-param-origin';
    origin.textContent = el.wordOrigin;

    const desc = doc.createElement('span');
    desc.className = 'nav-param-desc';
    desc.textContent = el.meaning;
    desc.title = el.meaning;

    meta.append(origin);
    meta.append(desc);
    card.append(header);
    card.append(valDiv);
    card.append(meta);
    grid.append(card);
  }

  details.append(grid);
  return details;
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
        <span data-navigation-summary></span></div>
      <label class="navigation-filter-label" title="현재 선택된 Epoch의 위성 위치 및 시계(PVT) 계산에 실제 사용된 최신 항법정보 1세트만 필터링하여 표시합니다.">
        <input type="checkbox" data-navigation-pvt-active checked>
        <span>선택 epoch</span>
      </label></div>
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
  const navFilterLabel = container.querySelector('.navigation-filter-label');
  if (navFilterLabel) navFilterLabel.hidden = !linkedNavigation;
  const allNavigation = container.querySelector('[data-navigation-all]');
  allNavigation.onclick = () => {
    showAllNavigation = true;
    render(false);
  };
  const navFilterCheckbox = container.querySelector('[data-navigation-pvt-active]');
  if (navFilterCheckbox && navFilterCheckbox.checked === undefined) {
    navFilterCheckbox.checked = true;
  }
  let showPvtActiveOnly = navFilterCheckbox ? !!navFilterCheckbox.checked : true;
  if (navFilterCheckbox) {
    const handleNavFilter = () => {
      showPvtActiveOnly = !!navFilterCheckbox.checked;
      renderAssociation(data?.epochs?.[Number(select.value)]?.observation?.observations || []);
    };
    navFilterCheckbox.onchange = handleNavFilter;
    navFilterCheckbox.oninput = handleNavFilter;
  }

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

    // Raw processing order, not UTC or a sorted sequence number, determines availability.
    const records = data?.records || [];
    const epochPositions = [], navigationPositions = [];
    records.forEach((record, index) => {
      if (record.type === 'OBSERVATION_EPOCH') epochPositions.push(index);
      if (record.type === 'NAVIGATION_UPDATE') navigationPositions.push(index);
    });
    const epochPosition = epochPositions[Number(select.value)];
    const activeSet = getActivePvtNavigationItems(navigation, navigationPositions, epochPosition);

    const baseShown = !selected || showAllNavigation ? navigation : matching;
    const shown = showPvtActiveOnly ? baseShown.filter(item => activeSet.has(item)) : baseShown;

    container.querySelector('[data-navigation-title]').textContent =
      selected && !showAllNavigation
        ? satelliteName(selected) + (showPvtActiveOnly ? '의 PVT 계산 활성 항법정보 · ' : '에서 받은 항법정보 · ') + shown.length + '건'
        : '전체 항법정보' + (showPvtActiveOnly ? ' (PVT 계산 활성) · ' : ' · ') + shown.length + '건';
    allNavigation.textContent = '전체 보기';
    allNavigation.hidden = showAllNavigation || !selected;
    allNavigation.disabled = !selected;
    allNavigation.setAttribute('aria-pressed', String(showAllNavigation));
    const summary = container.querySelector('[data-navigation-summary]');
    if (selected && !showAllNavigation) {
      if (selected.constellationId === 0) {
        const counts = [1, 2, 3].map(sf => 'SF' + sf + ' ' + (showPvtActiveOnly
          ? shown.filter(n => (n.display?.subframeId ?? ((Number(n.message?.words?.[1]) >>> 8) & 0x7)) === sf).length
          : matching.filter(n => n.display?.subframeId === sf).length) + '건');
        summary.textContent = counts.join(' · ') + ' · GPS LNAV ' + (showPvtActiveOnly ? 'PVT 활성 세트' : '메시지 보유 현황');
        summary.title = 'SF1~3이 모두 있어야 시각·궤도가 완성되어 PVT 계산에 채택됩니다.';
      } else if (selected.constellationId === 3) {
        const bdsSf = sf => (showPvtActiveOnly ? shown : matching).filter(n => {
          const w = n.message?.words;
          return w && w.length === 10 && ((Number(w[0]) >>> 12) & 0x7) === sf;
        }).length;
        const counts = [1, 2, 3, 4, 5].map(sf => 'SF' + sf + ' ' + bdsSf(sf) + '건');
        summary.textContent = counts.join(' · ') + ' · BDS B1I ' + (showPvtActiveOnly ? 'PVT 활성 세트' : '메시지 보유 현황');
        summary.title = 'BDS B1I SF1~3(시계 및 궤도) 수신 현황입니다. 실제 계산 성공 여부는 PVT 상태를 확인하세요.';
      } else if (selected.constellationId === 2) {
        const galType = type => (showPvtActiveOnly ? shown : matching).filter(n => {
          const w = n.message?.words;
          return w && w.length === 8 && ((Number(w[0]) >>> 24) & 0x3f) === type;
        }).length;
        const counts = [1, 2, 3, 4, 5].map(t => 'W' + t + ' ' + galType(t) + '건');
        summary.textContent = counts.join(' · ') + ' · GAL I/NAV ' + (showPvtActiveOnly ? 'PVT 활성 세트' : '메시지 보유 현황');
        summary.title = 'Galileo Word 1~4(궤도·시계) 및 Word 5(보정) 수신 현황입니다. Word 1~4가 모두 있어야 궤도가 복원됩니다.';
      } else if (selected.constellationId === 5) {
        const qzssSf = sf => (showPvtActiveOnly ? shown : matching).filter(n => {
          const w = n.message?.words;
          return w && w.length === 10 && ((Number(w[1]) >>> 8) & 0x7) === sf;
        }).length;
        const counts = [1, 2, 3].map(sf => 'SF' + sf + ' ' + qzssSf(sf) + '건');
        summary.textContent = counts.join(' · ') + ' · QZSS L1 ' + (showPvtActiveOnly ? 'PVT 활성 세트' : '메시지 보유 현황');
        summary.title = 'QZSS L1 SF1~3 수신 현황입니다.';
      } else {
        summary.textContent = (showPvtActiveOnly ? 'PVT 계산 활성 항법 ' : '수신 항법 메시지 ') + shown.length + '건';
        summary.title = '';
      }
    } else {
      summary.textContent = showPvtActiveOnly ? '활성 세트 필터 적용 (' + shown.length + '건)' : '';
      summary.title = '';
    }
    container.querySelector('[data-navigation-caption]').textContent = iq ? (data.assistance || 'I/Q 복호 항법정보')
      : frame ? 'AFS 프레임 복원 · 원본 수집 순번·시각 없음'
      : showPvtActiveOnly ? '현재 에폭 PVT 계산에 채택된 활성 항법 메시지만 필터링하여 표시합니다.'
      : '원본 레코드 순서 기준 · 관측 이후 메시지는 해당 시점 계산과 구분';

    const navigationBody = container.querySelector('[data-navigation]');
    const headings = container.querySelector('[data-navigation-head]');
    headings.innerHTML = '<th>방송 위성</th><th>내용</th><th>관측 기준</th><th>원문</th>';
    container.querySelector('[data-navigation-table]').classList.add('navigation-linked');
    navigationBody.replaceChildren();

    function appendMessage(target, item) {
      const row = document.createElement('tr');
      const label = navigationLabel(item);
      const position = navigationPositions[navigation.indexOf(item)];
      const isActive = activeSet.has(item);
      let timingText = '';
      let timingClass = '';
      if (frame) {
        timingText = isActive ? '프레임 복원 · PVT 활성' : '프레임 복원';
        timingClass = isActive ? 'timing-active-pvt' : '';
      } else if (iq) {
        timingText = isActive ? '복호·보조 정보 · PVT 활성' : '복호·보조 정보';
        timingClass = isActive ? 'timing-active-pvt' : '';
      } else if (position == null || epochPosition == null) {
        timingText = '순서 확인 불가';
      } else if (position < epochPosition) {
        if (isActive) {
          timingText = '선택 Epoch PVT 계산 활성';
          timingClass = 'timing-active-pvt';
        } else {
          timingText = '선택 Epoch 이전 수신 (이전 이력)';
          timingClass = 'timing-history-pvt';
        }
      } else {
        timingText = '선택 Epoch 이후 수신 (계산 제외)';
        timingClass = 'timing-future-pvt';
      }

      if (isActive) {
        if (row.classList?.add) row.classList.add('nav-row-active');
        else row.className = 'nav-row-active';
      }

      const cellSat = document.createElement('td');
      const badge = document.createElement('span');
      const cid = item.message.constellationId;
      const sid = item.message.satelliteId;
      badge.className = 'satellite-badge constellation-badge ' + constellationClass(cid);
      badge.textContent = satelliteBadgeText(cid, sid);
      badge.title = satelliteName(item.message);
      cellSat.append(badge);
      row.append(cellSat);

      const cellLabel = document.createElement('td');
      cellLabel.textContent = label;
      row.append(cellLabel);

      const cellTiming = document.createElement('td');
      if (timingClass) {
        const badgeSpan = document.createElement('span');
        badgeSpan.className = 'timing-badge ' + timingClass;
        badgeSpan.textContent = timingText;
        cellTiming.append(badgeSpan);
      } else {
        cellTiming.textContent = timingText;
      }
      row.append(cellTiming);

      const cell = document.createElement('td');
      const content = document.createElement('pre');
      const values = navigationCells(item, iq);
      content.textContent = (frame || iq ? '' : '수집 순번 ' + (item.sequence ?? '—')
        + ' · ' + (item.capturedAt ?? '시각 없음') + ' · 신호 ID ' + (item.message.signalId ?? '—')
        + ' · 주파수 ID ' + (item.message.frequencyId ?? '—') + ' · 버전 ' + (item.message.sfrbxVersion ?? '—') + ' · ')
        + values[7] + ' words · HEX ' + (iq ? '24 bit (패리티 제외)' : '32 bit') + '\n' + values[8];
      cell.append(content);
      const parsed = decodeNavigationParameters(item, iq);
      if (parsed && parsed.elements && parsed.elements.length > 0) {
        const details = renderNavigationParametersDetails(parsed);
        if (details) cell.append(details);
      }
      row.append(cell); target.append(row);
    }
    shown.forEach(item => appendMessage(navigationBody, item));
    if (!shown.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      cell.colSpan = 4;
      cell.textContent = selected
        ? (showPvtActiveOnly ? satelliteName(selected) + '의 현재 에폭 PVT 계산 활성 항법정보가 없습니다.' : '이 위성에서 받은 항법 메시지가 없습니다.')
        : (showPvtActiveOnly ? '현재 에폭 PVT 계산에 사용된 항법정보가 없습니다.' : '표시할 항법정보가 없습니다.');
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
