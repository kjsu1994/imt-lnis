const receiver = !!document.getElementById('receiver-type');
const targets = [
  ['#dtn-settings-title', 'settings'],
  ['.preset-bar', 'presets', '프리셋'],
  ['#dtn-input-card h2', 'upload'],
  ['.receiver-test-summary h2', 'receiver-map'],
  ['.adapter-url-controls:has(#dtn-connection-test)', 'routes', '상대 수신 서비스'],
  ['.adapter-url-controls:has(#dtn-adapter-health):has(#dtn-adapter-dot)', 'health', '송신 어댑터'],
  ['#dtn-iq-panel .compact-input-row', 'iq-generate', 'I/Q 생성'],
  ['#hdtn-config-panel .adapter-heading', 'hdtn', 'HDTN 설정'],
  ['#dtn-config-panel .adapter-heading', 'dtn', 'DTN 설정'],
  ['.serial-controls', 'serial', 'COM 포트 연결'],
  ['.serial-status', 'capture', 'GNSS 수집'],
  ['#dtn-settings-view section.card > .card-title h2', 'types'],
  ['.receiver-connection-heading:has(label[for="sender-address"])', 'routes', '상대 송신 서비스'],
  ['.receiver-connection-heading:has(label[for="dtn-send-url"])', 'health', '수신 어댑터'],
  ['.reference-heading', 'reference', '송신 비교 자료'],
  ['.card-title:has(+ .gnss-time) h2', 'sender-reference'],
  ['.receiver-log h2, .dtn-log-tools h2', 'logs'],
  ['.pvt-header-left', 'pvt-result', '수신 PVT'],
  ['#dtn-observations .gnss-data-header', receiver ? 'received-values' : 'sender-data', 'GNSS 관측값'],
  ['#reference-observations .gnss-data-header', 'reference', '송신 비교 관측값'],
  ['#dtn-payload h2', 'json'],
];
function helpButton(topic) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'manual-help';
  button.dataset.manualTopic = topic;
  button.textContent = '?';
  button.setAttribute('aria-haspopup', 'dialog');
  return button;
}
function addButtons() {
  for (const [selector, topic, label] of targets) {
    for (const target of document.querySelectorAll(selector)) {
      if (!target.querySelector('.manual-help')) {
        const button = helpButton(topic);
        button.setAttribute('aria-label', (label || target.textContent.trim()) + ' 설명서 보기');
        button.title = (label || target.textContent.trim()) + ' 설명서';
        const progress = target.querySelector(':scope > progress');
        if (progress) progress.before(button);
        else target.append(button);
      }
    }
  }
}
addButtons();
for (const id of ['dtn-observations', 'reference-observations', 'dtn-payload']) {
  const container = document.getElementById(id);
  if (container) new MutationObserver(addButtons).observe(container, {childList: true});
}

const dialog = document.createElement('dialog');
dialog.className = 'manual-help-dialog';
dialog.setAttribute('aria-labelledby', 'manual-help-title');
dialog.innerHTML = '<div class="manual-help-heading">'
  + '<strong id="manual-help-title">사용자 설명서</strong>'
  + '<a target="_blank" rel="noopener">전체 설명서 ↗</a>'
  + '<button type="button" autofocus aria-label="설명서 닫기">✕ 닫기</button>'
  + '</div><iframe title="선택한 기능의 설명서"></iframe>';
document.body.append(dialog);
const frame = dialog.querySelector('iframe');
const link = dialog.querySelector('a');
let opener;
function close() { dialog.close(); }
dialog.querySelector('button').addEventListener('click', close);
dialog.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  event.preventDefault();
  event.stopPropagation();
  close();
}, true);
dialog.addEventListener('click', event => {
  if (event.target !== dialog) return;
  const rect = dialog.getBoundingClientRect();
  if (event.clientX < rect.left || event.clientX > rect.right
      || event.clientY < rect.top || event.clientY > rect.bottom) close();
});
dialog.addEventListener('close', () => {
  frame.removeAttribute('src');
  opener?.focus();
});
document.addEventListener('click', event => {
  const button = event.target.closest('.manual-help');
  if (!button) return;
  opener = button;
  let selectedTopic = button.dataset.manualTopic;
  if (selectedTopic === 'received-values' && document.getElementById('receiver-type')?.textContent.includes('I/Q')) {
    selectedTopic = 'iq-observations';
  }
  const topic = encodeURIComponent(selectedTopic);
  frame.src = '/user-manual/index.html?embed=1#' + topic;
  link.href = '/user-manual/#' + topic;
  dialog.showModal();
});
window.addEventListener('message', event => {
  if (event.origin === location.origin && event.source === frame.contentWindow
      && event.data === 'manual-help-close' && dialog.open) close();
});
