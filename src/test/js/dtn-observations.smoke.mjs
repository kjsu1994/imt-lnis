import assert from 'node:assert/strict';
import {numeric, clockBias, renderClockBias, observationCells, navigationCells, createObservationView, transmitTime, pvtInputStatus, lockTime, unclassifiedNavigation, receiverInformation, observationCounts} from '../../main/resources/static/assets/dtn/dtn-observations.js';

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
  assert.match(unclassifiedNavigation({constellationId}),/(항법정보 수신.*계산 대상 아님|Signal X)/);
}
assert.equal(unclassifiedNavigation({constellationId:0,signalId:0}),'메시지 종류 확인 불가');
assert.equal(unclassifiedNavigation({constellationId:99}),'메시지 종류 확인 불가');
assert.match(unclassifiedNavigation({constellationId:0,signalId:3}),/GPS L1 C\/A 전용/);
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
