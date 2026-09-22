import { request } from '../common/api.js?v=20260922-logging';

const FRAME_BITS = 6000;
const COLUMNS = 100;
const ROWS = 60;
const CELL_SIZE = 5;

/** Base64로 받은 750 byte AFS 프레임을 브라우저 byte 배열로 변환한다. */
function decodeFrame(value) {
    if (!value) return null;
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function bitAt(bytes, position) {
    if (!bytes || position < 0 || position >= FRAME_BITS) return null;
    return (bytes[position >> 3] >> (7 - (position & 7))) & 1;
}

function number(value) {
    return value === null || value === undefined
        ? '비교 자료 없음'
        : `${Number(value).toLocaleString('ko-KR')} bit`;
}

function shortHash(value) {
    return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '없음';
}

/** 한 단계의 6,000개 비트를 100×60 점자형 지도에 그린다. */
function drawMap(canvas, bytes, differences, selectedPosition = null) {
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#edf2f4';
    context.fillRect(0, 0, canvas.width, canvas.height);
    if (!bytes) {
        context.fillStyle = '#607780';
        context.font = 'bold 16px sans-serif';
        context.fillText('이 단계의 프레임 자료가 없습니다.', 24, 42);
        return;
    }
    for (let position = 0; position < FRAME_BITS; position += 1) {
        const column = position % COLUMNS;
        const row = Math.floor(position / COLUMNS);
        context.fillStyle = differences.has(position)
            ? '#e05252'
            : bitAt(bytes, position) === 1 ? '#183d4c' : '#dbe5e8';
        context.fillRect(
            column * CELL_SIZE,
            row * CELL_SIZE,
            CELL_SIZE - 1,
            CELL_SIZE - 1,
        );
    }
    if (selectedPosition !== null) {
        const column = selectedPosition % COLUMNS;
        const row = Math.floor(selectedPosition / COLUMNS);
        context.strokeStyle = '#f4b400';
        context.lineWidth = 2;
        context.strokeRect(
            column * CELL_SIZE,
            row * CELL_SIZE,
            CELL_SIZE,
            CELL_SIZE,
        );
    }
}

function stageCard(stage) {
    const card = document.createElement('article');
    const heading = document.createElement('div');
    const title = document.createElement('strong');
    const badge = document.createElement('span');
    const description = document.createElement('p');
    const viewport = document.createElement('div');
    const canvas = document.createElement('canvas');
    const hover = document.createElement('p');

    card.className = 'frame-map-card';
    title.textContent = stage.title;
    const defaultSame = stage.differences.size === 0;
    badge.className = `frame-map-badge ${stage.statusTone || (defaultSame ? 'same' : 'different')}`;
    badge.textContent = stage.statusText || (stage.bytes
        ? (defaultSame
            ? '비교 기준과 동일'
            : `${stage.differences.size.toLocaleString('ko-KR')} bit 차이`)
        : '자료 없음');
    description.textContent = stage.description;
    canvas.width = COLUMNS * CELL_SIZE;
    canvas.height = ROWS * CELL_SIZE;
    canvas.setAttribute('role', 'img');
    canvas.setAttribute(
        'aria-label',
        `${stage.title}, 100열 60행 AFS 6,000비트 지도, ${badge.textContent}`,
    );
    canvas.tabIndex = 0;
    hover.className = 'frame-hover-info';
    hover.textContent = `SHA-256 ${shortHash(stage.hash)} · ${stage.comparison}`;
    heading.className = 'frame-map-heading';
    heading.append(title, badge);
    viewport.className = 'frame-map-viewport';
    viewport.append(canvas);
    card.append(heading, description, viewport, hover);
    return { card, canvas, hover, ...stage };
}

function comparisonCards(summary) {
    const grid = document.createElement('div');
    grid.className = 'frame-comparison-summary';
    const values = [
        ['오류 주입 결과', number(summary.referenceToTransmittedDifferences),
            '기준 프레임과 실제 송신 프레임의 차이입니다. Test B/C/D에서는 설정한 오류가 이 단계에 나타납니다.'],
        ['전송 중 추가 변화', number(summary.transmittedToReceivedDifferences),
            '실제 송신 프레임과 Receiver가 채택한 프레임의 차이입니다. 0이면 전달된 AFS 원문이 같습니다.'],
        ['최종 복구 차이', number(summary.referenceToReencodedDifferences),
            '기준 프레임과 복호화 후 재인코딩 검증 프레임의 차이입니다. 0이면 6,000비트 기준으로 복구됐습니다.'],
    ];
    for (const [title, value, description] of values) {
        const item = document.createElement('article');
        const name = document.createElement('span');
        const result = document.createElement('strong');
        const help = document.createElement('p');
        name.textContent = title;
        result.textContent = value;
        help.textContent = description;
        item.title = description;
        item.append(name, result, help);
        grid.append(item);
    }
    return grid;
}

function crcState(valid) {
    return valid
        ? { text: '정상', tone: 'pass' }
        : { text: '실패', tone: 'fail' };
}

/** 프레임 하나의 Decoder 처리와 블록별 CRC 결과를 성공 여부와 분리해 표시한다. */
function decodeDiagnostics(summary) {
    const section = document.createElement('section');
    const heading = document.createElement('div');
    const title = document.createElement('h4');
    const description = document.createElement('p');
    const grid = document.createElement('div');
    title.textContent = '프레임별 복호 진단';
    description.textContent = 'Decoder 처리 완료만으로 복구 성공으로 판정하지 않습니다. SB2·SB3·SB4 CRC가 모두 정상이어야 완전 복호입니다.';
    heading.className = 'frame-diagnostic-heading';
    heading.append(title, description);
    grid.className = 'frame-diagnostic-grid';

    const decoder = summary.decoderCompleted
        ? { text: '처리 완료', tone: 'info' }
        : { text: '처리 실패', tone: 'fail' };
    const complete = summary.decodeSucceeded
        ? { text: '완전 복호', tone: 'pass' }
        : summary.intentionalSyncRejection
            ? { text: '의도적 제외', tone: 'warning' }
            : { text: '복호 실패', tone: 'fail' };
    const items = [
        ['Decoder', decoder, summary.failureReason || 'Native AFS Decoder 호출 상태입니다.'],
        ['SB2 CRC', crcState(summary.sb2CrcValid), `LDPC 내부 판정 변경량 ${Number(summary.sb2DecisionChanges || 0).toLocaleString('ko-KR')} bit`],
        ['SB3 CRC', crcState(summary.sb3CrcValid), `LDPC 내부 판정 변경량 ${Number(summary.sb3DecisionChanges || 0).toLocaleString('ko-KR')} bit`],
        ['SB4 CRC', crcState(summary.sb4CrcValid), `LDPC 내부 판정 변경량 ${Number(summary.sb4DecisionChanges || 0).toLocaleString('ko-KR')} bit`],
        ['최종 상태', complete, summary.decodeSucceeded
            ? '세 블록 CRC를 모두 통과했습니다.'
            : summary.failureReason || '완전 복호 조건을 충족하지 못했습니다.'],
        ['GRAW 재조립', summary.usedForGrawReassembly
            ? { text: '사용됨', tone: 'pass' }
            : { text: '제외됨', tone: 'warning' },
        summary.usedForGrawReassembly
            ? 'GRAW 데이터가 위치한 SB3·SB4 CRC가 정상이라 재조립에 사용했습니다.'
            : 'SB3 또는 SB4 CRC 실패로 GRAW 재조립에 사용하지 않았습니다.'],
    ];
    for (const [name, state, help] of items) {
        const item = document.createElement('article');
        const label = document.createElement('span');
        const value = document.createElement('strong');
        const explanation = document.createElement('p');
        item.className = `frame-diagnostic-item ${state.tone}`;
        label.textContent = name;
        value.textContent = state.text;
        explanation.textContent = help;
        item.title = help;
        item.append(label, value, explanation);
        grid.append(item);
    }
    section.append(heading, grid);
    return section;
}

async function renderSelectedFrame(container, sessionId, frameIndex) {
    const detail = await request(`/sessions/${sessionId}/frame-evidence/${frameIndex}`);
    const summary = detail.summary;
    const stages = [
        {
            title: '1. 기준 AFSFrame',
            description: 'GRAW를 정상 인코딩한 비교 기준입니다. 아직 시험 오류를 넣지 않은 6,000비트입니다.',
            bytes: decodeFrame(detail.referenceFrame),
            hash: summary.referenceSha256,
            differences: new Set(),
            comparison: '이 지도가 이후 단계의 기준입니다.',
        },
        {
            title: '2. 실제 송신 AFSFrame',
            description: '시험 오류를 주입한 뒤 Sender가 전달한 실제 6,000비트입니다. 빨강은 기준과 달라진 위치입니다.',
            bytes: decodeFrame(detail.transmittedFrame),
            hash: summary.transmittedSha256,
            differences: new Set(detail.referenceToTransmittedPositions || []),
            comparison: `기준 대비 ${number(summary.referenceToTransmittedDifferences)}`,
        },
        {
            title: '3. Receiver 수신 AFSFrame',
            description: '전송 구조 검사를 통과해 Receiver가 채택한 실제 6,000비트입니다. 빨강은 송신 프레임과 달라진 위치입니다.',
            bytes: decodeFrame(detail.receivedFrame),
            hash: summary.receivedSha256,
            differences: new Set(detail.transmittedToReceivedPositions || []),
            comparison: `송신 대비 ${number(summary.transmittedToReceivedDifferences)}`,
        },
        {
            title: '4. 복호화 후 재인코딩 검증',
            description: 'Receiver가 복호화한 SB2/SB3/SB4를 같은 TOI로 다시 인코딩한 검증 프레임입니다. 빨강은 기준과 아직 다른 위치입니다.',
            bytes: decodeFrame(detail.reencodedFrame),
            hash: summary.reencodedSha256,
            differences: new Set(detail.referenceToReencodedPositions || []),
            comparison: `기준 대비 ${number(summary.referenceToReencodedDifferences)}`,
            statusText: summary.decodeSucceeded
                ? 'CRC 통과 · 완전 복호'
                : summary.intentionalSyncRejection
                    ? '의도적 동기 제외'
                    : summary.decoderCompleted ? 'CRC 실패 · 진단용' : 'Decoder 처리 실패',
            statusTone: summary.decodeSucceeded
                ? 'same'
                : summary.intentionalSyncRejection ? 'warning' : 'failed',
        },
    ];

    const content = container.querySelector('.frame-evidence-content');
    content.replaceChildren();
    const interpretation = document.createElement('div');
    interpretation.className = `frame-interpretation ${summary.decodeSucceeded ? 'pass' : summary.intentionalSyncRejection ? 'warning' : 'fail'}`;
    const label = document.createElement('strong');
    const interpretationText = document.createElement('p');
    label.textContent = '이 프레임의 해석';
    interpretationText.textContent = summary.interpretation;
    interpretation.append(label, interpretationText);
    content.append(
        interpretation,
        comparisonCards(summary),
        decodeDiagnostics(summary),
    );

    const maps = document.createElement('div');
    maps.className = 'frame-map-grid';
    const rendered = stages.map(stageCard);
    rendered.forEach((item) => maps.append(item.card));
    content.append(maps);

    const redraw = (selectedPosition = null) => {
        rendered.forEach((item) => drawMap(
            item.canvas,
            item.bytes,
            item.differences,
            selectedPosition,
        ));
    };
    redraw();

    rendered.forEach((item) => {
        item.canvas.addEventListener('mousemove', (event) => {
            const bounds = item.canvas.getBoundingClientRect();
            const column = Math.floor((event.clientX - bounds.left) * COLUMNS / bounds.width);
            const row = Math.floor((event.clientY - bounds.top) * ROWS / bounds.height);
            const position = Math.min(FRAME_BITS - 1, row * COLUMNS + column);
            redraw(position);
            const values = stages.map((stage, index) => `${index + 1}단계=${bitAt(stage.bytes, position) ?? '-'}`);
            const message = `심볼 ${position.toLocaleString('ko-KR')} · ${values.join(' · ')}`;
            rendered.forEach((target) => {
                target.canvas.title = message;
                target.hover.textContent = message;
            });
        });
        item.canvas.addEventListener('mouseleave', () => {
            redraw();
            rendered.forEach((target) => {
                target.hover.textContent = `SHA-256 ${shortHash(target.hash)} · ${target.comparison}`;
            });
        });
    });
}

/** 세션 결과 아래에 프레임 선택기, 비교 요약, 네 장의 6,000비트 지도를 표시한다. */
/** SB CRC와 재인코딩 비교를 모두 통과했는지 동일한 기준으로 판단한다. */
function isFullyRecovered(summary) {
    return summary.decodeSucceeded
        && summary.referenceToReencodedDifferences === 0;
}

/** FAIL 시험은 첫 실패 프레임을, 그 외 시험은 첫 보관 프레임을 기본 선택한다. */
export function selectInitialFrameIndex(summaries, sessionVerdict) {
    const fallback = summaries[0]?.frameIndex ?? 0;
    if (String(sessionVerdict || '').toUpperCase() !== 'FAIL') {
        return fallback;
    }
    return summaries.find((summary) => !isFullyRecovered(summary))?.frameIndex
        ?? fallback;
}

/** 내부 0-base index를 숨기고 사용자가 읽는 1-base 프레임 번호만 표시한다. */
export function frameOptionLabel(summary) {
    const state = summary.decodeSucceeded
        && summary.referenceToReencodedDifferences === 0
        ? '완전 복구'
        : summary.intentionalSyncRejection
            ? '의도적 동기 제외'
            : summary.decoderCompleted ? 'CRC 실패' : 'Decoder 실패';
    return `${summary.frameIndex + 1}번 프레임 · ${state}`;
}

/** 전체 시험과 현재 선택한 단일 프레임의 판정 범위를 나란히 보여준다. */
function updateScopeSummary(
    container,
    summaries,
    selectedFrameIndex,
    sessionVerdict,
) {
    const scope = container.querySelector('.frame-scope-summary');
    const selected = summaries.find(
        (summary) => summary.frameIndex === selectedFrameIndex,
    );
    if (!scope || !selected) return;

    const recoveredCount = summaries.filter(isFullyRecovered).length;
    const overallVerdict = String(sessionVerdict || '').toUpperCase();
    const selectedRecovered = isFullyRecovered(selected);
    const overallFailed = overallVerdict === 'FAIL';

    scope.replaceChildren();
    const overall = document.createElement('article');
    const current = document.createElement('article');
    overall.className = `frame-scope-card ${overallFailed ? 'fail' : 'pass'}`;
    current.className = `frame-scope-card ${selectedRecovered ? 'pass' : 'fail'}`;

    const overallLabel = document.createElement('span');
    const overallValue = document.createElement('strong');
    const overallHelp = document.createElement('p');
    overallLabel.textContent = '전체 시험 판정';
    overallValue.textContent = overallVerdict || '프레임 결과 확인';
    overallHelp.textContent = `보관된 ${summaries.length}개 프레임 중 ${recoveredCount}개가 CRC와 6,000비트 재인코딩 비교를 모두 통과했습니다.`;
    overall.append(overallLabel, overallValue, overallHelp);

    const currentLabel = document.createElement('span');
    const currentValue = document.createElement('strong');
    const currentHelp = document.createElement('p');
    currentLabel.textContent = '현재 선택 프레임 판정';
    currentValue.textContent = `${selected.frameIndex + 1}번 · ${selectedRecovered ? '완전 복구' : '복구 실패'}`;
    currentHelp.textContent = overallFailed && selectedRecovered
        ? '전체 시험은 FAIL이지만, 현재 선택한 이 프레임 하나는 정상 복구됐습니다. 다른 실패 프레임도 선택해 원인을 확인하세요.'
        : selectedRecovered
            ? '현재 선택한 프레임은 기준 AFSFrame과 재인코딩 검증 결과가 같습니다.'
            : `현재 선택한 프레임의 실패 원인: ${selected.failureReason || 'CRC 또는 재인코딩 비교 실패'}`;
    current.append(currentLabel, currentValue, currentHelp);

    scope.append(overall, current);
}

export async function renderFrameEvidence(
    container,
    sessionId,
    sessionVerdict = null,
) {
    container.classList.remove('hidden');
    container.innerHTML = `
        <div class="frame-evidence-heading">
            <div>
                <span class="eyebrow">AFS FRAME EVIDENCE</span>
                <h3>6,000비트 프레임 직접 비교</h3>
                <p>각 점은 AFS 심볼 1개입니다. 빨간 점은 해당 단계의 비교 기준과 다른 심볼입니다.</p>
            </div>
            <label title="보관된 AFS 프레임 중 점자형 지도로 비교할 프레임을 선택합니다.">확인할 프레임 <select class="frame-selector" aria-label="확인할 AFS 프레임" title="확인할 AFS 프레임을 선택합니다."></select></label>
        </div>
        <p class="frame-retention-note">최대 500프레임의 상세 원문을 H2에 24시간 보관합니다. 500프레임을 넘으면 처음 250개와 마지막 250개를 보관합니다.</p>
        <aside class="frame-comparison-guide" aria-label="프레임 단계 비교 기준">
            <span>Test A <code>1=2=3=4</code></span>
            <span>Test B/C <code>1≠2, 2=3, 복구 성공 시 1=4</code></span>
            <span>Test D <code>1≠2</code> 후 다음 프레임 재동기화 확인</span>
            <span>네트워크 이상 <code>2≠3</code></span>
            <span>복호화 실패 <code>1≠4</code> 또는 SB2·SB3·SB4 CRC 실패</span>
        </aside>
        <div class="frame-scope-summary" aria-live="polite"></div>
        <div class="frame-evidence-content" aria-live="polite">프레임 증거를 불러오는 중입니다.</div>`;
    try {
        let summaries = [];
        // RESULT와 별도 WebSocket 메시지인 프레임 증거 저장이 수백 ms 늦을 수 있어 짧게 재조회한다.
        for (let attempt = 0; attempt < 20; attempt += 1) {
            summaries = await request(`/sessions/${sessionId}/frame-evidence`);
            const rolesMerged = summaries.length > 0
                && summaries.every((item) => item.senderEvidenceAvailable
                    && item.receiverEvidenceAvailable);
            if (rolesMerged) break;
            await new Promise((resolve) => setTimeout(resolve, 250));
        }
        if (summaries.length === 0) {
            container.querySelector('.frame-evidence-content').textContent =
                '아직 보관된 프레임 증거가 없습니다. 최신 Agent가 실행 중인지 확인하세요.';
            return;
        }
        const selector = container.querySelector('.frame-selector');
        summaries.forEach((summary) => {
            selector.add(new Option(
                frameOptionLabel(summary),
                summary.frameIndex,
            ));
        });
        // 전체 시험이 FAIL이면 사용자가 원인을 바로 볼 수 있도록 첫 실패 프레임을 기본 선택한다.
        selector.value = String(selectInitialFrameIndex(
            summaries,
            sessionVerdict,
        ));

        selector.onchange = async () => {
            const selectedFrameIndex = Number(selector.value);
            updateScopeSummary(
                container,
                summaries,
                selectedFrameIndex,
                sessionVerdict,
            );
            await renderSelectedFrame(container, sessionId, selectedFrameIndex);
        };
        const initialFrameIndex = Number(selector.value);
        updateScopeSummary(
            container,
            summaries,
            initialFrameIndex,
            sessionVerdict,
        );
        await renderSelectedFrame(container, sessionId, initialFrameIndex);
    } catch (error) {
        container.querySelector('.frame-evidence-content').textContent =
            `프레임 증거를 불러오지 못했습니다: ${error.message}`;
    }
}
