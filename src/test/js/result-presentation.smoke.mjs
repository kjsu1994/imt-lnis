import assert from 'node:assert/strict';
import { buildResultPresentation } from '../../main/resources/static/assets/common/api.js';

const result = {
    role: 'RECEIVER',
    verdict: 'PASS',
    integrity: {
        success: true,
        sourceLength: 464,
        reconstructedLength: 464,
        sourceSha256: 'same-hash',
        reconstructedSha256: 'same-hash',
        expectedRecords: 4,
        reconstructedRecords: 4,
    },
    counters: {
        expectedFrames: 4,
        transferredFrames: 4,
        processedFrames: 4,
        decodeFailedFrames: 0,
        injectedBitCount: 0,
        syncRejectedFrames: 0,
        rawBytes: 464,
    },
    metrics: [
        { name: 'DecodedFrames', value: 4 },
        { name: 'FullyDecodedFrames', value: 4 },
        { name: 'Sb2CrcValidFrames', value: 4 },
        { name: 'Sb3CrcValidFrames', value: 4 },
        { name: 'Sb4CrcValidFrames', value: 4 },
        { name: 'CorrectedSymbols', value: 19424 },
    ],
};

const presentation = buildResultPresentation(result);
assert.equal(presentation.summary.passed, true);
assert.match(presentation.summary.title, /정상적으로 복원/);
assert.deepEqual(
    presentation.groups.map((group) => group.title),
    ['1. 원본 복원 결과', '2. 프레임 처리 결과', '3. 전송 및 오류 처리 현황'],
);

const allMetrics = presentation.groups.flatMap((group) => group.metrics);
assert.equal(
    allMetrics.find((metric) => metric.name === '전체 데이터 동일 여부')?.value,
    '일치',
);
assert.equal(
    allMetrics.find((metric) => metric.name === 'AFS 프레임 전달')?.value,
    '4 / 4 frame',
);
assert.equal(
    allMetrics.find((metric) => metric.name === 'AFS Decoder 처리')?.value,
    '4 / 4 frame',
);
assert.equal(
    allMetrics.find((metric) => metric.name === 'CRC까지 통과한 완전 복호')?.value,
    '4 / 4 frame',
);
assert.equal(
    allMetrics.find((metric) => metric.name === '연결로 전달한 AFS 프레임')?.value,
    '4 frame',
);
assert.equal(
    allMetrics.find((metric) => metric.name === 'AFS 복호화 실패')?.value,
    '0 frame',
);
assert.equal(allMetrics.some((metric) => metric.name === '손상 패킷'), false);

const ldpc = presentation.diagnostics.find(
    (metric) => metric.name === 'LDPC 내부 판정 변경량',
);
assert.equal(ldpc.value, '19,424 bit');
assert.equal(ldpc.status.text, '참고값');
assert.match(ldpc.description, /실제 주입 오류 개수로 해석하면 안 됩니다/);

// Test D는 동기 손상 프레임을 의도적으로 제외하므로 전체 파일 불일치를 실패처럼 표시하면 안 된다.
const syncRecoveryResult = {
    ...result,
    integrity: {
        success: false,
        sourceLength: 464,
        reconstructedLength: 348,
        sourceSha256: 'source-hash',
        reconstructedSha256: 'partial-hash',
        expectedRecords: 4,
        reconstructedRecords: 3,
    },
    counters: {
        ...result.counters,
        testType: 'TEST_D_SYNC_RECOVERY',
        injectedFrameCount: 1,
        injectedBitCount: 1,
        syncRejectedFrames: 1,
        rawBytes: 348,
    },
    metrics: [
        { name: 'DecodedFrames', value: 3 },
        { name: 'FullyDecodedFrames', value: 3 },
        { name: 'RecoveredSyncFrames', value: 3 },
    ],
};
const syncPresentation = buildResultPresentation(syncRecoveryResult);
const syncMetrics = syncPresentation.groups.flatMap((group) => group.metrics);

assert.match(syncPresentation.summary.title, /다음 정상 동기부터 CRC 복호화/);
assert.deepEqual(
    syncPresentation.groups.map((group) => group.title),
    ['1. 손상 프레임 제외 후 재동기 판정', '2. GRAW 부분 복원 범위', '3. 전송 및 오류 처리 현황'],
);
assert.equal(
    syncMetrics.find((metric) => metric.name === '전체 데이터 동일 여부')?.status.text,
    '예상 결과',
);
assert.equal(
    syncMetrics.find((metric) => metric.name === '연속 정상 SP 재획득')?.value,
    '3 / 3 frame',
);
assert.equal(
    syncMetrics.find((metric) => metric.name === 'CRC 통과 완전 복호')?.value,
    '3 / 3 frame',
);

// Decoder 호출이 끝났더라도 SB2·SB3·SB4 CRC를 모두 통과하지 못하면 Test D 핵심 검사가 실패해야 한다.
const incompleteCrcPresentation = buildResultPresentation({
    ...syncRecoveryResult,
    verdict: 'FAIL',
    metrics: syncRecoveryResult.metrics.map((metric) => (
        metric.name === 'FullyDecodedFrames' ? { ...metric, value: 2 } : metric
    )),
});
assert.equal(
    incompleteCrcPresentation.summary.checks
        .find((check) => check.label === '다음 정상 SP 재획득 및 CRC 완전 복호')?.ok,
    false,
);
assert.equal(
    syncMetrics.find((metric) => metric.name === '시험에서 주입한 오류')?.value,
    '1 bit',
);
assert.equal(
    syncMetrics.find((metric) => metric.name === '동기 손상으로 제외')?.value,
    '1 frame',
);

console.log('result-presentation smoke test passed');
