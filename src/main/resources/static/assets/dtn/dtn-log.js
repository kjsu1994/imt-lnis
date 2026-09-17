// Local LNIS history plus shared adapter events, ordered by occurrence time.
export function logLine(entry) {
  const at = new Date(entry.occurredAt).toLocaleTimeString('ko-KR', {hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'});
  return `${at} [${entry.level}] [${entry.stage}] ${entry.message}`;
}
export function createDtnLog(target) {
  const $ = id => document.getElementById(id);
  const toggle=$('dtn-log-detail'), select=$('dtn-log-history'), download=$('dtn-log-download');
  let current='', currentType='TEST', scope='', version=0, cursor=0, cleared=0, entries=[], local=[], detailed=false, running=false, lastError='';
  let historyAt=0;
  const render=()=>{
    const follow=target.scrollHeight-target.scrollTop-target.clientHeight<36;
    const visible=entries.filter(e=>e.sequence>cleared && (detailed || !e.detail));
    target.textContent=[...visible,...(select.value?[]:local)].sort((a,b)=>new Date(a.occurredAt)-new Date(b.occurredAt)).map(logLine).join('\n');
    if(!target.textContent && scope) target.textContent=currentType==='TEST'
      ? '저장된 로그 없음 · 도입 전 시험 또는 처리 대기' : '처리 로그 대기';
    if(follow) target.scrollTop=target.scrollHeight;
  };
  const change=()=>{
    const next=select.value || current;
    if(next!==scope) {scope=next;version++;cursor=cleared=0;entries=[];lastError='';}
    if(scope) {download.href='/lnis/api/v1/dtn/logs?scopeId='+encodeURIComponent(scope)+'&download=true';download.setAttribute('aria-disabled','false');}
    else {download.removeAttribute('href');download.setAttribute('aria-disabled','true');}
    render();
  };
  async function poll() {
    if(running || document.visibilityState==='hidden') return;
    running=true;
    try {
      if(Date.now()-historyAt>10000) {
        const response=await fetch('/lnis/api/v1/dtn/tests',{cache:'no-store',signal:AbortSignal.timeout(8000)});
        if(response.ok) {
          const tests=await response.json(), chosen=select.value;
          select.replaceChildren(new Option('현재 작업',''),...tests.map(t=>new Option(new Date(t.createdAt).toLocaleString('ko-KR')+' · '+t.testType+' · '+t.testId.slice(0,8),t.testId)));
          if(tests.some(t=>t.testId===chosen)) select.value=chosen;
          historyAt=Date.now();change();
        }
      }
      if(!scope) return;
      const requested=scope, revision=version;
      let more;
      do {
        const response=await fetch('/lnis/api/v1/dtn/logs?scopeId='+encodeURIComponent(requested)+'&after='+cursor,{cache:'no-store',signal:AbortSignal.timeout(8000)});
        if(!response.ok) throw new Error('처리 로그 조회 실패 · HTTP '+response.status);
        const page=await response.json();
        if(revision!==version || requested!==scope) return;
        entries.push(...page.entries.filter(e=>e.sequence>cursor));
        cursor=page.nextSequence; more=page.hasMore;
        if(entries.length>10000) entries=entries.slice(-10000);
      } while(more);
      lastError='';render();
    } catch(error) {
      if(error.message!==lastError) {lastError=error.message;write(lastError,'WARN');}
    } finally {running=false;}
  }
  function write(message,level='INFO') {
    if(local.at(-1)?.message===message) return;
    const entry={occurredAt:new Date().toISOString(),level,stage:'화면',message};
    local.push(entry);
    // 전송 실패를 다시 로그로 보내면 무한 반복되므로 화면 표시만 유지한다.
    void fetch('/lnis/api/v1/dtn/logs/screen',{
      method:'POST',headers:{'Content-Type':'application/json'},signal:AbortSignal.timeout(5000),
      body:JSON.stringify({scopeId:current||null,occurredAt:entry.occurredAt,
        level:['INFO','WARN','ERROR'].includes(level)?level:'INFO',message:String(message).slice(0,2000)})
    }).catch(()=>{});
    local=local.slice(-200);render();
  }
  toggle.onclick=()=>{detailed=!detailed;toggle.setAttribute('aria-pressed',String(detailed));render();};
  select.onchange=()=>{change();void poll();};
  $('dtn-log-clear').onclick=()=>{cleared=cursor;local=[];target.textContent='';};
  setInterval(()=>void poll(),2000);
  return {write,refresh:poll,setContext(id,type='TEST') {
    const next=id || '';
    if(current!==next) {current=next;currentType=type;local=[];select.value='';}
    change();void poll();
  }};
}
