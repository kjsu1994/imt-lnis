import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const base=new URL('../../main/resources/static/',import.meta.url);
const html=readFileSync(new URL('data-management.html',base),'utf8');
const elements=new Map();
class Element {
 constructor(tag){this.tag=tag;this.children=[];this.value='';this.dataset={};this.checked=false;this.disabled=false;this.hidden=false;this.classList={toggle(){}};}
 set id(value){this._id=value;elements.set(value,this);}get id(){return this._id;}
 append(...nodes){this.children.push(...nodes);}
 replaceChildren(...nodes){this.children=nodes;if(this.tag==='select')this.value=nodes[0]?.value||'';}
 setAttribute(key,value){this[key]=value;}
 querySelectorAll(){const result=[];const visit=e=>{if(e.tag==='input'&&!e.disabled)result.push(e);for(const c of e.children||[])visit(c);};visit(this);return result;}
 showModal(){this.open=true;}close(){this.open=false;}scrollIntoView(){}
}
for(const [,tag,id]of html.matchAll(/<([a-z][a-z0-9]*)\b[^>]*\bid="([^"]+)"/g)){const el=new Element(tag);el.id=id;}
elements.get('kind').value='DTN';
const tabs=['records','files','settings'].map(value=>{const e=new Element('button');e.dataset.tab=value;return e;});
const requests=[];
const item={key:{kind:'DTN',id:'11111111-1111-1111-1111-111111111111'},name:'AFS_METADATA',state:'COMPLETED',createdAt:new Date().toISOString(),bytes:0,pinned:false,blocked:''};
let settings={tests:{enabled:false,days:30},receipts:{enabled:false,days:30},files:{enabled:false,days:30}};
const preview={token:'preview-token',role:'receiver',items:[{row:item,related:[],logs:1,receipts:1,evidence:0,blocked:''}]};
let proposedSettings;
const context={console,URLSearchParams,Date,Set,AbortSignal,Option:function(text,value){const e=new Element('option');e.textContent=text;e.value=String(value);return e;},document:{getElementById:id=>elements.get(id),createElement:tag=>new Element(tag),querySelectorAll:()=>tabs},fetch:async(url,options)=>{
 requests.push({url,...options,body:options.body?JSON.parse(options.body):undefined});
 let data={};
 if(url.endsWith('/summary'))data={role:'receiver',counts:{DTN:1,AFS:0,RECEIPT:1},databaseBytes:123,inputBytes:0,iqBytes:0};
 else if(url.includes('/items?'))data={items:[item],total:1,page:0};
 else if(url.endsWith('/settings/preview')){proposedSettings=JSON.parse(options.body);data={preview,settings:proposedSettings};}
 else if(url.endsWith('/settings')){if(options.method==='POST')settings=proposedSettings;data=settings;}
 else if(url.endsWith('/preview'))data=preview;
 else if(url.endsWith('/delete'))data={results:[{key:item.key,status:'DELETED'}]};
 else if(url.endsWith('/history'))data=[];
 return {ok:true,json:async()=>data};
}};
const httpSource = readFileSync(new URL('assets/common/http.js', base), 'utf8').replace(/^export /gm, '');
const managementSource = readFileSync(new URL('assets/management/data-management.js', base), 'utf8').replace(/^import .*;\r?\n/gm, '');
vm.runInNewContext(httpSource + '\n' + managementSource, context);
for(let i=0;i<8;i++)await new Promise(resolve=>setImmediate(resolve));
assert.equal(elements.get('rows').children.length,1);
assert.match(elements.get('node').textContent,/수신 PC/);
assert.equal(requests.some(r=>r.url.endsWith('/delete')),false);
elements.get('select-page').onclick();await elements.get('delete-selected').onclick();
assert.equal(elements.get('confirm').open,true,elements.get('message').textContent);
assert.equal(requests.some(r=>r.url.endsWith('/delete')),false,'preview must not delete');
await elements.get('confirm-apply').onclick();
assert.equal(requests.filter(r=>r.url.endsWith('/delete')).length,1);
assert.equal(requests.find(r=>r.url.endsWith('/delete')).body.token,'preview-token');
await tabs[2].onclick();
assert.equal(elements.get('tests-enabled').checked,false);
assert.equal(elements.get('files-enabled').checked,false);
assert.equal(elements.get('brand-home').href, '/lnis/dtntest/receiver');
assert.equal(elements.get('tests-period').disabled, true);
assert.equal(elements.get('tests-days').disabled, true);
assert.equal(elements.get('settings-save').disabled, true);
assert.equal(elements.get('cleanup-now').disabled, true);
const enable = elements.get('tests-enabled');
enable.checked = true; enable.onchange();
assert.equal(elements.get('tests-period').disabled, false);
assert.equal(elements.get('settings-save').disabled, false);
assert.equal(elements.get('cleanup-now').disabled, true);
await elements.get('cleanup-now').onclick();
assert.equal(requests.some(r=>r.url.endsWith('/cleanup/preview')), false);
assert.match(elements.get('message').textContent, /먼저 저장/);
elements.get('tests-period').value = 'custom';
elements.get('tests-period').onchange();
assert.equal(elements.get('tests-days').disabled, false);
for (const invalid of ['', '0', '3651', '1.5']) {
 elements.get('tests-days').value = invalid;
 elements.get('tests-days').oninput();
 assert.equal(elements.get('settings-save').disabled, true);
}
elements.get('tests-days').value = '14';
elements.get('tests-days').oninput();
assert.equal(elements.get('settings-save').disabled, false);
const deletedBeforeSettings = requests.filter(r=>r.url.endsWith('/delete')).length;
await elements.get('settings-form').onsubmit({preventDefault(){}});
assert.equal(elements.get('confirm').open, true);
assert.equal(elements.get('confirm-apply').className, 'primary');
assert.match(elements.get('confirm-warning').textContent, /다음 자동 정리/);
assert.equal(requests.filter(r=>r.url.endsWith('/settings')&&r.method==='POST').length,0);
elements.get('confirm-cancel').onclick();
assert.equal(elements.get('settings-save').disabled, false);
await elements.get('settings-form').onsubmit({preventDefault(){}});
await elements.get('confirm-apply').onclick();
assert.equal(requests.filter(r=>r.url.endsWith('/settings')&&r.method==='POST').length,1);
assert.equal(settings.tests.days,14);
assert.equal(elements.get('settings-save').disabled, true);
assert.equal(elements.get('cleanup-now').disabled, false);
assert.equal(requests.filter(r=>r.url.endsWith('/delete')).length,deletedBeforeSettings);
await elements.get('cleanup-now').onclick();
assert.equal(elements.get('confirm').open,true);
assert.equal(elements.get('confirm-apply').className,'danger');
assert.equal(requests.filter(r=>r.url.endsWith('/delete')).length,deletedBeforeSettings);
elements.get('confirm-cancel').onclick();
elements.get('tests-period').value = 'custom';
elements.get('tests-period').onchange();
elements.get('tests-days').value = '';
elements.get('tests-days').oninput();
enable.checked = false; enable.onchange();
assert.equal(Number(elements.get('tests-days').value),14);
assert.equal(elements.get('settings-save').disabled,false);

for(const name of ['dtn-sender.html','dtn-receiver.html']) {
 const page=readFileSync(new URL(name,base),'utf8');
 const entry=page.match(/<a[^>]*href="\/lnis\/data-manager"[^>]*>/)[0];
 assert.doesNotMatch(entry,/target=/);
 assert.match(page,/<a[^>]*class="data-management-entry"[^>]*href="\/lnis\/data-manager"[^>]*aria-label="데이터 관리"/);
}
console.log('PASS: management retention toggles, custom-day validation, unsaved cleanup guard, save/cancel/delete separation and same-tab return links');
