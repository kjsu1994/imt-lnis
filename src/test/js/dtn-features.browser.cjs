// Run with PLAYWRIGHT_MODULE pointing to an installed Playwright package.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {createServer} = require('node:http');
const {readFileSync} = require('node:fs');
const {resolve, extname} = require('node:path');
const assert = require('node:assert/strict');
const root = resolve(__dirname, '../../main/resources/static');
const config = {maxNumberOfBundlesInPipeline:50,maxSumOfBundleBytesInPipeline:50000000,maxBundleSizeBytes:10485760,
  tcpclMaxSegmentSizeBytes:20000,neighborDepletedStorageDelaySeconds:10,enforceBundlePriority:false,
  storageDeletionPolicy:'DELETE_AFTER_FORWARDING',totalStorageCapacityBytes:8589934592,maxLtpReceiveUdpPacketSizeBytes:65536,acsSendPeriodMilliseconds:1000};
let browser, bulkRequests=0;
let rows=[], id=0, job=null, starts=0, reportFixture={referencePvt:[],receivedPvt:[]};
const server=createServer((req,res)=>{
 const path=new URL(req.url,'http://localhost').pathname;
 const json=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value));};
 if(path.startsWith('/lnis/api/v1/')){
  let body='';req.on('data',c=>body+=c);req.on('end',()=>{
   const data=body?JSON.parse(body):{};
   if(path.includes('/presets')){
    const key=path.split('/presets/')[1];const index=rows.findIndex(r=>r.id===key);
    if(req.method==='GET')return json(rows);
    if(req.method==='POST'){
     if(rows.length>=5)return json({detail:'최대 5개'},409);
     const row={...data,id:String(++id),version:0,updatedAt:new Date().toISOString()};rows.push(row);return json(row);
    }
    if(index<0)return json({detail:'없음'},404);
    if(req.method==='DELETE'){rows.splice(index,1);res.writeHead(204);return res.end();}
    if(data.version!==rows[index].version)return json({detail:'변경 충돌'},409);
    rows[index]={...rows[index],...data,version:data.version+1};return json(rows[index]);
   }
   if(path.endsWith('/waiting-summary'))return json({count:3,asOf:'2026-10-01T00:00:00Z',testIds:['waiting-a','waiting-b','waiting-c']});
   if(path.endsWith('/cancel-waiting')){assert.equal(data.asOf,'2026-10-01T00:00:00Z');assert.deepEqual(data.testIds,['waiting-a','waiting-b','waiting-c']);bulkRequests++;return json({requested:3,cancelled:2,skipped:1,pending:0});}
   if(path.endsWith('/node/gnss/ports')||path.endsWith('/captures/pending'))return json([]);
   if(path.endsWith('/node/gnss'))return json({state:'DISCONNECTED',timeState:'UNAVAILABLE'});
   if(path.endsWith('/node/clock'))return json({busy:false,clock:{trialAt:new Date().toISOString(),rawAt:new Date().toISOString(),source:'SYSTEM',offsetSeconds:0,ageSeconds:0,clockChanges:0}});
   if(path.endsWith('/config'))return json({delaySupported:true,iqEnabled:false,defaultSendUrl:'http://adapter:8080',adapterUrl:'http://adapter:8080'});
   if(path.endsWith('/agents'))return json([{agentId:'sender-1',role:'SENDER',state:'READY'},{agentId:'receiver-1',role:'RECEIVER',state:'READY'}]);
   if(path.endsWith('/node/connection'))return json({ip:'127.0.0.1',port:8091,editable:true,peerOnline:true});
   if(path.includes('adapter-health'))return json({checkedAt:new Date().toISOString(),adapter:{status:'ready',message:'정상연결',elapsedMillis:1,url:'http://adapter:8080/sender/health'}});
   if(path.endsWith('/tests')&&req.method==='POST'){starts++;return json({});}
   if(path.endsWith('/tests'))return json(job?[job]:[]);
   if(path.endsWith('/tests/test1'))return json(job);
   if(path.endsWith('/report'))return json(reportFixture);
   if(path.endsWith('/receipts'))return json([]);
   if(path.endsWith('/logs'))return json({entries:[{sequence:1,occurredAt:new Date().toISOString(),level:'INFO',stage:'검증',message:'일반 로그',detail:false},{sequence:2,occurredAt:new Date().toISOString(),level:'INFO',stage:'검증',message:'상세 확인',detail:true}],nextSequence:2,hasMore:false});
   return json({});
  });return;
 }
 try{
  const file=resolve(root, (path==='/sender'?'dtn-sender.html':path==='/receiver'?'dtn-receiver.html':path==='/dtn-intro'?'dtn-intro.html':path.replace(/^\/lnis\//,'')).replace(/^\//,''));
  if(!file.startsWith(root+require('node:path').sep))throw Error('path');
  res.writeHead(200,{'Content-Type':{'.js':'text/javascript','.html':'text/html','.css':'text/css','.png':'image/png'}[extname(file)]||'text/plain'});res.end(readFileSync(file));
 }catch{res.writeHead(404);res.end();}
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 browser=await chromium.launch();const context=await browser.newContext();
 await context.addInitScript(()=>{window.WebSocket=class {};});
 const page=await context.newPage();page.setDefaultTimeout(15000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/sender');await page.locator('#dtn-settings-open').click();await page.waitForFunction(()=>!document.getElementById('preset-save').disabled);
 await page.locator('#preset-save').click();await page.locator('#preset-name').fill('기본');await page.locator('#preset-confirm').click();await page.waitForFunction(()=>document.getElementById('preset-status').textContent==='저장했습니다.');
 assert.equal(rows.length,1);assert.equal(rows[0].settings.hdtnConfig.maxNumberOfBundlesInPipeline,50);
 await page.locator('#hdtn-maxNumberOfBundlesInPipeline').fill('60');await page.locator('#preset-load').click();assert.equal(await page.locator('#hdtn-maxNumberOfBundlesInPipeline').inputValue(),'50');
 await page.locator('#hdtn-maxNumberOfBundlesInPipeline').fill('70');await page.locator('#preset-update').click();await page.locator('#preset-confirm').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows[0].settings.hdtnConfig.maxNumberOfBundlesInPipeline,70);
 await page.locator('#preset-manage').click();await page.locator('#preset-name').fill('수정 이름');await page.locator('#preset-confirm').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows[0].name,'수정 이름');
 const other=await context.newPage();await other.goto(base+'/sender');await other.locator('#dtn-settings-open').click();await other.waitForFunction(()=>document.querySelectorAll('#preset-select option').length===2);assert.match(await other.locator('#preset-select').textContent(),/수정 이름/);await other.close();
 for(let i=2;i<=5;i++)rows.push({...rows[0],id:String(++id),name:'P'+i});await page.locator('#preset-refresh').click();await page.waitForFunction(()=>document.getElementById('preset-count').textContent==='5 / 5');assert.ok(await page.locator('#preset-save').isDisabled());
 await page.locator('#preset-manage').click();page.once('dialog',d=>d.accept());await page.locator('#preset-delete').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows.length,4);assert.equal(starts,0);
 job={testId:'test1',state:'COMPLETED',testType:'AFS_METADATA',senderMode:'HDTN',receiverMode:'HDTN',hdtnConfig:config,createdAt:'2026-09-23T00:00:00Z',updatedAt:'2026-09-23T00:00:00Z',dtnReceived:true};
 for(const route of ['/sender','/receiver']){
  await page.goto(base+route);await page.waitForFunction(()=>document.getElementById('trial-settings').textContent.includes('50'));
  await page.locator('#trial-settings summary').click();assert.equal(await page.locator('.trial-config-values > div').count(),10);assert.match(await page.locator('#trial-settings').innerText(),/8,589,934,592/);
  const beforeBulk = bulkRequests;
  page.once('dialog', dialog => {assert.match(dialog.message(), /3건/);dialog.accept();});
  await page.locator('#dtn-cancel-waiting').click();
  await page.waitForFunction(()=>!document.getElementById('dtn-cancel-waiting').disabled);
  assert.equal(bulkRequests,beforeBulk+1);
  assert.match(await page.locator('#dtn-log').innerText(),/종료 2건/);
  await page.locator('#dtn-log-fullscreen').click();
  await page.waitForFunction(()=>!!document.querySelector('.log-maximized'));
  const viewportLog = await page.locator('.log-maximized').boundingBox();
  assert.equal(viewportLog.x,0);assert.equal(viewportLog.y,0);
  assert.equal(Math.round(viewportLog.width),page.viewportSize().width);
  assert.equal(Math.round(viewportLog.height),page.viewportSize().height);
  await page.locator('#dtn-log-detail').click();
  await page.waitForFunction(()=>document.getElementById('dtn-log').textContent.includes('상세 확인'));
  await page.keyboard.press('Escape');assert.equal(await page.locator('.log-maximized').count(),0);
  for (const width of [1600,1100,390]) {
    await page.setViewportSize({width,height:1000});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),route+' fits '+width);
    assert.ok(await page.locator('#dtn-cancel-waiting').isVisible());
  }
  await page.setViewportSize({width:1600,height:1000});
  await page.screenshot({path:'build/frontend-review-'+route.slice(1)+'.png',fullPage:true});
 }

 const original = {constellationId:0,satelliteId:19,signalId:0,pseudorangeMeters:21000000,
   dopplerHz:-100,carrierToNoiseDbHz:45,trackingStatus:1};
 const epoch = {week:2400,receiverTowSeconds:100000,leapSeconds:18,receiverStatus:0,observations:[original]};
 const received = {...original, transmitAt:{seconds:1790000000,femtoseconds:123456789012345}};
 delete received.pseudorangeMeters;
 job.receivedEpochs=1;
 reportFixture = {
   comparisonMode:'DELAY',referenceStatus:'COMPLETE',
   referenceObservations:{epochs:[{observation:epoch}],navigation:[],navigationCount:0,records:[]},
   observations:{epochs:[{observation:{...epoch,receiverTowSeconds:100001,
     observations:[{...original,pseudorangeMeters:320792458}]}}],navigation:[],navigationCount:0,records:[],
     receivedValues:{records:[{observation:{...epoch,observations:[received]}}]}},
   delayEvidence:{delaySeconds:1},referencePvt:[],receivedPvt:[]
 };
 await page.goto(base+'/receiver');
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='데이터 일치');
 const observationPanel=page.locator('#dtn-observations');
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/21000000\.000/);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/320792458\.000/);
 assert.equal(await observationPanel.locator('td.converted-range').evaluate(el=>getComputedStyle(el).color),'rgb(180, 35, 24)');
 assert.equal(await observationPanel.locator('[data-epoch]').inputValue(),'0');
 assert.match(await observationPanel.locator('[data-epoch]').innerText(),/100000\.000/);
 assert.equal(await observationPanel.locator('[data-observations] tr').count(),1);
 await page.screenshot({path:'build/receiver-observation-review.png',fullPage:true});
 reportFixture.referenceStatus='WAITING';
 reportFixture.referenceObservations=null;
 await page.reload();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='비교 대기');
 assert.equal(await observationPanel.locator('[data-observations] td').nth(3).textContent(),'—');
 assert.equal(await observationPanel.locator('td.converted-range').textContent(),'320792458.000');
 reportFixture.referenceStatus='MISMATCH';
 await page.reload();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='데이터 불일치');
 reportFixture={referencePvt:[],receivedPvt:[]};

 await page.goto(base+'/dtn-intro');assert.equal(await page.locator('.pc-boundary').count(),2);assert.equal(await page.locator('.scope-boundary').count(),4);
 await page.locator('#data-switch').click();assert.ok(await page.locator('.tx-folder').isVisible());
 const count=await page.locator('#step-count').innerText();await page.locator('#tx-adapter').click();assert.match(await page.locator('#step-title').innerText(),/POST \/transfers/);assert.equal(await page.locator('#step-count').innerText(),count);
 await page.locator('#rx-adapter').click();assert.match(await page.locator('#step-title').innerText(),/dtn\/receive/);
 for (const width of [1600,1100,390]) {
  await page.setViewportSize({width,height:1000});
  for (const mode of ['raw','afs','iq']) {
   await page.evaluate(mode=>{state.data=mode;state.step=0;render();},mode);
   const geometry=await page.evaluate(()=>{
    const box=selector=>document.querySelector(selector).getBoundingClientRect();
    const aligned=['tx','rx'].every(side=>{
     const service=box('.'+side+'-service'), routing=box('.'+side+'-routing');
     return Math.abs(service.top-routing.top)<1 && Math.abs(service.bottom-routing.bottom)<1;
    });
    const folder=document.querySelector('.tx-folder');
    return {
     aligned,
     fits:document.documentElement.scrollWidth<=innerWidth,
     folderCorrect:state.data==='iq' ? !folder.hidden && box('.tx-folder').bottom<box('.tx-routing').top && box('.tx-folder').bottom<box('#tx-adapter').top : folder.hidden,
     noteCorrect:state.data!=='iq' || document.getElementById('clock-note').hidden,
     routersAligned:Math.abs((box('#tx-switch').left+box('#tx-switch').right)/2-(box('#rx-switch').left+box('#rx-switch').right)/2)<1
    };
   });
   assert.ok(Object.values(geometry).every(Boolean),JSON.stringify({width,mode,geometry}));
  }
 }
 await page.setViewportSize({width:1600,height:1000});
 await page.screenshot({path:'build/intro-layout-review.png',fullPage:true});
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: preset CRUD/apply/capacity/shared list, trial settings, viewport fullscreen, waiting batch cancellation, scoped intro and responsive layout.');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{await browser?.close();server.close();});
