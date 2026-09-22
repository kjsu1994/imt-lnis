import assert from 'node:assert/strict';
import {numeric, observationCells, navigationCells, createObservationView, transmitTime} from '../../main/resources/static/assets/dtn/dtn-observations.js';

assert.equal(numeric(null), '—');
assert.equal(numeric(NaN), '—');
assert.equal(numeric(Infinity), '—');
assert.equal(numeric(0), '0.000');
const raw = {constellationId: 0, satelliteId: 9, signalId: 0,
  pseudorangeMeters: 21234567.123, carrierPhaseCycles: -123456,
  dopplerHz: -987.5, carrierToNoiseDbHz: 44, lockTimeMilliseconds: 4000,
  pseudorangeStdDev: 3, carrierPhaseStdDev: 4, dopplerStdDev: 5, trackingStatus: 3};
const cells = observationCells(raw);
assert.equal(cells[3], '21234567.123');
assert.equal(cells[5], '-987.500');
assert.equal(cells[8], '3 / 4 / 5');
assert.equal(cells[10], '계산 대상');
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
assert.equal(iq[7],undefined); // Never invent F9T lock-time or deviation codes for SDR observations.

// Exercise the complete view update, including navigation rendering and epoch selection.
const element = () => ({value: '0', children: [], append(child) { this.children.push(child); },
  replaceChildren(...children) { this.children = children; }});
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
assert.equal(nodes.get('tbody').children[0].children[11].textContent, transmitTime(raw, 100021));
assert.equal(nodes.get('[data-navigation]').children[0].children[8].textContent, '008B0000 00000000');
view.setData(null);
assert.equal(nodes.get('tbody').children[0].children[0].colSpan, 12);
assert.equal(nodes.get('[data-epoch]').disabled, true);
const original = {navigationCount:1,navigation,epochs};
const originalJson = JSON.stringify(original);
const receiverView = createObservationView(container, () => {}, '수신 원본');
const evidence = {originalTime:{week:2400,towSeconds:100021},shiftedTime:{week:2400,towSeconds:100040.222158},
  addedMeters:5762657994.884364,satellites:[{constellationId:0,satelliteId:9,signalId:0,
    originalMeters:raw.pseudorangeMeters,recalculatedMeters:raw.pseudorangeMeters+5762657994.884364}]};
receiverView.setData(original, false, evidence);
assert.equal(nodes.get('[data-range-after]').hidden,false);
assert.equal(nodes.get('tbody').children[0].children.length,14);
assert.equal(nodes.get('tbody').children[0].children[3].textContent,numeric(raw.pseudorangeMeters));
assert.equal(nodes.get('tbody').children[0].children[4].textContent,numeric(evidence.satellites[0].recalculatedMeters));
assert.equal(nodes.get('tbody').children[0].children[5].textContent,numeric(evidence.addedMeters));
assert.equal(nodes.get('tbody').children[0].children[7].textContent,numeric(raw.dopplerHz));
assert.match(nodes.get('[data-delay-summary]').textContent,/100040.222158/);
receiverView.setData(original, false, {...evidence,error:'음수 지연'});
assert.equal(nodes.get('tbody').children[0].children[4].textContent,'—');
receiverView.setData(original, false, {...evidence,satellites:[{...evidence.satellites[0],satelliteId:99}]});
assert.equal(nodes.get('tbody').children[0].children[4].textContent,'—');
receiverView.setData(original);
assert.equal(nodes.get('[data-range-after]').hidden,true);
assert.equal(nodes.get('tbody').children[0].children.length,12);
assert.equal(JSON.stringify(original),originalJson);
console.log('PASS: receiver original/converted ranges, Doppler preservation, evidence mismatch and restore isolation');

// Frame-derived solver input is a separate view; never overwrite the original RAWX table.
const frameView = {...original, frameInput: {source: 'AFS_V4', frameCount: 1, epochs, navigation}};
receiverView.setData(frameView);
assert.equal(nodes.get('[data-frame-input]').hidden, false);
assert.equal(nodes.get('[data-frame-values]').children[0].children[3].textContent, numeric(raw.pseudorangeMeters, 6));
assert.equal(nodes.get('tbody').children[0].children[3].textContent, numeric(raw.pseudorangeMeters));
receiverView.setData(original);
assert.equal(nodes.get('[data-frame-input]').hidden, true);
assert.equal(nodes.get('[data-frame-values]').children.length, 0);
assert.equal(JSON.stringify(original), originalJson);
console.log('PASS: separate AFS frame calculation inputs and legacy view reset');

delete globalThis.document;
delete globalThis.Option;
console.log('PASS: full I/Q and GRAW view updates, navigation HEX widths and empty state');
