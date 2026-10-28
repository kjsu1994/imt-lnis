import assert from 'node:assert/strict';
import {numeric, iqIntegrity, clockBias, renderClockBias, observationCells, navigationCells, createObservationView, transmitTime, pvtInputStatus, lockTime, unclassifiedNavigation, navigationLabel, receiverInformation, observationCounts, decodeNavigationParameters, renderNavigationParametersDetails, navigationSubframeKey, getActivePvtNavigationItems} from '../../main/resources/static/assets/dtn/dtn-observations.js';

assert.equal(numeric(null), '—');
assert.equal(numeric(NaN), '—');
assert.equal(numeric(Infinity), '—');
assert.equal(numeric(0), '0.000');
assert.equal(clockBias(-2.4781070279303227e-13), '-2.478107 × 10^-13');
assert.equal(clockBias(2.478107e-13), '2.478107 × 10^-13');
assert.equal(clockBias(0), '0.000000000');
assert.equal(clockBias(0.5119249119991983), '0.511924912');
assert.equal(clockBias(null), '-');
assert.equal(clockBias(NaN), '-');
const clockElement = {};
renderClockBias(clockElement, -2.4781070279303227e-13);
assert.equal(clockElement.title, '-2.4781070279303227e-13 s');
renderClockBias(clockElement, null);
assert.equal(clockElement.title, '');

const raw = {constellationId: 0, satelliteId: 9, signalId: 0,
  pseudorangeMeters: 21234567.123, carrierPhaseCycles: -123456,
  dopplerHz: -987.5, carrierToNoiseDbHz: 44, lockTimeMilliseconds: 4000,
  pseudorangeStdDev: 3, carrierPhaseStdDev: 4, dopplerStdDev: 5, trackingStatus: 3};
const cells = observationCells(raw);
assert.equal(cells[3], '21234567.123');
assert.equal(cells[5], '-987.500');
assert.equal(cells[8], 'PR 3 / CP 4 / D 5');
assert.equal(cells[10], '입력 대상');
assert.equal(cells.length, 12);
assert.equal(cells[11], '—');
assert.equal(transmitTime({...raw, pseudorangeMeters: 29979245.8}, 100000), '99999.900000000');
assert.equal(transmitTime({...raw, pseudorangeMeters: 29979245.8}, 0), '604799.900000000 (이전 주)');
for (const value of [undefined, null, NaN, Infinity, -1, 604800]) assert.equal(transmitTime(raw, value), '—');
for (const value of [undefined, null, NaN, Infinity, -1, 0]) assert.equal(transmitTime({...raw, pseudorangeMeters: value}, 100000), '—');
assert.equal(transmitTime({...raw, trackingStatus: 0}, 100000), '—');
assert.notEqual(transmitTime(raw, 100000), transmitTime({...raw, pseudorangeMeters: 22000000}, 100000));
assert.equal(observationCells({...raw, constellationId: 2})[10], '제외');
assert.equal(observationCells({...raw, trackingStatus: 0})[10], '제외');
assert.equal(observationCells({...raw, signalId: 3})[10], '제외');
console.log('PASS: RAWX numeric units, invalid/missing values, deviation codes and GPS L1 input eligibility');
assert.deepEqual(navigationCells({sequence: 7, capturedAt: '2026-09-14T00:00:00Z', message: {
  constellationId: 0, satelliteId: 19, signalId: 0, frequencyId: 0, sfrbxVersion: 2,
  words: [0, 4294967295, 2147483648]
}}), [7, '2026-09-14T00:00:00Z', 'GPS', 19, 0, 0, 2, 3, '00000000 FFFFFFFF 80000000']);
console.log('PASS: SFRBX sequence, all words and unsigned 32-bit HEX');

assert.equal(numeric(null, 3, '-'), '-');
assert.equal(numeric('1.2', 3, '-'), '-');
assert.equal(numeric(NaN, 3, '-'), '-');
assert.equal(numeric(Infinity, 3, '-'), '-');
assert.equal(numeric(0, 9, '-'), '0.000000000');
assert.equal(numeric(-1.23456, 3, '-'), '-1.235');
for (const id of [0, 2, 6, 99]) {
  const nav = navigationCells({message: {constellationId:id, words:[]}});
  assert.equal(observationCells({...raw, constellationId:id})[0], nav[2]);
}
console.log('PASS: sender/receiver missing values, precision and shared constellation names');
const iq=observationCells({source:'IQ_TRACKING',constellationId:0,satelliteId:19,signalId:0,
  pseudorangeMeters:21000000,dopplerHz:100,carrierPhaseCycles:200,carrierToNoiseDbHz:45,trackingStatus:1});
assert.equal(iq[2],'AFS Data · L1');
assert.equal(iq[8],'—');
assert.equal(iq[9],'PR 유효 · 위상 상대값');
assert.equal(iq[7],'—'); // Never invent F9T lock-time or deviation codes for SDR observations.

// Exercise the complete view update, including navigation rendering and epoch selection.
const element = () => {
  const classes = new Set();
  const el = {
    value: '0',
    children: [],
    style: {},
    setAttribute(key, value) { this[key] = value; },
    append(child) { this.children.push(child); },
    replaceChildren(...children) { this.children = children; }
  };
  el.classList = {
    add(...names) { names.forEach(n => classes.add(n)); },
    remove(...names) { names.forEach(n => classes.delete(n)); },
    toggle(name, force) {
      const active = force !== undefined ? !!force : !classes.has(name);
      if (active) classes.add(name); else classes.delete(name);
      return active;
    },
    contains(name) { return classes.has(name); }
  };
  Object.defineProperty(el, 'className', {
    get() { return [...classes].join(' '); },
    set(v) {
      classes.clear();
      if (v) v.trim().split(/\s+/).forEach(n => classes.add(n));
    }
  });
  let html = '';
  Object.defineProperty(el, 'innerHTML', {
    get() { return html; },
    set(v) {
      html = String(v ?? '');
      el.textContent = html.replace(/<[^>]*>/g, '');
    }
  });
  return el;
};
globalThis.document = {createElement: element};
globalThis.Option = function(text, value) { this.text = text; this.value = value; };
const nodes = new Map();
const container = {querySelector(selector) {
  if (!nodes.has(selector)) nodes.set(selector, element());
  return nodes.get(selector);
}};
const view = createObservationView(container);
const navigation = [{sequence: 1, message: {constellationId: 0, satelliteId: 19, words: [0x8b0000, 0]}}];
const epochs = [{observation: {week: 2400, receiverTowSeconds: 100021,
  leapSeconds: 18, receiverStatus: 1, observations: [raw]}}];
view.setData({source: 'IQ_TRACKING', navigationCount: 1, navigation, epochs});
assert.equal(nodes.get('[data-navigation]').children[0].children[8].textContent, '8B0000 000000');
assert.equal(nodes.get('[data-epoch]').disabled, false);
view.setData({navigationCount: 1, navigation, epochs});
assert.equal(nodes.get('[data-observations]').children[0].children[11].textContent, transmitTime(raw, 100021));
assert.equal(nodes.get('[data-navigation]').children[0].children[8].textContent, '008B0000 00000000');
view.setData(null);
assert.equal(nodes.get('[data-observations]').children[0].children[0].colSpan, 12);
assert.equal(nodes.get('[data-epoch]').disabled, true);
view.setData({navigationCount: 0, navigation: [], epochs: [{observation: {
  week: 0, receiverTowSeconds: 100, leapSeconds: 18, receiverStatus: 0, rawxVersion: 1, observations: []}}]});
assert.match(nodes.get('[data-observations]').children[0].children[0].textContent, /관측 신호가 0개/);
assert.match(nodes.get('[data-status]').textContent, /RAWX v1 · 윤초 미확정/);
assert.equal(observationCells({...raw, pseudorangeMeters: NaN})[10], '제외');
assert.equal(observationCells({...raw, dopplerHz: NaN})[10], '제외');
const original = {navigationCount:1,navigation,epochs};
const originalJson = JSON.stringify(original);
const receiverView = createObservationView(container, () => {}, '수신 원본');
const evidence = {originalTime:{week:2400,towSeconds:100021},shiftedTime:{week:2400,towSeconds:100040.222158},
  addedMeters:5762657994.884364,satellites:[{constellationId:0,satelliteId:9,signalId:0,
    originalMeters:raw.pseudorangeMeters,recalculatedMeters:raw.pseudorangeMeters+5762657994.884364}]};
receiverView.setData(original, false, evidence);
assert.equal(nodes.get('[data-range-after]').hidden,false);
assert.equal(nodes.get('[data-observations]').children[0].children.length,14);
assert.equal(nodes.get('[data-observations]').children[0].children[3].textContent,numeric(raw.pseudorangeMeters));
assert.equal(nodes.get('[data-observations]').children[0].children[4].textContent,numeric(evidence.satellites[0].recalculatedMeters));
assert.equal(nodes.get('[data-observations]').children[0].children[5].textContent,numeric(evidence.addedMeters));
assert.equal(nodes.get('[data-observations]').children[0].children[7].textContent,numeric(raw.dopplerHz));
assert.match(nodes.get('[data-delay-summary]').textContent,/100040.222158/);
receiverView.setData(original, false, {...evidence,error:'음수 지연'});
assert.equal(nodes.get('[data-observations]').children[0].children[4].textContent,'—');
receiverView.setData(original, false, {...evidence,satellites:[{...evidence.satellites[0],satelliteId:99}]});
assert.equal(nodes.get('[data-observations]').children[0].children[4].textContent,'—');
receiverView.setData(original);
assert.equal(nodes.get('[data-range-after]').hidden,true);
assert.equal(nodes.get('[data-observations]').children[0].children.length,12);
assert.equal(JSON.stringify(original),originalJson);
console.log('PASS: receiver original/converted ranges, Doppler preservation, evidence mismatch and restore isolation');

// Frame-derived solver input is a separate view; never overwrite the original RAWX table.
const frameView = {...original, frameInput: {source: 'AFS_V4', frameCount: 1, epochs, navigation}};
receiverView.setData(frameView);
assert.equal(nodes.get('[data-frame-input]').hidden, false);
assert.equal(nodes.get('[data-frame-values]').children[0].children[3].textContent, numeric(raw.pseudorangeMeters, 6));
assert.equal(nodes.get('[data-observations]').children[0].children[3].textContent, numeric(raw.pseudorangeMeters));
receiverView.setData(original);
assert.equal(nodes.get('[data-frame-input]').hidden, true);
assert.equal(nodes.get('[data-frame-values]').children.length, 0);
assert.equal(JSON.stringify(original), originalJson);
console.log('PASS: separate AFS frame calculation inputs and legacy view reset');

// Wire omits original pseudorange. Eligibility must come from received calculation,
// including before Reference arrives; duplicate signal matches must not be guessed.
const wireObservation = {...raw, transmitAt:{seconds:1790000000,femtoseconds:0}};
delete wireObservation.pseudorangeMeters;
const wireView = {...original, receivedValues:{observations:[wireObservation]}};
receiverView.setData(wireView);
assert.equal(nodes.get('[data-observations]').children[0].children[11].textContent, '입력 대상');
assert.equal(nodes.get('[data-observations]').children[0].children[3].textContent, '—');
receiverView.setData(wireView, false, {error:'음수 지연'});
assert.equal(nodes.get('[data-observations]').children[0].children[11].textContent, '계산 불가');
receiverView.setData({...wireView, epochs:[{observation:{...epochs[0].observation,observations:[raw,raw]}}]});
assert.equal(nodes.get('[data-observations]').children[0].children[11].textContent, '확인 불가');
assert.equal(nodes.get('[data-observations]').children[0].children[4].textContent, '—');

// Sorting the view must preserve evidence's original index and source order.
const unsorted = {...original, epochs:[{observation:{...epochs[0].observation,
  observations:[{...raw,satelliteId:20},raw]}}]};
const savedOrder = JSON.stringify(unsorted);
receiverView.setData(unsorted, false, {...evidence,satellites:[
  {...evidence.satellites[0],satelliteId:20,recalculatedMeters:12345678},evidence.satellites[0]]});
assert.equal(nodes.get('[data-observations]').children[0].children[4].textContent,
  numeric(evidence.satellites[0].recalculatedMeters));
assert.equal(nodes.get('[data-observations]').children[1].children[4].textContent,'12345678.000');
assert.equal(JSON.stringify(unsorted),savedOrder);
console.log('PASS: received eligibility without Reference, ambiguous signals, sorting/evidence isolation');

const multiEpoch = {...original, epochs:[...epochs,{observation:{...epochs[0].observation,
  observations:[{...raw,constellationId:2}]}}]};
receiverView.setData(multiEpoch);
receiverView.select(1);
assert.match(nodes.get('[data-navigation-title]').textContent,/전체 항법정보/);
receiverView.setData({...multiEpoch,epochs:[{observation:{...epochs[0].observation,observations:[]}}]});
assert.match(nodes.get('[data-navigation-title]').textContent,/전체 항법정보/);
assert.equal(nodes.get('[data-navigation-all]').disabled,true);
assert.equal(nodes.get('[data-navigation]').children.length,1);
console.log('PASS: epoch selection fallback and navigation without observations');

const senderView = createObservationView(container, () => {}, '송신 원본');
senderView.setData(original);
assert.equal(nodes.get('[data-association]').hidden,false);
assert.equal(nodes.get('[data-transmit-heading]').hidden,true);
assert.equal(nodes.get('[data-observations]').children[0].children.length,11);
assert.equal(nodes.get('[data-observations]').children[0].children[3].textContent,numeric(raw.pseudorangeMeters));
assert.match(nodes.get('[data-navigation-title]').textContent,/전체 항법정보/);
assert.equal(JSON.stringify(original),originalJson);
console.log('PASS: sender association retains original values and hidden transmit estimate');

const referenceView = createObservationView(container, () => {}, '송신 비교원본');
referenceView.setData(original);
assert.equal(nodes.get('[data-association]').hidden,false);
assert.equal(nodes.get('[data-transmit-heading]').hidden,true);
assert.equal(nodes.get('[data-observations]').children[0].children.length,11);
assert.match(nodes.get('[data-navigation-title]').textContent,/전체 항법정보/);
assert.equal(JSON.stringify(original),originalJson);

assert.equal(nodes.get('[data-navigation-all]').hidden,true);
assert.equal(nodes.get('[data-observations]').children[0].classList.contains('pvt-eligible-row'), true);
assert.equal(nodes.get('[data-observations]').children[0].classList.contains('satellite-selected'), false);

// Verify PVT filter toggle hides excluded rows and restores them
const filterToggle = nodes.get('[data-pvt-filter]');
filterToggle.checked = true;
filterToggle.onchange();
assert.equal(nodes.get('[data-observations]').children[0].hidden, false);
const epochWithExcluded = [{observation: {week: 2400, receiverTowSeconds: 100021,
  leapSeconds: 18, receiverStatus: 1, observations: [raw, {...raw, constellationId: 2}]}}];
referenceView.setData({navigationCount: 1, navigation, epochs: epochWithExcluded});
assert.equal(nodes.get('[data-observations]').children.length, 2);
assert.equal(nodes.get('[data-observations]').children[1].classList.contains('pvt-excluded-row'), true);
assert.equal(nodes.get('[data-observations]').children[1].hidden, true);
filterToggle.checked = false;
filterToggle.onchange();
assert.equal(nodes.get('[data-observations]').children[1].hidden, false);
referenceView.setData(original);
assert.equal(pvtInputStatus(null).label,'입력 대기');
assert.equal(pvtInputStatus(original,0,[],{error:'음수 지연'}).label,'계산 불가');
assert.match(pvtInputStatus(original).reasons.join(' '),/최소 4위성/);
assert.equal(pvtInputStatus(original,0,[{positionValid:true,velocityValid:true,satellitesUsed:6}]).label,'PVT 계산 완료');
assert.equal(pvtInputStatus(original,0,[{positionValid:true,velocityValid:false}]).level,'warning');
assert.match(pvtInputStatus(original,0,[{positionValid:false,message:'궤도 유효기간 초과'}]).reasons[0],/궤도/);
const ready = {source:'IQ_TRACKING',epochs:[{observation:{...epochs[0].observation,
  observations:[1,2,3,4].map(satelliteId=>({...raw,satelliteId}))}}],
  navigation:[1,2,3,4].flatMap(satelliteId=>[1,2,3].map(subframeId=>({
    message:{constellationId:0,satelliteId},display:{subframeId}})))};
assert.equal(pvtInputStatus(ready).label,'계산 결과 대기'); // Presence never claims solver success.
const missing = structuredClone(ready);missing.navigation.pop();
assert.match(pvtInputStatus(missing).reasons.join(' '),/G04: SF3 미확인/);
const rawOrder = {...ready,source:undefined,records:[{type:'OBSERVATION_EPOCH'},
  ...ready.navigation.map(()=>({type:'NAVIGATION_UPDATE'}))]};
assert.match(pvtInputStatus(rawOrder).reasons.join(' '),/관측 이전/);
senderView.setData(original);
senderView.setPvt([{positionValid:true,velocityValid:true,satellitesUsed:6}]);
assert.match(nodes.get('[data-pvt-status-label]').textContent,/PVT 계산 완료/);
senderView.setData(null);
assert.equal(nodes.get('[data-pvt-status]').hidden,true);
console.log('PASS: all navigation default, basic input checks versus solver result, late navigation and state reset');

delete globalThis.document;
delete globalThis.Option;
console.log('PASS: full I/Q and GRAW view updates, navigation HEX widths and empty state');

assert.equal(lockTime(64500),'64.5 이상 (64500 ms)');
assert.equal(lockTime(64501),'64.501 (64501 ms)');
assert.equal(lockTime(4000),'4 (4000 ms)');
assert.equal(lockTime(1),'0.001 (1 ms)');
assert.equal(lockTime(0),'0 (0 ms)');
for (const missing of [null,undefined,NaN,-1]) assert.equal(lockTime(missing),'—');
for (const constellationId of [1,2,3,4,5,6,7]) {
  assert.match(unclassifiedNavigation({constellationId}),/항법정보 수신/);
}
assert.equal(unclassifiedNavigation({constellationId:0,signalId:0}),'메시지 종류 확인 불가');
assert.equal(unclassifiedNavigation({constellationId:99}),'메시지 종류 확인 불가');
assert.match(unclassifiedNavigation({constellationId:0,signalId:3}),/GPS L1 C\/A 전용/);

// Multi-GNSS navigation label formatting
assert.match(navigationLabel({message: {constellationId: 3, signalId: 0, words: [(0x712 << 19) | (1 << 12), 0, 0, 0, 0, 0, 0, 0, 0, 0]}}), /SF1 · 위성 시계·상태 \(BDS B1I\)/);
assert.match(navigationLabel({message: {constellationId: 3, signalId: 0, words: [(0x712 << 19) | (2 << 12), 0, 0, 0, 0, 0, 0, 0, 0, 0]}}), /SF2 · 궤도정보 ① \(BDS B1I\)/);
assert.match(navigationLabel({message: {constellationId: 2, signalId: 1, words: [1 << 24, 0, 0, 0, 0, 0, 0, 0]}}), /Word 1 · 궤도정보 ① \(GAL I\/NAV\)/);
assert.match(navigationLabel({message: {constellationId: 2, signalId: 1, words: [4 << 24, 0, 0, 0, 0, 0, 0, 0]}}), /Word 4 · 궤도정보 ④ & 시계 \(GAL I\/NAV\)/);
assert.match(navigationLabel({message: {constellationId: 5, signalId: 0, words: [0, 1 << 8, 0, 0, 0, 0, 0, 0, 0, 0]}}), /SF1 · 위성 시계·상태 \(QZSS\)/);
assert.match(navigationLabel({message: {constellationId: 6}}), /GLONASS 항법 스트링/);
console.log('PASS: unsupported constellations versus unknown messages, RAWX lock saturation and missing values');

const stats = observationCounts(ready,0,[{positionValid:true,satellitesUsed:3}]);
assert.deepEqual(stats,{satellites:4,signals:4,gps:4,eligible:4,complete:4,used:3});
assert.equal(observationCounts(rawOrder).complete,0);
assert.equal(observationCounts(ready,0,[],{error:'음수'}).eligible,0);
const device = {receiverInfo:{model:'ZED-F9T-20B',firmware:'TIM 2.25',protocol:'29.25',
  supportedConstellations:['GPS','BeiDou']},epochs:[{observation:{week:2400,receiverTowSeconds:604799}},
  {observation:{week:2401,receiverTowSeconds:1}}]};
assert.match(receiverInformation(device),/29.25/);
assert.match(receiverInformation(device),/2.000초/);
assert.match(receiverInformation(device),/2개/);
assert.match(receiverInformation({epochs:[]}),/미기록/);
assert.equal(receiverInformation(null),'');
console.log('PASS: counts do not conflate input/nav presence/solver use, device metadata and week rollover span');

// Test GPS LNAV parameter decoding (SB2/SB3)
const sampleSf1 = ['22C18609','906AA963','1864006E','91A2C9FF','28055365','B84137BF','2A8ABDB8','30CCBE8D','8000007A','822266D8'].map(h => parseInt(h, 16));
const sampleSf2 = ['22C18609','906ACAF3','30C0BA0D','8C45772E','88DBDB96','8093C0A7','024A190E','83996865','834E1EBC','0CBE9F7B'].map(h => parseInt(h, 16));
const sampleSf3 = ['22C18609','906AEB7B','00058E9F','2352E562','BFF9C9C1','A82EB216','87EF625A','903A6FC3','3FEA12EF','30C40B90'].map(h => parseInt(h, 16));

const decSf1 = decodeNavigationParameters({message: {constellationId: 0, satelliteId: 4, words: sampleSf1}});
assert.equal(decSf1.constellation, 'GPS');
assert.equal(decSf1.subframeId, 1);
assert.equal(decSf1.elements.find(e => e.name === 't_oc').value, 208800);
assert.equal(decSf1.elements.find(e => e.name === 't_oc').target, 'SB2');
assert.equal(decSf1.elements.find(e => e.name === 't_oc').wordOrigin, 'Word 8');
assert.equal(decSf1.elements.find(e => e.name === 'a_f0').target, 'SB2');
assert.equal(decSf1.elements.find(e => e.name === 'a_f0').wordOrigin, 'Word 10');
assert.equal(decSf1.elements.find(e => e.name === 'a_f1').target, 'SB2');
assert.equal(decSf1.elements.find(e => e.name === 'a_f1').wordOrigin, 'Word 9');
assert.equal(decSf1.elements.find(e => e.name === 'a_f2').target, 'SB2');
assert.equal(decSf1.elements.find(e => e.name === 'a_f2').wordOrigin, 'Word 9');
assert.equal(decSf1.elements.find(e => e.name === 'T_GD').target, 'SB2');
assert.equal(decSf1.elements.find(e => e.name === 'T_GD').wordOrigin, 'Word 7');
assert.equal(decSf1.elements.find(e => e.name === 'IODC').value, 451);

const decSf2 = decodeNavigationParameters({message: {constellationId: 0, satelliteId: 4, words: sampleSf2}});
assert.equal(decSf2.subframeId, 2);
assert.equal(decSf2.elements.find(e => e.name === 't_oe').value, 208800);
assert.equal(decSf2.elements.find(e => e.name === 't_oe').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 't_oe').wordOrigin, 'Word 10');
assert.equal(decSf2.elements.find(e => e.name === '√a').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === '√a').wordOrigin, 'Word 8~9');
assert.equal(decSf2.elements.find(e => e.name === 'e').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'e').wordOrigin, 'Word 6~7');
assert.equal(decSf2.elements.find(e => e.name === 'M_0').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'M_0').wordOrigin, 'Word 4~5');
assert.equal(decSf2.elements.find(e => e.name === 'C_rs').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'C_rs').wordOrigin, 'Word 3');
assert.equal(decSf2.elements.find(e => e.name === 'C_uc').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'C_uc').wordOrigin, 'Word 6');
assert.equal(decSf2.elements.find(e => e.name === 'C_us').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'C_us').wordOrigin, 'Word 8');
assert.equal(decSf2.elements.find(e => e.name === 'Δn').target, 'SB2');
assert.equal(decSf2.elements.find(e => e.name === 'Δn').wordOrigin, 'Word 4');

const decSf3 = decodeNavigationParameters({message: {constellationId: 0, satelliteId: 4, words: sampleSf3}});
assert.equal(decSf3.subframeId, 3);
assert.equal(decSf3.elements.find(e => e.name === 'i_0').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'i_0').wordOrigin, 'Word 5~6');
assert.equal(decSf3.elements.find(e => e.name === 'Ω_0').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'Ω_0').wordOrigin, 'Word 3~4');
assert.equal(decSf3.elements.find(e => e.name === 'ω').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'ω').wordOrigin, 'Word 7~8');
assert.equal(decSf3.elements.find(e => e.name === 'C_rc').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'C_rc').wordOrigin, 'Word 7');
assert.equal(decSf3.elements.find(e => e.name === 'C_ic').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'C_ic').wordOrigin, 'Word 3');
assert.equal(decSf3.elements.find(e => e.name === 'C_is').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'C_is').wordOrigin, 'Word 5');
assert.equal(decSf3.elements.find(e => e.name === 'Ω̇').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'Ω̇').wordOrigin, 'Word 9');
assert.equal(decSf3.elements.find(e => e.name === 'IDOT').target, 'SB2');
assert.equal(decSf3.elements.find(e => e.name === 'IDOT').wordOrigin, 'Word 10');

// Test QZSS decoding
const decQzss = decodeNavigationParameters({message: {constellationId: 5, satelliteId: 193, words: sampleSf1}});
assert.equal(decQzss.constellation, 'QZSS');
assert.equal(decQzss.subframeId, 1);
assert.match(decQzss.title, /QZSS/);

// Test BeiDou B1I D1 decoding
const sampleBdsSf1 = [0x38901313, 0x0fe010e5, 0x087462e2, 0x1c0040c7, 0x041e01b7, 0x3d0591fe, 0x0e227ea4, 0x100031dc, 0x2f0d9f6f, 0x3b1f8136];
const sampleBdsSf2 = [0x38902311, 0x0ff8abba, 0x2efd3882, 0x317c9cc5, 0x3b980163, 0x1abe3594, 0x05d670cc, 0x2021fe58, 0x0c7a513c, 0x1558907b];
const decBdsSf1 = decodeNavigationParameters({message: {constellationId: 3, satelliteId: 25, words: sampleBdsSf1}});
assert.ok(decBdsSf1);
assert.equal(decBdsSf1.constellation, 'BeiDou');
assert.equal(decBdsSf1.subframeId, 1);
assert.match(decBdsSf1.title, /BDS B1I/);
assert.equal(decBdsSf1.elements.find(e => e.name === 't_oc').target, 'SB2');
assert.equal(decBdsSf1.elements.find(e => e.name === 't_oc').wordOrigin, 'Word 3~4');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f0').target, 'SB2');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f0').wordOrigin, 'Word 8~9');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f1').target, 'SB2');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f1').wordOrigin, 'Word 9~10');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f2').target, 'SB2');
assert.equal(decBdsSf1.elements.find(e => e.name === 'a_f2').wordOrigin, 'Word 8');
assert.equal(decBdsSf1.elements.find(e => e.name === 'T_GD1').target, 'SB2');
assert.equal(decBdsSf1.elements.find(e => e.name === 'T_GD1').wordOrigin, 'Word 4');

const decBdsSf2 = decodeNavigationParameters({message: {constellationId: 3, satelliteId: 25, words: sampleBdsSf2}});
assert.ok(decBdsSf2);
assert.equal(decBdsSf2.constellation, 'BeiDou');
assert.equal(decBdsSf2.subframeId, 2);
assert.equal(decBdsSf2.elements.find(e => e.name === '√a').target, 'SB2');
assert.equal(decBdsSf2.elements.find(e => e.name === '√a').wordOrigin, 'Word 9~10');
assert.equal(decBdsSf2.elements.find(e => e.name === 'e').target, 'SB2');
assert.equal(decBdsSf2.elements.find(e => e.name === 'e').wordOrigin, 'Word 5~6');
assert.equal(decBdsSf2.elements.find(e => e.name === 'M_0').target, 'SB2');
assert.equal(decBdsSf2.elements.find(e => e.name === 'M_0').wordOrigin, 'Word 4~5');
assert.equal(decBdsSf2.elements.find(e => e.name === 'C_rs').target, 'SB2');
assert.equal(decBdsSf2.elements.find(e => e.name === 'C_rs').wordOrigin, 'Word 8~9');
assert.equal(decBdsSf2.elements.find(e => e.name === 'Δn').target, 'SB2');
assert.equal(decBdsSf2.elements.find(e => e.name === 'Δn').wordOrigin, 'Word 2~3');

// Test Galileo E1B I/NAV decoding
const sampleGalW1 = [0x052a7fd8, 0x0280437a, 0x17d5cbb3, 0x7f174000, 0xa63572c7, 0x83fcf92a, 0xaaaa744e, 0xcdc10000];
const sampleGalW3 = [0x0c5126aa, 0x0280437a, 0x17d5cbb3, 0x7f174000, 0xa63572c7, 0x83fcf92a, 0xaaaa744e, 0xcdc10000];
const sampleGalW4 = [0x116761a7, 0x0280437a, 0x17d5cbb3, 0x7f174000, 0xa63572c7, 0x83fcf92a, 0xaaaa744e, 0xcdc10000];

const decGalW1 = decodeNavigationParameters({message: {constellationId: 2, satelliteId: 27, words: sampleGalW1}});
assert.ok(decGalW1);
assert.equal(decGalW1.constellation, 'Galileo');
assert.equal(decGalW1.subframeId, 1);
assert.match(decGalW1.title, /Word 1/);
assert.equal(decGalW1.elements.find(e => e.name === 't_oe').target, 'SB2');
assert.equal(decGalW1.elements.find(e => e.name === 't_oe').wordOrigin, 'Word 1');
assert.equal(decGalW1.elements.find(e => e.name === '√a').target, 'SB2');
assert.equal(decGalW1.elements.find(e => e.name === '√a').wordOrigin, 'Word 3~4');
assert.equal(decGalW1.elements.find(e => e.name === 'e').target, 'SB2');
assert.equal(decGalW1.elements.find(e => e.name === 'e').wordOrigin, 'Word 2~3');
assert.equal(decGalW1.elements.find(e => e.name === 'M_0').target, 'SB2');
assert.equal(decGalW1.elements.find(e => e.name === 'M_0').wordOrigin, 'Word 1~2');
assert.equal(decGalW1.elements.find(e => e.name === 'IODnav').target, 'SB2');

const decGalW3 = decodeNavigationParameters({message: {constellationId: 2, satelliteId: 32, words: sampleGalW3}});
assert.ok(decGalW3);
assert.equal(decGalW3.constellation, 'Galileo');
assert.equal(decGalW3.subframeId, 3);
assert.match(decGalW3.title, /Word 3/);
assert.equal(decGalW3.elements.find(e => e.name === 'C_rs').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'C_rs').wordOrigin, 'Word 4');
assert.equal(decGalW3.elements.find(e => e.name === 'C_rc').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'C_rc').wordOrigin, 'Word 3~4');
assert.equal(decGalW3.elements.find(e => e.name === 'C_uc').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'C_uc').wordOrigin, 'Word 2~3');
assert.equal(decGalW3.elements.find(e => e.name === 'C_us').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'C_us').wordOrigin, 'Word 3');
assert.equal(decGalW3.elements.find(e => e.name === 'Ω̇').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'Ω̇').wordOrigin, 'Word 1~2');
assert.equal(decGalW3.elements.find(e => e.name === 'Δn').target, 'SB2');
assert.equal(decGalW3.elements.find(e => e.name === 'Δn').wordOrigin, 'Word 2');

const decGalW4 = decodeNavigationParameters({message: {constellationId: 2, satelliteId: 32, words: sampleGalW4}});
assert.ok(decGalW4);
assert.equal(decGalW4.constellation, 'Galileo');
assert.equal(decGalW4.subframeId, 4);
assert.match(decGalW4.title, /Word 4/);
assert.equal(decGalW4.elements.find(e => e.name === 't_oc').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 't_oc').wordOrigin, 'Word 2~3');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f0').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f0').wordOrigin, 'Word 3~4');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f1').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f1').wordOrigin, 'Word 4');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f2').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 'a_f2').wordOrigin, 'Word 4');
assert.equal(decGalW4.elements.find(e => e.name === 'C_ic').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 'C_ic').wordOrigin, 'Word 1~2');
assert.equal(decGalW4.elements.find(e => e.name === 'C_is').target, 'SB2');
assert.equal(decGalW4.elements.find(e => e.name === 'C_is').wordOrigin, 'Word 2');

// Test details DOM rendering
globalThis.document = {createElement: element};
const detailsEl = renderNavigationParametersDetails(decSf1);
assert.ok(detailsEl);
assert.equal(detailsEl.className, 'nav-param-details');
assert.ok(detailsEl.children.length >= 2);

const detailsElBds = renderNavigationParametersDetails(decBdsSf1);
assert.ok(detailsElBds);
assert.equal(detailsElBds.className, 'nav-param-details');

const detailsElGal = renderNavigationParametersDetails(decGalW1);
assert.ok(detailsElGal);
assert.equal(detailsElGal.className, 'nav-param-details');

// Test active navigation subframe key extraction
assert.equal(navigationSubframeKey({message: {constellationId: 0, satelliteId: 4, words: [0, (1 << 8)]}}), 'SF1');
assert.equal(navigationSubframeKey({message: {constellationId: 0, satelliteId: 4, words: [0, (2 << 8)]}}), 'SF2');
assert.equal(navigationSubframeKey({message: {constellationId: 0, satelliteId: 4, words: [0, (3 << 8)]}}), 'SF3');
assert.equal(navigationSubframeKey({message: {constellationId: 0, satelliteId: 4, words: [0, (5 << 8)]}}), null); // Almanac excluded
assert.equal(navigationSubframeKey({message: {constellationId: 3, satelliteId: 10, words: [(1 << 12), ...new Array(9).fill(0)]}}), 'SF1');
assert.equal(navigationSubframeKey({message: {constellationId: 3, satelliteId: 10, words: [(2 << 12), ...new Array(9).fill(0)]}}), 'SF2');
assert.equal(navigationSubframeKey({message: {constellationId: 3, satelliteId: 10, words: [(5 << 12), ...new Array(9).fill(0)]}}), null); // Almanac excluded
assert.equal(navigationSubframeKey({message: {constellationId: 2, satelliteId: 5, words: [(1 << 24), ...new Array(7).fill(0)]}}), 'W1');
assert.equal(navigationSubframeKey({message: {constellationId: 2, satelliteId: 5, words: [(4 << 24), ...new Array(7).fill(0)]}}), 'W4');
assert.equal(navigationSubframeKey({message: {constellationId: 2, satelliteId: 5, words: [(7 << 24), ...new Array(7).fill(0)]}}), null); // Almanac excluded

// Test getActivePvtNavigationItems chronology & replacement
const navGpsOld1 = {message: {constellationId: 0, satelliteId: 4, words: [0, (1 << 8)]}};
const navGpsOld2 = {message: {constellationId: 0, satelliteId: 4, words: [0, (2 << 8)]}};
const navGpsNew1 = {message: {constellationId: 0, satelliteId: 4, words: [0, (1 << 8)], seq: 2}};
const navGpsNew2 = {message: {constellationId: 0, satelliteId: 4, words: [0, (2 << 8)], seq: 2}};
const navGpsNew3 = {message: {constellationId: 0, satelliteId: 4, words: [0, (3 << 8)], seq: 2}};
const navGpsFuture = {message: {constellationId: 0, satelliteId: 4, words: [0, (1 << 8)], seq: 3}};

const sampleNav = [navGpsOld1, navGpsOld2, navGpsNew1, navGpsNew2, navGpsNew3, navGpsFuture];
const sampleNavPos = [0, 1, 3, 4, 5, 8];
// At epoch at index 2: only Old1 and Old2 exist
const activeAt2 = getActivePvtNavigationItems(sampleNav, sampleNavPos, 2);
assert.equal(activeAt2.size, 2);
assert.ok(activeAt2.has(navGpsOld1));
assert.ok(activeAt2.has(navGpsOld2));

// At epoch at index 6: New1, New2, New3 exist and overwrite Old1, Old2. Future (index 8) is ignored.
const activeAt6 = getActivePvtNavigationItems(sampleNav, sampleNavPos, 6);
assert.equal(activeAt6.size, 3);
assert.ok(activeAt6.has(navGpsNew1));
assert.ok(!activeAt6.has(navGpsOld1)); // Overwritten by New1!
assert.ok(activeAt6.has(navGpsNew2));
assert.ok(!activeAt6.has(navGpsOld2)); // Overwritten by New2!
assert.ok(activeAt6.has(navGpsNew3));
assert.ok(!activeAt6.has(navGpsFuture)); // Future ignored!

delete globalThis.document;
console.log('PASS: GPS LNAV / QZSS / BDS B1I / GAL I/NAV parameter decoding, SB2/SB3 mappings, originating words and DOM inspector');
console.log('PASS: Active navigation subframe keys, multi-epoch history replacement and PVT active filter');


assert.equal(getActivePvtNavigationItems([{message:{constellationId:3,satelliteId:4,words:[1<<12]}}], [0], 1).size, 0);

globalThis.document = {createElement: element};
globalThis.Option = function(text, value) { this.text = text; this.value = value; };

// I/Q file identity must not be inferred from a successful PVT calculation.
for (const [file, pvt, label, tone] of [
  ['PASS','MEASURED','파일 일치 · PVT 측정 완료','online'],
  ['PASS','INCONCLUSIVE','파일 일치 · PVT 비교 불가','warning'],
  ['PASS','FAIL','파일 일치 · PVT 불일치','error'],
  ['FAIL','MEASURED','파일 불일치 · PVT 측정 완료','error'],
  [null,'MEASURED','파일 검증 미확인 · PVT 측정 완료','warning'],
  ['PASS',null,'파일 일치 · PVT 비교 대기','warning']
]) assert.deepEqual(iqIntegrity({verdict:file},{verdict:pvt}),{label,tone});

assert.deepEqual(iqIntegrity({verdict:'PASS'},null,'FAILED'),{label:'파일 일치 · PVT 비교 불가',tone:'warning'});
assert.deepEqual(iqIntegrity({verdict:'FAIL'},null,'FAILED'),{label:'파일 불일치 · PVT 비교 불가',tone:'error'});

const iqWire = {constellationId:0,satelliteId:9,signalId:0,week:2400,towSeconds:100021,
  dopplerHz:raw.dopplerHz,carrierToNoiseDbHz:raw.carrierToNoiseDbHz,trackingStatus:3,
  transmitAt:{seconds:1790000000,femtoseconds:123456789000000}};
const iqData = {...original,source:'IQ_FRAME',receivedValues:{observations:[iqWire]},
  trackingEpochs:[{elapsedSeconds:21,observations:[{satelliteId:9,pseudorangeMeters:21000001,
    dopplerHz:-101,carrierToNoiseDbHz:44,carrierPhaseCycles:123.5}]}]};
const iqReport = {testType:'IQ_SAMPLE',fileResult:{verdict:'PASS'},comparison:{verdict:'MEASURED'},
  referenceObservations:original};
receiverView.setData(iqData,false,null,iqReport);
let iqRow=nodes.get('[data-observations]').children[0];
assert.equal(nodes.get('[data-integrity]').textContent,'파일 일치 · PVT 측정 완료');
assert.equal(nodes.get('[data-range-heading]').hidden,true);
assert.equal(nodes.get('[data-phase-heading]').hidden,true);
assert.equal(nodes.get('[data-navigation-pvt-active]').checked,false);
assert.equal(nodes.get('[data-pvt-filter]').checked,false);
assert.equal(nodes.get('[data-navigation]').children.length,1);
assert.equal(iqRow.children[3].textContent,'—','I/Q never reads Reference pseudorange');
for(const i of [3,5,8,9]) assert.equal(iqRow.children[i].hidden,true);
assert.equal(iqRow.children[4].textContent,numeric(raw.pseudorangeMeters));
assert.equal(iqRow.children[4].className,'converted-range');
assert.equal(iqRow.children[10].textContent,'PR 유효');
assert.equal(nodes.get('[data-iq-tracking]').open,true);
assert.equal(nodes.get('[data-iq-tracking-body]').children[0].children[5].textContent,'123.500');
receiverView.setData({...iqData,epochs:[]},false,null,{...iqReport,comparison:{verdict:'INCONCLUSIVE'}});
assert.equal(nodes.get('[data-observations]').children.length,1,'received values remain visible without PVT');
assert.equal(nodes.get('[data-integrity]').textContent,'파일 일치 · PVT 비교 불가');
receiverView.setData({...wireView,receivedValues:{observations:[{...wireObservation,week:2400,towSeconds:100021}]}},false,null,{referenceStatus:'COMPLETE',referenceObservations:original});
assert.equal(nodes.get('[data-range-heading]').hidden,false);
assert.equal(nodes.get('[data-phase-heading]').hidden,false);
assert.equal(nodes.get('[data-navigation-pvt-active]').checked,true);
assert.equal(nodes.get('[data-observations]').children[0].children[3].textContent,numeric(raw.pseudorangeMeters));
assert.equal(nodes.get('[data-integrity]').textContent,'데이터 일치');
console.log('PASS: I/Q decoded values without original comparison, file/PVT separation, phase diagnostics and RAW/AFS reset');

delete globalThis.document;
delete globalThis.Option;
