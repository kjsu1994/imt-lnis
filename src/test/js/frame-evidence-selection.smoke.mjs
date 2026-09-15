import assert from 'node:assert/strict';
import {
    frameOptionLabel,
    selectInitialFrameIndex,
} from '../../main/resources/static/assets/afs/frame-evidence.js';

const summaries = [
    {
        frameIndex: 0,
        decodeSucceeded: true,
        referenceToReencodedDifferences: 0,
    },
    {
        frameIndex: 1,
        decodeSucceeded: false,
        referenceToReencodedDifferences: 2001,
    },
    {
        frameIndex: 2,
        decodeSucceeded: false,
        referenceToReencodedDifferences: 577,
    },
];

// 전체 FAIL에서는 성공한 1번이 아니라 원인을 보여주는 첫 실패 프레임을 선택한다.
assert.equal(selectInitialFrameIndex(summaries, 'FAIL'), 1);

// PASS 시험과 판정 미지정 상태는 기존처럼 첫 보관 프레임부터 보여준다.
assert.equal(selectInitialFrameIndex(summaries, 'PASS'), 0);
assert.equal(selectInitialFrameIndex(summaries, null), 0);

// 실패 시험이라도 모든 프레임이 완전히 복구됐다면 안전하게 첫 프레임으로 돌아간다.
assert.equal(selectInitialFrameIndex([summaries[0]], 'FAIL'), 0);

assert.equal(
    frameOptionLabel(summaries[0]),
    '1번 프레임 · 완전 복구',
);
assert.equal(
    frameOptionLabel({ ...summaries[1], decoderCompleted: true }),
    '2번 프레임 · CRC 실패',
);

console.log('frame-evidence selection smoke test passed');
