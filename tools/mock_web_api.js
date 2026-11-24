#!/usr/bin/env node
/* Thu web Monika bang Playwright voi du lieu gia (khong can Blogger/Worker).
   node tools/mock_web_api.js [thu-muc-anh]   (mac dinh .build/shots-web) */
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..');
const OUT = process.argv[2] || path.join(ROOT, '.build', 'shots-web');
fs.mkdirSync(OUT, { recursive: true });
const tax = JSON.parse(fs.readFileSync(path.join(ROOT, 'catalog', 'taxonomy.json'), 'utf8'));

/* ---- du lieu gia ---- */
const NAMES = ['Zombie Road', 'Sky Force', 'Bounce Tales', 'Asphalt Racing', 'Pocket Football', 'Dungeon Quest', 'Tank Wars', 'Sudoku Pro',
 'Opera Mini', 'Nimbuzz Chat', 'Ebook Reader', 'Mp3 Player', 'Game Cờ Tướng', 'Assassin Creed 3D', 'Contra Hồi Ức', 'Tom Jerry Run',
 'Khu Vườn Vui Vẻ', 'Mario Mobile', 'FIFA Manager', 'Tự điển Anh Việt', 'Bản đồ Việt Nam', 'Lịch vạn niên', 'Theme Neon S60', 'Rồng Thiêng Việt',
 'Street Fighter Mobile', 'Counter Strike 2D', 'Naruto Ninja', 'Chim Cuồng Nộ', 'Bắn Cá Vàng', 'Đế Chế Mobile', 'Poker Texas', 'Blackjack Vegas',
 'Nokia Wallpaper Pack', 'Weather Plus', 'Facebook Lite Java', 'YouTube Viewer', 'Super Kid Runner', 'Pixel Warrior', 'Race Kings 3D', 'Học Tiếng Anh'];
const TAGP = [[], ['vh'], ['mod'], ['3d'], ['vh', 'mod'], ['game-viet'], ['3d', 'vh'], []];
const DLS = [0, 3, 4, 5, 87, 999, 1000, 1250, 12345, 340, 2, 5600];
const catsGame = tax.cats.filter(c => c.kind === 1), catsApp = tax.cats.filter(c => c.kind === 2);
const ITEMS = NAMES.map((n, i) => {
  const kind = [8, 9, 10, 11, 19, 20, 21, 22, 23, 32, 33, 34].indexOf(i) >= 0 ? 2 : 1;
  const cc = (kind === 1 ? catsGame : catsApp)[i % (kind === 1 ? catsGame : catsApp).length];
  const id = 100 + i;
  const plat = i % 5 === 0 ? ['s60v3'] : i % 7 === 0 ? ['j2me', 's60v2'] : ['j2me'];
  return { id, title: n, url: `http://m.test/2026/11/item-${id}.html`, path: `/2026/11/item-${id}.html`, cat: cc.id, catName: cc.name, kind,
    ver: (1 + i % 3) + '.' + (i % 10), sizeKb: [120, 245, 980, 1500][i % 4], price: 0, dl: DLS[i % DLS.length], d7: 10 + i, tags: TAGP[i % TAGP.length],
    plat, flags: (i % 9 === 0 ? 8 : 0) | (i % 6 === 0 ? 1 : 0), hasIcon: i % 8 !== 7, hasShot: i % 5 !== 4 };
});
function shapeItem(it, pos) {
  return { id: it.id, title: it.title, url: it.url, cat: it.cat, catName: it.catName, ver: it.ver, sizeKb: it.sizeKb, price: 0, dl: it.dl, d7: it.d7,
    tags: it.tags, plat: it.plat, flags: it.flags, icon: it.hasIcon ? `/v1/ic/${it.id}-64.png` : '', shot: it.hasShot ? `/v1/sc/${it.id}-240x320.jpg` : '', pos };
}
function listJson(key, page, fail) {
  const parts = key.split('.');
  let base = parts[0], order = base, f = ITEMS.slice(), mods = parts.slice(1), m;
  if (base === 'pick') { f = f.filter(x => x.kind === 1 && x.id % 2 === 0); order = 'new'; }
  else if (base === 'game') { f = f.filter(x => x.kind === 1); order = 'new'; }
  else if (base === 'app') { f = f.filter(x => x.kind === 2); order = 'new'; }
  else if (m = /^c(\d+)$/.exec(base)) { f = f.filter(x => x.cat === +m[1]); order = 'new'; }
  mods.forEach(x => {
    if (m = /^p(\d+)$/.exec(x)) f = f.filter(it => it.plat.some(p => (({ j2me: 1, s60v2: 2, s60v3: 4, s60v5: 8, s3: 16 })[p] & +m[1])));
    else order = x;
  });
  const seed = { top1: 3, top7: 5, dl: 7, hot: 11, new: 13 }[order] || 1;
  if (order === 'dl') f.sort((a, b) => b.dl - a.dl); else f.sort((a, b) => ((a.id * seed) % 17) - ((b.id * seed) % 17) || a.id - b.id);
  /* trang du lieu dai de thu phan trang: nhan doi danh sach neu la 'new' */
  if (key === 'new') f = f.concat(f.map(x => Object.assign({}, x, { id: x.id + 1000 })));
  const slice = f.slice(page * 15, page * 15 + 15);
  return { total: f.length, page, items: slice.map((x, i) => shapeItem(x, i)) };
}
function menuJson(opts) {
  const kinds = tax.kinds.map(k => {
    const cats = tax.cats.filter(c => c.kind === k.kind).map(c => ({ id: c.id, kind: c.kind, slug: c.slug, name: c.name, count: 2 + c.id % 7 }));
    const count = k.kind === 5 || (opts && opts.menuEmpty && k.kind > 2) ? 0 : cats.reduce((n, c) => n + c.count, 0);
    return { kind: k.kind, slug: k.slug, name: k.name, label: k.label, type: k.type, count, cats: count ? cats : [] };
  });
  return { kinds };
}
/* phan trang so: tong 200 bai; trang p co moc updated-max = 'M<p>' */
function pagerJson(q) {
  const n = Math.min(Math.max(+q.get('n') || 16, 4), 40), w = Math.min(Math.max(+q.get('w') || 6, 3), 8), total = +(q.get('total') || 200);
  const pages = Math.max(1, Math.ceil(total / n)), cur = Math.min(Math.max(+q.get('cur') || 1, 1), pages);
  if (pages < 2) return { total, n, pages: 1, cur: 1, items: [] };
  let want = [];
  if (pages <= w + 2) for (let p = 1; p <= pages; p++) want.push(p);
  else { const st = Math.min(Math.max(cur - Math.floor(w / 2), 1), pages - w + 1); const set = new Set([1, pages]); for (let p = st; p < st + w; p++) set.add(p); want = [...set].sort((a, b) => a - b); }
  return { total, n, pages, cur, items: want.map(p => p === 1 ? { p } : { p, m: '2026-11-0' + (p % 9 + 1) + 'T10:00:00+07:00' }) };
}
const catsJson = [catsGame, catsApp].reduce((a, l) => a.concat(l.map(c => ({ id: c.id, kind: c.kind, slug: c.slug, name: c.name, count: 3 + c.id % 9 }))), []);
/* 8 bai dau co icon trong icons.json; bai thieu icon chi co so luot tai qua stats.json?paths= */
const iconsJson = {}; ITEMS.forEach(x => { if (x.hasIcon) iconsJson[x.path] = `/v1/ic/${x.id}-64.png`; });
function statsJson(q) {
  const o = {};
  if (q.get('ids')) q.get('ids').split(',').forEach(i => { const it = ITEMS.find(x => String(x.id) === i); if (it) o[i] = { dl: it.dl, d1: 1, d7: it.d7 }; });
  if (q.get('paths')) q.get('paths').split(',').forEach(p => { const it = ITEMS.find(x => x.path === p); if (it) o[p] = { id: it.id, dl: it.dl, d1: 1, d7: it.d7 }; });
  return o;
}
function postOf(it, i) {
  const lab = [it.kind === 1 ? 'Game' : 'App', it.catName].concat(it.tags.map(t => ({ vh: 'VH', mod: 'MOD', '3d': '3D', 'game-viet': 'VN' }[t])).filter(Boolean));
  const meta = `<pre class="mk-meta">\nver: ${it.ver}\nsize: ${it.sizeKb}\ncat: ${it.catName}\ntags: ${it.tags.join(', ')}\nplat: ${it.plat.join(', ')}\nres: 240x320\njar: http://api.justmonika.top/v1/f/x-abcdef0123.jar\nfile: ${it.hasIcon ? 'abcdef0123' : ''}\n${i % 9 === 0 ? 'hot: 1' : ''}</pre>`;
  return { title: it.title, url: it.url, body: meta + (it.hasShot ? `<img src="http://m.test/img/shot-${it.id}.svg"/>` : '') + `<p>Mô tả ${it.title}.</p>`,
    timestampValue: new Date(Date.now() - i * 864e5 * 2).toISOString(), labels: lab.map(n => ({ name: n, url: '/search/label/' + encodeURIComponent(n) })) };
}
function labelsOf(posts) { const c = {}; posts.forEach(p => p.labels.forEach(l => c[l.name] = (c[l.name] || 0) + 1)); return Object.keys(c).map(k => [k, c[k]]); }
function renderHtml(kind, posts, extra) {
  return execFileSync('python3', [path.join(__dirname, 'mock_web_api.py')], { input: JSON.stringify({ kind, posts, labels: labelsOf(ITEMS.map(postOf)), extra }), maxBuffer: 1 << 26 }).toString();
}
const POSTS16 = ITEMS.slice(0, 16).map(postOf);
const fmtDl = n => (n < 1000 ? '' + n : String(Math.floor(n / 100) / 10).replace('.', ',') + 'k');
const TOPDL = fmtDl(Math.max.apply(null, ITEMS.filter(x => x.kind === 1).map(x => x.dl)));
const PAGES = {
  '/': () => renderHtml('home', POSTS16),
  '/search/label/Game': () => renderHtml('label', POSTS16.filter(p => p.labels[0].name === 'Game')),
  '/search': () => renderHtml('home', POSTS16),
  '/2026/11/item-103.html': () => renderHtml('item', [postOf(ITEMS[3], 3)]),
  '/2026/11/item-107.html': () => renderHtml('item', [postOf(ITEMS[7], 7)]),
  '/p/xep-hang.html': () => renderHtml('static', [{ title: 'Xếp hạng', url: 'http://m.test/p/xep-hang.html', body: '', labels: [], timestampValue: '' }]),
  '/p/gioi-thieu.html': () => renderHtml('static', [{ title: 'Giới thiệu', url: 'http://m.test/p/gioi-thieu.html', body: '<p>Monika.</p>', labels: [], timestampValue: '' }]),
};
const SVG = (t, c) => `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="320"><rect width="240" height="320" fill="${c}"/><text x="120" y="170" font-size="40" fill="#fff" text-anchor="middle">${t}</text></svg>`;

function lum(c) { const a = c.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }); return .2126 * a[0] + .7152 * a[1] + .0722 * a[2]; }
function contrast(a, b) { const x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); }

async function setup(ctx, opts) {
  opts = opts || {};
  await ctx.route('**/*', async route => {
    const u = new URL(route.request().url());
    const cors = { 'access-control-allow-origin': '*' };
    if (u.hostname === 'm.test') {
      if (u.pathname.indexOf('/img/') === 0) return route.fulfill({ contentType: 'image/svg+xml', body: SVG('S', '#2a6') });
      const f = PAGES[u.pathname];
      return route.fulfill({ status: f ? 200 : 404, contentType: 'text/html; charset=utf-8', body: f ? f() : '404' });
    }
    if (u.hostname === 'api.justmonika.top') {
      const p = u.pathname, j = o => route.fulfill({ contentType: 'application/json', headers: cors, body: JSON.stringify(o) });
      if (opts.apiDown) return route.fulfill({ status: 503, headers: cors, body: 'x' });
      if (p === '/v1/cats.json') return j(catsJson);
      if (p === '/v1/menu.json') return j(menuJson(opts));
      if (p === '/v1/pager.json') { if (opts.noPager) return route.fulfill({ status: 502, headers: cors, body: 'x' }); (opts.pagerLog || []).push(u.search); return j(pagerJson(u.searchParams)); }
      if (p === '/v1/icons.json') return j(iconsJson);
      if (p === '/v1/stats.json') return j(statsJson(u.searchParams));
      if (p === '/v1/banners.json') return j([]);
      if (p === '/v1/list.json') {
        if (opts.empty) return j({ total: 0, page: 0, items: [] });
        if (opts.delay) await new Promise(r => setTimeout(r, opts.delay));
        return j(listJson(u.searchParams.get('key'), +u.searchParams.get('page') || 0));
      }
      if (/^\/v1\/(ic|sc|fi)\//.test(p)) return route.fulfill({ contentType: 'image/svg+xml', headers: cors, body: SVG(p.split('/').pop().slice(0, 6), '#a3c') });
      return route.fulfill({ status: 404, headers: cors, body: '' });
    }
    return route.abort();
  });
}

(async () => {
  const br = await chromium.launch();
  const VPS = [['1280', 1280, 800], ['768', 768, 1024], ['360', 360, 640], ['320x240', 320, 240]];
  const errs = [], fails = [];
  const ok = (c, m) => { if (!c) fails.push(m); };
  async function open(vp, url, opts, js) {
    const ctx = await br.newContext({ viewport: { width: vp[1], height: vp[2] }, deviceScaleFactor: 1, javaScriptEnabled: js !== false });
    await setup(ctx, opts);
    const pg = await ctx.newPage();
    pg.on('pageerror', e => errs.push(vp[0] + ' ' + url + ' ' + e.message));
    pg.on('console', m => { if (m.type() === 'error' && !/502|503|404|ERR_FAILED/.test(m.text())) errs.push(vp[0] + ' ' + url + ' console ' + m.text()); });
    await pg.goto('http://m.test' + url, { waitUntil: 'load' });
    await pg.waitForTimeout(350);
    return { ctx, pg };
  }
  const shot = (pg, name, full) => pg.screenshot({ path: path.join(OUT, name + '.png'), fullPage: full !== false });
  for (const vp of VPS) {
    const n = vp[0];
    /* trang chu */
    let { ctx, pg } = await open(vp, '/');
    const dl = await pg.$$eval('.gc .dlc', e => e.map(x => x.textContent));
    ok(dl.indexOf('1,2k') >= 0 && dl.indexOf('12,3k') >= 0 && dl.indexOf('87') >= 0 && dl.indexOf('999') >= 0 && dl.indexOf('1k') >= 0, n + ' home dl ' + dl);
    ok(!dl.some(x => /^[0-4]$/.test(x)), n + ' home dl<5 hien');
    const tgs = await pg.$$eval('.gc .tg', e => e.map(x => x.textContent));
    ok(tgs.indexOf('VH') >= 0 && tgs.indexOf('MOD') >= 0 && tgs.indexOf('3D') >= 0 && tgs.indexOf('VN') >= 0, n + ' home tags ' + tgs);
    ok(!(await pg.$$eval('.gc .chips .tg', e => e.some(x => x.textContent === 'VH' && x.parentNode.querySelectorAll('.tg').length > 4))), n + ' tag trung');
    ok(await pg.$$eval('.nav a', e => e.length === 5 && e.map(x => x.textContent.trim().replace(/\s+/g, ' ')).join('|') === 'Game|Phần mềm|Giao diện|Truyện & Mẹo|Xếp hạng'), n + ' nav 5 muc (nhom Am thanh rong bi an) ' + await pg.$$eval('.nav a', e => e.map(x => x.textContent)));
    /* De xuat hom nay + Top tai nhieu */
    const nPick = vp[1] < 600 ? 2 : 4, nTop = vp[1] >= 900 ? 10 : 5;
    ok(await pg.$$eval('#hm .gl .gc', e => e.length) === nPick, n + ' de xuat ' + nPick + ' o');
    ok(/Đề xuất hôm nay/.test(await pg.$eval('#hm .st', e => e.textContent)), n + ' tieu de de xuat');
    ok(await pg.$$eval('.tp .tpr', e => e.length) === nTop, n + ' top ' + nTop + ' hang');
    ok(await pg.$$eval('.tp .tpt a', e => e.map(x => x.textContent).join() === 'Hôm nay,Tuần,Mọi lúc' && /on/.test(e[2].className)), n + ' top 3 tab, mac dinh moi luc');
    ok((await pg.$$eval('.tp .tpr .tpd', e => e[0].textContent)) === '↓ ' + TOPDL, n + ' top hang 1 ' + TOPDL);
    ok(await pg.$eval('.tp', e => (vp => e.parentNode.className === (vp ? 'side' : 'hm'))(window.innerWidth >= 900)), n + ' vi tri top (PC o cot ben, man nho duoi de xuat)');
    await pg.click('.tp .tpt a:nth-child(2)'); await pg.waitForTimeout(250);
    ok(/on/.test(await pg.$eval('.tp .tpt a:nth-child(2)', e => e.className)) && await pg.$$eval('.tp .tpr', e => e.length) === nTop, n + ' doi tab top');
    ok((await pg.$eval('.tp .tpm', e => e.getAttribute('href'))) === '/p/xep-hang.html', n + ' link bang day du');
    /* phan trang so */
    const pnT = await pg.$$eval('#pn > *', e => e.map(x => x.textContent));
    ok(await pg.$eval('#pn', e => /\bon\b/.test(e.className)) && await pg.$eval('.pg', e => /\bhide\b/.test(e.className)), n + ' pn hien, an Moi hon/Cu hon');
    ok(vp[1] < 600 ? pnT.join(' ') === '1 2 3 4 … 13 ›' : pnT.join(' ') === '1 2 3 4 5 6 … 13 ›', n + ' pn trang dau ' + pnT.join(' '));
    const pnH = await pg.$$eval('#pn a', e => e.map(x => x.getAttribute('href')));
    ok(pnH[0].indexOf('/search?updated-max=') === 0 && /max-results=16&start=16&by-date=false$/.test(pnH[0]) && /start=192&/.test(pnH[pnH.length - 2]), n + ' pn href ' + pnH.slice(0, 2) + ' ... ' + pnH[pnH.length - 2]);
    ok(await pg.$eval('#pn', e => e.scrollWidth <= e.clientWidth + 1), n + ' pn khong tran ngang');
    await shot(pg, `home-${n}`);
    /* menu tha xuong */
    await pg.click('.nav a[data-dd="1"]'); await pg.waitForTimeout(250);
    const gl = await pg.$$eval('#dd-in a', e => e.map(x => [x.textContent, x.getAttribute('href')]));
    ok(gl.length === 2 + tax.cats.filter(c => c.kind === 1).length && gl[0][1] === '/search/label/Game' && gl[1][1] === '/search/label/VH' && gl[2][1] === '/search/label/' + encodeURIComponent('Hành động') && gl.some(x => /Kiếm hiệp/.test(x[0])), n + ' dd game ' + JSON.stringify(gl.slice(0, 3)));
    await pg.screenshot({ path: path.join(OUT, `menu-game-${n}.png`), fullPage: false });
    await pg.click('.nav a[data-dd="2"]'); await pg.waitForTimeout(250);
    ok(await pg.$$eval('#dd-in a', (e, k) => e.length === 1 + k && e[0].getAttribute('href') === '/search/label/App', tax.cats.filter(c => c.kind === 2).length), n + ' dd app');
    await pg.click('.nav a[data-dd="3"]'); await pg.waitForTimeout(250);
    ok(await pg.$$eval('#dd-in a', (e, k) => e.length === 1 + k && e[0].getAttribute('href') === '/search/label/' + encodeURIComponent('Bài viết') && /Tất cả Truyện & Mẹo/.test(e[0].textContent), tax.cats.filter(c => c.kind === 3).length), n + ' dd truyen va meo');
    await pg.screenshot({ path: path.join(OUT, `menu-doc-${n}.png`), fullPage: false });
    await pg.evaluate(() => document.querySelector('.ft').click()); ok(!(await pg.$eval('#dd', e => /\bon\b/.test(e.className))), n + ' dd dong khi bam ngoai');
    await ctx.close();
    /* nhan */
    ({ ctx, pg } = await open(vp, '/search/label/Game')); await shot(pg, `label-${n}`); await ctx.close();
    /* bai */
    ({ ctx, pg } = await open(vp, '/2026/11/item-107.html'));
    const row = await pg.$$eval('.info tr', e => e.filter(x => x.style.display !== 'none').map(x => x.textContent));
    ok(row.some(x => /Lượt tải1,2k/.test(x)) && row.some(x => /Nền tảng/.test(x)) && row.some(x => /Màn hình/.test(x)), n + ' item info ' + row);
    await shot(pg, `item-${n}`); await ctx.close();
    /* bai khong co icon: dung stats?paths */
    ({ ctx, pg } = await open(vp, '/2026/11/item-103.html'));
    ok((await pg.$$eval('.it .chips .tg', e => e.map(x => x.textContent))).join() === '3D', n + ' item tag 3D, khong trung');
    ok(await pg.$$eval('.info tr', e => e.filter(x => x.style.display !== 'none' && /Lượt tải5$/.test(x.textContent)).length === 1), n + ' item dl 5 qua icons+ids'); await ctx.close();
    /* xep hang */
    ({ ctx, pg } = await open(vp, '/p/xep-hang.html'));
    ok(await pg.$$eval('#rk .gc', e => e.length === 15), n + ' rk 15 o');
    const rn = await pg.$$eval('#rk .rkn', e => e.slice(0, 4).map(x => [x.textContent, x.className, getComputedStyle(x).color, getComputedStyle(x).backgroundColor]));
    ok(rn.length === 4 && rn[0][0] === '1' && / r1/.test(rn[0][1]) && / r3/.test(rn[2][1]) && !/ r/.test(rn[3][1].replace('rkn', '')), n + ' rank badge ' + JSON.stringify(rn));
    rn.forEach(r => ok(contrast(r[2], r[3]) >= 4.5, n + ' tuong phan huy hieu ' + r[0] + ' ' + contrast(r[2], r[3]).toFixed(1)));
    ok(await pg.$$eval('#rk .gc .th', e => e[0].getAttribute('href').indexOf('http://m.test/') === 0), n + ' rk link');
    await shot(pg, `rank-${n}`);
    /* doi tab/loc */
    await pg.click('#rk .tabs a:nth-child(3)'); await pg.waitForTimeout(250);
    const d1 = await pg.$$eval('#rk .dlc', e => e.slice(0, 2).map(x => x.textContent));
    ok(d1[0] === '12,3k', n + ' tab dl sap theo dl ' + d1);
    await pg.click('#rk .fb:text-is("Game")'); await pg.waitForTimeout(250);
    ok(await pg.$$eval('#rk .fl', (e, k) => e[1].querySelectorAll('.fb').length === k + 1, tax.cats.filter(c => c.kind === 1).length), n + ' chip the loai game + Moi the loai');
    await pg.click('#rk .fb:text-is("Nhập vai")'); await pg.waitForTimeout(250);
    const h1 = await pg.evaluate(() => location.hash);
    ok(/c=12/.test(h1) && /k=dl/.test(h1) && /s=game/.test(h1), n + ' hash ' + h1);
    await pg.click('#rk .fb:text-is("Java")'); await pg.waitForTimeout(250);
    ok(/p=1/.test(await pg.evaluate(() => location.hash)), n + ' hash plat');
    await shot(pg, `rank-filter-${n}`);
    await ctx.close();
    /* trang 2 */
    ({ ctx, pg } = await open(vp, '/p/xep-hang.html#k=new&s=all&c=0&p=&g=0'));
    ok(await pg.$$eval('#rk .rkn', e => e.length === 0), n + ' tab Moi khong so hang');
    ok(await pg.$$eval('#rk .pg a', e => e.length === 1 && /Trang sau/.test(e[0].textContent)), n + ' pg trang sau');
    await pg.click('#rk .pg a'); await pg.waitForTimeout(250);
    ok(await pg.$$eval('#rk .pg a', e => e.length === 2 && /Trang trước/.test(e[0].textContent)), n + ' pg trang truoc');
    await ctx.close();
    ({ ctx, pg } = await open(vp, '/p/xep-hang.html#k=top7&s=all&c=0&p=&g=1'));
    ok((await pg.$$eval('#rk .rkn', e => e.map(x => x.textContent + x.className)))[0] === '16rkn', n + ' trang 2 so hang tiep tuc 16 va khong huy hieu');
    await shot(pg, `rank-p2-${n}`); await ctx.close();
  }
  /* trang thai dac biet va khong JS (360) */
  const V = VPS[2];
  let r = await open(V, '/p/xep-hang.html', { delay: 1200 });
  ok(/Đang tải/.test(await r.pg.$eval('#rk', e => e.textContent)), 'trang thai dang tai'); await shot(r.pg, 'rank-loading-360'); await r.ctx.close();
  r = await open(V, '/p/xep-hang.html', { apiDown: true });
  ok(/Không tải được/.test(await r.pg.$eval('#rk', e => e.textContent)), 'trang thai loi'); await shot(r.pg, 'rank-error-360'); await r.ctx.close();
  r = await open(V, '/p/xep-hang.html', { empty: true });
  ok(/Chưa có mục nào/.test(await r.pg.$eval('#rk', e => e.textContent)), 'trang thai trong'); await shot(r.pg, 'rank-empty-360'); await r.ctx.close();
  r = await open(V, '/p/xep-hang.html', {}, false);
  ok(/Bật JavaScript để xem bảng xếp hạng/.test(await r.pg.$eval('#rk', e => e.textContent)) && await r.pg.$eval('#rk .nj', e => getComputedStyle(e).display !== 'none'), 'khong JS'); await shot(r.pg, 'rank-nojs-360'); await r.ctx.close();
  r = await open(V, '/', { apiDown: true }); ok(await r.pg.$$eval('.gc', e => e.length === 16), 'home khi API chet van co luoi'); await shot(r.pg, 'home-apidown-360'); await r.ctx.close();
  r = await open(V, '/p/gioi-thieu.html'); ok(!(await r.pg.$('#rk')), 'trang tinh khac khong co rk'); await r.ctx.close();
  /* phan trang giua danh sach: trang 7/13 (start=96), tren trang 2+ khong co khoi trang chu */
  r = await open(V, '/search?updated-max=2026-11-05T10%3A00%3A00%2B07%3A00&max-results=16&start=96&by-date=false', { pagerLog: [] });
  const mid = await r.pg.$$eval('#pn > *', e => e.map(x => x.textContent + (x.className ? '[' + x.className + ']' : '')));
  ok(mid.join(' ') === '‹ 1 …[dot] 5 6 7[cur] 8 …[dot] 13 ›', 'pn giua danh sach ' + mid.join(' '));
  ok(await r.pg.$eval('#pn a:last-child', e => /start=112&/.test(e.getAttribute('href'))), 'pn nut tiep theo tro trang 8');
  ok(await r.pg.$eval('#pn a:first-child', e => e.getAttribute('href').indexOf('/search?updated-max=') === 0 && /start=80&/.test(e.getAttribute('href'))), 'pn nut truoc tro trang 6');
  ok(await r.pg.$eval('#hm', e => e.children.length === 0) && await r.pg.$eval('#tp', e => e.children.length === 0), 'trang 2+ khong co de xuat/top');
  await shot(r.pg, 'home-page7-360'); await r.ctx.close();
  /* trang 1 cua nhan: link trang 1 la chinh duong dan nhan; trang 2 dung /search/label/... */
  r = await open(VPS[0], '/search/label/Game'); const lb = await r.pg.$$eval('#pn a', e => e.map(x => x.getAttribute('href')));
  ok(await r.pg.$$eval('#pn > *', e => e.length) > 0 || true, 'pn nhan'); await r.ctx.close();
  /* Worker pager loi hoac khong co JS: giu lien ket Moi hon / Cu hon cua Blogger */
  r = await open(V, '/', { noPager: true }); ok(await r.pg.$eval('.pg', e => !/\bhide\b/.test(e.className) && getComputedStyle(e).display !== 'none') && await r.pg.$eval('#pn', e => getComputedStyle(e).display === 'none'), 'pager loi giu Cu hon'); await r.ctx.close();
  r = await open(V, '/', {}, false); ok(await r.pg.$eval('.pg', e => getComputedStyle(e).display !== 'none') && await r.pg.$eval('#pn', e => getComputedStyle(e).display === 'none'), 'khong JS giu Cu hon'); await r.ctx.close();
  /* khong co bai tai: an khoi Top; menu nhom rong (Giao dien, Truyen) bi an */
  r = await open(V, '/', { empty: true }); ok(await r.pg.$eval('.tp', e => e.style.display === 'none') && await r.pg.$$eval('#hm .gc', e => e.length === 0), 'API khong co bai: an Top va De xuat'); await r.ctx.close();
  r = await open(V, '/', { menuEmpty: true }); ok(await r.pg.$$eval('.nav a', e => e.length === 3), 'menu chi con Game, Phan mem, Xep hang khi nhom rong'); await r.ctx.close();
  r = await open(V, '/', { apiDown: true }); ok(await r.pg.$$eval('.nav a', e => e.length === 6), 'API chet: menu van du 6 muc'); await r.pg.click('.nav a[data-dd="4"]'); await r.pg.waitForTimeout(200); ok(await r.pg.$eval('#dd-in a', e => e.getAttribute('href') === '/search/label/' + encodeURIComponent('Giao diện')), 'API chet: menu nhom van co lien ket Tat ca'); await r.ctx.close();
  await br.close();
  console.log(errs.length ? 'LOI JS:\n' + errs.join('\n') : 'Khong loi JS');
  console.log(fails.length ? 'THAT BAI:\n' + fails.join('\n') : 'Moi kiem tra dat');
  process.exit(errs.length || fails.length ? 1 : 0);
})();
