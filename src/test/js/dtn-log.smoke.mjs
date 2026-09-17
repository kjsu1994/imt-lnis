import assert from 'node:assert/strict';
import {createDtnLog,logLine} from '../../main/resources/static/assets/dtn/dtn-log.js';
class Element {
  constructor(){this.value='';this.textContent='';this.scrollTop=0;this.scrollHeight=100;this.clientHeight=100;}
  setAttribute(k,v){this[k]=v;} removeAttribute(k){delete this[k];}
  replaceChildren(...v){this.options=v;this.value=v[0]?.value||'';}
}
const elements=new Map(['dtn-log-detail','dtn-log-history','dtn-log-download','dtn-log-clear'].map(k=>[k,new Element()]));
globalThis.document={visibilityState:'visible',getElementById:id=>elements.get(id)};
globalThis.Option=function(text,value){this.text=text;this.value=value;};
globalThis.setInterval=()=>{};
const target=new Element();
let entries=[{sequence:1,occurredAt:'2026-09-15T00:00:00Z',level:'INFO',stage:'PVT',detail:false,message:'계산 완료'},
  {sequence:2,occurredAt:'2026-09-15T00:00:01Z',level:'INFO',stage:'PVT',detail:true,message:'ECEF 상세 값'}];
let finish;
globalThis.fetch=async url=>({ok:true,json:async()=>url.endsWith('/tests')?[]:{entries:entries.filter(e=>e.sequence>Number(new URL(url,'http://test').searchParams.get('after'))),nextSequence:entries.at(-1)?.sequence||0,hasMore:false}});
const view=createDtnLog(target);
view.setContext('first');
await new Promise(r=>setTimeout(r,0));
await view.refresh();
assert.match(target.textContent,/계산 완료/);
assert.doesNotMatch(target.textContent,/ECEF 상세 값/);
elements.get('dtn-log-detail').onclick();
assert.match(target.textContent,/ECEF 상세 값/);
await view.refresh();assert.equal(target.textContent.split('계산 완료').length,2,'no duplicates');
elements.get('dtn-log-clear').onclick();await view.refresh();
assert.doesNotMatch(target.textContent,/계산 완료/);
assert.match(elements.get('dtn-log-download').href,/scopeId=first.*download=true/);
globalThis.fetch=()=>new Promise(r=>{finish=r;});
const pending=view.refresh();view.setContext('second');
finish({ok:true,json:async()=>({entries,nextSequence:2,hasMore:false})});await pending;
assert.doesNotMatch(target.textContent,/계산 완료/,'old responses cannot replace selected history');
assert.match(logLine(entries[0]),/\[INFO\] \[PVT\]/);
console.log('PASS: detail toggle, incremental logs, non-destructive clear, download scope and stale response guard');

let posted=[];
globalThis.fetch=async(url,options)=>{posted.push({url,options});throw new Error('offline');};
view.write('adapter state changed','WARN');
await Promise.resolve();
assert.equal(posted.length,1);
assert.equal(posted[0].options.method,'POST');
assert.equal(JSON.parse(posted[0].options.body).level,'WARN');
assert.equal(JSON.parse(posted[0].options.body).scopeId,'second');
view.write('adapter state changed','WARN');
assert.equal(posted.length,1,'duplicate screen event is not sent twice');
console.log('PASS: screen events forwarded once; reporting failures do not recurse');
