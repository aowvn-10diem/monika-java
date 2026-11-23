// Monika API (Cloudflare Worker + D1). Contract: memory "monika-api-contract".
// Nguồn dữ liệu: feed Blogger (BLOG_URL). Cron quét từng trang 40 bài, xóa mục mất khỏi blog sau mỗi vòng quét.

import { UP_PAGE, handleUpload, fileDownload, fileJad, itemFile, iconOfItem, iconOfFile, iconsJson, ingestExternal, backfillIcons, admin } from "./up.js";
import { serveFile } from "./store.js";
import { resolveCat, parseTags, parsePlat, parseRes, guessTags, parseKey, orderFor, fetchRows, jsonItem, countDownload, tooFast, hotlinked, recompute, statsJson, catsJson } from "./rank.js";

const PAGE = 15;
const FEED_PAGE = 40;
const DAY = 86400000;
const NEW_DAYS = 7;

// ---------- encoding (Java DataOutputStream, modified UTF-8) ----------
class Out {
  constructor() { this.a = []; }
  u8(v) { this.a.push(v & 255); return this; }
  u16(v) { this.a.push((v >> 8) & 255, v & 255); return this; }
  i32(v) { this.a.push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255); return this; }
  utf(s, max = 65535) {
    s = String(s == null ? "" : s);
    const b = [];
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c !== 0 && c < 0x80) b.push(c);
      else if (c < 0x800) b.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else b.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      if (b.length > max - 3) break;
    }
    this.u16(b.length);
    for (const x of b) this.a.push(x);
    return this;
  }
  bytes() { return new Uint8Array(this.a); }
}

const norm = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase();
const clip = (s, n) => { s = String(s || ""); return s.length > n ? s.slice(0, n - 1) + "…" : s; };

function decodeEntities(s) {
  return s.replace(/&(#x?[0-9a-f]+|amp|lt|gt|quot|apos|nbsp);/gi, (m, e) => {
    e = e.toLowerCase();
    if (e === "amp") return "&"; if (e === "lt") return "<"; if (e === "gt") return ">";
    if (e === "quot") return '"'; if (e === "apos") return "'"; if (e === "nbsp") return " ";
    const n = e[1] === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
    return Number.isFinite(n) ? String.fromCodePoint(n) : m;
  });
}
function htmlToText(h) {
  return decodeEntities(String(h || "").replace(/<(br|\/p|\/div|\/li|\/h\d|\/tr)[^>]*>/gi, "\n").replace(/<[^>]+>/g, ""));
}

// ---------- Blogger post -> record ----------
const META_RE = /<(pre|blockquote|div)\b[^>]*mk-meta[^>]*>([\s\S]*?)<\/\1>/i;
function parseEntry(e) {
  const html = (e.content && e.content.$t) || "";
  const m = META_RE.exec(html);
  const meta = {};
  const vl = []; // dòng v, v1..v99: link tải ngoài theo độ phân giải (bài chỉ dẫn link, không có jar)
  if (m) {
    for (const line of htmlToText(m[2]).split("\n")) {
      const i = line.indexOf(":");
      if (i <= 0) continue;
      const k = line.slice(0, i).trim().toLowerCase(), val = line.slice(i + 1).trim();
      const vm = /^v(\d{1,2})?$/.exec(k);
      if (vm) vl.push([vm[1] ? +vm[1] : 0, val]); else meta[k] = val;
    }
  }
  const ext = vl.sort((a, b) => a[0] - b[0]).map((x) => x[1]);
  const labels = (e.category || []).map((c) => c.term);
  const labelsL = labels.map((l) => l.toLowerCase());
  let type = (meta.type || "").toLowerCase();
  if (!type) for (const t of ["banner", "self", "game", "app"]) if (labelsL.includes(t)) { type = t; break; }
  const id = String((e.id && e.id.$t) || "");
  const src = (id.match(/post-(\d+)$/) || [])[1];
  const alt = ((e.link || []).find((l) => l.rel === "alternate") || {}).href || "";
  const title = decodeEntities((e.title && e.title.$t) || "").trim();
  const body = m ? html.replace(m[0], "") : html;
  const img = meta.img || firstShot(body) || (e.media$thumbnail && e.media$thumbnail.url) || "";
  const typeSet = new Set(["banner", "self", "game", "app"]);
  const cat = meta.cat || labels.find((l) => !typeSet.has(l.toLowerCase())) || "";
  const num = (v) => { const n = parseInt(String(v || "").replace(/[^\d]/g, ""), 10); return Number.isFinite(n) ? n : 0; };
  const descr = htmlToText(body).replace(/\s+/g, " ").trim();
  const rc = resolveCat(cat, type);
  const net = meta.net === "1" || /^(y|yes|true)$/i.test(meta.net || "");
  const tags = (parseTags(meta.tags) || (meta.tags ? 0 : guessTags(title) | parseTags(labels.join(",")))) | (net ? 128 : 0);
  return {
    src, type, title, alt, img, ext, cat, catId: rc.id, catUnknown: rc.unknown || "", tags, plat: parsePlat(meta.plat), res: parseRes(meta.res || "", meta.jar || "", title), meta,
    fileK: (/^[0-9a-f]{10}$/i.test(meta.file || "") ? meta.file : ((/\/v1\/f\/[^\/?#]*-([0-9a-f]{10})\.jar/i.exec(meta.jar || "") || [])[1] || "")).toLowerCase(),
    ver: meta.ver || meta.version || "", size: num(meta.size), price: num(meta.price),
    jar: directJar(meta.jar || ""), descr: meta.desc || descr,
    flags: (meta.net === "1" || /^(y|yes|true)$/i.test(meta.net || "") ? 4 : 0) | (meta.hot === "1" ? 8 : 0),
    published: Date.parse(e.published && e.published.$t) || 0,
    updated: Date.parse(e.updated && e.updated.$t) || 0,
  };
}
// Ảnh minh họa = ảnh đầu tiên trong bài không phải icon Monika (/v1/fi/, /v1/ic/), bỏ ảnh quá nhỏ khai báo width/height <= 80.
function firstShot(body) {
  const re = /<img\b[^>]*>/gi;
  let m;
  while ((m = re.exec(body))) {
    const t = m[0];
    const src = (/\bsrc=["']([^"']+)["']/i.exec(t) || [])[1];
    if (!src || /\/v1\/(fi|ic)\//.test(src)) continue;
    const w = parseInt((/\bwidth=["']?(\d+)/i.exec(t) || [])[1], 10);
    if (w && w <= 80) continue;
    return decodeEntities(src);
  }
  return "";
}
function directJar(u) {
  u = (u || "").trim();
  let m = /^https?:\/\/drive\.google\.com\/file\/d\/([\w-]+)/i.exec(u) || /^https?:\/\/drive\.google\.com\/(?:open|uc)\?(?:[^#]*&)?id=([\w-]+)/i.exec(u);
  if (m) return "https://drive.usercontent.google.com/download?export=download&confirm=t&id=" + m[1];
  m = /^https?:\/\/github\.com\/([^/]+)\/([^/]+)\/blob\/(.+)$/i.exec(u);
  if (m) return `https://raw.githubusercontent.com/${m[1]}/${m[2]}/${m[3]}`;
  return u;
}

// ---------- sync ----------
async function getMeta(db, k, d) {
  const r = await db.prepare("SELECT v FROM meta WHERE k=?").bind(k).first();
  return r ? r.v : d;
}
const setMetaStmt = (db, k, v) => db.prepare("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(k, String(v));

async function syncStep(env) {
  const base = (env.BLOG_URL || "").replace(/\/+$/, "");
  if (!base) return { skipped: "no BLOG_URL" };
  const db = env.DB;
  const cursor = parseInt(await getMeta(db, "cursor", "1"), 10) || 1;
  const sweep = parseInt(await getMeta(db, "sweep", "1"), 10) || 1;
  const r = await fetch(`${base}/feeds/posts/default?alt=json&orderby=published&start-index=${cursor}&max-results=${FEED_PAGE}`, { headers: { "user-agent": "MonikaSync/1" } });
  if (!r.ok) return { error: "feed " + r.status };
  const feed = (await r.json()).feed || {};
  const entries = feed.entry || [];
  const st = [];
  let n = 0;
  for (const e of entries) {
    const p = parseEntry(e);
    if (!p.src || !p.type) continue;
    n++;
    if (p.type === "banner") {
      st.push(db.prepare(
        `INSERT INTO banners(src,slot,img,link,alt,updated,sweep) VALUES(?,?,?,?,?,?,?)
         ON CONFLICT(src) DO UPDATE SET slot=excluded.slot,img=excluded.img,link=excluded.link,alt=excluded.alt,updated=excluded.updated,sweep=excluded.sweep`
      ).bind(p.src, (p.meta.slot || "top").toLowerCase(), p.img, p.meta.link || "", p.meta.alt || p.title, p.updated, sweep));
      continue;
    }
    if (p.catUnknown) st.push(db.prepare("INSERT OR IGNORE INTO meta(k,v) VALUES(?,?)").bind("unk:" + p.catUnknown.slice(0, 60), p.alt || p.src));
    st.push(db.prepare(
      `INSERT INTO items(src,type,title,norm,cat_n,ver,size_kb,price,jar,descr,url,flags,published,updated,sweep,img,file_h,tags,plat,res)
       VALUES(?,?,?,?,?,?,COALESCE(NULLIF(?,0),(SELECT MAX(1,(size+512)/1024) FROM files WHERE k=?),0),?,?,?,?,?,?,?,?,?,(SELECT k FROM files WHERE k=?),?,?,?)
       ON CONFLICT(src) DO UPDATE SET type=excluded.type,title=excluded.title,norm=excluded.norm,cat_n=excluded.cat_n,ver=excluded.ver,tags=excluded.tags,plat=excluded.plat,res=excluded.res,
         size_kb=CASE WHEN excluded.size_kb>0 THEN excluded.size_kb ELSE items.size_kb END,
         price=excluded.price,descr=excluded.descr,url=excluded.url,flags=excluded.flags,published=excluded.published,updated=excluded.updated,sweep=excluded.sweep,
         img=excluded.img,file_h=excluded.file_h,
         jad_text=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.jad_text END,
         jar_size=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.jar_size END,
         ic32=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.ic32 END,
         ic64=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.ic64 END,
         icon_state=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.icon_state END,
         jar=excluded.jar`
    ).bind(p.src, p.type, p.title, norm(p.title), p.catId || null, p.ver, p.size, p.fileK || "", p.price, p.jar, clip(p.descr, 300), p.alt, p.flags, p.published, p.updated, sweep, p.img || "", p.fileK || "", p.tags, p.plat, p.res));
  }
  const done = entries.length < FEED_PAGE;
  if (done) {
    if (!(cursor === 1 && entries.length === 0)) {
      st.push(db.prepare("DELETE FROM items WHERE sweep < ?").bind(sweep));
      st.push(db.prepare("DELETE FROM items WHERE src LIKE 'up-%' AND file_h IN (SELECT file_h FROM items WHERE src NOT LIKE 'up-%' AND file_h IS NOT NULL)"));
      st.push(db.prepare("DELETE FROM banners WHERE sweep < ?").bind(sweep));
      st.push(setMetaStmt(db, "sweep", sweep + 1));
    }
    st.push(setMetaStmt(db, "cursor", 1));
    st.push(setMetaStmt(db, "synced", Date.now()));
  } else {
    st.push(setMetaStmt(db, "cursor", cursor + FEED_PAGE));
  }
  if (st.length) await db.batch(st);
  return { cursor, entries: entries.length, parsed: n, done };
}

// ---------- JAD cho JAR ở link ngoài ----------
async function buildJad(env, it, origin) {
  return ingestExternal(env, it, origin);
}

// ---------- images ----------
function resizeGoogle(u, w, h) {
  if (!/(googleusercontent|ggpht)\.com|blogger\.com\/img/i.test(u)) return u.replace("{w}", w);
  const spec = `w${w}-h${h}-c-rp`;
  if (/=[swh]\d[^/]*$/i.test(u)) return u.replace(/=[swh]\d[^/]*$/i, "=" + spec);
  if (/\/[swh]\d+(-[a-z0-9-]*)?\/[^/]+$/i.test(u)) return u.replace(/\/[swh]\d+(-[a-z0-9-]*)?\/([^/]+)$/i, `/${spec}/$2`);
  return u + "=" + spec;
}

// ---------- handlers ----------
const flagsOf = (r, now) => {
  let f = r.flags & 12;
  if (now - r.published < NEW_DAYS * DAY) f |= 1;
  else if (now - r.updated < NEW_DAYS * DAY && r.updated - r.published > DAY) f |= 2;
  if (r.hot) f |= 8;
  if (r.hasic) f |= 16; // có icon: app gọi /v1/ic/{id}-32.png
  if (r.hasimg) f |= 32; // có ảnh minh họa: app gọi /v1/sc/{id}-{w}x{h}.jpg
  return f;
};
const bin = (u8, ttl = 60) => new Response(u8, { headers: { "content-type": "application/octet-stream", "cache-control": `public, max-age=${ttl}`, "access-control-allow-origin": "*" } });
const text = (s, status = 200) => new Response(s, { status, headers: { "content-type": "text/plain; charset=utf-8", "access-control-allow-origin": "*" } });

async function resolveBannerLink(env, link) {
  const l = (link || "").trim();
  if (!l) return "";
  if (/^(hot|new|dl|app|game)$/i.test(l)) return "l:" + l.toLowerCase();
  let m = /^cat:(.+)$/i.exec(l);
  if (m) { const c = await env.DB.prepare("SELECT n FROM cats WHERE name=?").bind(m[1].trim()).first(); return c ? "l:c" + c.n : ""; }
  if (/^https?:\/\//i.test(l)) {
    const it = await env.DB.prepare("SELECT n FROM items WHERE url=?").bind(l).first();
    return it ? "g:" + it.n : "";
  }
  m = /^(?:g:)?(\d+)$/.exec(l);
  return m ? "g:" + m[1] : "";
}

async function home(env) {
  const db = env.DB, now = Date.now();
  const newCount = (await db.prepare("SELECT COUNT(*) c FROM items WHERE type IN ('game','app') AND published > ?").bind(now - NEW_DAYS * DAY).first()).c;
  const self = await db.prepare("SELECT n,ver FROM items WHERE type='self' ORDER BY updated DESC LIMIT 1").first();
  const cats = (await db.prepare("SELECT c.n,c.name,COUNT(i.n) cnt FROM cats c JOIN items i ON i.cat_n=c.n AND i.type IN ('game','app') GROUP BY c.n ORDER BY c.name LIMIT 100").all()).results;
  const bn = (await db.prepare("SELECT n,alt,link FROM banners WHERE slot='top' ORDER BY updated DESC LIMIT 10").all()).results;
  const o = new Out().i32(0x4d4b4831).u8(1).u16(newCount).utf(self ? self.ver : "").utf(self ? `http://${env.API_HOST}/v1/j/${self.n}.jar` : "");
  o.u8(cats.length);
  for (const c of cats) o.u8(c.n).utf(c.name).u16(c.cnt);
  o.u8(bn.length);
  for (const b of bn) o.u8(b.n).utf(b.alt).utf(await resolveBannerLink(env, b.link));
  return bin(o.bytes());
}

async function list(env, where, binds, page, order) {
  const now = Date.now();
  const { total, rows } = await fetchRows(env, where, binds, page, order, PAGE);
  const o = new Out().i32(0x4d4b4c31).u8(1).u16(Math.min(total, 65535)).u16(page).u8(rows.length);
  for (const r of rows) {
    o.i32(r.n).u8(r.cat).u8(flagsOf(r, now)).u16(Math.min(r.size_kb || 0, 65535)).i32(r.price || 0)
      .utf(r.ver).utf(r.title).utf(clip(r.descr, 120)).utf(`/v1/j/${r.n}.jad`);
  }
  return bin(o.bytes());
}

// v2: thêm dl (int), tags (ushort), plat (ubyte) giữa price và version. App 0.3 không dùng.
async function list2(env, where, binds, page, order) {
  const now = Date.now();
  const { total, rows } = await fetchRows(env, where, binds, page, order, PAGE);
  const o = new Out().i32(0x4d4b4c32).u8(2).u16(Math.min(total, 65535)).u16(page).u8(rows.length);
  for (const r of rows) {
    o.i32(r.n).u8(r.cat).u8(flagsOf(r, now)).u16(Math.min(r.size_kb || 0, 65535)).i32(r.price || 0).i32(r.dl || 0).u16(r.tags || 0).u8(r.plat || 1)
      .utf(r.ver).utf(r.title).utf(clip(r.descr, 120)).utf(`/v1/j/${r.n}.jad`);
  }
  return bin(o.bytes());
}

async function listJson(env, key, page) {
  const q = parseKey(key);
  if (!q) return jsonResp({ error: "bad key" }, 400);
  const now = Date.now();
  const { total, rows } = await fetchRows(env, q.where, q.binds, page, await orderFor(env, q.sort), PAGE);
  return jsonResp({ total, page, items: rows.map((r, i) => jsonItem(r, i, flagsOf(r, now))) });
}
const jsonResp = (o, status = 200, ttl = 60) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": `public, max-age=${ttl}`, "access-control-allow-origin": "*" } });

// danh mục v2: kind 1 game, 2 app; tên chuẩn lấy từ taxonomy
async function home2(env) {
  const db = env.DB, now = Date.now();
  const newCount = (await db.prepare("SELECT COUNT(*) c FROM items WHERE type IN ('game','app') AND published > ?").bind(now - NEW_DAYS * DAY).first()).c;
  const self = await db.prepare("SELECT n,ver FROM items WHERE type='self' ORDER BY updated DESC LIMIT 1").first();
  const cats = (await catsJson(env)).filter((c) => c.kind === 1 || c.kind === 2).slice(0, 60);
  const bn = (await db.prepare("SELECT n,alt,link FROM banners WHERE slot='top' ORDER BY updated DESC LIMIT 10").all()).results;
  const o = new Out().i32(0x4d4b4832).u8(2).u16(newCount).utf(self ? self.ver : "").utf(self ? `http://${env.API_HOST}/v1/j/${self.n}.jar` : "");
  o.u8(cats.length);
  for (const c of cats) o.u8(c.id).u8(c.kind).utf(c.name).u16(c.count);
  o.u8(bn.length);
  for (const b of bn) o.u8(b.n).utf(b.alt).utf(await resolveBannerLink(env, b.link));
  return bin(o.bytes());
}

async function banner(env, id, w) {
  const b = await env.DB.prepare("SELECT img FROM banners WHERE n=?").bind(id).first();
  if (!b || !b.img) return text("not found", 404);
  const h = w <= 240 ? 60 : Math.round(w / 5);
  const tryUrls = [resizeGoogle(b.img, w, h), b.img];
  for (const u of [...new Set(tryUrls)]) {
    const r = await fetch(u, { headers: { "user-agent": "MonikaImg/1" } });
    const ct = r.headers.get("content-type") || "";
    if (r.ok && /^image\//.test(ct)) {
      return new Response(r.body, { headers: { "content-type": ct, "cache-control": "public, max-age=3600", "access-control-allow-origin": "*" } });
    }
  }
  return text("upstream image error", 502);
}

// Ảnh minh họa cho lưới trong app: thu nhỏ vừa khung w x h (giữ tỉ lệ, không cắt, không méo; ảnh dọc 240x320 và ngang 320x240 đều vừa), JPEG chất lượng 70 (vài KB). Nguồn Google/Blogger thì Google thu nhỏ và nén,
// nguồn khác trả nguyên bản. Cloudflare cache 1 ngày nên mỗi cỡ chỉ kéo từ Google một lần.
function resizeShot(u, w, h) {
  if (!/(googleusercontent|ggpht)\.com|blogger\.com\/img|bp\.blogspot\.com/i.test(u)) return u;
  const spec = `w${w}-h${h}-rj-l70`;
  if (/=[swh]\d[^/]*$/i.test(u)) return u.replace(/=[swh]\d[^/]*$/i, "=" + spec);
  if (/\/[swh]\d+(-[a-z0-9-]*)?\/[^/]+$/i.test(u)) return u.replace(/\/[swh]\d+(-[a-z0-9-]*)?\/([^/]+)$/i, `/${spec}/$2`);
  return u + "=" + spec;
}
async function shot(env, id, w, h) {
  const it = await env.DB.prepare("SELECT img FROM items WHERE n=?").bind(id).first();
  if (!it || !it.img) return text("not found", 404);
  const pn = picName(env, it.img);
  if (pn) return await pic(env, pn);
  const urls = [...new Set([resizeShot(it.img, w, h), it.img])];
  for (const u of urls) {
    const r = await fetch(u, { headers: { "user-agent": "MonikaImg/1" }, cf: { cacheTtl: 86400, cacheEverything: true } });
    const ct = r.headers.get("content-type") || "";
    if (r.ok && /^image\//.test(ct)) {
      return new Response(r.body, { headers: { "content-type": ct, "cache-control": "public, max-age=86400", "access-control-allow-origin": "*" } });
    }
  }
  return text("upstream image error", 502);
}

// Link tải thẳng /v1/f/<tên>-<hash10>.jar: đếm vào mục (ưu tiên bài thật, không phải mục up-) dùng file đó.
async function countFile(env, req, fname) {
  const m = /-([0-9a-f]{10})\.jar$/.exec(fname);
  if (!m) return "skip:name";
  const it = await env.DB.prepare("SELECT n FROM items WHERE file_h=? ORDER BY (src LIKE 'up-%') ASC, n LIMIT 1").bind(m[1]).first();
  return it ? countDownload(env, req, it.n) : "skip:noitem";
}

// Bài link-only (không có jar, có các dòng v1..vN trong mk-meta): trang HTML nhẹ liệt kê bản tải theo độ phân giải.
// Dòng v: "240x320|365|JAD=http://...|JAR=http://..." (cỡ | KB | nhãn=URL ...). Nút Tải của app mở /v1/j/<id>.jad trên trình duyệt máy, nên máy cũ vào trang này qua HTTP.
const hesc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
export function chooserHtml(title, lines, blog) {
  const rows = [];
  for (const l of lines.slice(0, 40)) {
    const p = l.split("|").map((x) => x.trim());
    const links = p.slice(2).map((x) => /^([^=<>"]{1,20})=(https?:\/\/[^\s<>"]+)$/i.exec(x)).filter(Boolean)
      .map((x) => `<a href="${hesc(x[2])}">${hesc(x[1])}</a>`);
    if (!/^\d{2,3}x\d{2,3}$/.test(p[0] || "") || !links.length) continue;
    rows.push(`<p><b>${hesc(p[0])}</b>${p[1] && /^\d+$/.test(p[1]) ? " · " + p[1] + " KB" : ""} · ${links.join(" · ")}</p>`);
  }
  if (!rows.length) return "";
  return `<html><head><meta charset="utf-8"><title>${hesc(title)}</title></head><body><h3>${hesc(title)}</h3>` +
    `<p>Chọn dòng đúng độ phân giải màn hình máy bạn. Tệp tải từ trang nguồn.</p>${rows.join("")}` +
    `<p><a href="${hesc(blog || "/")}">Monika</a></p></body></html>`;
}
async function chooser(env, it) {
  const base = (env.BLOG_URL || "").replace(/\/+$/, "");
  if (!base || !/^\d+$/.test(String(it.src))) return null;
  let e;
  try {
    const r = await fetch(`${base}/feeds/posts/default/${it.src}?alt=json`, { headers: { "user-agent": "MonikaSync/1" }, cf: { cacheTtl: 300, cacheEverything: true } });
    if (!r.ok) return null;
    e = (await r.json()).entry;
  } catch (_) { return null; }
  if (!e) return null;
  const html = chooserHtml(it.title || "Tải game", parseEntry(e).ext, base);
  if (!html) return null;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300", "access-control-allow-origin": "*" } });
}

// Ảnh minh họa lưu trong R2 (khóa pic/<tên>.jpg), phát qua /v1/pic/<tên>.jpg. Không có R2 thì 404.
async function pic(env, name) {
  if (!env.R2) return text("no r2", 404);
  // Cache biên: ảnh đã đọc một lần thì các lần sau không tốn lượt đọc R2 (hạn mức Class B).
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const key = new Request(`https://${env.API_HOST || "api"}/v1/pic/${name}`);
  if (cache) { const hit = await cache.match(key); if (hit) return hit; }
  const o = await env.R2.get("pic/" + name);
  if (!o) return text("not found", 404);
  const res = new Response(o.body, { headers: { "content-type": "image/jpeg", "cache-control": "public, max-age=86400", "access-control-allow-origin": "*" } });
  if (cache) { try { await cache.put(key, res.clone()); } catch (_) {} }
  return res;
}
const picName = (env, u) => { const m = /^https?:\/\/([^/]+)\/v1\/pic\/([A-Za-z0-9_-]{1,60}\.jpg)$/.exec(u || ""); return m && m[1].toLowerCase() === String(env.API_HOST || "").toLowerCase() ? m[2] : ""; };

const tooMany = () => new Response("Tai qua nhanh, thu lai sau 1 phut.", { status: 429, headers: { "content-type": "text/plain; charset=utf-8", "retry-after": "60", "access-control-allow-origin": "*" } });

async function download(env, ctx, req, n, ext) {
  const it = await env.DB.prepare("SELECT n,src,title,jar,jad_text,file_h,url FROM items WHERE n=?").bind(n).first();
  if (it && !it.jar && !it.file_h && ext === "jad") { const pg = await chooser(env, it); if (pg) return pg; }
  if (!it || (!it.jar && !it.file_h)) return text("not found", 404);
  if (ext === "jar") {
    if (hotlinked(env, req)) return Response.redirect(it.url || env.BLOG_URL || `http://${env.API_HOST}/`, 302);
    if (await tooFast(env, req, "RL_DL")) return tooMany();
    ctx.waitUntil(countDownload(env, req, n));
  }
  const origin = `http://${env.API_HOST}`;
  if (ext === "jad") {
    let jad = it.file_h ? await fileJad(env, n, origin) : it.jad_text;
    if (!jad) {
      try { jad = await buildJad(env, { ...it, title: "" }, origin); } catch (e) { return text("jad error: " + e.message, 502); }
    }
    return new Response(jad, { headers: { "content-type": "text/vnd.sun.j2me.app-descriptor; charset=utf-8", "cache-control": "no-cache" } });
  }
  if (it.file_h) {
    const f = await itemFile(env, n);
    if (f) return serveFile(env, req, f, `monika-${n}.jar`);
  }
  const h = {};
  const rg = req.headers.get("range"); if (rg) h.range = rg;
  const r = await fetch(it.jar, { headers: { ...h, "user-agent": "MonikaDl/1" } });
  if (!r.ok && r.status !== 206) return text("upstream " + r.status, 502);
  const hd = new Headers({ "content-type": "application/java-archive", "cache-control": "public, max-age=300", "accept-ranges": "bytes", "content-disposition": `attachment; filename="monika-${n}.jar"` });
  for (const k of ["content-length", "content-range"]) if (r.headers.get(k)) hd.set(k, r.headers.get(k));
  return new Response(r.body, { status: r.status, headers: hd });
}

async function bannersJson(env, slot) {
  const rows = (await env.DB.prepare("SELECT n,slot,alt,link,img FROM banners WHERE (?1='' OR slot=?1) ORDER BY updated DESC LIMIT 20").bind(slot).all()).results;
  const out = [];
  for (const b of rows) {
    const l = (b.link || "").trim();
    let href = "";
    if (/^https?:\/\//i.test(l)) href = l;
    else if (/^(hot|new|dl)$/i.test(l)) href = "/search/label/" + encodeURIComponent(l);
    else if (/^(app|game)$/i.test(l)) href = "/search/label/" + encodeURIComponent(l[0].toUpperCase() + l.slice(1).toLowerCase());
    else if (/^cat:/i.test(l)) href = "/search/label/" + encodeURIComponent(l.slice(4).trim());
    out.push({ id: b.n, slot: b.slot, alt: b.alt, href, src: String(b.img).replace("{w}", "640"), img: `/v1/b/${b.n}-320.png`, img2: `/v1/b/${b.n}-640.png` });
  }
  return new Response(JSON.stringify(out), { headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=60", "access-control-allow-origin": "*" } });
}

export default {
  async fetch(req, env, ctx) {
    try {
      const u = new URL(req.url);
      const p = u.pathname;
      let m;
      if (p === "/" || p === "/v1") return text("Monika API v1\n");
      if (p === "/v1/home.bin") return await home(env);
      if (p === "/v1/home2.bin") return await home2(env);
      if ((m = /^\/v1\/(l|l2)\/([a-z0-9.]+)\/(\d+)\.bin$/.exec(p))) {
        const page = Math.min(parseInt(m[3], 10), 4000), q = parseKey(m[2]);
        if (!q) return text("bad key", 404);
        return await (m[1] === "l2" ? list2 : list)(env, q.where, q.binds, q.where.startsWith("i.n=?") ? 0 : page, await orderFor(env, q.sort));
      }
      if (p === "/v1/list.json") return await listJson(env, u.searchParams.get("key") || "new", Math.min(Math.max(parseInt(u.searchParams.get("page") || "0", 10) || 0, 0), 4000));
      if (p === "/v1/stats.json") return jsonResp(await statsJson(env, u.searchParams.get("ids"), u.searchParams.get("paths")));
      if (p === "/v1/cats.json") return jsonResp(await catsJson(env), 200, 300);
      if ((m = /^\/v1\/s\/([^/]*)\/(\d+)\.bin$/.exec(p))) {
        let key = "";
        try { key = norm(decodeURIComponent(m[1].replace(/\+/g, " "))).replace(/[%_\\]/g, "").trim().slice(0, 40); } catch (_) {}
        if (!key) return await list(env, "0=1", [], 0, "i.n");
        if (await tooFast(env, req, "RL_SEARCH")) return tooMany();
        return await list(env, "i.norm LIKE ?", ["%" + key + "%"], Math.min(parseInt(m[2], 10), 4000), "i.updated DESC");
      }
      if ((m = /^\/v1\/b\/(\d+)-(\d+)\.png$/.exec(p))) return await banner(env, parseInt(m[1], 10), Math.min(Math.max(parseInt(m[2], 10), 60), 1000));
      if ((m = /^\/v1\/sc\/(\d+)-(\d+)x(\d+)\.jpg$/.exec(p))) return await shot(env, parseInt(m[1], 10), Math.min(Math.max(parseInt(m[2], 10), 40), 320), Math.min(Math.max(parseInt(m[3], 10), 30), 320));
      if ((m = /^\/v1\/pic\/([A-Za-z0-9_-]{1,60}\.jpg)$/.exec(p))) return await pic(env, m[1]);
      if ((m = /^\/v1\/j\/(\d+)\.(jad|jar)$/.exec(p))) return await download(env, ctx, req, parseInt(m[1], 10), m[2]);
      if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization,content-type,x-token,x-filename", "access-control-allow-methods": "GET,POST,OPTIONS" } });
      if (p === "/up") return new Response(UP_PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
      if (p === "/v1/up" && req.method === "POST") return await handleUpload(env, ctx, req, u);
      if (p.startsWith("/v1/admin/")) return await admin(env, req, u);
      if ((m = /^\/v1\/f\/([^/]+)$/.exec(p))) {
        const fname = decodeURIComponent(m[1]);
        if (hotlinked(env, req)) return Response.redirect(env.BLOG_URL || `http://${env.API_HOST}/`, 302);
        if (await tooFast(env, req, "RL_DL")) return tooMany();
        ctx.waitUntil(countFile(env, req, fname));
        return await fileDownload(env, req, fname);
      }
      if ((m = /^\/v1\/ic\/(\d+)-(32|64)\.png$/.exec(p))) return await iconOfItem(env, parseInt(m[1], 10), parseInt(m[2], 10));
      if ((m = /^\/v1\/fi\/([0-9a-f]{10})-(32|64)\.png$/.exec(p))) return await iconOfFile(env, m[1], parseInt(m[2], 10));
      if (p === "/v1/icons.json") return await iconsJson(env);
      if (p === "/v1/banners.json") return await bannersJson(env, (u.searchParams.get("slot") || "").toLowerCase());
      if (p === "/v1/sync" && env.SYNC_TOKEN && u.searchParams.get("t") === env.SYNC_TOKEN) {
        return text(JSON.stringify(await syncStep(env)));
      }
      return text("not found", 404);
    } catch (e) {
      return text("error: " + (e && e.message), 500);
    }
  },
  async scheduled(ev, env, ctx) {
    ctx.waitUntil(syncStep(env));
    ctx.waitUntil(backfillIcons(env));
    if (Math.floor(Date.now() / 60000) % 15 < 5) ctx.waitUntil(recompute(env)); // 15 phút một lần (cron chạy 5 phút)
  },
};
