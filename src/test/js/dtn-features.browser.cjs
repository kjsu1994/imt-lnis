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
 await page.reload();
 await page.waitForFunction(()=>document.querySelector('#dtn-observations [data-navigation-title]')?.textContent.includes('전체 항법정보 · 6건'));
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),6);
 assert.equal(await observationPanel.locator('[data-navigation-all]').isVisible(),false);
 assert.equal(await observationPanel.locator('[data-observations] tr.satellite-selected').count(),0);
 await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().click();
 assert.equal(await observationPanel.locator('[data-observations] tr.satellite-selected').count(),2);
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),4);
 assert.match(await observationPanel.locator('[data-navigation]').innerText(),/관측 이후/);
 assert.match(await observationPanel.locator('[data-navigation-summary]').innerText(),/SF1 2건 · SF2 1건 · SF3 1건/);
 assert.equal(await observationPanel.locator('[data-common-navigation]').isVisible(),true);
 await observationPanel.locator('[data-common-title]').click();
 assert.match(await observationPanel.locator('[data-common-body]').innerText(),/GPS G04/);
 await observationPanel.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),1);
 assert.match(await observationPanel.locator('[data-navigation]').innerText(),/종류 미분류/);
 await observationPanel.locator('[data-navigation-all]').click();
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),6);
 assert.equal(await observationPanel.locator('[data-common-navigation]').isVisible(),false);
 await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().focus();
 await page.keyboard.press('Enter');
 assert.equal(await observationPanel.locator('[data-navigation] tr').count(),4);
 assert.equal(await observationPanel.getByRole('button',{name:'GPS G19 항법정보 보기'}).first().evaluate(el=>el===document.activeElement),true);
 assert.equal(await observationPanel.locator('[data-navigation] details').count(),0);
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
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-pvt-status');
   const host=document.createElement('div');host.id='association-test';document.body.append(host);
   window.associationView=createObservationView(host,()=>{},'수신 원본');
 });
 const fixture=structuredClone(reportFixture.observations);
 await page.evaluate(fixture=>associationView.setData(fixture),fixture);
 const isolated=page.locator('#association-test');
 await isolated.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 await page.evaluate(fixture=>associationView.setData(fixture,true),fixture);
 assert.match(await isolated.locator('[data-navigation-title]').innerText(),/Galileo/);
 await page.evaluate(fixture=>{associationView.setData(null);associationView.setData(fixture);},fixture);
 assert.match(await isolated.locator('[data-navigation-title]').innerText(),/전체 항법정보/);
 // Sender uses the same association with source values and its own column layout.
 await page.evaluate(async fixture=>{
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-pvt-status');
   const host=document.createElement('div');host.id='sender-association-test';document.body.append(host);
   const source=structuredClone(fixture);delete source.receivedValues;
   source.epochs[0].observation.observations.push({...source.epochs[0].observation.observations[0],constellationId:2});
   createObservationView(host,()=>{},'송신 원본').setData(source);
 },fixture);
 const senderAssociation=page.locator('#sender-association-test');
 assert.equal(await senderAssociation.locator('[data-navigation-all]').isVisible(),false);
 assert.equal(await senderAssociation.locator('[data-transmit-heading]').isVisible(),false);
 assert.equal(await senderAssociation.locator('[data-observations] tr').first().locator('td').count(),11);
 await senderAssociation.getByRole('button',{name:'Galileo 19 항법정보 보기'}).click();
 assert.match(await senderAssociation.locator('[data-navigation]').innerText(),/종류 미분류/);
 await senderAssociation.locator('[data-navigation-all]').click();
 assert.equal(await senderAssociation.locator('[data-navigation] tr').count(),6);
 await senderAssociation.screenshot({path:'build/sender-navigation.png'});
 await page.evaluate(async fixture=>{
   const {createObservationView}=await import('/assets/dtn/dtn-observations.js?v=20261001-pvt-status');
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
   assert.ok(await page.locator('#evidence-detail-copy').isVisible(), mode + '/' + stage + ' detail');
   assert.ok((await page.locator('#evidence-details').innerText()).trim().length > 15);
   await page.locator('#evidence-summary').click();
   const referenceActive = await page.locator('.wire.reference.active').count();
   assert.equal(referenceActive > 0, mode !== 'iq' && stage === 'compare', mode + '/' + stage + ' reference route');
  }
 }

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
 await selectStage('afs', 'calculate');
 await openStageDetails();
 for (const seconds of [0, 0.001, 1, 20, 60]) {
  await page.locator('#delay').fill(String(seconds));
  assert.equal(await page.locator('#delay').getAttribute('aria-invalid'), 'false');
  const values = await page.locator('#satellite-rows tr').evaluateAll(rows => rows.map(row =>
   [...row.cells].slice(1).map(cell => Number(cell.textContent.replace(/[+,\s]/g, '')))));
  assert.equal(values.length, 4);
  for (const [original, added, converted] of values) {
   assert.ok(Math.abs(added - 299792458 * seconds) < 0.002, 'common delay distance');
   assert.ok(Math.abs(converted - original - added) < 0.002, 'recomputed pseudorange');
  }
 }
 for (const invalid of ['', '-1', '61', '0.0001']) {
  await page.locator('#delay').fill(invalid);
  assert.equal(await page.locator('#delay').getAttribute('aria-invalid'), 'true', 'invalid delay ' + invalid);
  assert.deepEqual(await page.locator('#satellite-rows td:nth-child(3)').allTextContents(), ['—','—','—','—']);
 }
 await page.locator('[data-delay="20"]').click();
 assert.equal(await page.locator('#delay').inputValue(), '20');
 await page.locator('#delay').focus();
 await page.keyboard.press('ArrowLeft');
 assert.equal(await currentStage(), 'calculate', 'editing delay must not navigate');
 await selectStage('afs', 'compare');
 assert.equal(await page.locator('#delay').inputValue(), '20', 'delay survives stage navigation');
 await page.locator('#tx-switch').click();
 await page.locator('#data-switch').click();
 await page.locator('#data-switch').click();
 assert.equal(await page.locator('#delay').inputValue(), '20', 'delay survives engine and route changes');

 // The two AFS variants and the send request examples remain available.
 for (const mode of ['afs', 'iq']) {
  await selectStage(mode, 'frame');
  await openStageDetails();
  await page.locator('[data-sb="4"]').click();
  const description = await page.locator('#frame-description').innerText();
  assert.match(description, mode === 'afs' ? /보정 송신 시각/ : /원본 관측값/);
  assert.match(description, mode === 'afs' ? /670/ : /506/);
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
     noteCorrect: state.data !== 'iq' || document.getElementById('clock-note').hidden,
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
 assert.deepEqual(errors,[]);await browser.close();console.log('PASS: preset CRUD/apply/capacity/shared list, trial settings, viewport fullscreen, waiting batch cancellation, scoped intro and responsive layout.');
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{await browser?.close();server.close();});
