import assert from 'node:assert/strict';
import {numeric, observationCells} from '../../main/resources/static/assets/dtn-observations.js';

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
assert.equal(observationCells({...raw, constellationId: 2})[10], '제외');
assert.equal(observationCells({...raw, trackingStatus: 0})[10], '제외');
assert.equal(observationCells({...raw, signalId: 3})[10], '제외');
console.log('PASS: RAWX numeric units, invalid/missing values, deviation codes and GPS L1 input eligibility');
