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

// 자동 범위에는 실제 데이터 경계만 누적하여 다시 그릴 때 여백이 커지지 않게 한다.
export function historyExtent(points, previous = null) {
  const extent = previous ? {...previous} : {minE:0, maxE:0, minN:0, maxN:0};
  for (const point of points) {
    extent.minE = Math.min(extent.minE, point.e);
    extent.maxE = Math.max(extent.maxE, point.e);
    extent.minN = Math.min(extent.minN, point.n);
    extent.maxN = Math.max(extent.maxN, point.n);
  }
  return extent;
}

export function historyViewport(extent, width, height, padding = 1.15, minimumSpan = 0.002) {
  const plotWidth = Math.max(1, width - 84), plotHeight = Math.max(1, height - 52);
  const e = (extent.minE + extent.maxE) / 2, n = (extent.minN + extent.maxN) / 2;
  const scale = Math.max((extent.maxE - extent.minE) / plotWidth,
    (extent.maxN - extent.minN) / plotHeight, minimumSpan / Math.min(plotWidth,plotHeight)) * padding;
  return {width, height, plotWidth, plotHeight, scale, e, n,
    minE:e - plotWidth * scale / 2, maxE:e + plotWidth * scale / 2,
    minN:n - plotHeight * scale / 2, maxN:n + plotHeight * scale / 2};
}

// Reference 기준 거리 눈금 중 현재 화면에 걸치는 구간만 그린다.
export function historyRings(e, n, radius) {
  const scale = radius / 140, distance = Math.hypot(e,n);
  const raw = radius / 3, power = 10 ** Math.floor(Math.log10(raw));
  const step = [1,2,5,10].find(value => value * power >= raw) * power;
  const first = Math.max(1, Math.ceil((distance - scale * 145) / step));
  const count = Math.min(12, Math.floor((distance + scale * 145) / step) - first + 1);
  const x = -e / scale, y = n / scale, offset = Math.hypot(x,y);
  const ux = offset ? x / offset : 0, uy = offset ? y / offset : 1;
  return Array.from({length:Math.max(0,count)}, (_,i) => {
    const meters = (first+i) * step, r = meters / scale;
    const nearest = (distance - meters) / scale;
    const label = Math.max(-125,Math.min(125,nearest));
    const ring = {meters,x,y,r,labelX:ux*label+6,labelY:uy*label+(uy*label<0 ? 12 : -7)};
    if (offset > 290) {
      // 먼 Reference에서도 큰 SVG 좌표를 빼지 않아 확대 시 정밀도를 유지한다.
      ring.path = Array.from({length:33}, (_,index) => {
        const v = (index-16) * 145 / 16;
        const q = nearest + v*v / (r + Math.sqrt(Math.max(0,r*r-v*v)));
        return (index ? 'L' : 'M') + (ux*q-uy*v) + ' ' + (uy*q+ux*v);
      }).join(' ');
    }
    return ring;
  });
}

// shortcut: 수십~수백 회차용 SVG이며, 수천 회차를 상시 표시할 때 Canvas로 전환한다.
export function createPvtHistory({root, request, project, onSelect}) {
  const find = id => root.querySelector('#' + id);
  const svg = find('enu-target-svg'), layer = find('pvt-history-points');
  const header = find('pvt-history-header'), footer = find('pvt-history-footer');
  const status = find('pvt-history-status'), picker = find('pvt-history-picker');
  const expand = find('pvt-history-expand');
  const overviewSvg = find('pvt-history-overview');
  const origin = find('pvt-history-origin-layer'), rings = find('pvt-history-rings');
  const axes = [...find('pvt-history-axes').querySelectorAll('.enu-axis')];
  let overviewExtent = null, overviewAutomatic = true, overviewView = null;
  let overviewVisible = [], centerE = 0, centerN = 0;
  let drag = null, ignoreClick = false, frame = 0;
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
    endDrag();
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
      endDrag();
      centerE = centerN = 0;
      overviewExtent = null; overviewAutomatic = true; overviewView = null; overviewVisible = [];
      placeOrigin();
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
      + ' · E ' + historyDistance(point.e) + ' / N ' + historyDistance(point.n) + ' / U ' + historyDistance(point.u);
  }

  async function select(point) {
    picker.hidden = true;
    try { await onSelect(point.testId); }
    catch (error) { status.textContent = '시험 선택 실패 · ' + error.message; }
  }

  function draw() {
    if (!key) return;
    root.hidden = false;
    if (automatic && !expanded) radius = Math.max(radius, historyRadius(Math.max(0, ...points.map(p => p.h))));
    const epoch = data?.selectedEpoch || job?.selectedEpoch;
    find('pvt-history-context').textContent = [
      job?.testType === 'GNSS_RAW' ? 'RAW' : 'AFS',
      epoch ? 'Week ' + epoch.week + ' / TOW ' + epoch.towSeconds + ' s' : '',
      '표시 ' + points.length + ' / 등록 ' + (data?.total ?? 0),
      '대기 ' + (data?.waiting ?? 0),
      data?.unavailable ? '표시 불가 ' + data.unavailable : ''
    ].filter(Boolean).join(' · ');
    drawRings();
    const focused = layer.contains(document.activeElement) ? document.activeElement?.dataset.testId : null;
    placeOrigin();
    visible = points.filter(p => Math.hypot(p.e-centerE,p.n-centerN) <= radius);
    const fragment = document.createDocumentFragment();
    const ordered = [...visible.filter(p => p.testId !== job?.testId), ...visible.filter(p => p.testId === job?.testId)];
    ordered.forEach((point,index) => {
      const circle = document.createElementNS(SVG, 'circle');
      const selected = point.testId === job?.testId;
      circle.setAttribute('cx', String((point.e-centerE) / radius * 140));
      circle.setAttribute('cy', String(-(point.n-centerN) / radius * 140));
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
    if (focused) ([...layer.children].find(el => el.dataset.testId === focused)
      || find('pvt-history-auto')).focus({preventScroll:true});
    const routes = [...new Set(points.map(p => p.senderMode + ' → ' + p.receiverMode))];
    const legend = find('pvt-history-legend');
    legend.replaceChildren();
    routes.forEach(route => {
      const item = document.createElement('span'), dot = document.createElement('i');
      dot.style.background = ROUTES[route] || '#475569';
      item.append(dot, route); legend.append(item);
    });
    if (expanded) drawExpanded();
    const outside = expanded ? points.length - overviewVisible.length
      : points.length - visible.length;
    find('pvt-history-summary').textContent = (expanded && overviewView
      ? '가로 ' + historyDistance(overviewView.maxE - overviewView.minE)
        + ' × 세로 ' + historyDistance(overviewView.maxN - overviewView.minN)
      : '화면 반경 ' + historyDistance(radius))
      + ((expanded ? overviewAutomatic : automatic) ? ' · 자동' : ' · 수동')
      + (!expanded ? ' · 눈금: Reference 거리' : '')
      + (!expanded && (centerE || centerN) ? ' · 화면 중심 E ' + historyDistance(centerE) + ' / N ' + historyDistance(centerN) : '')
      + (outside ? ' · 범위 밖 ' + outside + '개' : '')
      + (points.length ? ' · 평균 수평 ' + historyDistance(points.reduce((sum,p) => sum+p.h,0)/points.length)
        + ' · 최대 3차원 ' + historyDistance(Math.max(...points.map(p => p.r))) : ' · 비교 가능한 위치 결과 없음');
    const selected = points.find(p => p.testId === job?.testId);
    if (selected) find('alt-gauge-thumb-val').textContent = (selected.u >= 0 ? '+' : '') + historyDistance(selected.u);
    const trial = data?.trials.find(p => p.testId === job?.testId);
    const reason = {WAITING:'수신·비교자료 대기',FAILED:'시험 실패',CANCELLED:'시험 종료',
      UNAVAILABLE:'위치 비교 자료 없음',REFERENCE_MISMATCH:'Reference 위치 확인 필요'};
    find('pvt-history-inspect').textContent = selected ? describe(selected)
      : '선택 시험 · ' + (reason[trial?.availability] || (data ? '표시 가능한 점 없음' : '조회 중'));
    find('pvt-history-auto').setAttribute('aria-pressed',String(expanded ? overviewAutomatic : automatic));
    detail(selectedAvailable);
  }

  function element(name, attributes, text) {
    const node = document.createElementNS(SVG,name);
    for (const [key,value] of Object.entries(attributes)) node.setAttribute(key,String(value));
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function drawPlot(target, view, values) {
    const x = e => 62 + (e-view.minE) / view.scale;
    const y = n => 14 + (view.maxN-n) / view.scale;
    const inside = point => point.e >= view.minE && point.e <= view.maxE
      && point.n >= view.minN && point.n <= view.maxN;
    const shown = values.filter(inside);
    const focused = target.contains(document.activeElement) ? document.activeElement?.dataset.testId : null;
    const fragment = document.createDocumentFragment();
    target.setAttribute('viewBox','0 0 ' + view.width + ' ' + view.height);
    fragment.append(element('rect',{x:62,y:14,width:view.plotWidth,height:view.plotHeight,fill:'#fff'}));
    for (const axis of ['e','n']) {
      const minimum = axis === 'e' ? view.minE : view.minN;
      const maximum = axis === 'e' ? view.maxE : view.maxN;
      const pixels = axis === 'e' ? view.plotWidth : view.plotHeight;
      const rawStep = (maximum-minimum) / Math.max(2,Math.floor(pixels / (axis === 'e' ? 120 : 65)));
      const power = 10 ** Math.floor(Math.log10(rawStep));
      const step = [1,2,5,10].find(value => value * power >= rawStep) * power;
      const first = Math.ceil(minimum / step);
      const count = Math.min(30,Math.floor(maximum / step) - first + 1);
      for (let i = 0; i < count; i++) {
        const value = (first+i) * step;
        if (axis === 'e') {
          fragment.append(element('line',{x1:x(value),x2:x(value),y1:14,y2:14+view.plotHeight,class:'plot-grid'}));
          fragment.append(element('text',{x:x(value),y:view.height-17,'text-anchor':'middle'},historyDistance(value)));
        } else {
          fragment.append(element('line',{x1:62,x2:62+view.plotWidth,y1:y(value),y2:y(value),class:'plot-grid'}));
          fragment.append(element('text',{x:55,y:y(value)+4,'text-anchor':'end'},historyDistance(value)));
        }
      }
    }
    if (view.minE <= 0 && view.maxE >= 0)
      fragment.append(element('line',{x1:x(0),x2:x(0),y1:14,y2:14+view.plotHeight,class:'plot-zero'}));
    if (view.minN <= 0 && view.maxN >= 0)
      fragment.append(element('line',{x1:62,x2:62+view.plotWidth,y1:y(0),y2:y(0),class:'plot-zero'}));
    if (inside({e:0,n:0})) {
      fragment.append(element('path',{d:'M '+(x(0)-5)+' '+y(0)+' h 10 M '+x(0)+' '+(y(0)-5)+' v 10',class:'plot-reference'}));
      const right = x(0) + 68 <= 62 + view.plotWidth;
      const labelY = y(0) + 20 <= 14 + view.plotHeight ? y(0) + 17 : y(0) - 10;
      fragment.append(element('text',{
        x:x(0) + (right ? 8 : -8),y:labelY,'text-anchor':right ? 'start' : 'end'
      },'Reference'));
    }
    fragment.append(element('text',{x:view.width-5,y:view.height-17,'text-anchor':'end'},'E'));
    fragment.append(element('text',{x:62,y:10},'N'));
    const ordered = [...shown.filter(p => p.testId !== job?.testId), ...shown.filter(p => p.testId === job?.testId)];
    ordered.forEach((point,index) => {
      const selected = point.testId === job?.testId;
      fragment.append(element('circle',{
        cx:x(point.e),cy:y(point.n),r:selected ? 9 : 7,
        fill:ROUTES[point.senderMode + ' → ' + point.receiverMode] || '#475569',
        class:'pvt-history-point' + (selected ? ' selected' : ''),
        role:'button',tabindex:selected || (!shown.some(p => p.testId === job?.testId) && index === 0) ? 0 : -1,
        'aria-label':describe(point),'aria-pressed':selected,'data-test-id':point.testId
      }));
    });
    target.replaceChildren(fragment);
    if (focused) ([...target.querySelectorAll('[data-test-id]')]
      .find(el => el.dataset.testId === focused) || find('pvt-history-auto')).focus({preventScroll:true});
    return shown;
  }

  function drawRings() {
    const fragment = document.createDocumentFragment();
    for (const ring of historyRings(centerE,centerN,radius)) {
      fragment.append(ring.path
        ? element('path',{d:ring.path,class:'enu-ring ring-mid'})
        : element('circle',{cx:ring.x,cy:ring.y,r:ring.r,class:'enu-ring ring-mid'}));
      fragment.append(element('text',{
        x:ring.labelX,y:ring.labelY,class:'enu-ring-label'
      },historyDistance(ring.meters)));
    }
    rings.replaceChildren(fragment);
  }

  function placeOrigin() {
    const x = -centerE / radius * 140, y = centerN / radius * 140;
    origin.setAttribute('transform','translate(' + x + ' ' + y + ')');
    const reach = key ? 185 : 165;
    axes[0].setAttribute('x1',-reach); axes[0].setAttribute('x2',reach);
    axes[0].setAttribute('y1',y); axes[0].setAttribute('y2',y);
    axes[1].setAttribute('y1',-reach); axes[1].setAttribute('y2',reach);
    axes[1].setAttribute('x1',x); axes[1].setAttribute('x2',x);
  }

  function drawExpanded() {
    const bounds = overviewSvg.getBoundingClientRect();
    if (bounds.width < 85 || bounds.height < 53) return;
    if (overviewAutomatic) overviewExtent = historyExtent(points,overviewExtent);
    overviewView = historyViewport(overviewExtent,bounds.width,bounds.height,
      overviewAutomatic ? 1.15 : 1,overviewAutomatic ? 0.002 : 1e-12);
    overviewVisible = drawPlot(overviewSvg,overviewView,points);
  }

  function pointFor(event) {
    const id = event.target.closest('[data-test-id]')?.dataset.testId;
    return points.find(p => p.testId === id);
  }
  function activate(point, source = svg) {
    const view = source === overviewSvg ? overviewView : null;
    const ctm = source.getScreenCTM();
    const scale = ctm ? Math.hypot(ctm.a,ctm.b) : 1;
    const units = view ? 1 / view.scale : 140 / radius;
    const nearby = (view ? overviewVisible : visible).filter(p =>
      Math.hypot(p.e-point.e,p.n-point.n) * units * scale <= 10);
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
  for (const target of [svg, overviewSvg]) {
    target.addEventListener('click',event => {
      if (ignoreClick) { ignoreClick = false; event.preventDefault(); return; }
      const point = pointFor(event);
      if (point) activate(point,target);
    });
    for (const name of ['pointerover','focusin']) target.addEventListener(name,event => {
      const point = pointFor(event);
      if (point) find('pvt-history-inspect').textContent = describe(point);
    });
    target.addEventListener('keydown',event => {
      const point = pointFor(event);
      if (!point) return;
      if (['Enter',' '].includes(event.key)) {
        event.preventDefault(); activate(point,target);
      }
      if (['ArrowLeft','ArrowUp','ArrowRight','ArrowDown'].includes(event.key)) {
        event.preventDefault();
        const nodes = [...target.querySelectorAll('[data-test-id]')], index = nodes.indexOf(event.target);
        const step = ['ArrowLeft','ArrowUp'].includes(event.key) ? -1 : 1;
        nodes[(index + step + nodes.length) % nodes.length]?.focus({preventScroll:true});
      }
    });
  }
  find('pvt-history-picker-close').onclick = () => { picker.hidden = true; (expanded ? overviewSvg : layer).querySelector('[tabindex="0"]')?.focus(); };
  find('pvt-history-auto').onclick = () => {
    if (expanded) { overviewAutomatic = true; overviewExtent = null; }
    else { automatic = true; radius = 0.001; centerE = centerN = 0; }
    draw();
  };
  function camera(target) {
    return target === svg
      ? {e:centerE,n:centerN,scale:radius/140,x:0,y:0}
      : {e:overviewView.e,n:overviewView.n,scale:overviewView.scale,
        x:62+overviewView.plotWidth/2,y:14+overviewView.plotHeight/2};
  }

  function moveView(target, e, n, scale) {
    if (target === svg) {
      automatic = false;
      centerE = e; centerN = n; radius = scale * 140;
    } else {
      overviewAutomatic = false;
      const halfE = overviewView.plotWidth * scale / 2, halfN = overviewView.plotHeight * scale / 2;
      overviewExtent = {minE:e-halfE,maxE:e+halfE,minN:n-halfN,maxN:n+halfN};
      overviewView = {...overviewView,e,n,scale,...overviewExtent};
    }
  }

  function zoom(factor, target = expanded ? overviewSvg : svg, anchor = null) {
    const view = camera(target);
    const scale = Math.max(target === svg ? 1e-9/140 : 1e-12,view.scale * factor);
    const x = (anchor?.x ?? view.x) - view.x, y = (anchor?.y ?? view.y) - view.y;
    moveView(target,view.e + x*(view.scale-scale),view.n - y*(view.scale-scale),scale);
    picker.hidden = true;
    draw();
  }
  find('pvt-history-zoom-in').onclick = () => zoom(0.5);
  find('pvt-history-zoom-out').onclick = () => zoom(2);

  function localPoint(event, target) {
    const matrix = target.getScreenCTM();
    return matrix ? new DOMPoint(event.clientX,event.clientY).matrixTransform(matrix.inverse()) : null;
  }

  function interactive(target, point) {
    return key && point && (target === svg ? !expanded && Math.hypot(point.x,point.y) <= 145
      : expanded && overviewView && point.x >= 62 && point.x <= overviewView.width-22
        && point.y >= 14 && point.y <= overviewView.height-38);
  }

  function endDrag(event) {
    if (!drag || (event && drag.id !== event.pointerId)) return;
    const ended = drag;
    drag = null; ignoreClick = ended.moved;
    ended.target.classList.remove('is-panning');
    if (ended.target.hasPointerCapture(ended.id)) ended.target.releasePointerCapture(ended.id);
  }

  for (const target of [svg,overviewSvg]) {
    target.addEventListener('pointerdown',event => {
      const point = localPoint(event,target);
      if (drag || event.button !== 0 || !event.isPrimary || !interactive(target,point)) return;
      ignoreClick = false;
      drag = {target,id:event.pointerId,
        clientX:event.clientX,clientY:event.clientY,...camera(target),point,moved:false};
    });
    target.addEventListener('lostpointercapture',endDrag);
    target.addEventListener('wheel',event => {
      const point = localPoint(event,target);
      if (event.ctrlKey || event.metaKey || !event.deltaY || !interactive(target,point)) return;
      event.preventDefault();
      if (drag) endDrag();
      const pixels = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? target.clientHeight : 1);
      zoom(Math.exp(Math.max(-0.7,Math.min(0.7,pixels * 0.002))),target,point);
    },{passive:false});
  }
  window.addEventListener('pointermove',event => {
    if (!drag || drag.id !== event.pointerId) return;
    if (!drag.moved && Math.hypot(event.clientX-drag.clientX,event.clientY-drag.clientY) < 4) return;
    const point = localPoint(event,drag.target);
    if (!point) return;
    if (!drag.moved) {
      drag.moved = true;
      drag.target.setPointerCapture(event.pointerId);
      drag.target.classList.add('is-panning');
      picker.hidden = true;
    }
    moveView(drag.target,drag.e - (point.x-drag.point.x)*drag.scale,
      drag.n + (point.y-drag.point.y)*drag.scale,drag.scale);
    if (!frame) frame = requestAnimationFrame(() => { frame = 0; draw(); });
  });
  window.addEventListener('pointerup',endDrag);
  window.addEventListener('pointercancel',endDrag);
  window.addEventListener('blur',() => endDrag());
  expand.onclick = () => {
    if (expanded) { exit(); return; }
    previous3d = find('btn-view-3d').getAttribute('aria-selected') === 'true';
    find('btn-view-hud').click();
    overviewExtent = null; overviewAutomatic = true;
    expanded = true; savedOverflow = document.body.style.overflow; savedScroll = window.scrollY;
    document.body.style.overflow = 'hidden';
    root.classList.add('history-expanded');
    root.setAttribute('role','dialog'); root.setAttribute('aria-modal','true');
    root.setAttribute('aria-label','Reference 대비 위치 편차 확대');
    expand.textContent = '✕'; expand.title = 'HUD 크게 보기 종료';
    expand.setAttribute('aria-label',expand.title); expand.setAttribute('aria-pressed','true');
    expand.focus({preventScroll:true});
    draw();
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
    if (!root.contains(document.activeElement)) {
      event.preventDefault(); (event.shiftKey ? last : first)?.focus(); return;
    }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });
  const resize = new ResizeObserver(() => { if (expanded && key) draw(); });
  resize.observe(overviewSvg);
  return {setJob,refresh,draw,detail,active:()=>!!key};
}
