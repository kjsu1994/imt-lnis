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
const settings={tests:{enabled:false,days:30},receipts:{enabled:false,days:30},files:{enabled:false,days:30}};
const preview={token:'preview-token',role:'receiver',items:[{row:item,related:[],logs:1,receipts:1,evidence:0,blocked:''}]};
const context={console,URLSearchParams,Date,Set,Option:function(text,value){const e=new Element('option');e.textContent=text;e.value=String(value);return e;},document:{getElementById:id=>elements.get(id),createElement:tag=>new Element(tag),querySelectorAll:()=>tabs},fetch:async(url,options)=>{
 requests.push({url,...options,body:options.body?JSON.parse(options.body):undefined});
 let data={};
 if(url.endsWith('/summary'))data={role:'receiver',counts:{DTN:1,AFS:0,RECEIPT:1},databaseBytes:123,inputBytes:0,iqBytes:0};
 else if(url.includes('/items?'))data={items:[item],total:1,page:0};
 else if(url.endsWith('/settings/preview'))data={preview,settings};
 else if(url.endsWith('/settings'))data=settings;
 else if(url.endsWith('/preview'))data=preview;
 else if(url.endsWith('/delete'))data={results:[{key:item.key,status:'DELETED'}]};
 else if(url.endsWith('/history'))data=[];
 return {ok:true,json:async()=>data};
}};
vm.runInNewContext(readFileSync(new URL('assets/management/data-management.js',base),'utf8'),context);
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
for(const name of ['afs-sender.html','afs-receiver.html','dtn-sender.html','dtn-receiver.html']) {
 const page=readFileSync(new URL(name,base),'utf8');
 assert.match(page,/<a[^>]*class="data-management-entry"[^>]*hidden/);
}
console.log('PASS: local management UI requires preview/confirmation, defaults retention off, and hides entry links');
