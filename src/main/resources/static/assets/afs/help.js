const HELP_TEXT = new Map([
    ['.role-nav a[href="/lnis/afstest/sender"]', 'AFS 송신 시험을 설정하고 시작하는 Sender 화면으로 이동합니다.'],
    ['.role-nav a[href="/lnis/afstest/receiver"]', 'AFS 수신 상태와 시험 결과를 확인하는 Receiver 화면으로 이동합니다.'],
    ['#server-status', '웹 화면과 LNIS 서버 사이의 실시간 WebSocket 연결 상태입니다.'],
    ['#sender-status', '송신 PC에서 실행 중인 Sender Agent의 연결 및 준비 상태입니다.'],
    ['#receiver-status', '수신 PC에서 실행 중인 Receiver Agent의 연결 및 준비 상태입니다.'],
    ['#session-state', '현재 시험 세션의 진행 상태입니다. 진행 중인 시험은 여기서 취소할 수 있는 상태로 표시됩니다.'],
    ['#listen-status', 'Receiver Agent가 시험 데이터를 기다리거나 수신·검증하는 현재 단계입니다.'],
    ['#notice-close', '현재 안내 메시지를 화면에서 닫습니다.'],
    ['.tab[data-tab="upload"]', '이미 저장된 GRAW 파일을 시험 입력 데이터로 사용하는 화면을 엽니다.'],
    ['.tab[data-tab="capture"]', 'GNSS 장비의 COM 포트에서 데이터를 직접 수집하는 화면을 엽니다.'],
    ['#graw-file', '시험에 사용할 capture.graw 파일을 선택합니다. 선택한 파일은 서버의 임시 파일 저장소로 전송됩니다.'],
    ['#upload-button', '선택한 GRAW 파일을 분할 업로드하고 서버에서 무결성을 확인한 뒤 H2 메타데이터와 파일로 저장합니다.'],
    ['#upload-progress', '선택한 GRAW 파일이 서버로 전송된 비율입니다.'],
    ['#input-summary', '업로드 또는 수집한 시험 입력의 레코드 수, 크기와 SHA-256 검증값입니다.'],
    ['#capture-agent', 'GNSS 장비가 연결된 Windows PC에서 실행 중인 Sender Agent를 선택합니다.'],
    ['#com-port', 'GNSS 장비가 연결된 Windows COM 포트를 선택합니다.'],
    ['#refresh-ports', '선택한 Sender Agent가 설치된 PC에서 현재 사용 가능한 COM 포트를 다시 검색합니다.'],
    ['#baud', 'GNSS 장비와 COM 포트가 통신하는 초당 비트 속도입니다. 장비 설정과 같은 값을 선택해야 합니다.'],
    ['#protocol', 'COM 포트에서 수신한 GNSS 데이터를 해석할 프로토콜 형식입니다.'],
    ['#capture-start', '선택한 Agent와 COM 포트에서 GNSS 데이터 수집을 시작합니다.'],
    ['#capture-stop', '진행 중인 GNSS 데이터 수집을 끝내고 수집 결과를 시험 입력으로 준비합니다.'],
    ['#capture-summary', 'COM 포트 검색, 수집 진행 및 완료 결과를 요약해 표시합니다.'],
    ['#sender-agent', 'AFS 프레임을 생성해 기존 Agent 연결로 전송할 Sender Agent를 선택합니다.'],
    ['#receiver-agent', '시험 데이터를 수신하고 무결성을 판정할 Receiver Agent를 선택합니다.'],
    ['#test-type', '수행할 시험 시나리오입니다. Test D는 동기 손상 프레임 자체를 복구하지 않고 제외한 뒤, 다음 연속 정상 SP를 재획득하는지 확인합니다.'],
    ['#error-count', 'Test B/C에서는 각 대상 AFS 프레임의 손상 비트 수(1~5,880), Test D에서는 68심볼 SP 안에서 손상할 bit 수(1~68)입니다. PocketSDR-AFS 방식은 SP 한 bit만 달라도 현재 동기를 인정하지 않습니다.'],
    ['#error-seed', '같은 오류 위치를 재현하기 위한 난수 시작값입니다. 같은 Seed는 같은 오류 패턴을 만듭니다.'],
    ['#sync-interval', 'Test D에서 첫 프레임부터 몇 프레임마다 SP를 손상할지 정합니다. 재동기 판정에는 6,000심볼 간격의 연속 정상 SP가 필요하므로 손상 프레임 사이에 정상 프레임이 충분히 있어야 합니다. 마지막 프레임은 손상하지 않습니다.'],
    ['#test-start', '현재 입력 데이터와 설정으로 Sender·Receiver 시험을 시작합니다.'],
    ['#test-cancel', '현재 진행 중인 시험을 안전하게 취소하고 새 시험을 시작할 수 있는 상태로 전환합니다.'],
    ['#current-test', 'Receiver가 현재 수신 중이거나 마지막으로 완료한 시험의 종류입니다.'],
    ['#test-conditions', 'Receiver가 전달받은 비트 오류 또는 동기 손상 조건입니다.'],
    ['#receiver-verdict', 'Receiver가 수신 데이터의 무결성과 시험 기준을 검사한 최종 판정입니다.'],
    ['#progress-label', '현재 시험의 전체 진행률입니다. 수신 후 검증과 결과 확정까지 끝나면 100%가 됩니다.'],
    ['#session-progress', '시험 데이터 준비, 송수신, 무결성 검증과 결과 확정을 포함한 전체 진행률입니다.'],
    ['#verdict', 'Sender와 Receiver의 결과를 바탕으로 한 최종 시험 판정입니다.'],
    ['#metrics', '원본 복원, 프레임 전달·처리, 주입 오류와 AFS 복호화 실패를 구분한 시험 결과입니다.'],
    ['#event-log', 'Agent 연결, 송수신 단계, 오류 및 결과 이벤트를 시간순으로 표시합니다.'],
    ['#clear-log', '화면에 표시된 이벤트 로그만 지웁니다. H2의 시험 데이터와 결과에는 영향을 주지 않습니다.'],
    ['#downloads', '현재 시험 결과를 JSON 또는 CSV 실제 파일로 내려받는 영역입니다.'],
]);

const TOOLTIP_ID = 'lnis-help-tooltip';
let activeTarget = null;

/**
 * 도움말을 화면 요소와 연결한다.
 * title은 브라우저 기본 도움말을 제공하고 data-tooltip은 LNIS 공통 툴팁에 사용한다.
 */
function attachHelp(root = document) {
    for (const [selector, message] of HELP_TEXT) {
        root.querySelectorAll(selector).forEach((element) => {
            element.dataset.tooltip = message;
            element.title = message;
            element.setAttribute('aria-describedby', TOOLTIP_ID);
        });
    }

    root.querySelectorAll('.metric').forEach((element) => {
        const name = element.querySelector('span')?.textContent?.trim() || '측정값';
        // 결과 렌더러가 항목별 전문 설명을 넣었다면 이를 유지하고, 없을 때만 기본 설명을 쓴다.
        const message = element.dataset.tooltip
            || `${name} 항목의 시험 측정 결과입니다.`;
        element.dataset.tooltip = message;
        element.title = message;
        element.tabIndex = 0;
        element.setAttribute('aria-describedby', TOOLTIP_ID);
    });

    root.querySelectorAll('.downloads a').forEach((element) => {
        const message = `${element.textContent.trim()} 시험 산출물을 실제 파일로 내려받습니다.`;
        element.dataset.tooltip = message;
        element.title = message;
        element.setAttribute('aria-describedby', TOOLTIP_ID);
    });
}

/** 마우스와 키보드 모두에서 현재 항목 가까이에 도움말을 표시한다. */
function showTooltip(target) {
    const message = target?.dataset?.tooltip;
    if (!message) {
        return;
    }

    activeTarget = target;
    const tooltip = document.getElementById(TOOLTIP_ID);
    tooltip.textContent = message;
    tooltip.classList.add('visible');

    const rect = target.getBoundingClientRect();
    const left = Math.min(
        window.innerWidth - tooltip.offsetWidth - 12,
        Math.max(12, rect.left + (rect.width - tooltip.offsetWidth) / 2),
    );
    const preferredTop = rect.bottom + 9;
    const top = preferredTop + tooltip.offsetHeight <= window.innerHeight - 12
        ? preferredTop
        : Math.max(12, rect.top - tooltip.offsetHeight - 9);

    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${top}px`;
}

function hideTooltip(target) {
    if (target && activeTarget !== target) {
        return;
    }
    activeTarget = null;
    document.getElementById(TOOLTIP_ID)?.classList.remove('visible');
}

const tooltip = document.createElement('div');
tooltip.id = TOOLTIP_ID;
tooltip.className = 'help-tooltip';
tooltip.setAttribute('role', 'tooltip');
document.body.append(tooltip);

attachHelp();

document.addEventListener('mouseover', (event) => {
    showTooltip(event.target.closest?.('[data-tooltip]'));
});
document.addEventListener('mouseout', (event) => {
    const target = event.target.closest?.('[data-tooltip]');
    if (target && !target.contains(event.relatedTarget)) {
        hideTooltip(target);
    }
});
document.addEventListener('focusin', (event) => {
    showTooltip(event.target.closest?.('[data-tooltip]'));
});
document.addEventListener('focusout', (event) => {
    hideTooltip(event.target.closest?.('[data-tooltip]'));
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
        hideTooltip();
    }
});

// 결과 지표와 다운로드 링크는 시험 완료 후 동적으로 생성되므로 추가 시점에 도움말을 연결한다.
new MutationObserver((mutations) => {
    for (const mutation of mutations) {
        mutation.addedNodes.forEach((node) => {
            if (node.nodeType === Node.ELEMENT_NODE) {
                attachHelp(node.matches('.metric, .downloads a') ? node.parentElement : node);
            }
        });
    }
}).observe(document.body, { childList: true, subtree: true });
