const SVG = 'http://www.w3.org/2000/svg';
const ROUTES = {
  'DTN → DTN':'#2563eb', 'DTN → HDTN':'#0f766e',
  'HDTN → DTN':'#a855f7', 'HDTN → HDTN':'#b45309'
};
export const historyDistance = value => {
  if (!Number.isFinite(value)) return '—';
  const magnitude = Math.abs(value);
  if (magnitude > 0 && magnitude < 1e-6) return value.toExponential(2) + ' m';
  return magnitude < 0.01 ? (value * 1000).toFixed(3) + ' mm'
    : magnitude < 1 ? (value * 100).toFixed(2) + ' cm' : value.toFixed(3) + ' m';
};
const time = value => value ? new Date(value).toLocaleString('ko-KR') : '—';

// 1·2·5 눈금을 사용하며 새 결과가 들어와도 자동 축척을 줄이지 않는다.
export function historyRadius(horizontal) {
  const required = Math.max(0.001, horizontal * 1.15);
  const power = 10 ** Math.floor(Math.log10(required));
  return [1, 2, 5, 10].find(step => step * power >= required) * power;
}

// shortcut: 수십~수백 회차용 SVG이며, 수천 회차를 상시 표시할 때 Canvas로 전환한다.
export function createPvtHistory({root, request, project, onSelect}) {
  const find = id => root.querySelector('#' + id);
  const svg = find('enu-target-svg'), layer = find('pvt-history-points');
  const header = find('pvt-history-header'), footer = find('pvt-history-footer');
  const status = find('pvt-history-status'), picker = find('pvt-history-picker');
  const expand = find('pvt-history-expand');
  let job = null, key = null, data = null, points = [], visible = [];
  let radius = 0.001, automatic = true, lastFetch = 0, controller = null, pending = false;
  let version = 0, expanded = false, savedOverflow = '', savedScroll = 0, selectedAvailable = false, previous3d = false;

  function detail(available) {
    selectedAvailable = available;
    root.querySelector('.pvt-details-section').hidden = !available;
    root.querySelector('.hud-alt-gauge-wrap').hidden = !available;
    find('btn-view-3d').disabled = !available;
  }

  function exit() {
    if (!expanded) return;
    expanded = false;
    root.classList.remove('history-expanded');
    root.removeAttribute('role');
    root.removeAttribute('aria-modal');
    root.removeAttribute('aria-label');
    if (previous3d && selectedAvailable) find('btn-view-3d').click();
    document.body.style.overflow = savedOverflow;
    window.scrollTo({top:savedScroll});
    expand.textContent = '⛶';
    expand.title = 'HUD 크게 보기';
    expand.setAttribute('aria-label', expand.title);
    expand.setAttribute('aria-pressed', 'false');
    expand.focus({preventScroll:true});
    draw();
  }

  function setJob(value) {
    job = value;
    const nextKey = ['GNSS_RAW','AFS_METADATA'].includes(job?.testType) ? job?.pvtHistoryKey || null : null;
    if (key !== nextKey) {
      version++;
      controller?.abort();
      controller = null; pending = false;
      key = nextKey; data = null; points = []; visible = [];
      radius = 0.001; automatic = true; lastFetch = 0;
      layer.replaceChildren();
      picker.hidden = true;
      if (!key) exit();
    }
    root.classList.toggle('history-active', !!key);
    header.hidden = footer.hidden = !key;
    if (!key) {
      root.querySelector('.pvt-details-section').hidden = false;
      root.querySelector('.hud-alt-gauge-wrap').hidden = false;
      find('btn-view-3d').disabled = false;
      return;
    }
    root.hidden = false;
    if (!data) status.textContent = '누적 이력 조회 중';
    draw();
  }

  async function refresh(force = false) {
    if (!key || pending || (!force && Date.now() - lastFetch < 5000)) return;
    const stamp = version;
    controller = new AbortController();
    pending = true; lastFetch = Date.now();
    try {
      const result = await request('/dtn/tests/' + encodeURIComponent(job.testId) + '/pvt-history',
        {signal:controller.signal});
      if (stamp !== version || result.historyKey !== key) return;
      data = result;
      points = data.trials.flatMap((trial, index) => {
        if (trial.availability !== 'AVAILABLE' || !trial.ecefMeters || !data.referenceEcefMeters) return [];
        const enu = project(data.referenceEcefMeters, trial.ecefMeters);
        if (!enu || ![enu.e,enu.n,enu.u].every(Number.isFinite)) return [];
        return [{...trial, ...enu, number:index+1, h:Math.hypot(enu.e,enu.n), r:Math.hypot(enu.e,enu.n,enu.u)}];
      });
      status.textContent = '';
      draw();
    } catch (error) {
      if (stamp !== version || error.name === 'AbortError') return;
      status.textContent = '누적 이력 조회 실패 · ' + error.message;
    } finally {
      if (stamp === version) pending = false;
    }
  }

  function describe(point) {
    return '#' + point.number + ' · ' + point.testId + ' · ' + time(point.startedAt)
      + ' · ' + point.senderMode + ' → ' + point.receiverMode
      + ' · 지연 ' + (Number.isFinite(point.delaySeconds) ? point.delaySeconds.toFixed(6) + ' s' : '—')
      + ' · E ' + historyDistance(point.e) + ' / N ' + historyDistance(point.n) + ' / U ' + historyDistance(point.u)
      + (point.clockWarning ? ' · ' + point.clockWarning : '');
  }

  async function select(point) {
    picker.hidden = true;
    try { await onSelect(point.testId); }
    catch (error) { status.textContent = '시험 선택 실패 · ' + error.message; }
  }

  function draw() {
    if (!key) return;
    root.hidden = false;
    if (automatic) radius = Math.max(radius, historyRadius(Math.max(0, ...points.map(p => p.h))));
    const epoch = data?.selectedEpoch || job?.selectedEpoch;
    find('pvt-history-context').textContent = [
      job?.testType === 'GNSS_RAW' ? 'RAW' : 'AFS',
      epoch ? 'Week ' + epoch.week + ' / TOW ' + epoch.towSeconds + ' s' : '',
      '표시 ' + points.length + ' / 등록 ' + (data?.total ?? 0),
      '대기 ' + (data?.waiting ?? 0),
      data?.unavailable ? '표시 불가 ' + data.unavailable : ''
    ].filter(Boolean).join(' · ');
    for (const [id,factor] of [['enu-ring-outer-label',1],['enu-ring-mid-label',95/140],['enu-ring-inner-label',50/140]]) {
      find(id).textContent = historyDistance(radius * factor);
    }
    const focused = layer.contains(document.activeElement) ? document.activeElement?.dataset.testId : null;
    visible = points.filter(p => p.h <= radius);
    const fragment = document.createDocumentFragment();
    const ordered = [...visible.filter(p => p.testId !== job?.testId), ...visible.filter(p => p.testId === job?.testId)];
    ordered.forEach((point,index) => {
      const circle = document.createElementNS(SVG, 'circle');
      const selected = point.testId === job?.testId;
      circle.setAttribute('cx', String(point.e / radius * 140));
      circle.setAttribute('cy', String(-point.n / radius * 140));
      circle.setAttribute('r', selected ? '7' : '4.5');
      circle.setAttribute('fill', ROUTES[point.senderMode + ' → ' + point.receiverMode] || '#475569');
      circle.setAttribute('class','pvt-history-point' + (selected ? ' selected' : ''));
      circle.setAttribute('role','button');
      circle.setAttribute('tabindex', selected || (!visible.some(p => p.testId === job?.testId) && index === 0) ? '0' : '-1');
      circle.setAttribute('aria-label',describe(point));
      circle.setAttribute('aria-pressed',String(selected));
      circle.dataset.testId = point.testId;
      fragment.append(circle);
    });
    layer.replaceChildren(fragment);
    if (focused) [...layer.children].find(el => el.dataset.testId === focused)?.focus({preventScroll:true});
    const routes = [...new Set(points.map(p => p.senderMode + ' → ' + p.receiverMode))];
    const legend = find('pvt-history-legend');
    legend.replaceChildren();
    routes.forEach(route => {
      const item = document.createElement('span'), dot = document.createElement('i');
      dot.style.background = ROUTES[route] || '#475569';
      item.append(dot, route); legend.append(item);
    });
    const outside = points.length - visible.length;
    find('pvt-history-summary').textContent = '반경 ' + historyDistance(radius)
      + (automatic ? ' · 자동' : ' · 수동') + (outside ? ' · 범위 밖 ' + outside + '개' : '')
      + (points.length ? ' · 평균 수평 ' + historyDistance(points.reduce((sum,p) => sum+p.h,0)/points.length)
        + ' · 최대 3차원 ' + historyDistance(Math.max(...points.map(p => p.r))) : ' · 비교 가능한 위치 결과 없음');
    const selected = points.find(p => p.testId === job?.testId);
    if (selected) find('alt-gauge-thumb-val').textContent = (selected.u >= 0 ? '+' : '') + historyDistance(selected.u);
    const trial = data?.trials.find(p => p.testId === job?.testId);
    const reason = {WAITING:'수신·비교자료 대기',FAILED:'시험 실패',CANCELLED:'시험 종료',
      UNAVAILABLE:'위치 비교 자료 없음',REFERENCE_MISMATCH:'Reference 위치 확인 필요'};
    find('pvt-history-inspect').textContent = selected ? describe(selected)
      : '선택 시험 · ' + (reason[trial?.availability] || (data ? '표시 가능한 점 없음' : '조회 중'));
    find('pvt-history-auto').setAttribute('aria-pressed',String(automatic));
    detail(selectedAvailable);
  }

  function pointFor(event) {
    const id = event.target.closest('[data-test-id]')?.dataset.testId;
    return points.find(p => p.testId === id);
  }
  function activate(point) {
    const ctm = svg.getScreenCTM();
    const scale = ctm ? Math.hypot(ctm.a,ctm.b) : 1;
    const nearby = visible.filter(p => Math.hypot(p.e-point.e,p.n-point.n) / radius * 140 * scale <= 10);
    if (nearby.length < 2) { void select(point); return; }
    find('pvt-history-picker-title').textContent = '겹친 시험 ' + nearby.length + '개';
    const list = find('pvt-history-picker-list');
    list.replaceChildren();
    nearby.forEach(p => {
      const button = document.createElement('button');
      button.type = 'button'; button.textContent = '#' + p.number + ' · ' + time(p.startedAt)
        + ' · ' + p.senderMode + ' → ' + p.receiverMode;
      button.title = p.testId;
      button.onclick = () => select(p);
      list.append(button);
    });
    picker.hidden = false;
    list.querySelector('button')?.focus({preventScroll:true});
  }
  layer.addEventListener('click',event => { const point = pointFor(event); if (point) activate(point); });
  for (const name of ['pointerover','focusin']) layer.addEventListener(name,event => {
    const point = pointFor(event);
    if (point) find('pvt-history-inspect').textContent = describe(point);
  });
  layer.addEventListener('keydown',event => {
    const point = pointFor(event);
    if (!point) return;
    if (['Enter',' '].includes(event.key)) { event.preventDefault(); activate(point); }
    if (['ArrowLeft','ArrowUp','ArrowRight','ArrowDown'].includes(event.key)) {
      event.preventDefault();
      const nodes = [...layer.children], index = nodes.indexOf(event.target);
      const step = ['ArrowLeft','ArrowUp'].includes(event.key) ? -1 : 1;
      nodes[(index + step + nodes.length) % nodes.length]?.focus({preventScroll:true});
    }
  });
  find('pvt-history-picker-close').onclick = () => { picker.hidden = true; layer.querySelector('[tabindex="0"]')?.focus(); };
  find('pvt-history-auto').onclick = () => { automatic = true; radius = 0.001; draw(); };
  find('pvt-history-zoom-in').onclick = () => { automatic = false; radius = Math.max(1e-9,radius/2); draw(); };
  find('pvt-history-zoom-out').onclick = () => { automatic = false; radius *= 2; draw(); };
  expand.onclick = () => {
    if (expanded) { exit(); return; }
    previous3d = find('btn-view-3d').getAttribute('aria-selected') === 'true';
    find('btn-view-hud').click();
    expanded = true; savedOverflow = document.body.style.overflow; savedScroll = window.scrollY;
    document.body.style.overflow = 'hidden';
    root.classList.add('history-expanded');
    root.setAttribute('role','dialog'); root.setAttribute('aria-modal','true');
    root.setAttribute('aria-label','Reference 대비 위치 편차 확대');
    expand.textContent = '✕'; expand.title = 'HUD 크게 보기 종료';
    expand.setAttribute('aria-label',expand.title); expand.setAttribute('aria-pressed','true');
    expand.focus({preventScroll:true});
  };
  document.addEventListener('keydown',event => {
    if (event.key === 'Escape' && (expanded || !picker.hidden)) {
      event.preventDefault();
      if (!picker.hidden) find('pvt-history-picker-close').click(); else exit();
    }
    if (!expanded || event.key !== 'Tab') return;
    const controls = [...root.querySelectorAll('button:not(:disabled),[tabindex="0"]')]
      .filter(el => el.getClientRects().length);
    const first = controls[0], last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });
  return {setJob,refresh,draw,detail,active:()=>!!key};
}
