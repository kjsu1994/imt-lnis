import assert from 'node:assert/strict';
import {readFile,writeFile,stat,open} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const tx='http://127.0.0.1:18090/lnis/api/v1', rx='http://127.0.0.1:18091/lnis/api/v1';
const results=[];
async function call(base,path,method='GET',body) {
  const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const value=await response.json(); assert.ok(response.ok,`${path} HTTP ${response.status}: ${JSON.stringify(value)}`); return value;
}
function pass(name,detail={}) { results.push({name,...detail}); console.log('PASS '+name+' '+JSON.stringify(detail)); }
async function upload(name) {
 const bytes=await readFile('build/dtn-example/'+name);
 const input=await call(tx,'/inputs?dtn=true','POST',{fileName:name,size:bytes.length,kind:'GRAW_UPLOAD'});
 const response=await fetch(tx+'/inputs/'+input.inputId+'/chunks/0',{method:'PUT',headers:{'Content-Type':'application/octet-stream'},body:bytes});
 assert.equal(response.status,200); await call(tx,'/inputs/'+input.inputId+'/complete','POST',{}); return input.inputId;
}
async function wait(path,seconds=180) {
 const end=Date.now()+seconds*1000; let job;
 while(Date.now()<end) {
   job=await call(tx,path);
   if(['READY','COMPLETED','INCONCLUSIVE','FAILED','CANCELLED'].includes(job.state)) return job;
   await new Promise(r=>setTimeout(r,500));
 }
 throw new Error('Timed out '+JSON.stringify(job));
}
async function reject(base,path,body,headers={}) {
 const r=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body)});
 assert.ok(r.status>=400 && r.status<500,`${path}: ${r.status}`);
 pass('reject '+path,{status:r.status});
}
for(const base of [tx,rx]) {
 const deadline=Date.now()+120000;
 for(;;) {
  try { const n=await call(base,'/node'); assert.ok(n.online); break; }
  catch(error) { if(Date.now()>=deadline) throw error; await new Promise(r=>setTimeout(r,1000)); }
 }
}
pass('both independent Linux nodes online');
await reject(tx,'/dtn/iq',{});
await reject(rx,'/dtn/receive',{});
const f9t=await upload('observations-only.graw');
await reject(tx,'/dtn/iq',{inputId:f9t});
const inputId=await upload('synthetic-earth-pvt.graw');
const pvt=await call(tx,'/dtn/inputs/'+inputId+'/pvt');
assert.ok(pvt[0].positionValid && pvt[0].velocityValid); pass('GRAW upload + valid Earth PVT',{inputId});
const reuse=process.argv[2];
if(!reuse) {
const cancelled=await call(tx,'/dtn/iq','POST',{inputId});
await reject(tx,'/dtn/iq',{inputId});
await call(tx,'/dtn/iq/'+cancelled.id+'/cancel','POST',{});
assert.equal((await wait('/dtn/iq/'+cancelled.id)).state,'CANCELLED');
await new Promise(r=>setTimeout(r,1000));
pass('I/Q cancel + concurrent generation rejected');
}
const iq=reuse ? {id:reuse} : await call(tx,'/dtn/iq','POST',{inputId});
console.log('Generating 90 seconds '+iq.id);
const completed=await wait('/dtn/iq/'+iq.id,600);
assert.equal(completed.state,'READY',JSON.stringify(completed));
assert.equal(completed.file.sizeBytes,2160000000); assert.ok(completed.preview.length);
const local='build/native-system/sender-files/'+iq.id+'.bin';
assert.equal((await stat(local)).size,2160000000);
const hash=createHash('sha256'); const handle=await open(local);
const counts=new Uint32Array(256); const buffer=Buffer.alloc(4*1024*1024);
try { for(;;) { const {bytesRead}=await handle.read(buffer,0,buffer.length,null); if(!bytesRead)break;
 hash.update(buffer.subarray(0,bytesRead)); for(let i=0;i<bytesRead;i++)counts[buffer[i]]++;
}} finally {await handle.close();}
assert.equal(hash.digest('hex').toUpperCase(),completed.file.sha256);
assert.deepEqual(Array.from(counts.keys()).filter(k=>counts[k]),[1,3,253,255]);
pass('90 second I/Q complete + full-file quantization + SHA256',{id:iq.id,file:completed.file});
for(const testType of ['GNSS_RAW','AFS_METADATA','IQ_SAMPLE']) {
 for(const [senderMode,receiverMode] of [['DTN','DTN'],['DTN','HDTN'],['HDTN','DTN'],['HDTN','HDTN']]) {
   const start=await call(tx,'/dtn/tests','POST',{inputId:testType==='IQ_SAMPLE'?null:inputId,iqFileId:iq.id,
      senderAgentId:'sender-1',receiverAgentId:'receiver-1',sendUrl:'http://relay:8080',testType,senderMode,receiverMode});
   const job=await wait('/dtn/tests/'+start.testId,600); assert.equal(job.verdict,testType==='IQ_SAMPLE'?'MEASURED':'PASS',JSON.stringify(job));
   const sent=await (await fetch(tx+'/dtn/tests/'+start.testId+'/payload/sent')).text();
   const received=await (await fetch(rx+'/dtn/tests/'+start.testId+'/payload/received')).text(); assert.equal(received,sent);
   const wire=JSON.parse(sent); assert.equal(wire.testType,testType); assert.equal(wire.senderMode,senderMode); assert.equal(wire.receiverMode,receiverMode);
   if(testType==='IQ_SAMPLE') {
     assert.equal(wire.file.sha256,completed.file.sha256);assert.ok(sent.length<16000);
     assert.equal(wire.metadata.pvtMethod,'AFS_IQ_GPS_LNAV_ASSISTED-v1');
     assert.equal(wire.metadata.gpsLnav.length,15); assert.equal(wire.referencePvt.length,1);
     const report=await call(rx,'/dtn/tests/'+start.testId+'/report');
     assert.equal(report.fileResult.verdict,'PASS');assert.equal(report.comparison.verdict,'MEASURED');
     assert.equal(report.observations.source,'IQ_TRACKING');
     assert.ok(report.receivedPvt.filter(p=>p.positionValid && p.velocityValid).length>=30);
     assert.ok(report.receivedPvt[0].towSeconds>wire.referencePvt[0].towSeconds);
     // Fixture-only safety bounds, NOT production accuracy acceptance thresholds.
     const measured=report.comparison.epochs.filter(e=>e.receivedValid);
     assert.ok(measured.every(e=>e.positionDifferenceMeters<100 && e.velocityDifferenceMetersPerSecond<10));
     assert.ok(measured.some(e=>e.positionDifferenceMeters>0.001),'I/Q results must not copy sender PVT');
   }
   else { const report=await call(rx,'/dtn/tests/'+start.testId+'/report');assert.equal(report.comparison.verdict,'PASS');assert.deepEqual(report.referencePvt,report.receivedPvt); }
   for(const base of [tx,rx]) {
     const log = await call(base,'/dtn/logs?scopeId='+start.testId);
     assert.ok(log.entries.length>0,'Persisted processing logs missing');
     assert.equal(new Set(log.entries.map(e=>e.sequence)).size,log.entries.length);
     const incremental=await call(base,'/dtn/logs?scopeId='+start.testId+'&after='+log.nextSequence);
     assert.ok(incremental.entries.every(e=>e.sequence>log.nextSequence));
     const download=await fetch(base+'/dtn/logs?scopeId='+start.testId+'&download=true');
     assert.ok(download.ok);assert.match(await download.text(),/LNIS local processing log/);
     if(base===rx) assert.ok(log.entries.some(e=>e.stage==='시험 등록'));
   }
   pass(testType+' '+senderMode+' -> '+receiverMode,{testId:start.testId,jsonBytes:Buffer.byteLength(sent)});
 }
}
const health=await call(tx,'/dtn/adapter-health?adapterUrl='+encodeURIComponent('http://relay:8080'));
const receiverHealth=await call(rx,'/dtn/adapter-health?adapterUrl='+encodeURIComponent('http://relay:8080'));
assert.ok(health.adapter.ok); assert.ok(receiverHealth.adapter.ok);pass('sender and receiver local adapter health');
await writeFile('build/native-system/results.json',JSON.stringify(results,null,2));
console.log('ALL SYSTEM CHECKS PASSED; relay only, not real DTN/HDTN transport');
