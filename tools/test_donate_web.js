#!/usr/bin/env node
/* Thu thong bao ung ho tren web (template Blogspot) bang Playwright, khong can Blogger/Worker.
   node tools/test_donate_web.js [thu-muc-anh]   (mac dinh .build/shots-donate) */
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..');
const OUT = process.argv[2] || path.join(ROOT, '.build', 'shots-donate');
fs.mkdirSync(OUT, { recursive: true });

const post = {
  title: 'Game thu', url: 'http://m.test/2026/11/g.html',
  body: '<pre class="mk-meta">\nver: 1.0\nsize: 120\ncat: Hành động\nplat: j2me\njar: http://api.justmonika.top/v1/f/x-abcdef0123.jar\n</pre><p>Mo ta.</p>',
  timestampValue: new Date().toISOString(), labels: [{ name: 'Game', url: '/search/label/Game' }],
};
const html = execFileSync('python3', [path.join(__dirname, 'mock_web_api.py')],
  { input: JSON.stringify({ kind: 'item', posts: [post], labels: [] }), maxBuffer: 1 << 26 }).toString();

(async () => {
  const br = await chromium.launch();
  const fails = [], errs = [];
  const ok = (c, m) => { if (!c) fails.push(m); };
  const VPS = [['1280', 1280, 800], ['360', 360, 640], ['320x240', 320, 240]];
  for (const vp of VPS) {
    const ctx = await br.newContext({ viewport: { width: vp[1], height: vp[2] }, acceptDownloads: true });
    await ctx.route('**/*', route => {
      const u = new URL(route.request().url());
      if (u.hostname === 'm.test') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      if (u.hostname === 'api.justmonika.top') {
        if (/\.jar$/.test(u.pathname)) return route.fulfill({ contentType: 'application/java-archive', headers: { 'content-disposition': 'attachment; filename=x.jar' }, body: 'PK' });
        return route.fulfill({ contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: '[]' });
      }
      return route.abort();
    });
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(vp[0] + ' ' + e.message));
    await pg.goto('http://m.test/2026/11/g.html', { waitUntil: 'load' });
    await pg.waitForSelector('a.dlbtn');
    const n = vp[0];
    ok(!(await pg.$('#mkd')), n + ' chua tai ma da hien');
    await pg.click('a.dlbtn'); await pg.waitForTimeout(3200);
    ok(!(await pg.$('#mkd')), n + ' hien o luot 1');
    await pg.click('a.dlbtn'); await pg.waitForTimeout(3200);
    ok(!!(await pg.$('#mkd')), n + ' khong hien o luot 2');
    if (await pg.$('#mkd')) {
      const txt = await pg.$eval('#mkd', e => e.textContent);
      ok(/Giữ Monika luôn miễn phí/.test(txt) && /duy trì hệ thống ổn định/.test(txt) && /<3/.test(txt) && /9021870093495, ngân hàng Timo/.test(txt), n + ' noi dung ' + txt);
      ok(await pg.$eval('#mkd .mkd-qr', i => i.complete && i.naturalWidth === 270), n + ' anh QR chua nap');
      ok(await pg.$eval('#mkd input', i => i.value === '9021870093495'), n + ' o STK');
      ok(await pg.$eval('#mkd', e => e.scrollWidth <= e.clientWidth + 1), n + ' tran ngang');
      await pg.screenshot({ path: path.join(OUT, `donate-${n}-top.png`) });
      await pg.evaluate(() => { const e = document.querySelector('#mkd'); e.scrollTop = e.scrollHeight; document.querySelector('#mkd .mkd-x').scrollIntoView(false); });
      await pg.waitForTimeout(150);
      await pg.screenshot({ path: path.join(OUT, `donate-${n}-bottom.png`) });
      ok(await pg.$eval('#mkd .mkd-x', x => { const r = x.getBoundingClientRect(); return r.bottom <= window.innerHeight + 1 && r.top >= 0; }), n + ' nut Dong khong thay sau khi keo xuong');
      await pg.click('#mkd .mkd-btn'); await pg.waitForTimeout(200);
      const lab = await pg.$eval('#mkd .mkd-btn', b => b.textContent);
      ok(/Đã sao chép|Chạm giữ/.test(lab), n + ' nut chep ' + lab);
      console.log(n, 'nut chep ->', lab);
      await pg.click('#mkd .mkd-x');
      ok(!(await pg.$('#mkd')), n + ' bam Dong van con');
    }
    await pg.click('a.dlbtn'); await pg.waitForTimeout(3200);
    ok(!(await pg.$('#mkd')), n + ' luot 3 khong duoc hien lai');
    /* quay lai trang sau luot 2 (cookie cho hien) */
    const ck = (await ctx.cookies()).find(c => c.name === 'mkd');
    ok(ck && ck.value.indexOf('3.2.') === 0, n + ' cookie ' + (ck && ck.value));
    await ctx.addCookies([{ name: 'mkd', value: '7.2.1', domain: 'm.test', path: '/' }]);
    await pg.reload({ waitUntil: 'load' });
    await pg.waitForTimeout(500);
    ok(!!(await pg.$('#mkd')), n + ' mo lai trang khi con co cho hien');
    await ctx.close();
  }
  await br.close();
  errs.forEach(e => console.log('JS loi:', e));
  fails.forEach(f => console.log('HONG:', f));
  console.log(fails.length || errs.length ? 'KET QUA: CO LOI' : 'KET QUA: OK');
  process.exit(fails.length || errs.length ? 1 : 0);
})();
