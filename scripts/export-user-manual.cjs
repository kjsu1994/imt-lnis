// Offline manual export; does not connect to LNIS or change operational data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

let chromium;
try {
  ({chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright'));
} catch {
  throw new Error('Playwright 설치 경로를 PLAYWRIGHT_MODULE에 지정하세요. 기존 browserTest와 같은 설치본을 사용합니다.');
}

const root = path.resolve(__dirname, '..');
const source = path.join(root, 'manual', 'index.html');
const output = path.join(root, 'build', 'distributions', 'lnis-user-manual.pdf');
const reports = path.join(root, 'build', 'reports', 'user-manual');

(async () => {
  fs.mkdirSync(path.dirname(output), {recursive: true});
  fs.mkdirSync(reports, {recursive: true});
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}, locale: 'ko-KR'});
    const errors = [], external = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route(/^https?:/, route => {
      external.push(route.request().url());
      return route.abort();
    });
    await page.goto(pathToFileURL(source).href);
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all([...document.images].map(image => image.decode().catch(() => {})));
    });

    const documentCheck = await page.evaluate(() => {
      const ids = [...document.querySelectorAll('[id]')].map(e => e.id);
      return {
        pages: document.querySelectorAll('.sheet').length,
        duplicateIds: ids.filter((id, i) => ids.indexOf(id) !== i),
        brokenLinks: [...document.querySelectorAll('a[href^="#"]')].map(a => a.getAttribute('href'))
          .filter(href => !document.getElementById(href.slice(1))),
        brokenImages: [...document.images].filter(i => !i.complete || !i.naturalWidth || !i.alt).map(i => i.src),
        unbranded: [...document.querySelectorAll('.sheet')].filter(p => p.querySelectorAll('.manual-kari').length !== 1).map(p => p.id)
      };
    });
    assert.ok(documentCheck.pages > 0);
    for (const key of ['duplicateIds', 'brokenLinks', 'brokenImages', 'unbranded']) {
      assert.deepEqual(documentCheck[key], [], key);
    }

    await page.getByLabel('문서 검색').fill('SHA-256');
    const matches = await page.locator('.sheet:visible').count();
    assert.ok(matches > 0 && matches < documentCheck.pages, 'Search must filter the manual');
    await page.getByRole('button', {name: '검색 초기화'}).click();
    assert.equal(await page.locator('.sheet:visible').count(), documentCheck.pages);
    await page.getByLabel('문서 검색').fill('__no_such_manual_section__');
    assert.ok(await page.locator('#empty').isVisible());
    await page.getByLabel('문서 검색').press('Escape');
    assert.equal(await page.locator('.sheet:visible').count(), documentCheck.pages);

    const popupPromise = page.waitForEvent('popup');
    await page.locator('figure a').first().click();
    const popup = await popupPromise;
    await popup.waitForLoadState();
    assert.ok(popup.url().startsWith('file:') && popup.url().endsWith('.png'));
    await popup.close();

    await page.screenshot({path: path.join(reports, 'desktop.png')});
    await page.setViewportSize({width: 390, height: 844});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      'Mobile horizontal overflow');
    await page.screenshot({path: path.join(reports, 'mobile.png')});

    await page.setViewportSize({width: 1440, height: 1000});
    await page.emulateMedia({media: 'print'});
    const overflow = await page.evaluate(() => [...document.querySelectorAll('.sheet')].flatMap(sheet => {
      const footer = sheet.querySelector('.page-foot').getBoundingClientRect();
      const content = [...sheet.children].filter(e => !e.classList.contains('page-foot'));
      const bottom = Math.max(...content.map(e => e.getBoundingClientRect().bottom));
      const right = sheet.getBoundingClientRect().right;
      return bottom > footer.top - 4 || sheet.scrollWidth > sheet.clientWidth + 1
        || content.some(e => e.getBoundingClientRect().right > right + 1)
        ? [{id: sheet.id, overlap: Math.round(bottom - footer.top)}] : [];
    }));
    fs.writeFileSync(path.join(reports, 'checks.json'), JSON.stringify({documentCheck, overflow, errors, external}, null, 2));
    assert.deepEqual(overflow, [], 'Print content overlaps footer or exceeds page');
    assert.deepEqual(external, [], 'Offline manual requested an external resource');
    assert.deepEqual(errors, [], 'Browser JavaScript exceptions');

    // Image enlargement is for HTML; omit absolute local-file links from the PDF.
    await page.locator('figure a').evaluateAll(links => links.forEach(a => a.removeAttribute('href')));
    await page.pdf({
      path: output,
      format: 'A4',
      landscape: true,
      preferCSSPageSize: true,
      printBackground: true,
      tagged: true,
      outline: true
    });
    assert.ok(fs.statSync(output).size > 10000, 'PDF was not written');
    fs.copyFileSync(output, path.join(root, 'manual', 'lnis-user-manual.pdf'));
    console.log('PASS: ' + documentCheck.pages + ' pages; offline links, images, search, zoom, mobile and print bounds');
    console.log(output);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
