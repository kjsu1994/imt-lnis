// 설명서 생성과 화면 검사. 운영 서비스에 접속하지 않는다.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

let chromium;
try {
  ({chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright'));
} catch {
  throw new Error('PLAYWRIGHT_MODULE에 기존 Playwright 설치 경로를 지정하세요.');
}
const root = path.resolve(__dirname,'..');
const source = path.join(root,'manual/index.html');
const output = path.join(root,'build/distributions/user-manual.pdf');
const reports = path.join(root,'build/reports/user-manual');

(async () => {
  fs.mkdirSync(path.dirname(output),{recursive:true});
  fs.mkdirSync(reports,{recursive:true});
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000},locale:'ko-KR'});
    const errors = [], external = [];
    page.on('pageerror',error => errors.push(error.message));
    await page.route(/^https?:/,route => {
      external.push(route.request().url());
      return route.abort();
    });

    const svg = fs.readFileSync(path.join(root,'src/main/resources/static/assets/common/transfer-mark.svg'),'utf8');
    await page.setContent('<style>body{margin:0}svg{display:block;width:512px;height:512px}</style>' + svg);
    const logo = await page.locator('svg').screenshot({omitBackground:true});
    const logoFile = path.join(root,'manual/assets/transfer-mark.png');
    if (!fs.existsSync(logoFile) || !fs.readFileSync(logoFile).equals(logo)) fs.writeFileSync(logoFile,logo);

    await page.goto(pathToFileURL(source).href);
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all([...document.images].map(image => image.decode().catch(() => {})));
    });
    const documentCheck = await page.evaluate(() => {
      const ids = [...document.querySelectorAll('[id]')].map(node => node.id);
      return {
        pages:document.querySelectorAll('.sheet').length,
        duplicateIds:ids.filter((id,index) => ids.indexOf(id) !== index),
        brokenLinks:[...document.querySelectorAll('a[href^="#"]')].map(a => a.getAttribute('href'))
          .filter(href => !document.getElementById(href.slice(1))),
        brokenImages:[...document.images].filter(image => !image.complete || !image.naturalWidth
          || (!image.alt && !image.closest('.manual-brand'))).map(image => image.src),
        unbranded:[...document.querySelectorAll('.sheet')].filter(sheet => sheet.querySelectorAll('.manual-kari').length !== 1).map(sheet => sheet.id)
      };
    });
    assert.ok(documentCheck.pages > 0);
    for (const key of ['duplicateIds','brokenLinks','brokenImages','unbranded']) assert.deepEqual(documentCheck[key],[],key);
    assert.equal(await page.locator('.sheet:visible').count(),1);
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'quick');
    assert.equal(await page.locator('.manual-version').innerText(),'v1.0');
    assert.equal(await page.evaluate(() => document.compatMode),'CSS1Compat');
    assert.match(await page.title(),/^GNSS 전송 시험/);
    const originalText = await page.locator('.sheet').evaluateAll(sheets => sheets.map(sheet => sheet.textContent));
    const originalStrong = await page.locator('.sheet strong,.sheet b').count();

    const find = async query => {
      await page.getByLabel('문서 검색',{exact:true}).fill(query);
      await page.waitForFunction(query => {
        const input = document.getElementById('search'), count = document.getElementById('matches');
        if (!query) return !count.textContent;
        return input.value === query && count.textContent.includes('곳')
          && [...document.querySelectorAll('#manual mark[data-manual-hit]')].every(mark =>
            mark.textContent.trim().length > 0);
      },query);
      // 같은 검색 횟수의 두 검색어 사이에서도 새 결과가 그려진 뒤 검사한다.
      await page.waitForTimeout(160);
    };
    await find('pvt');
    let check = await page.evaluate(() => {
      const ids = [...new Set([...document.querySelectorAll('#manual mark')].map(mark => mark.dataset.manualHit))];
      let expected = 0;
      document.querySelectorAll('.sheet').forEach(sheet => {
        const copy = sheet.cloneNode(true);
        copy.querySelectorAll('.page-foot,.eyebrow,#printed-toc,[data-doc-version],[data-doc-date]').forEach(node => node.remove());
        expected += (copy.textContent.match(/pvt/gi) || []).length;
      });
      return {expected,actual:ids.length,counter:document.getElementById('matches').textContent,
        active:[...new Set([...document.querySelectorAll('#manual .current-match')].map(mark => mark.dataset.manualHit))]};
    });
    assert.equal(check.actual,check.expected,'count every occurrence, including hidden topics');
    assert.deepEqual(check.active,['0']);
    assert.match(check.counter,new RegExp('^1 / ' + check.expected + '곳'));
    assert.ok(await page.locator('#result-list .result-link').count() > 1);
    await page.getByRole('button',{name:'다음 검색 결과',exact:true}).click();
    await page.waitForFunction(() => document.querySelector('#manual .current-match')?.dataset.manualHit === '1');
    await page.getByLabel('문서 검색',{exact:true}).press('Enter');
    assert.equal(await page.locator('#manual .current-match').first().getAttribute('data-manual-hit'),'2');
    await page.getByLabel('문서 검색',{exact:true}).press('Shift+Enter');
    assert.equal(await page.locator('#manual .current-match').first().getAttribute('data-manual-hit'),'1');
    await page.getByRole('button',{name:'이전 검색 결과',exact:true}).click();
    await page.getByRole('button',{name:'이전 검색 결과',exact:true}).click();
    assert.equal(await page.locator('#manual .current-match').first().getAttribute('data-manual-hit'),String(check.expected-1));

    await page.locator('#result-list .result-link').first().click();
    assert.equal(await page.getByLabel('문서 검색',{exact:true}).inputValue(),'pvt');
    await page.screenshot({path:path.join(reports,'search.png')});
    await page.getByRole('tab',{name:'목차',exact:true}).click();
    const navGroup = page.locator('#contents-panel details').filter({has:page.locator('a[href="#pvt-views"]')});
    if (!(await navGroup.evaluate(node => node.open))) await navGroup.locator('summary').click();
    await navGroup.locator('a').filter({hasText:'반복 시험 위치 편차'}).click();
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'pvt-views');
    assert.equal(await page.getByLabel('문서 검색',{exact:true}).inputValue(),'pvt');
    await page.goBack();
    await page.waitForFunction(() => !document.getElementById(location.hash.slice(1)).hidden);
    await page.getByRole('button',{name:'검색 초기화',exact:true}).click();
    assert.equal(await page.locator('#manual mark').count(),0);
    assert.equal(await page.locator('.sheet:visible').count(),1);
    assert.deepEqual(await page.locator('.sheet').evaluateAll(sheets => sheets.map(sheet => sheet.textContent)),originalText);
    assert.equal(await page.locator('.sheet strong,.sheet b').count(),originalStrong);

    await page.evaluate(() => {
      const fixture = document.createElement('p');
      fixture.id = 'manual-search-fixture';
      const bold = document.createElement('strong');
      bold.textContent = 'Epoch';
      fixture.append('검색검사 ',bold,' 연결문장');
      document.getElementById('quick').append(fixture);
    });
    await find('검색검사 Epoch 연결문장');
    assert.match(await page.locator('#matches').innerText(),/^1 \/ 1곳 · 1개 항목$/);
    assert.equal(await page.locator('#manual .current-match').count(),3,'one occurrence may span several styled text nodes');
    await page.getByLabel('문서 검색',{exact:true}).press('Escape');
    await page.locator('#manual-search-fixture').evaluate(node => node.remove());
    for (const query of ['[.*','<img src=x onerror=alert(1)>','__no_such_topic__']) {
      await find(query);
      assert.equal(await page.locator('#matches').innerText(),'0곳');
      assert.ok(await page.locator('#empty').isVisible());
      assert.equal(await page.locator('.sheet:visible').count(),1,'no results must not erase the current topic');
    }
    await page.getByLabel('문서 검색',{exact:true}).press('Escape');
    await page.setViewportSize({width:1440,height:650});
    await page.goto(pathToFileURL(source).href + '#capture');
    assert.ok(await page.locator('.topic-pagination').evaluate(node => node.compareDocumentPosition(document.querySelector('.sheet')) & Node.DOCUMENT_POSITION_FOLLOWING), 'navigation precedes the content');
    await page.evaluate(() => {
      const longContent = document.createElement('div');
      longContent.id = 'manual-scroll-fixture';
      longContent.style.height = '1200px';
      document.getElementById('capture').append(longContent);
    });
    const maximum = await page.evaluate(() => document.scrollingElement.scrollHeight - innerHeight);
    assert.ok(maximum > 120, 'long topic has ordinary scrolling');
    await page.mouse.move(1250,400);
    await page.evaluate(top => window.scrollTo({top,behavior:'instant'}), maximum / 3);
    await page.mouse.wheel(0,120);
    await page.waitForTimeout(300);
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'capture','ordinary scrolling keeps the topic');
    await page.evaluate(() => window.scrollTo({top:document.scrollingElement.scrollHeight,behavior:'instant'}));
    await page.waitForTimeout(250);
    await page.mouse.wheel(0,120);
    await page.waitForFunction(() => !document.getElementById('clock').hidden);
    await page.mouse.wheel(0,120);
    await page.waitForTimeout(100);
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'clock','wheel momentum cannot skip a topic');
    await page.waitForTimeout(850);
    await page.evaluate(() => window.scrollTo({top:0,behavior:'instant'}));
    await page.mouse.wheel(0,-120);
    await page.waitForFunction(() => !document.getElementById('capture').hidden);
    const longPosition = await page.locator('.topic-pagination').boundingBox();
    await page.locator('#topic-next').click();
    const shortPosition = await page.locator('.topic-pagination').boundingBox();
    assert.ok(Math.abs(longPosition.y-shortPosition.y)<2 && shortPosition.height<=36,'compact navigation stays at the same position');
    await page.mouse.move(150,300);
    await page.waitForTimeout(900);
    await page.mouse.wheel(0,-120);
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'clock','scrolling the contents does not change topics');
    await page.locator('#manual-scroll-fixture').evaluate(node => node.remove());
    await page.setViewportSize({width:1440,height:1000});
    await page.goto(pathToFileURL(source).href + '#sender-map');
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),'sender-map');
    const popupPromise = page.waitForEvent('popup');
    await page.locator('.sheet:visible figure a').first().click();
    const popup = await popupPromise;
    await popup.waitForLoadState();
    assert.ok(popup.url().startsWith('file:') && popup.url().endsWith('.png'));
    await popup.close();
    await page.screenshot({path:path.join(reports,'desktop.png')});

    await page.setViewportSize({width:390,height:844});
    await find('수신');
    assert.ok(await page.locator('#manual-sidebar').isVisible());
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),'Mobile horizontal overflow');
    await page.screenshot({path:path.join(reports,'mobile-search.png')});
    await page.locator('#result-list .result-link').first().click();
    assert.equal(await page.locator('#manual-sidebar').isVisible(),false);
    await page.screenshot({path:path.join(reports,'mobile.png')});

    await page.setViewportSize({width:1440,height:1000});
    const current = await page.locator('.sheet:visible').getAttribute('id');
    await page.emulateMedia({media:'print'});
    assert.equal(await page.locator('.sheet:visible').count(),documentCheck.pages,'Print the entire manual during search');
    assert.equal(await page.locator('.sidebar').isVisible(),false);
    const overflow = await page.evaluate(() => [...document.querySelectorAll('.sheet')].flatMap(sheet => {
      const footer = sheet.querySelector('.page-foot').getBoundingClientRect();
      const content = [...sheet.children].filter(node => !node.classList.contains('page-foot'));
      const bottom = Math.max(...content.map(node => node.getBoundingClientRect().bottom));
      const right = sheet.getBoundingClientRect().right;
      return bottom > footer.top - 4 || sheet.scrollWidth > sheet.clientWidth + 1
        || content.some(node => node.getBoundingClientRect().right > right + 1)
        ? [{id:sheet.id,overlap:Math.round(bottom-footer.top)}] : [];
    }));
    fs.writeFileSync(path.join(reports,'checks.json'),JSON.stringify({documentCheck,overflow,errors,external},null,2));
    assert.deepEqual(overflow,[],'Print content exceeds a page');
    await page.emulateMedia({media:'screen'});
    assert.equal(await page.locator('.sheet:visible').getAttribute('id'),current,'Keep the reading position after printing');
    assert.equal(await page.getByLabel('문서 검색',{exact:true}).inputValue(),'수신');
    assert.deepEqual(external,[],'Offline manual requested an external resource');
    assert.deepEqual(errors,[],'Browser JavaScript exceptions');

    await page.getByRole('button',{name:'검색 초기화',exact:true}).click();
    await page.emulateMedia({media:'print'});
    await page.locator('figure a').evaluateAll(links => links.forEach(link => link.removeAttribute('href')));
    await page.pdf({path:output,format:'A4',landscape:true,preferCSSPageSize:true,printBackground:true,tagged:true,outline:true});
    assert.ok(fs.statSync(output).size > 10000);
    fs.copyFileSync(output,path.join(root,'manual/user-manual.pdf'));
    console.log('PASS: ' + documentCheck.pages + ' sections; search counts/highlights/navigation, offline/mobile/print and logos');
    console.log(output);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
