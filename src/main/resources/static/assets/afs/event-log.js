const TEST_NAMES = {
    TEST_A_NORMAL: 'Test A 정상 전송',
    TEST_B_RANDOM_ERRORS: 'Test B 임의 비트 오류',
    TEST_C_BURST_ERRORS: 'Test C 연속 비트 오류',
    TEST_D_SYNC_RECOVERY: 'Test D 손상 프레임 제외 후 재동기',
};

const SESSION_STATES = {
    CREATED: '시험 생성',
    WAITING_RECEIVER: 'Receiver 준비 대기',
    TRANSMITTING: '시험 데이터 송신',
    EVALUATING: '수신 데이터 검증',
    COMPLETED: '시험 완료',
    CANCELLED: '시험 취소',
    FAILED: '시험 실패',
    INCONCLUSIVE: '판정 불가',
};

const METRIC_NAMES = {
    DecodedFrames: 'Decoder 처리 프레임',
    FullyDecodedFrames: 'CRC 통과 완전 복호 프레임',
    Sb2CrcValidFrames: 'SB2 CRC 정상 프레임',
    Sb3CrcValidFrames: 'SB3 CRC 정상 프레임',
    Sb4CrcValidFrames: 'SB4 CRC 정상 프레임',
    CorrectedSymbols: 'LDPC 내부 판정 변경량',
    RecoveredSyncFrames: '연속 정상 SP 재획득 프레임',
};

const METRIC_STATUSES = {
    PASS: '정상',
    FAIL: '실패',
    MEASURED: '측정',
    NOT_APPLICABLE: '해당 없음',
};

const testTypeBySession = new Map();

function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Agent Progress의 상세 값은 counters에 들어오므로 최상위 필드와 합쳐 사용한다. */
function detailsOf(payload) {
    if (!isObject(payload)) {
        return {};
    }
    return {
        ...(isObject(payload.counters) ? payload.counters : {}),
        ...payload,
    };
}

function number(value, fallback = 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
}

function percent(details) {
    const value = details.progress ?? details.percent;
    return Number.isFinite(Number(value)) ? `${Math.round(Number(value))}%` : '-';
}

function bytes(value) {
    return `${number(value).toLocaleString('ko-KR')} bytes`;
}

export function describeTestType(testType) {
    return TEST_NAMES[testType] || testType || '시험 종류 미확인';
}

/** Test A~D 설정을 시험 목적에 맞는 일반 사용자용 설명으로 변환한다. */
export function describeTestCondition(details) {
    switch (details.testType) {
        case 'TEST_A_NORMAL':
            return '오류나 패킷 손실을 주입하지 않고 원본과 수신 결과를 비교';
        case 'TEST_B_RANDOM_ERRORS':
            return `각 AFS 프레임 데이터 영역에 임의 비트 ${number(details.errorCount)}개 손상`
                + ` (Seed ${number(details.errorSeed)})`;
        case 'TEST_C_BURST_ERRORS':
            return `각 AFS 프레임 데이터 영역에 연속 비트 ${number(details.errorCount)}개 손상`
                + ` (Seed ${number(details.errorSeed)})`;
        case 'TEST_D_SYNC_RECOVERY':
            return `0번 프레임부터 ${number(details.syncDamageInterval)}프레임마다 68심볼 SP의 bit `
                + `${number(details.errorCount)}개 손상 후 해당 프레임 제외·다음 연속 정상 SP 재획득 `
                + `(총 ${number(details.injectedFrameCount)}프레임 제외)`;
        default:
            return '시험 조건 정보 없음';
    }
}

function rememberTestType(event, details) {
    if (event.sessionId && details.testType) {
        testTypeBySession.set(event.sessionId, details.testType);
    }
    return details.testType || testTypeBySession.get(event.sessionId);
}

function formatSession(event, details) {
    const testType = rememberTestType(event, details);
    const state = SESSION_STATES[details.state] || details.state || '상태 갱신';
    const values = [`세션 ${state}`, `진행률 ${percent(details)}`];

    if (testType) {
        values.push(describeTestType(testType));
    }
    if (details.verdict) {
        values.push(`판정 ${details.verdict}`);
    }
    if (details.message) {
        values.push(details.message);
    }
    return `SESSION_STATUS · ${values.join(' · ')}`;
}

function formatPrepared(details) {
    return [
        `TX 준비 ${percent(details)} · ${describeTestType(details.testType)}`,
        `원본 ${bytes(details.sourceBytes)}, GRAW ${number(details.recordCount)} records, AFS ${number(details.totalFrames)} frames`,
        `시험 조건: ${describeTestCondition(details)}`,
    ].join('\n    ');
}

function formatTransmitting(details) {
    return `TX ${percent(details)} · AFS 프레임 `
        + `${number(details.transferredFrames)}/${number(details.totalFrames)} 전달`;
}

function formatTxStatus(event, details) {
    rememberTestType(event, details);
    if (details.stage === 'Prepared') {
        return formatPrepared(details);
    }
    if (details.stage === 'Transmitting' && details.totalFrames !== undefined) {
        return formatTransmitting(details);
    }
    return `TX ${percent(details)} · ${details.message || details.stage || '상태 갱신'}`;
}

function formatRxStatus(event, details) {
    const testType = rememberTestType(event, details);
    if (details.message?.endsWith('session started')) {
        return [
            `RX 시작 ${percent(details)} · ${describeTestType(details.testType)}`,
            `예상 ${number(details.expectedFrames)} frames, GRAW ${number(details.recordCount)} records, 원본 ${bytes(details.sourceBytes)}`,
            `시험 조건: ${describeTestCondition(details)}`,
        ].join('\n    ');
    }
    if (details.receivedFrames !== undefined) {
        return `RX ${percent(details)} · AFS 프레임 수신`
            + ` · 누적 ${number(details.receivedFrames)}/${number(details.expectedFrames)} frames`;
    }
    if (details.stage === 'Evaluating') {
        return `RX ${percent(details)} · 프레임 재조립 및 복호화 중`
            + ` · ${number(details.receivedFrames)}/${number(details.expectedFrames)} frames`;
    }
    if (details.stage === 'Verifying') {
        if (testType === 'TEST_D_SYNC_RECOVERY' && !details.integritySuccess) {
            return `RX ${percent(details)} · Test D 예상 부분 복원`
                + ` · 크기 ${bytes(details.reconstructedLength)}/${bytes(details.sourceLength)}`
                + ` · records ${number(details.reconstructedRecords)}/${number(details.expectedRecords)}`
                + ' · 전체 SHA-256 불일치는 동기 손상 프레임 제외에 따른 정상 결과';
        }
        return `RX ${percent(details)} · 무결성 검증 ${details.integritySuccess ? '성공' : '실패'}`
            + ` · 크기 ${bytes(details.reconstructedLength)}/${bytes(details.sourceLength)}`
            + ` · records ${number(details.reconstructedRecords)}/${number(details.expectedRecords)}`
            + ` · SHA-256 ${details.sha256Match ? '일치' : '불일치'}`;
    }
    return `RX ${percent(details)} · ${details.message || details.stage || '상태 갱신'}`;
}

function formatMetrics(metrics) {
    if (!Array.isArray(metrics) || metrics.length === 0) {
        return '';
    }
    return metrics.map((metric) => {
        const name = METRIC_NAMES[metric.name] || metric.name;
        const value = metric.value === null || metric.value === undefined
            ? metric.status
            : `${metric.value}${metric.unit ? ` ${metric.unit}` : ''}`;
        const statusText = METRIC_STATUSES[metric.status] || metric.status;
        return `${name} ${value}${statusText ? ` (${statusText})` : ''}`;
    }).join(', ');
}

function formatResult(event, result) {
    const testType = testTypeBySession.get(event.sessionId);
    const role = result.role || event.role || 'UNKNOWN';
    const counters = isObject(result.counters) ? result.counters : {};
    const integrity = isObject(result.integrity) ? result.integrity : {};
    const lines = [
        `RESULT · ${role} 최종 판정 ${result.verdict || '미확인'}`,
        `AFS 프레임 생성 ${number(counters.expectedFrames)}`
            + ` · 전달 ${number(counters.transferredFrames)}`
            + ` · 처리 ${number(counters.processedFrames)}`
            + ` · 복호화 실패 ${number(counters.decodeFailedFrames)}`,
    ];

    if (counters.injectedBitCount > 0
        || ['TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS', 'TEST_D_SYNC_RECOVERY'].includes(testType)) {
        const injectionParts = [`시험 주입 오류 ${number(counters.injectedBitCount)} bit`];
        if (testType === 'TEST_D_SYNC_RECOVERY' || counters.syncRejectedFrames > 0) {
            injectionParts.push(`동기 손상 제외 ${number(counters.syncRejectedFrames)} frame`);
        }
        lines.push(injectionParts.join(' · '));
    }

    if (Object.keys(integrity).length > 0) {
        const integrityLabel = testType === 'TEST_D_SYNC_RECOVERY' && !integrity.success
            ? 'GRAW 부분 복원 (Test D 예상)'
            : `무결성 ${integrity.success ? '성공' : '실패'}`;
        lines.push(
            integrityLabel
            + ` · 크기 ${bytes(integrity.reconstructedLength)}/${bytes(integrity.sourceLength)}`
            + ` · records ${number(integrity.reconstructedRecords)}/${number(integrity.expectedRecords)}`
            + ` · SHA-256 ${integrity.sourceSha256 === integrity.reconstructedSha256 ? '일치' : '불일치'}`,
        );
    }

    const metrics = formatMetrics(result.metrics);
    if (metrics) {
        lines.push(`측정값: ${metrics}`);
    }
    if (result.error) {
        lines.push(`오류: ${result.error}`);
    }
    return lines.join('\n    ');
}

/** WebSocket 이벤트 하나를 빈 항목 없이 상세한 한글 로그 문장으로 변환한다. */
export function formatEventLog(event) {
    const payload = event.payload;
    const details = detailsOf(payload);

    switch (event.type) {
        case 'SESSION_STATUS':
            return formatSession(event, details);
        case 'TX_STATUS':
            return formatTxStatus(event, details);
        case 'RX_STATUS':
            return formatRxStatus(event, details);
        case 'RESULT':
            return formatResult(event, details);
        case 'AGENT_STATUS': {
            if (Array.isArray(details.ports)) {
                const ports = details.ports.map((port) => port.name).join(', ') || '없음';
                return `AGENT_STATUS · ${event.agentId || 'Agent'} COM 포트 ${details.ports.length}개: ${ports}`;
            }
            const state = typeof payload === 'string' ? payload : details.state;
            return `AGENT_STATUS · ${event.agentId || 'Agent'} (${event.role || '역할 미확인'})`
                + ` 연결 상태 ${state || '갱신'}`;
        }
        case 'GNSS_STATUS':
            return `GNSS ${percent(details)} · ${details.message || details.stage || '상태 갱신'}`;
        case 'ERROR':
            return `ERROR · ${details.message || details.detail || payload || '알 수 없는 오류'}`;
        default:
            return `${event.type} · ${details.message || details.stage || '상태 갱신'}`;
    }
}
