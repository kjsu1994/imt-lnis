import {requestJson} from './http.js?v=20260915-structure';
const API = '/lnis/api/v1';

/** LNIS REST API를 호출하고 서버 오류 본문을 사용자가 읽을 수 있는 예외로 변환한다. */
export function request(path, options = {}) {
    const binaryBody = options.body instanceof Uint8Array;
    return requestJson(path, {
        headers: {
            'Content-Type': binaryBody ? 'application/octet-stream' : 'application/json',
            ...(options.headers || {}),
        },
        ...options,
    });
}

export const agents = () => request('/agents');

/** 상태 WebSocket을 열고 연결이 끊기면 지수 백오프로 자동 재연결한다. */
export function statusSocket(onEvent, onState) {
    let attempt = 0;
    let socket;
    let closed = false;

    const connect = () => {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        socket = new WebSocket(`${scheme}://${location.host}/lnis/ws/status`);
        socket.onopen = () => {
            attempt = 0;
            onState(true);
        };
        socket.onmessage = (event) => {
            try {
                onEvent(JSON.parse(event.data));
            } catch {
                // 잘못된 단일 메시지가 이후 정상 상태 이벤트 처리를 중단시키지 않게 무시한다.
            }
        };
        socket.onclose = () => {
            onState(false);
            if (!closed) {
                const retryDelay = Math.min(30000, 1000 * (2 ** attempt++));
                setTimeout(connect, retryDelay);
            }
        };
        socket.onerror = () => socket.close();
    };

    connect();
    return () => {
        closed = true;
        socket?.close();
    };
}

/** 시간 접두어를 붙여 이벤트 로그를 추가하고 항상 최신 행으로 스크롤한다. */
export function log(target, message, options = {}) {
    const time = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    target.textContent += `[${time}] ${message}\n`;
    target.scrollTop = target.scrollHeight;
    if (options.serverEvent) return;

    const level = options.level || (/실패|오류|ERROR/.test(message) ? 'ERROR' : 'INFO');
    // Never log this request's failure through the same endpoint.
    void fetch(API + '/logs/screen', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        signal: AbortSignal.timeout(5000),
        body: JSON.stringify({scopeId: options.scopeId || null, occurredAt: new Date().toISOString(),
            level, message: String(message).slice(0, 2000)}),
    }).catch(() => {});
}

export function setPill(element, text, state = '') {
    element.textContent = text;
    element.className = `pill ${state}`;
}

/** H2에 보존된 결과를 요청 시점에 JSON/CSV 파일로 내려받는 링크를 만든다. */
export function downloads(container, sessionId) {
    container.classList.remove('hidden');
    container.replaceChildren();

    const files = [
        {
            name: 'lnis-report.xlsx',
            label: '통합 결과 Excel',
            title: '요약, 지표, 시계열, 프레임 비교를 시트별로 묶어 내려받습니다.',
        },
        {
            name: 'lnis-report.json',
            label: '통합 결과 JSON',
            title: 'Sender, Receiver와 프레임 증거 전체를 하나의 JSON으로 내려받습니다.',
        },
    ];
    for (const file of files) {
        const link = document.createElement('a');
        link.textContent = file.label;
        link.href = `${API}/sessions/${sessionId}/artifacts/${file.name}`;
        link.title = file.title;
        container.append(link);
    }
}

export {buildResultPresentation, renderMetrics} from '../afs/result-presentation.js?v=20260915-structure';
