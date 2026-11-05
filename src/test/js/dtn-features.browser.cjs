// Run with PLAYWRIGHT_MODULE pointing to an installed Playwright package.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {createServer} = require('node:http');
const {readFileSync} = require('node:fs');
const {resolve, extname} = require('node:path');
const assert = require('node:assert/strict');
const root = resolve(__dirname, '../../../build/resources/main/static');
const config = {maxNumberOfBundlesInPipeline:50,maxSumOfBundleBytesInPipeline:50000000,maxBundleSizeBytes:10485760,
  tcpclMaxSegmentSizeBytes:20000,neighborDepletedStorageDelaySeconds:10,enforceBundlePriority:false,
  storageDeletionPolicy:'DELETE_AFTER_FORWARDING',totalStorageCapacityBytes:8589934592,maxLtpReceiveUdpPacketSizeBytes:65536,acsSendPeriodMilliseconds:1000};
let browser, bulkRequests=0;
let rows=[], id=0, job=null, starts=0, reportFixture={referencePvt:[],receivedPvt:[]};
let managementRole = 'sender', policySaves = 0, managementDeletes = 0, proposedPolicies;
let retentionSettings = {tests:{enabled:false,days:7},receipts:{enabled:false,days:7},files:{enabled:false,days:30}};
let managedItems = [], managedTotal = 0, querySelections = 0, resetExecutions = 0, resetRetries = 0;
let resetView = null, resetStatusView = null, resetStatusReads = 0, failReset = false;
const managementItem = {key:{kind:'DTN',id:'management-test'},name:'GNSS_RAW',state:'COMPLETED'};
const retentionPreview = () => ({token:'retention-token',role:managementRole,
  items:[{row:managementItem,related:[],logs:1,receipts:0,evidence:0,blocked:''}]});
const server=createServer((req,res)=>{
 const path=new URL(req.url,'http://localhost').pathname;
 const json=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value));};
 if(path.startsWith('/lnis/api/v1/')){
  let body='';req.on('data',c=>body+=c);req.on('end',()=>{
   const data=body?JSON.parse(body):{};
   if(path.startsWith('/lnis/api/v1/data-management/')){
    const endpoint = path.slice('/lnis/api/v1/data-management'.length);
    if(endpoint === '/reset/status'){
     if(resetStatusView?.state === 'RUNNING' && ++resetStatusReads > 1)
      resetStatusView={...resetStatusView,state:'COMPLETED',completed:resetStatusView.total,message:'초기화 완료'};
     return json(resetStatusView);
    }
    if(endpoint === '/reset/preview'){
     resetView={token:'reset-token',mode:data.mode,role:managementRole,state:'PREVIEW',
      expiresAt:new Date(Date.now()+600000).toISOString(),
      counts:{DTN:2,RECEIPT:3,INPUT:data.mode==='SERVICE'?1:0,IQ:data.mode==='SERVICE'?1:0,PRESET:data.mode==='SERVICE'?5:0,PIN:1},
      fileBytes:data.mode==='SERVICE'?2160000000:0,retainedFiles:data.mode==='SERVICE'?2:0,
      blocked:[],waiting:{count:0,testIds:[]},completed:0,total:3,message:''};
     return json(resetView);
    }
    if(endpoint === '/reset/execute' || endpoint === '/reset/retry'){
     assert.equal(data.token,'reset-token');
     assert.equal(data.confirmation,resetView.mode==='SERVICE'?'서비스 초기화':'시험 데이터 초기화');
     if(endpoint.endsWith('/retry'))resetRetries++;else resetExecutions++;
     resetStatusReads=0;
     resetStatusView={...resetView,state:failReset?'FAILED':'RUNNING',
      message:failReset?'파일 초기화 실패 · 다시 실행하세요.':'파일 정리 중'};
     failReset=false;
     return json(resetStatusView);
    }
    if(endpoint === '/selection/preview'){
     querySelections++;
     assert.equal(data.kind,'DTN');
     return json({...retentionPreview(),fileBytes:1024,counts:{DTN:2,INPUT:1},
      excluded:[{key:{kind:'DTN',id:'protected'},blocked:'보관 고정'}]});
    }
    if(endpoint === '/summary')return json({role:managementRole,counts:{DTN:1,RECEIPT:0},databaseBytes:1024,inputBytes:0,iqBytes:0});
    if(endpoint === '/settings/preview'){proposedPolicies=data;return json({settings:data,preview:retentionPreview()});}
    if(endpoint === '/settings'){
     if(req.method === 'POST'){assert.equal(data.token,'retention-token');retentionSettings=proposedPolicies;policySaves++;}
     return json(retentionSettings);
    }
    if(endpoint === '/cleanup/preview')return json(retentionPreview());
    if(endpoint === '/delete'){assert.equal(data.token,'retention-token');managementDeletes++;return json({results:[{key:managementItem.key,status:'DELETED'}]});}
    if(endpoint === '/items')return json({items:managedItems,total:managedTotal});
    if(endpoint === '/history')return json([]);
    return json({},404);
   }
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
  const file=resolve(root, (['/sender','/lnis/dtntest/sender'].includes(path)?'dtn-sender.html':['/receiver','/lnis/dtntest/receiver'].includes(path)?'dtn-receiver.html':path==='/lnis/data-manager'?'data-management.html':path==='/dtn-intro'?'dtn-intro.html':path==='/user-manual/'?'user-manual/index.html':path.replace(/^\/lnis\//,'')).replace(/^\//,''));
  if(!file.startsWith(root+require('node:path').sep))throw Error('path');
  const content=readFileSync(file);
  res.writeHead(200,{'Content-Type':{'.js':'text/javascript','.html':'text/html','.css':'text/css','.png':'image/png','.pdf':'application/pdf'}[extname(file)]||'text/plain'});res.end(content);
 }catch{res.writeHead(404);res.end();}
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 browser=await chromium.launch();const context=await browser.newContext();
 await context.addInitScript(()=>{window.WebSocket=class {};});
 const page=await context.newPage();page.setDefaultTimeout(15000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 async function selectTrial(){await page.waitForFunction(()=>[...document.getElementById('dtn-tests').options].some(o=>o.value==='test1'));await page.locator('#dtn-tests').selectOption('test1');}
 await page.goto(base+'/sender');
 await page.waitForFunction(()=>!!document.querySelector('#dtn-observations .gnss-data-header #dtn-receiver-info'));
 assert.equal(await page.locator('#dtn-receiver-info').isVisible(),false);
 await page.evaluate(async()=>{
   const {receiverInformation}=await import('/assets/dtn/dtn-observations.js?v=20261001-gnss-info');
   const sample={receiverInfo:{model:'ZED-F9T-20B',firmware:'TIM 2.25',protocol:'29.25',
     supportedConstellations:['GPS','Galileo','BeiDou','SBAS','QZSS','NavIC']},
     epochs:Array.from({length:24},(_,i)=>({observation:{week:2438,receiverTowSeconds:201724.989+i}}))};
   document.getElementById('dtn-receiver-info-body').textContent=receiverInformation(sample);
   document.getElementById('dtn-receiver-info').hidden=false;
 });
 assert.equal(await page.locator('#dtn-receiver-info-body').isVisible(),false);
 await page.locator('#dtn-receiver-info summary').click();
 assert.match(await page.locator('#dtn-receiver-info-body').innerText(),/23.000초/);
 for(const width of [1366,390]){
   await page.setViewportSize({width,height:900});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'device info fits');
   const header=await page.locator('#dtn-observations .gnss-data-header').boundingBox();
   const receiverInfo=await page.locator('#dtn-receiver-info').boundingBox();
   assert.ok(receiverInfo.x+receiverInfo.width<=header.x+header.width+1,'receiver info stays in header');
   await page.locator('#dtn-observations .gnss-data-header').screenshot({path:'build/sender-observation-header-'+width+'.png'});
 }
 await page.setViewportSize({width:1366,height:900});
 await page.locator('#dtn-receiver-info').screenshot({path:'build/sender-device-info.png'});
 await page.locator('#dtn-receiver-info summary').click();
 await page.setViewportSize({width:1600,height:1000});
await page.locator('#dtn-settings-open').click();await page.waitForFunction(()=>!document.getElementById('preset-save').disabled);
 await page.locator('#preset-save').click();await page.locator('#preset-name').fill('기본');await page.locator('#preset-confirm').click();await page.waitForFunction(()=>document.getElementById('preset-status').textContent==='저장했습니다.');
 assert.equal(rows.length,1);assert.equal(rows[0].settings.hdtnConfig.maxNumberOfBundlesInPipeline,50);
 await page.locator('#adapter-tab-hdtn').click();
 await page.locator('#hdtn-maxNumberOfBundlesInPipeline').fill('60');await page.locator('#preset-load').click();assert.equal(await page.locator('#hdtn-maxNumberOfBundlesInPipeline').inputValue(),'50');
 await page.locator('#hdtn-maxNumberOfBundlesInPipeline').fill('70');await page.locator('#preset-update').click();await page.locator('#preset-confirm').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows[0].settings.hdtnConfig.maxNumberOfBundlesInPipeline,70);
 await page.locator('#preset-manage').click();await page.locator('#preset-name').fill('수정 이름');await page.locator('#preset-confirm').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows[0].name,'수정 이름');
 const other=await context.newPage();await other.goto(base+'/sender');await other.locator('#dtn-settings-open').click();await other.waitForFunction(()=>document.querySelectorAll('#preset-select option').length===2);assert.match(await other.locator('#preset-select').textContent(),/수정 이름/);await other.close();
 for(let i=2;i<=5;i++)rows.push({...rows[0],id:String(++id),name:'P'+i});await page.locator('#preset-refresh').click();await page.waitForFunction(()=>document.getElementById('preset-count').textContent==='5 / 5');assert.ok(await page.locator('#preset-save').isDisabled());
 await page.locator('#preset-manage').click();page.once('dialog',d=>d.accept());await page.locator('#preset-delete').click();await page.waitForFunction(()=>!document.getElementById('preset-dialog').open);assert.equal(rows.length,4);assert.equal(starts,0);
 job={testId:'test1',state:'COMPLETED',testType:'AFS_METADATA',senderMode:'HDTN',receiverMode:'HDTN',hdtnConfig:config,createdAt:'2026-09-23T00:00:00Z',updatedAt:'2026-09-23T00:00:00Z',dtnReceived:true};
 for(const route of ['/sender','/receiver']){
  await page.goto(base+route);
  assert.equal(await page.locator('#gnss-dtr, #gnss-rts, .serial-advanced').count(),0);
  if(route==='/receiver'){
   assert.equal(await page.locator('.receiver-overview > .receiver-trial-toolbar').count(),1);
   assert.equal(await page.locator('.receiver-connections > div').count(),3);
   for(const width of [1920,1366,1100,1000,820,390]){
    await page.setViewportSize({width,height:1000});
    const layout=await page.evaluate(()=>{
     const bounds=selector=>document.querySelector(selector).getBoundingClientRect().toJSON();
     return {
      connections:bounds('.receiver-connections'),
      toolbar:bounds('.receiver-trial-toolbar'),
      selection:bounds('.receiver-selection'),
      flow:bounds('.receiver-flow-container'),
      controls:['#sender-address-test','#dtn-adapter-health','#gnss-refresh'].map(bounds),
      groups:[...document.querySelectorAll('.receiver-connections > div')].map(e=>e.getBoundingClientRect().toJSON())
     };
    });
    assert.ok(layout.toolbar.y>=layout.connections.bottom,'trial controls stay below connections at '+width);
    assert.ok(Math.abs(layout.flow.x-layout.toolbar.x)<2,'receiver steps start at the left edge at '+width);
    assert.ok(layout.flow.y>=layout.selection.bottom,'receiver steps follow the trial selection at '+width);
    assert.ok(Math.abs(layout.toolbar.width-layout.connections.width)<2,'trial controls span the card at '+width);
    if(width>1200){
     assert.ok(layout.controls.every(b=>b.width<100),'connection buttons keep their content width');
     assert.ok(Math.abs(layout.controls[0].y-layout.controls[2].y)<6,'connection controls align');
    }
    for(let i=0;i<layout.groups.length;i++){
     const a=layout.groups[i];
     for(const b of layout.groups.slice(i+1)){
      assert.ok(a.right<=b.x+1||b.right<=a.x+1||a.bottom<=b.y+1||b.bottom<=a.y+1,'connection groups do not overlap');
     }
    }
   }
   await page.setViewportSize({width:1600,height:1000});
  }

  assert.equal(await page.locator('#dtn-observations [data-pvt-filter]').isChecked(),true);
  await page.waitForFunction(()=>[...document.getElementById('dtn-tests').options].some(o=>o.value==='test1'));
  await page.locator('#dtn-tests').selectOption('test1');
  await page.waitForTimeout(300);

  const manualLink = page.getByRole('link', {name:'사용자 설명서 · 새 탭에서 열기'});
  assert.equal(await manualLink.getAttribute('href'), '/user-manual/');
  assert.equal(await manualLink.getAttribute('target'), '_blank');
  assert.match(await page.locator('.site-footer').innerText(), /© 2026 imtplus\. All rights reserved\./);
  assert.match(await page.locator('.software-version').innerText(), /^v\d+\.\d+\.\d+$/);
  assert.notEqual(await page.locator('.site-footer').evaluate(e=>getComputedStyle(e).position), 'fixed');
  const popupPromise = page.waitForEvent('popup');
  await manualLink.click();
  const manualPage = await popupPromise;
  await manualPage.waitForLoadState();
  assert.match(await manualPage.title(), /사용자 설명서/);
  assert.equal(await manualPage.getByRole('link', {name:'PDF 다운로드'}).getAttribute('href'), 'lnis-user-manual.pdf');
  await manualPage.close();
  const pdfResponse = await context.request.get(base+'/user-manual/lnis-user-manual.pdf');
  assert.equal(pdfResponse.status(), 200);
  assert.ok((await pdfResponse.body()).subarray(0,5).equals(Buffer.from('%PDF-')));

  await page.locator('#dtn-trial-config-btn').click();
  await page.waitForFunction(()=>document.getElementById('trial-settings-dialog').open);
  assert.equal(await page.locator('.trial-param-card').count(),9);
  assert.match(await page.locator('#trial-settings-dialog-body').textContent(),/8,589,934,592/);
  await page.locator('#trial-settings-dialog-close').click();
  await page.locator('#dtn-cancel-waiting').evaluate(el=>el.hidden=false);
  const beforeBulk = bulkRequests;
  page.once('dialog', dialog => {assert.match(dialog.message(), /3건/);dialog.accept();});
  await page.locator('#dtn-cancel-waiting').click();
  await page.waitForFunction(()=>!document.getElementById('dtn-cancel-waiting').disabled);
  assert.equal(bulkRequests,beforeBulk+1);
  assert.match(await page.locator('#dtn-log').innerText(),/종료 2건/);
  await page.locator('#dtn-log-fullscreen').click();
  await page.waitForFunction(()=>!!document.querySelector('.log-maximized'));
  assert.ok(await page.locator('.user-manual-entry').isHidden());
  assert.ok(await page.locator('.site-footer').isHidden());
  const viewportLog = await page.locator('.log-maximized').boundingBox();
  assert.equal(viewportLog.x,0);assert.equal(viewportLog.y,0);
  assert.equal(Math.round(viewportLog.width),page.viewportSize().width);
  assert.equal(Math.round(viewportLog.height),page.viewportSize().height);
  await page.locator('#dtn-log-detail').click();
  await page.waitForFunction(()=>document.getElementById('dtn-log').textContent.includes('상세 확인'));
  await page.keyboard.press('Escape');assert.equal(await page.locator('.log-maximized').count(),0);
  assert.ok(await page.locator('.user-manual-entry').isVisible());
  assert.ok(await page.locator('.site-footer').isVisible());
  for (const width of [1600,1100,390]) {
    await page.setViewportSize({width,height:1000});
    const overflow=await page.evaluate(()=>[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth+1).map(e=>({tag:e.tagName,id:e.id,cls:e.className,right:e.getBoundingClientRect().right})).slice(0,20));
    if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))console.log(JSON.stringify({route,width,overflow}));
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),route+' fits '+width);
    assert.ok(await page.locator('#dtn-cancel-waiting').isVisible());

    await page.evaluate(()=>scrollTo(0,0));
    const helpGeometry = await page.evaluate(()=>{
      const help=document.querySelector('.user-manual-entry').getBoundingClientRect();
      const intro=document.querySelector('a[href="/dtn-intro"]').getBoundingClientRect();
      const brand=document.querySelector('.dtn-brand').getBoundingClientRect();
      const header=document.querySelector('header').getBoundingClientRect();
      return help.top>=intro.bottom && help.bottom<=header.bottom && help.right<brand.left
        && Math.abs(help.left-intro.left)<1 && help.width===intro.width && help.height===intro.height;
    });
    assert.ok(helpGeometry,route+' manual icon below intro at '+width);
    await page.screenshot({path:'build/manual-entry-'+route.slice(1)+'-'+width+'.png'});
    await page.locator('.site-footer').scrollIntoViewIfNeeded();
    const footerGeometry = await page.evaluate(()=>{
      const link=document.querySelector('.user-manual-entry').getBoundingClientRect();
      const settings=document.getElementById('dtn-settings-open')?.getBoundingClientRect();
      const overlap=(a,b)=>a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top;
      const text=[...document.querySelector('.site-footer').children].map(e=>e.getBoundingClientRect());
      return {linkFits:link.left>=0&&link.right<=innerWidth,
        noOverlap:text.every(r=>!settings||!overlap(settings,r))};
    });
    assert.ok(footerGeometry.linkFits && footerGeometry.noOverlap, JSON.stringify({route,width,footerGeometry}));
    await page.screenshot({path:'build/footer-'+route.slice(1)+'-'+width+'.png'});

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
 await page.goto(base+'/receiver');await selectTrial();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='데이터 일치').catch(async e=>{console.log(JSON.stringify({errors,observations:await page.locator('#dtn-observations').innerText(),logs:await page.locator('#dtn-log').innerText()}));throw e;});
 const observationPanel=page.locator('#dtn-observations');
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/21000000\.000/);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/320792458\.000/);
 assert.equal(await observationPanel.locator('td.converted-range').evaluate(el=>getComputedStyle(el).color),'rgb(180, 35, 24)');
 assert.equal(await observationPanel.locator('select[data-epoch]').count(),0);
 assert.equal(await page.locator('select#pvt-epoch').count(),0);
 assert.equal(await observationPanel.locator('[data-record-details]').count(),0);
 assert.equal(await observationPanel.locator('[data-navigation-pvt-active]').count(),0);
 assert.match(await observationPanel.locator('[data-epoch]').innerText(),/100000\.000/);
 assert.equal(await observationPanel.locator('[data-observations] tr').count(),1);
 await page.screenshot({path:'build/receiver-observation-review.png',fullPage:true});
 reportFixture.referenceStatus='WAITING';
 reportFixture.referenceObservations=null;
 await page.reload();await selectTrial();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='비교 대기');
 assert.equal(await observationPanel.locator('[data-observations] td').nth(3).textContent(),'—');
 assert.equal(await observationPanel.locator('td.converted-range').textContent(),'320792458.000');
 reportFixture.referenceStatus='MISMATCH';
 await page.reload();await selectTrial();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='데이터 불일치');

 // Satellite association: same PRN in another constellation and multiple signals.
 const nav = (gnss, sv, sf, common=false) => ({sequence:100+sf,
   capturedAt:'2026-10-01T00:00:00Z',
   message:{constellationId:gnss,satelliteId:sv,signalId:0,frequencyId:0,sfrbxVersion:2,words:[0x22c00000,0,0,0,0,0,0,0,0,0]},
   display:gnss===0?{subframeId:sf,commonCorrection:common}:undefined});
 const navRows=[nav(0,19,1),nav(0,19,2),nav(0,19,3),nav(2,19,1),nav(0,4,4,true),nav(0,19,1)];
 const records=[{type:'NAVIGATION_UPDATE'},{type:'NAVIGATION_UPDATE'},{type:'NAVIGATION_UPDATE'},
   {type:'NAVIGATION_UPDATE'},{type:'NAVIGATION_UPDATE'},{type:'OBSERVATION_EPOCH'},{type:'NAVIGATION_UPDATE'}];
 const receivedSignals=[{...received,constellationId:2},received,{...received,signalId:3}];
 reportFixture.observations.navigation=navRows;
 reportFixture.observations.navigationCount=navRows.length;
 reportFixture.observations.records=records;
 reportFixture.observations.receivedValues.records[0].observation.observations=receivedSignals;
 await page.reload();await selectTrial();
 assert.equal(await observationPanel.locator('[data-pvt-filter]').isChecked(),true);
 await observationPanel.locator('[data-pvt-filter]').uncheck();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-navigation-title]')?.textContent.includes('전체 항법정보 · 6건'));
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),6);
 assert.equal(await observationPanel.locator('[data-navigation-all]').isVisible(),false);
 assert.equal(await observationPanel.locator('[data-observations] tr.satellite-selected').count(),0);
 await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().click();
 assert.equal(await observationPanel.locator('[data-observations] tr.satellite-selected').count(),2);
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),4);
 assert.match(await observationPanel.locator('[data-navigation]').innerText(),/선택 Epoch 이후 수신/);
 assert.match(await observationPanel.locator('[data-navigation-summary]').innerText(),/SF1 2건 · SF2 1건 · SF3 1건/);
 assert.equal(await observationPanel.locator('[data-common-navigation]').isVisible(),true);
 await observationPanel.locator('[data-common-title]').click();
 assert.match(await observationPanel.locator('[data-common-body]').innerText(),/GPS 04/);
 await observationPanel.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),1);
 assert.match(await observationPanel.locator('[data-navigation]').innerText(),/현재 PVT 계산 대상 아님/);
 await observationPanel.locator('[data-navigation-all]').click();
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),6);
 assert.equal(await observationPanel.locator('[data-common-navigation]').isVisible(),false);
 await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().focus();
 await page.keyboard.press('Enter');
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),4);
 assert.equal(await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().evaluate(el=>el===document.activeElement),true);
 assert.equal(await observationPanel.locator('[data-navigation] pre').first().isVisible(),true);
 assert.match(await observationPanel.locator('[data-navigation]').innerText(),/수집 순번 101/);
 for(const width of [1366,390]){
   await page.setViewportSize({width,height:900});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await observationPanel.screenshot({path:'build/receiver-navigation-'+width+'.png'});
 }
 await observationPanel.locator('[data-pvt-status-label]').click();
 assert.equal(await observationPanel.locator('[data-pvt-status-reasons]').isVisible(),true);
 await observationPanel.locator('[data-pvt-status-label]').click();
 // Refresh preserves selection; a different trial resets via setData(null).
 await page.evaluate(async()=>{
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-gnss-info');
   const host=document.createElement('div');host.id='association-test';document.body.append(host);
   window.associationView=createObservationView(host,()=>{},'수신 원본');
 });
 const fixture=structuredClone(reportFixture.observations);
 await page.evaluate(fixture=>associationView.setData(fixture),fixture);
 const isolated=page.locator('#association-test');
 assert.equal(await isolated.locator('[data-pvt-filter]').isChecked(),true);
 await isolated.locator('[data-pvt-filter]').uncheck();
 await isolated.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 await page.evaluate(fixture=>associationView.setData(fixture,true),fixture);
 assert.match(await isolated.locator('[data-navigation-title]').innerText(),/Galileo/);
 await page.evaluate(fixture=>{associationView.setData(null);associationView.setData(fixture);},fixture);
 assert.match(await isolated.locator('[data-navigation-title]').innerText(),/전체 항법정보/);
 // Sender uses the same association with source values and its own column layout.
 await page.evaluate(async fixture=>{
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-gnss-info');
   const host=document.createElement('div');host.id='sender-association-test';document.body.append(host);
   const source=structuredClone(fixture);delete source.receivedValues;
   source.epochs[0].observation.observations.push({...source.epochs[0].observation.observations[0],constellationId:2});
   createObservationView(host,()=>{},'송신 원본').setData(source);
 },fixture);
 const senderAssociation=page.locator('#sender-association-test');
 assert.equal(await senderAssociation.locator('[data-pvt-filter]').isChecked(),true);
 await senderAssociation.locator('[data-pvt-filter]').uncheck();
 await senderAssociation.locator('[data-navigation-pvt-active]').uncheck();
 assert.equal(await senderAssociation.locator('[data-navigation-all]').isVisible(),false);
 assert.equal(await senderAssociation.locator('[data-transmit-heading]').isVisible(),false);
 assert.equal(await senderAssociation.locator('[data-observations] tr').first().locator('td').count(),11);
 await senderAssociation.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 assert.match(await senderAssociation.locator('[data-navigation]').innerText(),/현재 PVT 계산 대상 아님/);
 await senderAssociation.locator('[data-navigation-all]').click();
 assert.equal(await senderAssociation.locator('[data-navigation] tr').count(),6);
 await senderAssociation.screenshot({path:'build/sender-navigation.png'});
 await page.evaluate(async fixture=>{
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-gnss-info');
   const host=document.getElementById('sender-association-test');
   const source=structuredClone(fixture);delete source.receivedValues;
   createObservationView(host,()=>{},'송신 비교원본').setData(source);
 },fixture);
 assert.equal(await senderAssociation.locator('[data-transmit-heading]').isVisible(),false);
 assert.equal(await senderAssociation.locator('[data-observations] tr').first().locator('td').count(),11);
 assert.equal(await senderAssociation.locator('[data-navigation] details').count(),0);
 assert.equal(await senderAssociation.locator('[data-navigation] pre').first().isVisible(),true);
 assert.match(await senderAssociation.locator('[data-navigation]').innerText(),/SF1/);
 await page.evaluate(()=>document.getElementById('sender-association-test').remove());
 // AFS restored data must never show synthetic collection times/order as real.
 fixture.receivedValues={observations:receivedSignals};
 await page.evaluate(fixture=>associationView.setData(fixture),fixture);
 assert.match(await isolated.locator('[data-navigation]').innerText(),/프레임 복원/);
 assert.equal(await isolated.locator('[data-navigation] details').count(),0);
 assert.equal(await isolated.locator('[data-navigation] pre').first().isVisible(),true);
 assert.doesNotMatch(await isolated.locator('[data-navigation]').innerText(),/수집 순번|2026-10-01/);
 // IQ words remain 24-bit; non-GPS rows remain unclassified.
 delete fixture.receivedValues;
 fixture.source='IQ_TRACKING';fixture.assistance='AFS SB2 · 복원';
 fixture.navigation[0].message.words=[0x8b0000,0];
 await page.evaluate(fixture=>associationView.setData(fixture),fixture);
 assert.equal(await isolated.locator('[data-navigation] details').count(),0);
 assert.equal(await isolated.locator('[data-navigation] pre').first().isVisible(),true);
 assert.match(await isolated.locator('[data-navigation]').innerText(),/24 bit/);
 assert.match(await isolated.locator('[data-navigation]').innerText(),/8B0000 000000/);
 await page.evaluate(()=>document.getElementById('association-test').remove());
 await page.setViewportSize({width:1600,height:1000});

 reportFixture={referencePvt:[],receivedPvt:[]};


 // The actual receiver page shows decoded I/Q data even without a PVT result.
 const iqSignals=[received,{...received,satelliteId:23}].map(value=>({...value,week:2400,towSeconds:100000}));
 const iqNavigation=[...navRows.slice(0,3),...navRows.slice(0,3).map(row=>({...row,message:{...row.message,satelliteId:23}}))];
 job={...job,testType:'IQ_SAMPLE',state:'INCONCLUSIVE',dtnReceived:true,receivedEpochs:0,fileResult:{verdict:'PASS'}};
 reportFixture={testType:'IQ_SAMPLE',comparisonMode:'DELAY',fileResult:{verdict:'PASS'},comparison:{verdict:'INCONCLUSIVE'},
   referenceObservations:{epochs:[{observation:epoch}]},referencePvt:[],receivedPvt:[],
   observations:{source:'IQ_FRAME',epochs:[{observation:{...epoch,observations:[{...original,pseudorangeMeters:320792458},
     {...original,satelliteId:23,pseudorangeMeters:321792458}]}}],navigation:iqNavigation,navigationCount:6,
     records:[...iqNavigation.map(()=>({type:'NAVIGATION_UPDATE'})),{type:'OBSERVATION_EPOCH'}],
     receivedValues:{observations:iqSignals},trackingEpochs:[{elapsedSeconds:21,observations:[
       {satelliteId:19,pseudorangeMeters:21000001,dopplerHz:-101,carrierToNoiseDbHz:44,carrierPhaseCycles:123.5}]},{elapsedSeconds:22,observations:[{satelliteId:19,pseudorangeMeters:21000002,dopplerHz:-102,carrierToNoiseDbHz:43,carrierPhaseCycles:124.5},
      {satelliteId:31,pseudorangeMeters:23000002,dopplerHz:100,carrierToNoiseDbHz:42,carrierPhaseCycles:200}]}]}};
 const receivedReport=structuredClone(reportFixture), receivedJob={...job};
 job={...job,state:'WAITING_DTN',referenceEpochs:1,updatedAt:'2026-11-02T00:00:00Z'};
 reportFixture={referencePvt:[{week:2400,towSeconds:100000,positionValid:true,velocityValid:true,
   ecefMeters:[1,2,3],velocityMetersPerSecond:[4,5,6],satellitesUsed:4,receiverClockBiasSeconds:1e-12}],
   observations:{epochs:[{observation:epoch}],navigation:navRows,navigationCount:navRows.length,records}};
 await page.goto(base+'/sender');await selectTrial();
 await page.waitForFunction(()=>document.getElementById('pvt-x').textContent==='1.000');
 assert.match(await page.locator('#dtn-observations [data-observations]').innerText(),/21000000\.000/);
 await page.locator('#dtn-observations').screenshot({path:'build/iq-sender-history.png'});
 reportFixture.observations=null;
 await page.reload();await selectTrial();
 await page.waitForFunction(()=>document.getElementById('pvt-message').textContent.includes('관측값이 저장되지'));
 assert.equal(await page.locator('#pvt-x').textContent(),'1.000');
 assert.doesNotMatch(await page.locator('#dtn-observations [data-observations]').innerText(),/21000000/);
 job=receivedJob;reportFixture=receivedReport;
 await page.goto(base+'/receiver');await selectTrial();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='파일 일치 · PVT 비교 불가');
 assert.equal(await observationPanel.isVisible(),true);
 assert.equal(await page.locator('#receiver-iq').count(),0);
 assert.equal(await observationPanel.locator('[data-observations] tr').count(),2);
 assert.equal(await observationPanel.locator('[data-navigation-pvt-active]').count(),0);
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),6);
 assert.equal(await observationPanel.locator('[data-navigation] pre').first().isVisible(),true);
 assert.equal(await observationPanel.locator('[data-range-heading]').isVisible(),false);
 assert.equal(await observationPanel.locator('[data-phase-heading]').isVisible(),false);
 assert.doesNotMatch(await observationPanel.locator('[data-observations]').innerText(),/21000000\.000/);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/320792458\.000/);
 assert.equal(await observationPanel.locator('td.converted-range').first().evaluate(el=>getComputedStyle(el).color),'rgb(180, 35, 24)');
 assert.equal(await observationPanel.locator('[data-iq-tracking-epoch]').isVisible(),true);
 assert.equal(await observationPanel.locator('[data-iq-tracking-body]').count(),0);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/21000001\.000/);
 await observationPanel.locator('[data-iq-tracking-epoch]').selectOption('1');
 assert.equal(await observationPanel.locator('[data-observations] tr').count(),3);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/21000002\.000/);
 assert.doesNotMatch(await observationPanel.locator('[data-observations]').innerText(),/23000002\.000/);
 await observationPanel.locator('[data-pvt-filter]').uncheck();
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/23000002\.000/);
 assert.match(await observationPanel.locator('[data-observations]').innerText(),/진단용/);
 assert.equal(await observationPanel.locator('td.converted-range').first().textContent(),'320792458.000');
 for(const width of [1366,390]){
  await page.setViewportSize({width,height:900});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'I/Q observations fit '+width);
  await observationPanel.screenshot({path:'build/iq-received-observations-'+width+'.png'});
 }
 await page.setViewportSize({width:1600,height:1000});
 for(const [file,pvt,label,tone] of [['PASS','MEASURED','파일 일치 · PVT 측정 완료','online'],
   ['PASS','FAIL','파일 일치 · PVT 불일치','error'],['FAIL','MEASURED','파일 불일치 · PVT 측정 완료','error'],
   [null,'MEASURED','파일 검증 미확인 · PVT 측정 완료','warning']]){
  reportFixture.fileResult={verdict:file};reportFixture.comparison={verdict:pvt};
  await page.reload();await selectTrial();
  await page.waitForFunction(label=>document.querySelector('#dtn-observations [data-integrity]')?.textContent===label,label);
  assert.equal(await observationPanel.locator('[data-integrity]').getAttribute('class'),'pill '+tone);
 }
 // RF trials compare against the RF baseline while preserving the sender reference.
 const baseline={week:2400,towSeconds:100000,positionValid:true,velocityValid:true,
   ecefMeters:[-3049086,4046274,3861625],velocityMetersPerSecond:[1,2,3],satellitesUsed:5,receiverClockBiasSeconds:0};
 const rfFixture={testType:'IQ_SAMPLE',comparisonMode:'DELAY',comparisonBasis:'RF_BASELINE',
   fileResult:{verdict:'PASS'},referencePvt:[{...baseline,ecefMeters:[-3048000,4046000,3861000]}],
   rfBaselinePvt:[baseline],receivedPvt:[{...baseline,towSeconds:100001,receiverClockBiasSeconds:1}],
   delayEvidence:{timing:{startedAt:'2026-11-03T00:00:00Z',receivedAt:'2026-11-03T00:00:01Z'},delaySeconds:1,
     originalTime:{week:2400,towSeconds:100000},shiftedTime:{week:2400,towSeconds:100001},satellites:[]},
   comparison:{verdict:'MEASURED',epochs:[{week:2400,towSeconds:100001,positionDifferenceMeters:0,
     velocityDifferenceMetersPerSecond:0,clockDifferenceSeconds:1,clockResidualSeconds:0}]},
   observations:{...receivedReport.observations,source:'IQ_RF',selectedElapsedSeconds:21,
     receivedValues:{observations:iqSignals.map(o=>({...o,pseudorangeMeters:21000000}))}}};
 reportFixture=process.env.LNIS_RF_REPORT ? JSON.parse(readFileSync(process.env.LNIS_RF_REPORT,'utf8')) : rfFixture;
 job={...job,testType:'IQ_SAMPLE',state:'COMPLETED',receivedEpochs:1};
 await page.reload();await selectTrial();
 await page.waitForFunction(()=>document.getElementById('receiver-pvt-title').textContent.includes('RF PVT'));
 assert.match(await observationPanel.locator('[data-range-heading]').innerText(),/RF 추적/);
 assert.ok(await observationPanel.locator('[data-range-heading]').isVisible());
 assert.ok(await observationPanel.locator('td.converted-range').first().isVisible());
 assert.equal(await observationPanel.locator('td.converted-range').first().evaluate(el=>getComputedStyle(el).color),'rgb(180, 35, 24)');
 assert.equal(await page.locator('#reference-x').textContent(),reportFixture.rfBaselinePvt[0].ecefMeters[0].toFixed(3));
 assert.equal(await page.locator('#iq-original-reference').isVisible(),true);
 await page.locator('#iq-original-reference summary').click();
 assert.ok((await page.locator('#iq-original-reference-values').innerText()).includes(String(reportFixture.referencePvt[0].ecefMeters[0])));
 await page.locator('#iq-original-reference summary').click();
 assert.match(await observationPanel.locator('[data-source]').innerText(),/RF 관측/);
 assert.equal(await page.locator('[data-shifted-label]').innerText(),'지연 반영 PVT');
 for(const width of [1920,1366,390]) {
  await page.setViewportSize({width,height:900});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'RF result fits '+width);
  await observationPanel.screenshot({path:'build/iq-rf-observations-'+width+'.png'});
 }
 await page.setViewportSize({width:1366,height:900});
 await page.locator('.receiver-pvt').screenshot({path:'build/iq-rf-pvt.png'});
 await page.setViewportSize({width:1600,height:1000});
 reportFixture=receivedReport;
 // I/Q must not leave hidden columns or filters on a subsequent AFS trial.
 job={...job,testType:'AFS_METADATA',receivedEpochs:1};
 reportFixture={comparisonMode:'DELAY',referenceStatus:'COMPLETE',referenceObservations:{epochs:[{observation:epoch}]},
   referencePvt:[],receivedPvt:[],observations:{...reportFixture.observations,source:undefined}};
 await page.reload();await selectTrial();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-integrity]')?.textContent==='데이터 일치');
 assert.equal(await observationPanel.locator('[data-range-heading]').isVisible(),true);
 assert.equal(await observationPanel.locator('[data-phase-heading]').isVisible(),true);
 assert.equal(await observationPanel.locator('[data-navigation-pvt-active]').count(),0);

 await page.goto(base + '/dtn-intro');
 assert.equal(await page.locator('.pc-boundary').count(), 2);
 assert.equal(await page.locator('.scope-boundary').count(), 4);
 assert.equal(await page.locator('#evidence-details').evaluate(el => el.open), false);
 assert.match(await page.locator('#step-copy').innerText(), /관측/);
 assert.match(await page.locator('#step-copy').innerText(), /PVT|Reference/);
 assert.equal(await page.locator('.outcome, .learn, #step-owner, #step-takeaway').count(), 0);

 assert.equal(await page.locator('#gnss-fields').isVisible(), false, 'GNSS detail is collapsed initially');
 await page.locator('#evidence-summary').click();
 const gnssDetails = await page.locator('#gnss-fields').innerText();
 for (const sample of ['GPS G04 / L1 C/A', 'week 2438', '201724.989', '20,886,574.963', '+3,712.078', '27 dB-Hz', '109,759,724.342', '52.520 s']) {
  assert.ok(gnssDetails.includes(sample), 'verified UBX sample: ' + sample);
 }
 assert.match(gnssDetails, /현재 PVT 계산 입력에는 사용하지 않습니다/);
 assert.match(gnssDetails, /항법정보가 모두 확보됐다는 뜻은 아닙니다/);
 await page.locator('#evidence-summary').click();


 const selectStage = async (mode, stage) => {
  await page.evaluate(({mode, stage}) => {
   state.data = mode;
   goToStep(steps().indexOf(stage));
  }, {mode, stage});
 };
 const captureIntro = async path => {
  await page.mouse.move(2, 2);
  await page.waitForTimeout(250); // Let short stage/hover transitions settle before visual review.
  await page.screenshot({path,fullPage:true});
 };
 const currentStage = () => page.evaluate(() => steps()[state.step]);
 const openStageDetails = async () => {
  if (!await page.locator('#evidence-details').evaluate(el => el.open)) {
   await page.locator('#evidence-summary').click();
  }
 };
 const routeCounts = {raw:8, afs:9, iq:11};

 for (const mode of ['raw', 'afs', 'iq']) {
  await selectStage(mode, 'source');
  const route = await page.evaluate(() => steps());
  assert.equal(route.length, routeCounts[mode], mode + ' stage count');
  for (const stage of route) {
   await selectStage(mode, stage);
   assert.ok(await page.locator('#step-title').isVisible(), mode + '/' + stage + ' title');
   assert.ok((await page.locator('#step-copy').innerText()).trim(), mode + '/' + stage + ' copy');
   assert.equal(await page.locator('#evidence-details').evaluate(el => el.open), false, mode + '/' + stage + ' starts collapsed');
   await openStageDetails();
   assert.ok(await page.locator(stage === 'frame' ? '#frame-explorer' : '.evidence-detail-body').isVisible(), mode + '/' + stage + ' detail');
   assert.ok((await page.locator('#evidence-details').innerText()).trim().length > 15);
   if (mode === 'iq' && stage === 'decode') {
    const detail = await page.locator('#evidence-details').innerText();
    assert.match(detail, /RF 추적 의사거리/);
    assert.match(detail, /같은 RF Epoch.*지연 미적용·적용 PVT/);
    assert.doesNotMatch(detail, /SB4 원본 관측값은.*절대 전달되지/);
   }
   if (stage === 'compare') {
    assert.match(await page.locator('#evidence-details').innerText(), /Clock Bias 잔차/);
   }
   assert.doesNotMatch(await page.locator('#evidence-details').innerText(), /0\.000 mm|오인식 확률이 사실상 0/);
   await page.locator('#evidence-summary').click();
   const referenceActive = await page.locator('.wire.reference.active').count();
   assert.equal(referenceActive > 0, mode !== 'iq' && stage === 'compare', mode + '/' + stage + ' reference route');
  }
 }

 // On-screen arrows share keyboard navigation and stop at route boundaries.
 await selectStage('afs','source');
 assert.equal(await page.locator('#step-prev').isDisabled(),true);
 await page.locator('#step-next').click();
 assert.equal(await currentStage(),'data');
 await page.locator('#step-prev').click();
 assert.equal(await currentStage(),'source');
 await selectStage('iq','compare');
 assert.equal(await page.locator('#step-next').isDisabled(),true);
 await page.locator('#step-prev').click();
 assert.equal(await currentStage(),'calculate');

 // Documentation distinguishes the official frame structure from LNIS contents.
 await selectStage('afs', 'frame');
 await openStageDetails();
 for (let block=0; block<=4; block++) {
  await page.locator('[data-sb="'+block+'"]').click();
  const text=await page.locator('#sb-detail-view').innerText();
  assert.doesNotMatch(text,/NASA 표준 부합|107₈|MSG-G6|CC63F74536F49E04A0|동일 제공/);
  assert.equal(await page.locator('#sb-detail-view thead th').count(),5);
  if(block===0) assert.match(text,/CC63F74536F49E04A/);
  if(block===1) assert.match(text,/763₈/);
  if(block===3) assert.match(text,/구현하지 않/);
 }
 for(const mode of ['raw','afs','iq']) {
  await selectStage(mode,'send');
  await openStageDetails();
  const request=JSON.parse(await page.locator('#request-json').innerText());
  assert.equal(request.hdtnConfig.maxSumOfBundleBytesInPipeline,3000000000);
  assert.equal(request.hdtnConfig.maxBundleSizeBytes,3000000000);
  assert.equal(request.hdtnConfig.tcpclMaxSegmentSizeBytes,200000);
  assert.equal(request.hdtnConfig.storageDeletionPolicy,'never');
  assert.equal(await page.locator('#send-engine-fields [data-iq-only]').isVisible(),mode==='iq');
  await selectStage(mode,'receive');
  await openStageDetails();
  assert.equal(await page.locator('#receive-engine-fields [data-iq-only]').isVisible(),mode==='iq');
  assert.doesNotMatch(await page.locator('#evidence-details').innerText(),/영구|인입된 모든 본문/);
 }
 await selectStage('afs','restore');
 await openStageDetails();
 assert.match(await page.locator('#restore-engine-fields').innerText(),/BCH 오류 정정을 수행하지/);
 await selectStage('iq','decode');
 await openStageDetails();
 assert.match(await page.locator('#decode-engine-fields').innerText(),/복호 후/);

 // Route and engine switches preserve the current semantic stage.
 await selectStage('afs', 'calculate');
 await page.locator('#tx-switch').click();
 assert.equal(await currentStage(), 'calculate');
 await page.locator('#rx-switch').click();
 assert.equal(await currentStage(), 'calculate');
 await page.locator('#data-switch').click();
 assert.equal(await currentStage(), 'calculate');
 assert.equal(await page.evaluate(() => state.data), 'iq');
 await page.locator('#data-switch').click();
 assert.equal(await currentStage(), 'calculate');
 assert.equal(await page.evaluate(() => state.data), 'raw');
 await selectStage('iq', 'decode');
 await page.locator('#data-switch').click();
 assert.equal(await currentStage(), 'restore', 'removed decode continues to restoration');
 await selectStage('iq', 'encode');
 await page.locator('#data-switch').click();
 assert.equal(await currentStage(), 'send', 'removed encoding continues to sending');

 // Arrow keys navigate the diagram, but do not steal keys from data entry.
 await selectStage('afs', 'source');
 await page.locator('body').click({position:{x:4,y:4}});
 await page.keyboard.press('ArrowRight');
 assert.equal(await currentStage(), 'data');
 await page.keyboard.press('ArrowLeft');
 assert.equal(await currentStage(), 'source');
 for (const mode of ['afs','iq']) {
  await selectStage(mode, 'calculate');
  await openStageDetails();
  assert.equal(await page.locator('#pvt-time-system-card').isVisible(),true);
  assert.match(await page.locator('#pvt-time-system-card').innerText(),/시각/);
 }

 // The two AFS variants and the send request examples remain available.
 for (const mode of ['afs', 'iq']) {
  await selectStage(mode, 'frame');
  await openStageDetails();
  await page.locator('[data-sb="4"]').click();
  const description = await page.locator('#frame-description').innerText();
  assert.match(description, /가상 송신 시각/);
  assert.match(description, /670/);
 }
 for (const mode of ['raw', 'afs', 'iq']) {
  await selectStage(mode, 'send');
  await openStageDetails();
  assert.ok(await page.locator('#request-json').isVisible());
  const request = JSON.parse(await page.locator('#request-json').innerText());
  assert.equal(request.testType, {raw:'GNSS_RAW', afs:'AFS_METADATA', iq:'IQ_SAMPLE'}[mode]);
 }

 // Inspecting virtual links highlights their own routes without moving the stage.
 await selectStage('iq', 'calculate');
 for (const [selector, endpoint] of [['#tx-adapter', /POST \/transfers/], ['#rx-adapter', /dtn\/receive/], ['.tx-folder', /BIN|공유|exchange/], ['.rx-folder', /BIN|공유|exchange/], ['.management-link', /REST|원본|등록/]]) {
  const before = await page.locator('#step-count').innerText();
  await page.locator(selector).click();
  assert.equal(await page.locator('#step-count').innerText(), before);
  assert.ok(await page.locator('.wire.active').count() > 0, selector + ' route highlights');
  await openStageDetails();
  assert.match((await page.locator('#step-copy').innerText()) + ' ' + (await page.locator('#evidence-details').innerText()), endpoint);
 }

 await selectStage('afs', 'network');
 const moving = await page.evaluate(() => ({
  wireAnimation: [...document.querySelectorAll('.wire.active')].some(el => getComputedStyle(el).animationName !== 'none'),
  dotCount: document.querySelectorAll('.traveler animateMotion').length
 }));
 assert.equal(moving.wireAnimation, false, 'only traveler dots animate, not the wire stroke');
 assert.ok(moving.dotCount > 0, 'active edge shows moving data');
 await page.emulateMedia({reducedMotion:'reduce'});
 assert.ok(await page.evaluate(() => [...document.querySelectorAll('.traveler')].every(el =>
  getComputedStyle(el).display === 'none' || getComputedStyle(el).visibility === 'hidden' || !el.querySelector('animateMotion'))),
  'reduced motion suppresses moving dots');
 await page.emulateMedia({reducedMotion:'no-preference'});

 if (await page.evaluate(() => document.fullscreenEnabled)) {
  await page.locator('#fullscreen').click();
  await page.waitForFunction(() => !!document.fullscreenElement);
  await page.locator('#fullscreen').click();
  await page.waitForFunction(() => !document.fullscreenElement);
 }

 for (const width of [1600,1100,390]) {
  await page.setViewportSize({width,height:1000});
  for (const mode of ['raw','afs','iq']) {
   await selectStage(mode, 'source');
   const geometry = await page.evaluate(() => {
    const box = selector => document.querySelector(selector).getBoundingClientRect();
    const aligned = ['tx','rx'].every(side => {
     const service = box('.' + side + '-service'), routing = box('.' + side + '-routing');
     return Math.abs(service.top - routing.top) < 1 && Math.abs(service.bottom - routing.bottom) < 1;
    });
    const folder = document.querySelector('.tx-folder');
    return {
     aligned,
     fits: document.documentElement.scrollWidth <= innerWidth,
     folderCorrect: state.data === 'iq' ? !folder.hidden && box('.tx-folder').bottom < box('.tx-routing').top && box('.tx-folder').bottom < box('#tx-adapter').top : folder.hidden,
     noteCorrect: !document.getElementById('clock-note').hidden && document.getElementById('clock-note').textContent.includes('Δt'),
     routersAligned: Math.abs((box('#tx-switch').left + box('#tx-switch').right) / 2 - (box('#rx-switch').left + box('#rx-switch').right) / 2) < 1
    };
   });
   assert.ok(Object.values(geometry).every(Boolean), JSON.stringify({width,mode,geometry}));
   await selectStage(mode, mode === 'iq' ? 'frame' : 'calculate');
   await openStageDetails();
   assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'expanded details fit ' + width + '/' + mode);
  }
 }

 for (const viewport of [{width:1366,height:900},{width:1366,height:768},{width:390,height:844}]) {
  await page.setViewportSize(viewport);
  const heights = [];
  for (const mode of ['afs','iq','raw']) {
   await selectStage(mode, 'source');
   heights.push((await page.locator('#scene').boundingBox()).height);
  }
  assert.ok(Math.max(...heights) - Math.min(...heights) < 1, 'switching I/Q preserves diagram height: ' + JSON.stringify(viewport));
  await selectStage('afs', 'source');
  if (viewport.height === 900) {
   const explanation = await page.locator('.explanation').boundingBox();
   assert.ok(explanation.y + explanation.height <= viewport.height, 'initial diagram and brief fit 1366x900');
  }
  await captureIntro('build/intro-' + viewport.width + '-' + viewport.height + '.png');
 }
 await page.setViewportSize({width:1366,height:900});
 await selectStage('afs', 'source');
 await openStageDetails();
 await captureIntro('build/intro-gnss-values.png');
 await page.setViewportSize({width:1600,height:1000});
 await selectStage('afs', 'source');
 await captureIntro('build/intro-layout-review.png');
 await selectStage('afs', 'calculate');
 await openStageDetails();
 await captureIntro('build/intro-calculation-review.png');

 // The next transfer and selected history are separate sources of truth.
 const typed = await context.newPage();
 await typed.setViewportSize({width:1366,height:900});
 typed.on('pageerror', error => errors.push(error.message));
 job={testId:'test1',state:'WAITING_DTN',testType:'GNSS_RAW',senderMode:'DTN',receiverMode:'HDTN',
   createdAt:'2026-10-28T00:00:00Z',updatedAt:'2026-10-28T00:00:00Z',receivedEpochs:0};
 await typed.goto(base+'/sender');
 await typed.waitForFunction(()=>document.querySelector('#dtn-tests option[value="test1"]'));
 await typed.locator('#dtn-settings-open').click();
 for(const [type,label] of [['GNSS_RAW','RAW'],['AFS_METADATA','AFS'],['IQ_SAMPLE','I/Q']]){
  await typed.locator('.test-type-button[data-test-type="'+type+'"]').click();
  assert.equal(await typed.locator('#hud-type').innerText(),label);
 }
 await typed.locator('#dtn-settings-close').click();
 await typed.locator('#dtn-tests').selectOption('test1');
 await typed.waitForFunction(()=>document.getElementById('dtn-test-status').textContent.includes('RAW'));
 assert.match(await typed.locator('#dtn-config-hud').innerText(),/다음 전송/);
 assert.equal(await typed.locator('#hud-type').innerText(),'I/Q');
 assert.match(await typed.locator('#dtn-test-status').innerText(),/선택 시험 · RAW/);
 assert.match(await typed.locator('#dtn-tests option[value="test1"]').innerText(),/^\[RAW\]/);
 await typed.screenshot({path:'build/trial-type-sender.png',fullPage:true});
 for(const [type,label,short] of [['GNSS_RAW','GNSS RAW','RAW'],['AFS_METADATA','AFS Frame','AFS'],
   ['IQ_SAMPLE','I/Q Sample','I/Q'],[null,'유형 미확인','미확인']]){
  job={...job,testType:type};
  await typed.goto(base+'/receiver');
  await typed.waitForFunction(()=>document.querySelector('#dtn-tests option[value="test1"]'));
  await typed.locator('#dtn-tests').selectOption('test1');
  await typed.waitForFunction(expected=>document.getElementById('receiver-type').textContent===expected,label);
  assert.ok((await typed.locator('#dtn-tests option[value="test1"]').innerText()).startsWith('['+short+']'));
  assert.match(await typed.locator('#receive-state').innerText(),/외부 번들 수신 대기/);
 }
 await typed.screenshot({path:'build/trial-type-receiver.png',fullPage:true});
 await typed.close();
 // Multi-round target uses real SVG/DOM with mocked persisted API records.
 const historyPage=await context.newPage();
 historyPage.on('pageerror',e=>errors.push(e.message));
 const historyKey='A'.repeat(64), otherHistoryKey='B'.repeat(64);
 const historyReference={week:2400,towSeconds:1000,positionValid:true,velocityValid:true,
   ecefMeters:[6378137,0,0],velocityMetersPerSecond:[0,0,0],receiverClockBiasSeconds:0,satellitesUsed:6};
 let historyTrials=Array.from({length:240},(_,i)=>{
   const e=i<2?0.002:i===3?0:0.012*Math.cos(i*0.73);
   const n=i<2?0.003:i===3?0:0.012*Math.sin(i*0.73);
   return {testId:'history-'+String(i+1).padStart(3,'0'),state:'COMPLETED',senderMode:i%2?'HDTN':'DTN',
     receiverMode:i%4<2?'DTN':'HDTN',startedAt:new Date(Date.UTC(2026,10,5,0,0,i)).toISOString(),
     receivedAt:new Date(Date.UTC(2026,10,5,0,0,i+1)).toISOString(),delaySeconds:1,
     availability:'AVAILABLE',ecefMeters:[6378137+(i===1?2:0.001),e,n]};
 });
 const historyJob=(id,key=historyKey)=>({testId:id,pvtHistoryKey:key,testType:'GNSS_RAW',
   selectedEpoch:{recordIndex:4,week:2400,towSeconds:1000},state:'COMPLETED',
   createdAt:'2026-11-05T00:00:00Z',updatedAt:'1',dtnReceived:true,receivedEpochs:1,referenceEpochs:1,
   receivedPayloadAvailable:true,receivedOriginalAvailable:true,senderMode:'DTN',receiverMode:'HDTN'});
 let historyList=[historyJob('history-001'),historyJob('other-group',otherHistoryKey),
   {...historyJob('waiting-round'),state:'WAITING_DTN',receivedEpochs:0,dtnReceived:false},
   {...historyJob('legacy-iq'),testType:'IQ_SAMPLE',pvtHistoryKey:null}];
 let historyReads=0, waitingCompleted=false, historyFailure=false, holdHistory=false, historyRelease=null;
 await historyPage.route('**/lnis/api/v1/dtn/tests**',async route=>{
   const path=new URL(route.request().url()).pathname;
   if(path.endsWith('/tests')) return route.fulfill({json:historyList});
   if(path.endsWith('/pvt-history')){
     historyReads++;
     const other=path.includes('/other-group/');
     if(historyFailure)return route.fulfill({status:500,json:{message:'history unavailable'}});
     if(holdHistory&&!other)await new Promise(resolve=>historyRelease=resolve);
     const trials=other?[{...historyTrials[2],testId:'other-group'}]:historyTrials;
     return route.fulfill({json:{historyKey:other?otherHistoryKey:historyKey,testType:'GNSS_RAW',
       selectedEpoch:{recordIndex:4,week:2400,towSeconds:1000},referenceEcefMeters:[6378137,0,0],
       total:trials.length+(other||waitingCompleted?0:1),waiting:other||waitingCompleted?0:1,unavailable:0,trials}});
   }
   const match=path.match(/\/tests\/([^/]+)(?:\/(report|payload\/received))?$/);
   if(!match)return route.continue();
   const id=match[1], row=historyTrials.find(p=>p.testId===id)||historyTrials[2];
   const j=historyList.find(p=>p.testId===id)||historyJob(id);
   if(match[2]==='payload/received')return route.fulfill({json:{testId:id,format:'fixture'}});
   if(match[2]==='report')return route.fulfill({json:{
     ...j,referenceStatus:'COMPLETE',comparisonMode:'DELAY',referencePvt:[historyReference],
     receivedPvt:[{...historyReference,towSeconds:1001,ecefMeters:row.ecefMeters,receiverClockBiasSeconds:1}],
     delayEvidence:{delaySeconds:1,timing:{startedAt:row.startedAt,receivedAt:row.receivedAt}},
     comparison:{verdict:'MEASURED',epochs:[{week:2400,towSeconds:1001,positionDifferenceMeters:0.01,
       velocityDifferenceMetersPerSecond:0,clockDifferenceSeconds:1,clockResidualSeconds:0}]},
     observations:{epochs:[],navigation:[],records:[]}
   }});
   return route.fulfill({json:j});
 });
 await historyPage.setViewportSize({width:1366,height:900});
 await historyPage.goto(base+'/receiver');
 await historyPage.waitForFunction(()=>document.querySelector('#dtn-tests option[value="history-001"]'));
 await historyPage.locator('#dtn-tests').selectOption('history-001');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===240);
 assert.match(await historyPage.locator('#pvt-history-context').innerText(),/표시 240 \/ 등록 241 · 대기 1/);
 const position=await historyPage.locator('#pvt-history-points circle[data-test-id="history-001"]')
   .evaluate(el=>({x:Number(el.getAttribute('cx')),y:Number(el.getAttribute('cy'))}));
 assert.ok(position.x>0&&position.y<0,'east is right and north is up');
 assert.equal(await historyPage.locator('#pvt-history-legend span').count(),4);
 const scope=historyPage.locator('#pvt-geodetic-visualizer');
 await scope.screenshot({path:'build/pvt-history-inline.png'});
 await historyPage.locator('#pvt-history-expand').click();
 assert.equal(await scope.getAttribute('role'),'dialog');
 let bounds=await scope.boundingBox();
 assert.ok(bounds.width>=1340&&bounds.height>=875,'enlarged HUD fills viewport');
 await historyPage.locator('#pvt-history-points circle[data-test-id="history-001"]').click();
 await historyPage.waitForFunction(()=>!document.getElementById('pvt-history-picker').hidden);
 await historyPage.locator('#pvt-history-picker-list button[title="history-002"]').click();
 await historyPage.waitForFunction(()=>document.getElementById('test-id').textContent==='history-002');
 assert.equal(await historyPage.locator('#dtn-tests').inputValue(),'history-002');
 await historyPage.waitForFunction(()=>document.getElementById('pvt-clock-change').textContent.includes('1.000'));
 await historyPage.waitForFunction(()=>document.querySelector('#dtn-payload textarea').value.includes('history-002'));
 assert.equal(await historyPage.locator('#pvt-history-points circle').count(),240);
 assert.equal(await historyPage.locator('#pvt-history-points circle.selected').getAttribute('data-test-id'),'history-002');
 await scope.screenshot({path:'build/pvt-history-expanded.png'});
 await historyPage.keyboard.press('Escape');
 assert.equal(await scope.getAttribute('role'),null);
 assert.equal(await historyPage.locator('#dtn-tests').inputValue(),'history-002');
 await historyPage.locator('#pvt-history-zoom-in').click();
 assert.match(await historyPage.locator('#pvt-history-summary').innerText(),/수동/);
 await historyPage.locator('#pvt-history-auto').click();
 assert.equal(await historyPage.locator('#pvt-history-auto').getAttribute('aria-pressed'),'true');

 await historyPage.locator('#pvt-history-points circle.selected').focus();
 await historyPage.keyboard.press('ArrowRight');
 await historyPage.keyboard.press('Enter');
 assert.equal(await historyPage.locator('#pvt-history-picker').isVisible(),true);
 await historyPage.keyboard.press('Escape');
 assert.equal(await historyPage.locator('#pvt-history-picker').isVisible(),false);
 await historyPage.locator('#dtn-tests').selectOption('waiting-round');
 await historyPage.waitForFunction(()=>document.getElementById('test-id').textContent==='waiting-round');
 assert.equal(await historyPage.locator('#pvt-history-points circle').count(),240,'waiting selection preserves previous points');
 assert.equal(await historyPage.locator('.pvt-details-section').isVisible(),false,'waiting selection does not retain prior PVT details');
 waitingCompleted=true;
 historyTrials.push({...historyTrials[0],testId:'waiting-round',ecefMeters:[6378137,2,0]});
 historyList=historyList.map(p=>p.testId==='waiting-round'?{...p,state:'COMPLETED',dtnReceived:true,receivedEpochs:1,updatedAt:'2'}:p);
 await historyPage.locator('#dtn-refresh').click();
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===241);
 const initialRadius=await historyPage.locator('#pvt-history-summary').innerText();
 assert.match(initialRadius,/2.000 m/);
 await historyPage.locator('#dtn-tests').selectOption('history-001');
 historyTrials.push({...historyTrials[2],testId:'arrived-later'});
 historyList.push(historyJob('arrived-later'));
 await historyPage.locator('#dtn-refresh').click();
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===242);
 assert.equal(await historyPage.locator('#dtn-tests').inputValue(),'history-001','new arrival must not replace a selected trial');
 await historyPage.locator('#pvt-history-zoom-in').click();
 await historyPage.locator('#pvt-history-zoom-in').click();
 assert.match(await historyPage.locator('#pvt-history-summary').innerText(),/범위 밖 1개/);
 await historyPage.locator('#pvt-history-auto').click();
 await historyPage.locator('#dtn-tests').selectOption('other-group');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===1);
 await historyPage.locator('#dtn-tests').selectOption('history-001');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===242);
 for(const width of [1366,390]){
   await historyPage.setViewportSize({width,height:900});
   await historyPage.locator('#pvt-history-expand').click();
   await historyPage.screenshot({path:'build/pvt-history-expanded-'+width+'.png'});
   assert.ok(await scope.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'HUD fits '+width);
   await historyPage.keyboard.press('Tab');
   assert.ok(await scope.evaluate(el=>el.contains(document.activeElement)),'keyboard focus stays within enlarged HUD');
   const thumb=await historyPage.locator('#alt-gauge-thumb-val').boundingBox();
   const panel=await scope.boundingBox();
   assert.ok(thumb.x>=panel.x&&thumb.x+thumb.width<=panel.x+panel.width,'vertical value stays inside the enlarged HUD');
   await historyPage.keyboard.press('Escape');
   assert.ok(await scope.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'inline HUD fits '+width);
 }
 await historyPage.setViewportSize({width:1366,height:900});
 historyFailure=true;
 await historyPage.locator('#dtn-refresh').click();
 await historyPage.waitForFunction(()=>document.getElementById('pvt-history-status').textContent.includes('조회 실패'));
 assert.equal(await historyPage.locator('#pvt-history-points circle').count(),242,'query failure preserves existing points');
 historyFailure=false;
 holdHistory=true;
 const heldRequest=historyPage.waitForRequest(r=>r.url().includes('/pvt-history'));
 await historyPage.locator('#dtn-refresh').click();
 await heldRequest;
 await historyPage.waitForTimeout(100);
 await historyPage.locator('#dtn-tests').selectOption('other-group');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===1);
 holdHistory=false;historyRelease();
 await historyPage.waitForTimeout(100);
 assert.equal(await historyPage.locator('#pvt-history-points circle').count(),1,'late previous-group data must be ignored');
 await historyPage.locator('#dtn-tests').selectOption('history-001');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===242);
 const removedRound=historyTrials.find(p=>p.testId==='history-240');
 historyTrials=historyTrials.filter(p=>p.testId!=='history-240');
 await historyPage.locator('#dtn-refresh').click();
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===241);
 historyTrials.push(removedRound);
 await historyPage.locator('#dtn-tests').selectOption('legacy-iq');
 await historyPage.waitForFunction(()=>document.getElementById('test-id').textContent==='legacy-iq');
 assert.equal(await historyPage.locator('#pvt-history-header').isVisible(),false);
 assert.equal(await historyPage.locator('#enu-target-point').isVisible(),true,'I/Q keeps its existing single-point renderer');
 await historyPage.reload();
 await historyPage.waitForFunction(()=>document.querySelector('#dtn-tests option[value="history-001"]'));
 await historyPage.locator('#dtn-tests').selectOption('history-001');
 await historyPage.waitForFunction(()=>document.querySelectorAll('#pvt-history-points circle').length===242);
 assert.ok(historyReads>2);
 await historyPage.close();

 // COM capture: progress works without WebSocket, download is optional, and trials remain manual.
 const capturedPage=await context.newPage();
 capturedPage.on('pageerror',e=>errors.push(e.message));
 let captureRequests=[], captureFinished=false, captureFailed=false, downloads=0, targetEpochs=10;
 const capturePvt=()=>Array.from({length:targetEpochs},(_,i)=>({week:2438,towSeconds:1000+i,
   positionValid:true,velocityValid:true,ecefMeters:[1,2,3],velocityMetersPerSecond:[4,5,6],
   receiverClockBiasSeconds:0,satellitesUsed:6,message:'PVT 계산 완료'}));
 const captureObservations=()=>({navigation:[],navigationCount:0,records:[],
   epochs:capturePvt().map((p,i)=>({recordIndex:i,observation:{week:p.week,receiverTowSeconds:p.towSeconds,
     leapSeconds:18,receiverStatus:1,rawxVersion:1,observations:[{pseudorangeMeters:21000000+i,
       carrierPhaseCycles:42,dopplerHz:-100,constellationId:0,satelliteId:3,signalId:0,frequencyId:0,
       lockTimeMillis:64500,carrierToNoiseDbHz:40,pseudorangeStdDev:1,carrierPhaseStdDev:1,
       dopplerStdDev:1,trackingStatus:3}]}}))});
 await capturedPage.route('**/lnis/api/v1/node/gnss',r=>r.fulfill({json:{
   state:'CONNECTED',portName:'COM4',baudRate:38400,timeState:'VALID',capturing:false}}));
 await capturedPage.route('**/lnis/api/v1/node/gnss/ports',r=>r.fulfill({json:[{name:'COM4',description:'GNSS'}]}));
 await capturedPage.route('**/lnis/api/v1/captures',async r=>{
   const body=r.request().postDataJSON();captureRequests.push(body);
   targetEpochs=body.epochCount;captureFinished=false;
   await r.fulfill({json:{inputId:'captured-input',complete:false}});
 });
 await capturedPage.route('**/lnis/api/v1/inputs/captured-input',r=>r.fulfill({json:{
   inputId:'captured-input',kind:'GNSS_CAPTURE',complete:captureFinished,ubxAvailable:captureFinished,
   captureProgress:{stage:captureFailed?'CaptureFailed':'EpochCollected',
     message:captureFailed?'수집 연결 끊김':'유효 Epoch '+Math.min(3,targetEpochs)+' / '+targetEpochs,
     counters:{validEpochs:Math.min(3,targetEpochs),targetEpochs,rawxEpochs:12,navigationMessages:20}}}}));
 await capturedPage.route('**/lnis/api/v1/dtn/inputs/captured-input/**',r=>{
   const part=new URL(r.request().url()).pathname.split('/').pop();
   const data=part==='observations'?captureObservations():part==='pvt'?capturePvt():
     capturePvt().map((p,i)=>({epoch:{recordIndex:i,week:p.week,towSeconds:p.towSeconds},reference:p,afsReady:true}));
   return r.fulfill({json:data});
 });
 await capturedPage.route('**/lnis/api/v1/captures/captured-input/ubx',r=>r.fulfill({
   contentType:'application/octet-stream',headers:{'Content-Disposition':'attachment; filename="capture.ubx"'},
   body:Buffer.from([0xb5,0x62,2,0x15])}));
 capturedPage.on('download',()=>downloads++);
 await capturedPage.setViewportSize({width:1366,height:900});
 await capturedPage.goto(base+'/sender');
 await capturedPage.locator('#dtn-settings-open').click();
 await capturedPage.locator('.input-mode[data-input-mode="capture"]').click();
 await capturedPage.locator('#dtn-settings-close').click();
 assert.equal(await capturedPage.locator('#capture-epoch-target').inputValue(),'10');
 await capturedPage.waitForFunction(()=>!document.getElementById('dtn-start').disabled);
 for(const bad of ['0','1.5','51']){
   await capturedPage.locator('#capture-epoch-target').fill(bad);
   await capturedPage.locator('#dtn-start').click();
   assert.equal(captureRequests.length,0);
 }
 await capturedPage.locator('#capture-epoch-target').fill('10');
 await capturedPage.locator('#dtn-start').click();
 await capturedPage.waitForFunction(()=>document.getElementById('capture-epoch-count').textContent==='유효 Epoch 3 / 10');
 await capturedPage.waitForFunction(()=>document.getElementById('capture-elapsed').textContent!=='0초');
 assert.equal(captureRequests[0].epochCount,10);
 assert.equal(await capturedPage.locator('#dtn-message').innerText(),'GNSS 수집 중');
 assert.equal(await capturedPage.locator('#capture-epoch-target').isDisabled(),true);
 assert.match(await capturedPage.locator('#dtn-capture-status').innerText(),/수신 RAWX 12/);
 assert.equal(await capturedPage.locator('#capture-ubx-download').isVisible(),false);
 for(const width of [1366,390]){
   await capturedPage.setViewportSize({width,height:900});
   assert.ok(await capturedPage.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'capture progress fits '+width);
   await capturedPage.locator('.sender-actions').screenshot({path:'build/capture-progress-'+width+'.png'});
 }
 captureFinished=true;
 await capturedPage.waitForFunction(()=>document.getElementById('dtn-input-state').textContent==='수집 완료 · 유효 Epoch 10개');
 assert.equal(await capturedPage.locator('#capture-ubx-download').isVisible(),true);
 assert.equal(downloads,0,'capture must not force a download');
 const downloadPromise=capturedPage.waitForEvent('download');
 await capturedPage.locator('#capture-ubx-download').click();
 await downloadPromise;assert.equal(downloads,1);
 assert.equal(captureObservations().epochs.length,10);
 const epochSelect=capturedPage.locator('#dtn-observations select[data-epoch]');
 assert.equal(await epochSelect.locator('option').count(),10);
 await epochSelect.selectOption('2');
 assert.match(await capturedPage.locator('#dtn-gnss-time').innerText(),/1002/);
 await capturedPage.setViewportSize({width:1366,height:900});
 for(const count of [1,3,50]){
   await capturedPage.locator('#capture-epoch-target').fill(String(count));
   await capturedPage.locator('#dtn-start').click();
   await capturedPage.waitForFunction(n=>document.getElementById('capture-epoch-count').textContent==='유효 Epoch '+Math.min(3,n)+' / '+n,count);
   captureFinished=true;
   await capturedPage.waitForFunction(n=>document.getElementById('dtn-input-state').textContent==='수집 완료 · 유효 Epoch '+n+'개',count);
 }
 captureFailed=true;
 await capturedPage.locator('#dtn-start').click();
 await capturedPage.waitForFunction(()=>document.getElementById('dtn-input-state').textContent==='수집 미완료');
 assert.match(await capturedPage.locator('#capture-stage').innerText(),/수집 연결 끊김/);
 assert.equal(await capturedPage.locator('#capture-ubx-download').isVisible(),false);
 const stoppedClock=await capturedPage.locator('#capture-elapsed').innerText();
 await capturedPage.waitForTimeout(1100);
 assert.equal(await capturedPage.locator('#capture-elapsed').innerText(),stoppedClock);
 await capturedPage.close();
 // Retention settings use mocked APIs; no production settings or records are changed.
 const management = await context.newPage();
 management.on('pageerror', error => errors.push(error.message));
 await management.setViewportSize({width:1366,height:900});
 await management.goto(base+'/sender');
 const managementEntry = management.getByRole('link',{name:'데이터 관리',exact:true});
 assert.equal(await managementEntry.getAttribute('target'),null);
 await managementEntry.click();
 await management.waitForURL(base+'/lnis/data-manager');
 await management.waitForFunction(()=>document.getElementById('node').textContent.includes('송신 PC'));
 await management.getByRole('button',{name:'보관 설정',exact:true}).click();
 await management.waitForFunction(()=>document.querySelectorAll('.policy').length===3);
 assert.equal(await management.locator('#settings-save').isDisabled(),true);
 assert.equal(await management.locator('#cleanup-now').isDisabled(),true);
 assert.equal(await management.locator('#tests-period').isDisabled(),true);
 const retentionSwitch = management.getByRole('switch',{name:'종료된 DTN 시험 자동 삭제',exact:true});
 await retentionSwitch.focus();
 await management.keyboard.press('Space');
 assert.equal(await retentionSwitch.isChecked(),true);
 assert.equal(await management.locator('#tests-period').isEnabled(),true);
 await management.locator('#tests-period').selectOption('custom');
 const customDays = management.locator('#tests-days');
 for(const invalid of ['','0','3651','1.5']){
  await customDays.fill(invalid);
  assert.equal(await management.locator('#settings-save').isDisabled(),true);
 }
 await customDays.fill('14');
 assert.equal(await management.locator('#settings-save').isEnabled(),true);
 assert.equal(await management.locator('#cleanup-now').isDisabled(),true);
 for(const width of [1366,390]){
  await management.setViewportSize({width,height:900});
  assert.ok(await management.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'retention settings fit '+width);
  await management.locator('#settings').screenshot({path:'build/management-retention-'+width+'.png'});
 }
 await management.locator('#settings-save').click();
 await management.waitForFunction(()=>document.getElementById('confirm').open);
 assert.equal(await management.locator('#confirm').evaluate(el=>el.open),true);
 assert.match(await management.locator('#confirm-warning').innerText(),/다음 자동 정리/);
 assert.equal(policySaves,0);
 assert.equal(managementDeletes,0);
 await management.locator('#confirm-cancel').click();
 assert.equal(await management.locator('#settings-save').isEnabled(),true);
 await management.locator('#settings-save').click();
 await management.waitForFunction(()=>document.getElementById('confirm').open);
 await management.locator('#confirm-apply').click();
 await management.waitForFunction(()=>document.getElementById('settings-save').disabled);
 assert.equal(policySaves,1);
 assert.equal(retentionSettings.tests.days,14);
 assert.equal(managementDeletes,0,'saving retention settings must not delete records');
 assert.equal(await management.locator('#cleanup-now').isEnabled(),true);
 await management.locator('#cleanup-now').click();
 await management.waitForFunction(()=>document.getElementById('confirm').open);
 assert.equal(await management.locator('#confirm-apply').getAttribute('class'),'danger');
 assert.equal(managementDeletes,0,'cleanup requires confirmation');
 await management.locator('#confirm-cancel').click();
 await management.locator('#cleanup-now').click();
 await management.waitForFunction(()=>document.getElementById('confirm').open);
 await management.locator('#confirm-apply').click();
 await management.waitForFunction(()=>document.getElementById('message').textContent.includes('삭제 1건'));
 assert.equal(managementDeletes,1);
 await management.locator('#brand-home').click();
 await management.waitForURL(base+'/lnis/dtntest/sender');
 managementRole='receiver';
 await management.goto(base+'/receiver');
 await management.getByRole('link',{name:'데이터 관리',exact:true}).click();
 await management.waitForURL(base+'/lnis/data-manager');
 await management.waitForFunction(()=>document.getElementById('node').textContent.includes('수신 PC'));
 assert.equal(new URL(await management.locator('#brand-home').getAttribute('href'),base).pathname,'/lnis/dtntest/receiver');
 await management.locator('#brand-home').click();
 await management.waitForURL(base+'/lnis/dtntest/receiver');
 // Query-wide selection is preserved when paging and does not delete before confirmation.
 managedItems=[{...managementItem,createdAt:'2026-11-01T00:00:00Z',bytes:0,pinned:false,blocked:''}];
 managedTotal=503;
 await management.getByRole('link',{name:'데이터 관리',exact:true}).click();
 await management.waitForURL(base+'/lnis/data-manager');
 await management.waitForFunction(()=>document.getElementById('count').textContent.includes('503'));
 await management.locator('#select-results').click();
 await management.waitForFunction(()=>document.getElementById('selection-count').textContent.includes('503'));
 await management.locator('#next').click();
 await management.waitForFunction(()=>document.getElementById('page').textContent.startsWith('2 /'));
 assert.match(await management.locator('#selection-count').innerText(),/503/);
 const deletionsBeforePreview=managementDeletes;
 await management.locator('#delete-selected').click();
 await management.waitForFunction(()=>document.getElementById('confirm').open);
 assert.equal(querySelections,1);
 assert.match(await management.locator('.preview-summary').innerText(),/삭제 파일 1.0 KB/);
 assert.match(await management.locator('.preview-excluded summary').innerText(),/삭제 제외 1건/);
 assert.equal(managementDeletes,deletionsBeforePreview);
 await management.locator('#confirm-cancel').click();
 await management.locator('#clear-selection').click();
 await management.waitForFunction(()=>document.getElementById('selection-count').textContent==='');
 await management.getByRole('button',{name:'초기화',exact:true}).click();
 await management.locator('#reset-preview').click();
 await management.waitForFunction(()=>!document.getElementById('reset-summary').hidden);
 assert.match(await management.locator('#reset-summary').innerText(),/GNSS 입력.*유지/);
 assert.equal(resetExecutions,0);
 await management.locator('#reset-confirm-open').click();
 await management.locator('#reset-phrase').fill('초기화');
 await management.locator('#reset-acknowledge').check();
 assert.equal(await management.locator('#reset-confirm-run').isDisabled(),true);
 await management.locator('#reset-phrase').fill('시험 데이터 초기화');
 assert.equal(await management.locator('#reset-confirm-run').isEnabled(),true);
 await management.locator('#reset-confirm-cancel').click();
 assert.equal(resetExecutions,0);
 await management.locator('#reset-mode-service').check();
 await management.locator('#reset-preview').click();
 await management.waitForFunction(()=>document.getElementById('reset-summary').textContent.includes('프리셋'));
 assert.match(await management.locator('#reset-summary').innerText(),/소유 미확인/);
 for(const width of [1366,390]){
  await management.setViewportSize({width,height:900});
  assert.ok(await management.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'reset layout fits '+width);
  await management.locator('#reset').screenshot({path:'build/management-reset-'+width+'.png'});
 }
 failReset=true;
 await management.locator('#reset-confirm-open').click();
 await management.locator('#reset-acknowledge').check();
 await management.locator('#reset-phrase').fill('서비스 초기화');
 await management.locator('#reset-confirm-run').click();
 await management.waitForFunction(()=>document.getElementById('reset-state').textContent==='초기화 미완료');
 assert.equal(resetExecutions,1);
 assert.equal(await management.getByRole('button',{name:'시험 기록',exact:true}).isDisabled(),true);
 assert.equal(await management.locator('#reset-retry').isVisible(),true);
 await management.locator('#reset-retry').click();
 await management.locator('#reset-acknowledge').check();
 await management.locator('#reset-phrase').fill('서비스 초기화');
 await management.locator('#reset-confirm-run').click();
 await management.waitForFunction(()=>document.getElementById('reset-state').textContent==='초기화 완료');
 assert.equal(resetRetries,1);
 assert.equal(await management.locator('#reset-home').isVisible(),true);
 assert.equal(await management.getByRole('button',{name:'시험 기록',exact:true}).isEnabled(),true);
 await management.close();
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: preset CRUD/apply/capacity/shared list, trial settings, viewport fullscreen, waiting batch cancellation, scoped intro, retention confirmation and same-tab navigation, responsive layout.');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{await browser?.close();server.close();});
