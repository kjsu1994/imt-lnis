import {
    agents,
    statusSocket,
    log,
    setPill,
    downloads,
    renderMetrics,
} from '../common/api.js?v=20260922-logging';
import {
    formatEventLog,
    describeTestType,
    describeTestCondition,
} from './event-log.js?v=20260915-structure';
import { renderFrameEvidence } from './frame-evidence.js?v=20260915-structure';

const $ = (id) => document.getElementById(id);
const eventLog = $('event-log');

const TERMINAL_SESSION_STATES = new Set([
    'COMPLETED',
    'CANCELLED',
    'FAILED',
    'INCONCLUSIVE',
]);

let activeSession = null;
let resultContext = {};

/** 최종 SESSION_STATUS의 기본값 0이 앞서 받은 실제 시험 조건을 덮어쓰지 않게 병합한다. */
function mergeResultContext(details) {
    const merged = { ...resultContext };
    for (const [key, value] of Object.entries(details)) {
        const positiveCondition = [
            'errorCount',
            'errorSeed',
            'syncDamageInterval',
            'injectedFrameCount',
        ].includes(key);
        if (!positiveCondition || Number(value) > 0 || merged[key] === undefined) {
            merged[key] = value;
        }
    }
    resultContext = merged;
}

/** 시험별 핵심 조건이 실제로 포함된 이벤트인지 판별한다. */
function hasUsableTestCondition(details) {
    if (details.testType === 'TEST_A_NORMAL') {
        return true;
    }
    if (['TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS'].includes(details.testType)) {
        return Number(details.errorCount) > 0;
    }
    if (details.testType === 'TEST_D_SYNC_RECOVERY') {
        return Number(details.errorCount) > 0
            && Number(details.syncDamageInterval) > 0
            && Number(details.injectedFrameCount) > 0;
    }
    return false;
}

/** 서버가 보내는 진행률을 0~100 범위로 보정하여 화면에 표시한다. */
function updateProgress(value) {
    const numericValue = Number(value);
    if (!Number.isFinite(numericValue)) {
        return;
    }

    const percent = Math.min(100, Math.max(0, Math.round(numericValue)));
    $('session-progress').value = percent;
    $('progress-label').textContent = `${percent}%`;
}

/** Receiver Agent의 연결 상태를 주기적으로 확인하여 상단 상태표시에 반영한다. */
async function refresh() {
    const receiver = (await agents()).find((agent) => agent.role === 'RECEIVER');
    setPill(
        $('receiver-status'),
        receiver ? `Receiver ${receiver.state}` : 'Receiver OFFLINE',
        receiver?.state === 'READY' ? 'online' : '',
    );
}

/** 세션 종료 원인을 일반 사용자가 바로 이해할 수 있는 상태 문구로 표시한다. */
function applyTerminalState(state) {
    if (state === 'COMPLETED') {
        updateProgress(100);
        setPill($('listen-status'), '수신 및 검증 완료', 'online');
        return;
    }

    if (state === 'CANCELLED') {
        setPill($('listen-status'), '시험 취소됨', 'warning');
        return;
    }

    setPill($('listen-status'), '시험 확인 필요', 'error');
}

statusSocket(
    (event) => {
        // Receiver 화면은 Receiver 이벤트와 전체 세션 상태만 표시한다.
        if (event.role && event.role !== 'RECEIVER' && event.type !== 'SESSION_STATUS') {
            return;
        }

        if (event.sessionId && activeSession && event.sessionId !== activeSession) {
            // 새 세션의 조건에 이전 시험의 오류 수가 섞이지 않도록 문맥을 초기화한다.
            resultContext = {};
        }
        if (event.sessionId) {
            activeSession = event.sessionId;
        }

        const payload = event.payload || {};
        log(eventLog, formatEventLog(event), {serverEvent: true});

        const progressDetails = payload.counters || payload;
        // Agent Progress는 세부 조건을 counters 아래에, 세션 이벤트는 최상위에 담으므로 둘을 정규화한다.
        mergeResultContext(progressDetails);
        if (progressDetails.testType) {
            $('current-test').textContent = describeTestType(progressDetails.testType);
            if (hasUsableTestCondition(progressDetails)) {
                $('test-conditions').textContent = describeTestCondition(progressDetails);
            }
        }
        if (progressDetails.testConditions) {
            $('test-conditions').textContent = progressDetails.testConditions;
        }

        // Agent 진행 이벤트는 percent, 세션 상태 이벤트는 progress 필드를 사용한다.
        // 두 형식을 모두 처리해야 수신 80% 이후의 최종 100%가 화면에 반영된다.
        updateProgress(payload.progress ?? payload.percent);

        if (event.type === 'RX_STATUS') {
            setPill($('listen-status'), payload.stage || '수신 중', 'online');
        }

        if (event.type === 'SESSION_STATUS' && TERMINAL_SESSION_STATES.has(payload.state)) {
            applyTerminalState(payload.state);
        }

        if (event.type === 'RESULT') {
            renderMetrics($('metrics'), payload, resultContext);
            $('receiver-verdict').textContent = payload.verdict || '판정 확인 필요';

            // Receiver RESULT는 수신과 무결성 검증이 끝났다는 의미이므로 즉시 100%로 마무리한다.
            // 이후 도착하는 SESSION_STATUS도 progress 필드로 같은 완료 상태를 재확인한다.
            updateProgress(100);
            setPill($('listen-status'), '수신 및 검증 완료', 'online');

            if (event.sessionId) {
                downloads($('downloads'), event.sessionId, 'rx');
                renderFrameEvidence(
                    $('frame-evidence'),
                    event.sessionId,
                    payload.verdict,
                );
            }
        }
    },
    (online) => setPill(
        $('server-status'),
        online ? '서버 연결됨' : '서버 재연결 중',
        online ? 'online' : 'warning',
    ),
);

$('clear-log').onclick = () => {
    eventLog.textContent = '';
};

refresh().catch((error) => log(eventLog, error.message));
// WebSocket 이벤트가 없을 때도 Agent heartbeat가 갱신한 H2 상태를 주기적으로 다시 읽는다.
setInterval(() => refresh().catch(() => {}), 5000);
