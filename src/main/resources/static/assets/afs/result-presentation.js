function displayNumber(value) {
    return Number(value).toLocaleString('ko-KR', {
        maximumFractionDigits: 3,
    });
}

function hasValue(value) {
    return value !== undefined && value !== null;
}

function metricValue(result, name) {
    return result?.metrics?.find((metric) => metric.name === name)?.value;
}

function formatValue(value, unit = '') {
    const formatted = typeof value === 'number' ? displayNumber(value) : value;
    if (unit === '%') {
        return `${formatted}%`;
    }
    return `${formatted}${unit ? ` ${unit}` : ''}`;
}

function status(condition, successText = '정상', failureText = '확인 필요') {
    if (condition === undefined) {
        return { tone: 'info', text: '참고' };
    }
    return condition
        ? { tone: 'pass', text: successText }
        : { tone: 'fail', text: failureText };
}

function card(name, value, description, itemStatus, unit = '') {
    if (!hasValue(value)) {
        return null;
    }
    return {
        name,
        value: formatValue(value, unit),
        description,
        status: itemStatus || status(undefined),
    };
}

/** 일반 사용자가 가장 먼저 확인해야 할 최종 복원 결과를 한 문장으로 만든다. */
function resultSummary(result, context = {}) {
    const integrity = result?.integrity || {};
    const counters = result?.counters || {};
    const testType = context.testType || counters.testType;
    const passed = result?.verdict === 'PASS';
    const frameComplete = hasValue(counters.expectedFrames)
        && counters.transferredFrames === counters.expectedFrames;
    const sizeMatch = hasValue(integrity.sourceLength)
        && integrity.sourceLength === integrity.reconstructedLength;
    const recordMatch = hasValue(integrity.expectedRecords)
        && integrity.expectedRecords === integrity.reconstructedRecords;
    const hashMatch = Boolean(integrity.sourceSha256)
        && integrity.sourceSha256 === integrity.reconstructedSha256;

    const integrityPassed = integrity.success === true
        || (sizeMatch && recordMatch && hashMatch);

    if (testType === 'TEST_D_SYNC_RECOVERY') {
        const decoded = metricValue(result, 'DecodedFrames');
        const fullyDecoded = metricValue(result, 'FullyDecodedFrames');
        const recoveredSync = metricValue(result, 'RecoveredSyncFrames');
        const injected = Number(context.injectedFrameCount ?? counters.injectedFrameCount ?? 0);
        const recoveryTarget = hasValue(counters.expectedFrames)
            ? Math.max(0, counters.expectedFrames - injected)
            : undefined;
        const recoveredExpectedFrames = hasValue(recoveryTarget)
            && decoded === recoveryTarget
            && fullyDecoded === recoveryTarget
            && recoveredSync === recoveryTarget;

        return {
            passed,
            title: passed
                ? '손상 프레임을 제외하고 다음 정상 동기부터 CRC 복호화했습니다.'
                : '동기 손상 이후의 재동기화 결과를 확인해야 합니다.',
            description: passed
                ? `Test D는 손상 프레임 자체를 복구하는 시험이 아닙니다. 동기 패턴을 손상한 ${displayNumber(injected)}개 프레임을 제외하고, 연속된 정상 SP를 다시 확인해 나머지 프레임의 SB2·SB3·SB4 CRC를 검증합니다.`
                : result?.error || integrity.detail || '아래 동기 복구 항목에서 목표 수치와 실제 수치를 비교하세요.',
            checks: [
                { label: 'AFS 논리 프레임 모두 도착', ok: frameComplete },
                { label: `손상 ${displayNumber(injected)}개 의도적 제외`, ok: injected > 0 },
                { label: '다음 정상 SP 재획득 및 CRC 완전 복호', ok: recoveredExpectedFrames },
            ],
        };
    }
    const checks = [
        { label: '프레임', ok: frameComplete },
    ];
    if (integrityPassed || !passed) {
        checks.push(
            { label: '데이터 크기', ok: sizeMatch },
            { label: '레코드', ok: recordMatch },
            { label: 'SHA-256', ok: hashMatch },
        );
    } else {
        checks.push({ label: '시험별 복구 기준', ok: true });
    }

    return {
        passed,
        title: passed && integrityPassed
            ? '원본 데이터가 정상적으로 복원되었습니다.'
            : passed
                ? '선택한 시험의 복구 판정 기준을 충족했습니다.'
                : '복원 결과에 확인이 필요한 차이가 있습니다.',
        description: passed && integrityPassed
            ? '프레임 수신과 GRAW 재조립을 마친 뒤 원본 크기, 레코드 수와 SHA-256을 확인했습니다.'
            : passed
                ? 'Test D처럼 의도적으로 손상한 프레임이 있는 시험은 완전 복원 대신 동기 복구 기준으로 판정합니다.'
            : result?.error || integrity.detail || '아래 실패 항목을 확인해 원인을 판단하세요.',
        checks,
    };
}

function integrityCards(result, { expectedPartial = false } = {}) {
    const integrity = result?.integrity || {};
    const sizeMatch = integrity.sourceLength === integrity.reconstructedLength;
    const recordMatch = integrity.expectedRecords === integrity.reconstructedRecords;
    const hashMatch = Boolean(integrity.sourceSha256)
        && integrity.sourceSha256 === integrity.reconstructedSha256;

    return [
        card(
            '전체 데이터 동일 여부',
            hashMatch ? '일치' : '불일치',
            expectedPartial
                ? 'Test D는 동기를 손상한 프레임을 의도적으로 제외하므로 SHA-256 불일치가 예상됩니다. 동기 복구 성공 여부는 위의 전용 판정에서 확인합니다.'
                : 'SHA-256은 파일 전체 바이트를 비교합니다. 일치하면 원본과 복원 데이터가 사실상 동일하다는 뜻입니다.',
            expectedPartial && !hashMatch
                ? { tone: 'warning', text: '예상 결과' }
                : status(hashMatch, '완전 일치'),
        ),
        card(
            'GRAW 데이터 크기',
            `${displayNumber(integrity.reconstructedLength)} / ${displayNumber(integrity.sourceLength)}`,
            expectedPartial
                ? '앞 숫자는 손상 프레임을 제외하고 Receiver가 복원한 크기, 뒤 숫자는 Sender 원본 크기입니다. Test D 판정에는 전체 크기 일치를 요구하지 않습니다.'
                : '앞 숫자는 Receiver 복원 크기, 뒤 숫자는 Sender 원본 크기입니다.',
            expectedPartial && !sizeMatch
                ? { tone: 'warning', text: '부분 복원' }
                : status(sizeMatch, '크기 일치'),
            'byte',
        ),
        card(
            'GRAW 레코드',
            `${displayNumber(integrity.reconstructedRecords)} / ${displayNumber(integrity.expectedRecords)}`,
            expectedPartial
                ? '앞 숫자는 손상되지 않은 프레임에서 복원한 레코드, 뒤 숫자는 원본 레코드입니다. 빠진 레코드는 동기 손상 시험에서 의도적으로 제외된 데이터입니다.'
                : '앞 숫자는 완전히 복원한 레코드, 뒤 숫자는 원본 레코드입니다.',
            expectedPartial && !recordMatch
                ? { tone: 'warning', text: '부분 복원' }
                : status(recordMatch, '개수 일치'),
            'record',
        ),
    ].filter(Boolean);
}

/** Test D의 목적에 맞춰 손상 프레임 제외 수와 재동기화 성공 수를 직접 비교한다. */
function syncRecoveryCards(result, context = {}) {
    const counters = result?.counters || {};
    const expected = counters.expectedFrames;
    const received = counters.transferredFrames;
    const injected = Number(context.injectedFrameCount ?? counters.injectedFrameCount ?? 0);
    const recoveryTarget = hasValue(expected) ? Math.max(0, expected - injected) : undefined;
    const decoded = metricValue(result, 'DecodedFrames');
    const fullyDecoded = metricValue(result, 'FullyDecodedFrames');
    const recoveredSync = metricValue(result, 'RecoveredSyncFrames');

    return [
        card(
            '의도적 동기 손상',
            injected,
            'Sender가 AFS 동기 패턴을 일부러 훼손한 프레임 수입니다. 이 프레임은 복호화 대상에서 제외되는 것이 정상입니다.',
            { tone: 'warning', text: '시험 조건' },
            'frame',
        ),
        hasValue(received) && hasValue(expected) ? card(
            'AFS 논리 프레임 도착',
            `${displayNumber(received)} / ${displayNumber(expected)}`,
            '동기 패턴의 정상 여부와 별개로, 중복을 제거한 AFS FRAME 패킷이 모두 Receiver에 도착했는지 확인합니다.',
            status(received === expected, '모두 도착'),
            'frame',
        ) : null,
        hasValue(recoveredSync) && hasValue(recoveryTarget) ? card(
            '연속 정상 SP 재획득',
            `${displayNumber(recoveredSync)} / ${displayNumber(recoveryTarget)}`,
            'PocketSDR-AFS 방식처럼 68심볼 SP가 완전히 일치하고, 6,000심볼 간격의 이웃 SP와 쌍을 이루는지 확인한 수입니다.',
            status(recoveredSync === recoveryTarget, '재획득 성공'),
            'frame',
        ) : null,
        hasValue(decoded) && hasValue(recoveryTarget) ? card(
            'Decoder 처리 프레임',
            `${displayNumber(decoded)} / ${displayNumber(recoveryTarget)}`,
            '동기를 재획득한 뒤 Native AFS Decoder가 처리를 완료한 수입니다. CRC 완전 통과 여부는 다음 항목에서 별도로 확인합니다.',
            status(decoded === recoveryTarget, '처리 완료'),
            'frame',
        ) : null,
        hasValue(fullyDecoded) && hasValue(recoveryTarget) ? card(
            'CRC 통과 완전 복호',
            `${displayNumber(fullyDecoded)} / ${displayNumber(recoveryTarget)}`,
            '동기를 재획득한 모든 프레임에서 SB2·SB3·SB4 CRC-24Q가 전부 정상인지 확인합니다. Test D PASS의 필수 조건입니다.',
            status(fullyDecoded === recoveryTarget, '모두 정상'),
            'frame',
        ) : null,
    ].filter(Boolean);
}

function frameCards(result) {
    const counters = result?.counters || {};
    const processed = metricValue(result, 'DecodedFrames');
    const fullyDecoded = metricValue(result, 'FullyDecodedFrames');
    const expected = counters.expectedFrames;
    const received = counters.transferredFrames;
    const receivedAll = hasValue(expected) && received === expected;
    const processedAll = hasValue(received) && processed === received;
    const fullyDecodedAll = hasValue(received) && fullyDecoded === received;

    return [
        hasValue(received) && hasValue(expected) ? card(
            'AFS 프레임 전달',
            `${displayNumber(received)} / ${displayNumber(expected)}`,
            '앞 숫자는 기존 Agent 연결로 전달된 프레임, 뒤 숫자는 Sender가 준비한 전체 프레임입니다.',
            status(receivedAll, '모두 수신'),
            'frame',
        ) : null,
        hasValue(processed) && hasValue(received) ? card(
            'AFS Decoder 처리',
            `${displayNumber(processed)} / ${displayNumber(received)}`,
            'AFS Decoder 호출이 예외 없이 끝난 프레임 수입니다. CRC 통과나 완전 복구 성공을 뜻하지 않으며, 바로 다음 완전 복호 수치를 함께 확인해야 합니다.',
            status(processedAll, '모두 처리'),
            'frame',
        ) : null,
        hasValue(fullyDecoded) && hasValue(received) ? card(
            'CRC까지 통과한 완전 복호',
            `${displayNumber(fullyDecoded)} / ${displayNumber(received)}`,
            'Decoder 처리를 마친 뒤 SB2·SB3·SB4 CRC가 모두 정상인 프레임 수입니다. 이 값이 실제 프레임 완전 복호 성공 수입니다.',
            status(fullyDecodedAll, '모두 완전 복호', '일부 복호 실패'),
            'frame',
        ) : null,
    ].filter(Boolean);
}

function networkCards(result, context = {}) {
    const counters = result?.counters || {};
    const testType = context.testType || counters.testType;
    return [
        card(
            '시험에서 주입한 오류',
            counters.injectedBitCount,
            'Sender가 시험 조건에 따라 AFS 프레임 내부에 의도적으로 반전한 비트의 총합입니다.',
            counters.injectedBitCount > 0
                ? { tone: 'warning', text: '시험 조건' }
                : status(true, '주입 없음'),
            'bit',
        ),
        hasValue(counters.syncRejectedFrames)
            && (testType === 'TEST_D_SYNC_RECOVERY' || counters.syncRejectedFrames > 0) ? card(
            '동기 손상으로 제외',
            counters.syncRejectedFrames,
            'Test D에서 손상된 동기 패턴 때문에 현재 AFS 프레임을 정상 프레임으로 인식하지 않고 제외한 수입니다.',
            counters.syncRejectedFrames > 0
                ? { tone: 'warning', text: '의도적 제외' }
                : status(true, '제외 없음'),
            'frame',
        ) : null,
        card(
            '생성한 AFS 프레임',
            counters.expectedFrames,
            'Sender가 원본 GRAW에서 생성했거나 Receiver가 받을 것으로 확인한 전체 AFS 프레임 수입니다.',
            status(undefined), 'frame',
        ),
        card(
            '연결로 전달한 AFS 프레임',
            counters.transferredFrames,
            '기존 Agent 관리 연결을 통해 전달을 완료한 AFS 프레임 수입니다.',
            status(counters.transferredFrames === counters.expectedFrames, '모두 전달'), 'frame',
        ),
        card(
            'Receiver 처리 프레임',
            counters.processedFrames,
            'Receiver가 AFS 검증 대상으로 처리한 프레임 수입니다.',
            status(undefined), 'frame',
        ),
        card(
            'AFS 복호화 실패',
            counters.decodeFailedFrames,
            'Receiver가 처리한 AFS 프레임 중 Decoder 예외 또는 SB2·SB3·SB4 중 하나 이상의 CRC 실패가 발생한 수입니다. 프레임별 실패 블록은 아래 6,000비트 비교에서 확인할 수 있습니다.',
            status(counters.decodeFailedFrames === 0, '없음'),
            'frame',
        ),
        card(
            result?.role === 'SENDER' ? 'Sender 원본 GRAW' : 'Receiver 복원 GRAW',
            counters.rawBytes,
            result?.role === 'SENDER'
                ? 'Sender가 시험 입력으로 사용한 원본 GRAW 데이터 크기입니다.'
                : 'Receiver가 정상 프레임에서 재조립한 GRAW 데이터 크기입니다.',
            status(undefined),
            'byte',
        ),
    ].filter(Boolean);
}

/** 시험 종류에 맞는 전송/오류 그룹 설명만 노출해 관련 없는 계층을 먼저 떠올리지 않게 한다. */
function transmissionGroupDescription(testType) {
    if (['TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS', 'TEST_D_SYNC_RECOVERY'].includes(testType)) {
        return 'AFS 주입 비트와 프레임 전달·복호화 결과를 단위별로 구분합니다.';
    }
    return 'AFS 프레임 전달과 복호화 결과를 단위별로 구분합니다.';
}

function diagnosticCards(result) {
    const decoded = metricValue(result, 'DecodedFrames');
    const sb2 = metricValue(result, 'Sb2CrcValidFrames');
    const sb3 = metricValue(result, 'Sb3CrcValidFrames');
    const sb4 = metricValue(result, 'Sb4CrcValidFrames');
    const decisionChanges = metricValue(result, 'CorrectedSymbols');
    const recoveredSync = metricValue(result, 'RecoveredSyncFrames');

    return [
        hasValue(sb2) && hasValue(decoded)
            ? card('SB2 CRC 통과', `${displayNumber(sb2)} / ${displayNumber(decoded)}`,
                '복호화한 프레임 중 SB2 블록의 CRC가 정상인 수입니다.', status(sb2 === decoded), 'frame')
            : null,
        hasValue(sb3) && hasValue(decoded)
            ? card('SB3 CRC 통과', `${displayNumber(sb3)} / ${displayNumber(decoded)}`,
                '복호화한 프레임 중 실제 GRAW 조각을 담는 SB3 블록의 CRC가 정상인 수입니다.', status(sb3 === decoded), 'frame')
            : null,
        hasValue(sb4) && hasValue(decoded)
            ? card('SB4 CRC 통과', `${displayNumber(sb4)} / ${displayNumber(decoded)}`,
                '복호화한 프레임 중 실제 GRAW 조각을 담는 SB4 블록의 CRC가 정상인 수입니다.', status(sb4 === decoded), 'frame')
            : null,
        hasValue(decisionChanges) ? card(
            'LDPC 내부 판정 변경량',
            decisionChanges,
            'LDPC 디코더 내부에서 초기 판정과 달라진 누적 비트 수입니다. 천공·소거 처리도 포함될 수 있어 실제 주입 오류 개수로 해석하면 안 됩니다.',
            { tone: 'warning', text: '참고값' },
            'bit',
        ) : null,
        hasValue(recoveredSync)
            ? card('동기 복구 프레임', recoveredSync,
                'Test D에서 손상된 동기 패턴 이후 다시 찾아 복호화한 프레임 수입니다.', status(undefined), 'frame')
            : null,
    ].filter(Boolean);
}

function createMetricCard(metric) {
    const box = document.createElement('article');
    const heading = document.createElement('div');
    const name = document.createElement('span');
    const badge = document.createElement('em');
    const value = document.createElement('strong');

    box.className = `metric metric-${metric.status.tone}`;
    box.dataset.tooltip = metric.description;
    box.title = metric.description;
    box.tabIndex = 0;
    heading.className = 'metric-heading';
    name.textContent = metric.name;
    badge.className = `metric-status ${metric.status.tone}`;
    badge.textContent = metric.status.text;
    value.textContent = metric.value;
    heading.append(name, badge);
    box.append(heading, value);
    return box;
}

function createGroup(title, description, metrics) {
    const section = document.createElement('section');
    const heading = document.createElement('div');
    const name = document.createElement('h3');
    const help = document.createElement('p');
    const grid = document.createElement('div');

    section.className = 'result-group';
    heading.className = 'result-group-heading';
    name.textContent = title;
    help.textContent = description;
    grid.className = 'result-metric-grid';
    metrics.forEach((metric) => grid.append(createMetricCard(metric)));
    heading.append(name, help);
    section.append(heading, grid);
    return section;
}

function createDiagnostics(metrics) {
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    const description = document.createElement('p');
    const grid = document.createElement('div');

    details.className = 'result-group result-diagnostics';
    summary.textContent = '전문 진단 지표 보기';
    description.textContent = 'CRC와 LDPC 내부 값입니다. 일반 사용자는 위의 핵심 판정과 무결성 결과를 먼저 확인하세요.';
    grid.className = 'result-metric-grid';
    metrics.forEach((metric) => grid.append(createMetricCard(metric)));
    details.append(summary, description, grid);
    return details;
}

/** 결과 UI의 요약과 그룹 구성을 DOM과 분리해 회귀 테스트할 수 있게 제공한다. */
export function buildResultPresentation(result, context = {}) {
    const testType = context.testType || result?.counters?.testType;
    const syncRecovery = testType === 'TEST_D_SYNC_RECOVERY';

    return {
        summary: resultSummary(result, context),
        groups: syncRecovery ? [
            {
                title: '1. 손상 프레임 제외 후 재동기 판정',
                description: '손상 프레임 자체의 복구 시험이 아닙니다. 68심볼 SP가 손상된 프레임을 제외하고, 다음 연속 정상 SP를 다시 확인한 뒤 SB2·SB3·SB4 CRC까지 모두 통과했는지 판정합니다.',
                metrics: syncRecoveryCards(result, context),
            },
            {
                title: '2. GRAW 부분 복원 범위',
                description: '동기 손상 프레임을 의도적으로 버린 결과입니다. 이 불일치는 Test D의 실패 사유가 아닙니다.',
                metrics: integrityCards(result, { expectedPartial: true }),
            },
            {
                title: '3. 전송 및 오류 처리 현황',
                description: transmissionGroupDescription(testType),
                metrics: networkCards(result, context),
            },
        ] : [
            {
                title: '1. 원본 복원 결과',
                description: 'PASS 판정에서 가장 중요한 원본과 복원 GRAW 비교 결과입니다.',
                metrics: integrityCards(result),
            },
            {
                title: '2. 프레임 처리 결과',
                description: '보내기로 한 논리 프레임을 빠짐없이 받고 복호화했는지 확인합니다.',
                metrics: frameCards(result),
            },
            {
                title: '3. 전송 및 오류 처리 현황',
                description: transmissionGroupDescription(testType),
                metrics: networkCards(result, context),
            },
        ],
        diagnostics: diagnosticCards(result),
    };
}

/** 최종 결과를 해석 요약과 목적별 지표 그룹으로 나눠 일반 사용자가 읽기 쉽게 표시한다. */
export function renderMetrics(target, result, context = {}) {
    const presentation = buildResultPresentation(result, context);
    const summary = presentation.summary;
    const overview = document.createElement('section');
    const kicker = document.createElement('span');
    const title = document.createElement('strong');
    const description = document.createElement('p');
    const checks = document.createElement('div');

    target.replaceChildren();
    target.className = 'result-details';
    overview.className = `result-overview ${summary.passed ? 'pass' : 'fail'}`;
    kicker.className = 'result-overview-kicker';
    kicker.textContent = '시험 결과 해석';
    title.textContent = summary.title;
    description.textContent = summary.description;
    checks.className = 'result-checks';
    summary.checks.forEach((check) => {
        const item = document.createElement('span');
        item.className = check.ok ? 'pass' : 'fail';
        item.textContent = `${check.ok ? '✓' : '!'} ${check.label}`;
        checks.append(item);
    });
    overview.append(kicker, title, description, checks);
    target.append(overview);
    presentation.groups.forEach((group) => {
        target.append(createGroup(group.title, group.description, group.metrics));
    });
    target.append(createDiagnostics(presentation.diagnostics));
}
