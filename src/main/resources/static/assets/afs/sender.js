import {
    request,
    agents,
    statusSocket,
    log,
    setPill,
    downloads,
    renderMetrics,
} from '../common/api.js?v=20260922-logging';
import { formatEventLog } from './event-log.js?v=20260915-structure';
import { renderFrameEvidence } from './frame-evidence.js?v=20260915-structure';

const $ = (id) => document.getElementById(id);
const eventLog = $('event-log');

// 다음 값들은 서버/H2 원본이 아니라 현재 페이지의 버튼과 표시를 제어하는 UI 상태다.
let inputId = null;
let captureId = null;
let sessionId = null;
let captureRunning = false;
let sessionRunning = false;
let agentCache = [];
let resultContext = {};

const TERMINAL_SESSION_STATES = new Set([
    'COMPLETED',
    'CANCELLED',
    'FAILED',
    'INCONCLUSIVE',
]);

/**
 * 지정한 선택 상자의 Agent ID를 반환한다.
 *
 * Agent가 연결되지 않은 상태에서는 빈 경로 변수가 URL에 들어가지 않도록
 * API 호출 전에 사용자에게 원인을 설명하는 오류를 발생시킨다.
 */
function requireSelection(selectId, displayName) {
    const value = $(selectId).value.trim();
    if (!value) {
        throw new Error(
            `연결된 ${displayName} Agent가 없습니다. `
            + '해당 PC에서 LNIS Windows Agent를 먼저 실행하세요.',
        );
    }
    return value;
}

/** 선택 가능한 Agent가 없다는 상태를 select 안에서도 명확하게 표시한다. */
function addEmptyOption(select, roleName) {
    const option = new Option(`연결된 ${roleName} Agent 없음`, '');
    option.disabled = true;
    option.selected = true;
    select.add(option);
}

/** 팝업을 사용하지 않고 현재 작업 문맥을 유지한 채 결과를 안내한다. */
function showNotice(level, title, message) {
    const notice = $('page-notice');
    notice.className = `notice ${level}`;
    $('notice-title').textContent = title;
    $('notice-message').textContent = message;
}

function hideNotice() {
    $('page-notice').className = 'notice hidden';
}

/** 서버 내부 표현을 운영자가 바로 조치할 수 있는 안내 문장으로 바꾼다. */
function friendlyError(error) {
    const message = error?.message || '알 수 없는 오류가 발생했습니다.';
    if (/unknown agent|agent not found/i.test(message)) {
        return '선택한 Agent가 서버에 등록되어 있지 않습니다. Agent 실행 및 연결 상태를 확인하세요.';
    }
    if (/not connected|no active|websocket/i.test(message)) {
        return 'Agent WebSocket 연결이 끊어졌습니다. Agent를 다시 실행한 뒤 재시도하세요.';
    }
    if (/portname|must not be blank/i.test(message)) {
        return '수집에 사용할 COM 포트를 선택하세요.';
    }
    if (/another test session is active/i.test(message)) {
        return '이미 진행 중인 시험이 있습니다. 아래 취소 버튼으로 기존 시험을 종료하세요.';
    }
    return message;
}

function reportError(context, error) {
    const message = friendlyError(error);
    showNotice('error', `${context} 실패`, message);
    log(eventLog, `${context} 실패: ${message}`);
}

$('notice-close').onclick = hideNotice;

/**
 * Agent 및 시험 실행 상태에 따라 잘못된 요청을 만들 수 있는 버튼을 잠근다.
 * 포트 새로고침은 Sender Agent가 연결돼야 하고, 수집 시작은 COM 포트까지
 * 선택돼야 한다. 시험 시작은 입력과 양쪽 Agent가 모두 준비돼야 한다.
 */
function updateControls() {
    const captureAgentReady = Boolean($('capture-agent').value);
    const comPortReady = Boolean($('com-port').value);
    const senderReady = Boolean($('sender-agent').value);
    const receiverReady = Boolean($('receiver-agent').value);

    $('refresh-ports').disabled = !captureAgentReady || captureRunning;
    $('capture-start').disabled = !captureAgentReady || !comPortReady || captureRunning;
    $('capture-stop').disabled = !captureRunning;
    $('test-start').disabled = !inputId || !senderReady || !receiverReady || sessionRunning;
    $('test-cancel').disabled = !sessionRunning;
}

/** 활성 세션 유무를 화면 상태와 취소 버튼에 동일하게 반영한다. */
function applyActiveSession(session) {
    if (session && !TERMINAL_SESSION_STATES.has(session.state)) {
        sessionId = session.sessionId;
        sessionRunning = true;
        setPill($('session-state'), `${session.state} · 취소 가능`, 'warning');
    } else {
        sessionId = null;
        sessionRunning = false;
        setPill($('session-state'), '대기', '');
    }
    updateControls();
}

/** 페이지 새로고침 후에도 H2 활성 잠금의 세션 ID를 다시 가져온다. */
async function refreshActiveSession() {
    const active = await request('/sessions/active');
    applyActiveSession(active);
    return active;
}

/** 서버가 보관한 Agent 상태를 다시 읽고 역할별 선택 목록을 갱신한다. */
async function refreshAgents() {
    agentCache = await agents();

    for (const [id, role, roleName] of [
        ['sender-agent', 'SENDER', 'Sender'],
        ['capture-agent', 'SENDER', 'Sender'],
        ['receiver-agent', 'RECEIVER', 'Receiver'],
    ]) {
        const select = $(id);
        const previous = select.value;
        const matchingAgents = agentCache.filter((agent) => agent.role === role);

        select.replaceChildren();
        for (const agent of matchingAgents) {
            select.add(new Option(`${agent.agentId} · ${agent.state}`, agent.agentId));
        }

        if (matchingAgents.length === 0) {
            addEmptyOption(select, roleName);
        } else if ([...select.options].some((option) => option.value === previous)) {
            select.value = previous;
        }
    }

    paintAgents();
    updateControls();
}

/** 상단 상태 표시줄에 역할별 Agent의 현재 연결 상태를 표시한다. */
function paintAgents() {
    const sender = agentCache.find((agent) => agent.role === 'SENDER');
    const receiver = agentCache.find((agent) => agent.role === 'RECEIVER');

    setPill(
        $('sender-status'),
        sender ? `Sender ${sender.state}` : 'Sender OFFLINE',
        sender?.state === 'READY' ? 'online' : '',
    );
    setPill(
        $('receiver-status'),
        receiver ? `Receiver ${receiver.state}` : 'Receiver OFFLINE',
        receiver?.state === 'READY' ? 'online' : '',
    );
}

for (const tab of document.querySelectorAll('.tab')) {
    tab.onclick = () => {
        document.querySelectorAll('.tab').forEach((item) => item.classList.remove('active'));
        tab.classList.add('active');
        $('upload-panel').classList.toggle('hidden', tab.dataset.tab !== 'upload');
        $('capture-panel').classList.toggle('hidden', tab.dataset.tab !== 'capture');
    };
}

// 업로드 흐름: 메타데이터 생성 → 1 MiB 순차 청크 전송 → 서버 전체 검증 및 완료 확정.
$('upload-button').onclick = async () => {
    const file = $('graw-file').files[0];
    if (!file) {
        showNotice('warning', '파일 선택 필요', '업로드할 capture.graw 파일을 선택하세요.');
        return;
    }

    try {
        const input = await request('/inputs', {
            method: 'POST',
            body: JSON.stringify({
                fileName: file.name,
                size: file.size,
                kind: 'GRAW_UPLOAD',
            }),
        });
        inputId = input.inputId;

        const chunkSize = 1024 * 1024;
        for (let offset = 0, index = 0; offset < file.size; offset += chunkSize, index += 1) {
            const bytes = new Uint8Array(
                await file.slice(offset, offset + chunkSize).arrayBuffer(),
            );
            await request(`/inputs/${inputId}/chunks/${index}`, {
                method: 'PUT',
                body: bytes,
            });
            $('upload-progress').value = Math.round(
                (Math.min(file.size, offset + bytes.length) / file.size) * 100,
            );
        }

        const complete = await request(`/inputs/${inputId}/complete`, { method: 'POST' });
        $('input-summary').textContent = `${complete.recordCount.toLocaleString()} records · `
            + `${complete.receivedSize.toLocaleString()} bytes · SHA-256 ${complete.sha256}`;
        log(eventLog, 'GRAW 업로드 및 검증 완료');
        showNotice('success', '업로드 완료', 'GRAW 파일을 검증하고 서버 입력 저장소에 저장했습니다.');
        updateControls();
    } catch (error) {
        reportError('GRAW 업로드', error);
    }
};

$('capture-agent').onchange = () => {
    $('com-port').replaceChildren();
    $('capture-summary').textContent = 'COM 포트 새로고침을 눌러 조회하세요.';
    updateControls();
};

$('com-port').onchange = updateControls;
$('receiver-agent').onchange = () => {
    updateControls();
};

$('refresh-ports').onclick = async () => {
    try {
        const agentId = requireSelection('capture-agent', 'Sender');
        $('refresh-ports').disabled = true;
        await request(`/agents/${encodeURIComponent(agentId)}/serial-ports/refresh`, {
            method: 'POST',
        });
        $('capture-summary').textContent = 'Agent의 COM 포트 응답을 기다리는 중입니다.';
        log(eventLog, `COM 포트 조회 명령 전송: ${agentId}`);
        showNotice('info', 'COM 포트 조회 중', `${agentId} Agent의 응답을 기다리고 있습니다.`);
    } catch (error) {
        reportError('COM 포트 조회', error);
    } finally {
        updateControls();
    }
};

$('capture-start').onclick = async () => {
    try {
        const senderAgentId = requireSelection('capture-agent', 'Sender');
        const portName = $('com-port').value;
        if (!portName) {
            throw new Error('수집할 COM 포트를 먼저 선택하세요.');
        }

        const result = await request('/captures', {
            method: 'POST',
            body: JSON.stringify({
                senderAgentId,
                portName,
                baudRate: Number($('baud').value),
                protocolId: $('protocol').value,
                sessionName: 'web-capture',
                receiverModel: 'u-blox',
                firmwareVersion: 'auto',
                dtrEnabled: false,
                rtsEnabled: false,
            }),
        });
        captureId = result.inputId;
        inputId = result.inputId;
        captureRunning = true;
        $('capture-summary').textContent = `${portName} 수집 중`;
        log(eventLog, `GNSS 수집 시작: ${senderAgentId} / ${portName}`);
        showNotice('success', 'GNSS 수집 시작', `${senderAgentId}의 ${portName} 포트를 수집 중입니다.`);
        updateControls();
    } catch (error) {
        reportError('GNSS 수집 시작', error);
    }
};

// stop 명령 뒤 마지막 Agent 청크가 WebSocket으로 도착할 시간을 준 다음 서버 검증을 완료한다.
$('capture-stop').onclick = async () => {
    try {
        if (!captureId) {
            throw new Error('진행 중인 GNSS 수집이 없습니다.');
        }
        const senderAgentId = requireSelection('capture-agent', 'Sender');
        await request(
            `/captures/${captureId}/stop?senderAgentId=${encodeURIComponent(senderAgentId)}`,
            { method: 'POST' },
        );
        await new Promise((resolve) => setTimeout(resolve, 500));

        const complete = await request(`/captures/${captureId}/complete`, { method: 'POST' });
        $('capture-summary').textContent = `수집 완료 · ${complete.recordCount} records · `
            + `${complete.receivedSize.toLocaleString()} bytes`;
        captureRunning = false;
        captureId = null;
        showNotice('success', 'GNSS 수집 완료', '수집 데이터를 검증하고 시험 입력으로 준비했습니다.');
        updateControls();
    } catch (error) {
        reportError('GNSS 수집 종료', error);
    }
};

/** 선택한 시험 종류에 필요한 조건 입력만 화면에 노출한다. */
function conditions() {
    const type = $('test-type').value;
    const errorCount = $('error-count');
    const errorCountLabel = $('error-count-label');
    document.querySelectorAll('.conditional').forEach((item) => item.classList.add('hidden'));

    if (type === 'TEST_B_RANDOM_ERRORS' || type === 'TEST_C_BURST_ERRORS') {
        errorCountLabel.textContent = '프레임당 손상 비트 수';
        errorCount.min = '1';
        errorCount.max = '5880';
        errorCount.value = String(Math.min(5880, Math.max(1, Number(errorCount.value) || 1)));
        errorCount.dataset.tooltip = '각 대상 AFS 프레임의 데이터 영역에서 반전할 비트 수입니다. 허용 범위는 1~5,880입니다.';
        errorCount.title = errorCount.dataset.tooltip;
        document.querySelectorAll('.conditional.error')
            .forEach((item) => item.classList.remove('hidden'));
    }
    if (type === 'TEST_D_SYNC_RECOVERY') {
        errorCountLabel.textContent = '동기 패턴 손상 비트 수';
        errorCount.min = '1';
        errorCount.max = '68';
        errorCount.value = String(Math.min(68, Math.max(1, Number(errorCount.value) || 1)));
        errorCount.dataset.tooltip = '68심볼 AFS SP 안에서 반전할 bit 수입니다. PocketSDR-AFS 방식은 1bit만 달라도 현재 동기를 거부하며, 6,000심볼 간격의 연속 정상 SP를 다시 찾습니다. 손상 프레임 자체는 복구하지 않습니다.';
        errorCount.title = errorCount.dataset.tooltip;
        document.querySelectorAll('.conditional.error,.conditional.sync')
            .forEach((item) => item.classList.remove('hidden'));
    }
}

$('test-type').onchange = conditions;
conditions();

/** 서버 요청 전에 시험별 허용 범위를 일반 사용자가 이해할 수 있는 문장으로 검증한다. */
function validateTestSettings(options) {
    const afsPrn = Number($('afs-prn').value);
    if (!Number.isInteger(afsPrn) || afsPrn < 1 || afsPrn > 8) {
        return 'SB2 LANS Ephemeris PRN은 1~8 범위에서 선택하세요.';
    }
    if (['TEST_B_RANDOM_ERRORS', 'TEST_C_BURST_ERRORS'].includes(options.testType)
        && (options.errorCount < 1 || options.errorCount > 5880)) {
        return 'Test B/C의 프레임당 손상 비트 수는 1~5,880 범위로 입력하세요.';
    }
    if (options.testType === 'TEST_D_SYNC_RECOVERY'
        && (options.errorCount < 1 || options.errorCount > 68)) {
        return 'Test D의 동기 패턴 손상 비트 수는 1~68 범위로 입력하세요.';
    }
    if (options.testType === 'TEST_D_SYNC_RECOVERY' && options.syncDamageInterval < 1) {
        return 'Test D의 동기 손상 간격은 1 frame 이상으로 입력하세요.';
    }
    return null;
}

$('test-start').onclick = async () => {
    if (!inputId) {
        showNotice('warning', '시험 입력 필요', 'GRAW 업로드 또는 GNSS COM 수집을 먼저 완료하세요.');
        return;
    }

    try {
        const senderAgentId = requireSelection('sender-agent', 'Sender');
        const receiverAgentId = requireSelection('receiver-agent', 'Receiver');
        const body = {
            senderAgentId,
            receiverAgentId,
            inputId,
            afs: {
                prn: Number($('afs-prn').value),
            },
            options: {
                testType: $('test-type').value,
                errorCount: Number($('error-count').value),
                errorSeed: Number($('error-seed').value),
                syncDamageInterval: Number($('sync-interval').value),
                thresholds: {},
            },
        };
        const validationMessage = validateTestSettings(body.options);
        if (validationMessage) {
            showNotice('warning', '시험 조건 확인', validationMessage);
            return;
        }

        const session = await request('/sessions', {
            method: 'POST',
            body: JSON.stringify(body),
        });
        sessionId = session.sessionId;
        // RESULT에는 시험 조건이 일부 생략될 수 있으므로 시작 요청의 조건을 세션 문맥으로 보존한다.
        resultContext = { ...body.options };
        sessionRunning = true;
        setPill($('session-state'), '시험 진행 중 · 취소 가능', 'warning');
        log(eventLog, `시험 ${sessionId} 시작`);
        showNotice('success', '시험 시작', `시험 ${sessionId} 명령을 양쪽 Agent에 전송했습니다.`);
        updateControls();
    } catch (error) {
        reportError('시험 시작', error);
        refreshActiveSession().catch((refreshError) => {
            log(eventLog, `활성 시험 확인 실패: ${refreshError.message}`);
        });
    }
};

$('test-cancel').onclick = async () => {
    if (!sessionId) {
        showNotice('info', '진행 중인 시험 없음', '현재 취소할 시험이 없습니다.');
        return;
    }
    const cancellingSessionId = sessionId;
    $('test-cancel').disabled = true;
    try {
        await request(`/sessions/${cancellingSessionId}/cancel`, { method: 'POST' });
        showNotice('info', '시험 취소 완료', `시험 ${cancellingSessionId}을 종료했습니다.`);
        await refreshActiveSession();
    } catch (error) {
        reportError('시험 취소', error);
        await refreshActiveSession().catch(() => {});
    }
};

statusSocket(
    (event) => {
        // Agent 상태 이벤트는 시험 세션과 무관하므로 먼저 처리해 선택 목록을 최신 상태로 유지한다.
        if (event.type === 'AGENT_STATUS') {
            refreshAgents().catch((error) => log(eventLog, error.message));
        }

        if (event.payload?.ports) {
            const selectedAgentId = $('capture-agent').value;
            if (!event.agentId || event.agentId === selectedAgentId) {
                const select = $('com-port');
                const portOptions = event.payload.ports.map(
                    (port) => new Option(port.name, port.name),
                );
                select.replaceChildren(...portOptions);
                $('capture-summary').textContent = portOptions.length > 0
                    ? `${portOptions.length}개 COM 포트를 찾았습니다.`
                    : '사용 가능한 COM 포트가 없습니다.';
                updateControls();
            }
        }

        if (event.sessionId && sessionId && event.sessionId !== sessionId) {
            // 다른 브라우저가 시작한 세션 이벤트가 현재 화면의 결과 영역을 덮어쓰지 않게 한다.
            return;
        }

        const payload = event.payload || {};
        const progressDetails = payload.counters || payload;
        resultContext = {
            ...resultContext,
            ...progressDetails,
        };
        log(eventLog, formatEventLog(event), {serverEvent: true});
        if (event.type === 'SESSION_STATUS') {
            if (TERMINAL_SESSION_STATES.has(payload.state)) {
                applyActiveSession(null);
                showNotice(
                    payload.state === 'CANCELLED' ? 'info' : 'success',
                    payload.state === 'CANCELLED' ? '시험 취소 완료' : '시험 종료',
                    payload.message || `시험 상태: ${payload.state}`,
                );
            } else {
                sessionId = event.sessionId;
                sessionRunning = true;
                setPill($('session-state'), `${payload.state} · 취소 가능`, 'warning');
                updateControls();
            }
        }
        if (Number.isFinite(payload.percent)) {
            $('session-progress').value = payload.percent;
            $('progress-label').textContent = `${payload.percent}%`;
        }
        if (event.type === 'RESULT') {
            // RESULT는 역할별로 두 번 올 수 있으며 각 도착 시점에 해당 결과와 통합 다운로드를 갱신한다.
            renderMetrics($('metrics'), payload, resultContext);
            const verdict = payload.verdict?.toLowerCase();
            $('verdict').textContent = `판정: ${payload.verdict}`;
            $('verdict').className = `verdict ${verdict === 'pass' ? 'pass' : 'fail'}`;
            downloads(
                $('downloads'),
                event.sessionId,
                event.role === 'SENDER' ? 'tx' : 'rx',
            );
            renderFrameEvidence(
                $('frame-evidence'),
                event.sessionId,
                payload.verdict,
            );
            // 한쪽 RESULT만으로는 시험이 끝난 것이 아니므로 서버의 활성 상태를 다시 확인한다.
            refreshActiveSession().catch((error) => log(eventLog, error.message));
        }
    },
    (online) => setPill(
        $('server-status'),
        online ? '서버 연결됨' : '서버 재연결 중',
        online ? 'online' : 'warning',
    ),
);

// 새로고침으로 WebSocket 과거 이벤트를 잃어도 REST 조회로 Agent와 활성 세션 상태를 복원한다.
updateControls();
refreshAgents().catch((error) => log(eventLog, error.message));
refreshActiveSession().catch((error) => log(eventLog, error.message));
setInterval(() => {
    refreshAgents().catch(() => {});
    refreshActiveSession().catch(() => {});
}, 5000);
