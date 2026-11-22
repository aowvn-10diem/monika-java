// Monika API (Cloudflare Worker + D1). Contract: memory "monika-api-contract".
// Nguồn dữ liệu: feed Blogger (BLOG_URL). Cron quét từng trang 40 bài, xóa mục mất khỏi blog sau mỗi vòng quét.

import { UP_PAGE, handleUpload, fileDownload, fileJad, itemFile, iconOfItem, iconOfFile, iconsJson, ingestExternal, backfillIcons, admin } from "./up.js";
import { serveFile } from "./store.js";

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
  if (m) {
    for (const line of htmlToText(m[2]).split("\n")) {
      const i = line.indexOf(":");
      if (i > 0) meta[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
    }
  }
  const labels = (e.category || []).map((c) => c.term);
  const labelsL = labels.map((l) => l.toLowerCase());
  let type = (meta.type || "").toLowerCase();
  if (!type) for (const t of ["banner", "self", "game", "app"]) if (labelsL.includes(t)) { type = t; break; }
  const id = String((e.id && e.id.$t) || "");
  const src = (id.match(/post-(\d+)$/) || [])[1];
  const alt = ((e.link || []).find((l) => l.rel === "alternate") || {}).href || "";
  const title = decodeEntities((e.title && e.title.$t) || "").trim();
  const body = m ? html.replace(m[0], "") : html;
  const img = meta.img || ((/<img[^>]+src=["']([^"']+)["']/i.exec(body) || [])[1]) || (e.media$thumbnail && e.media$thumbnail.url) || "";
  const typeSet = new Set(["banner", "self", "game", "app"]);
  const cat = meta.cat || labels.find((l) => !typeSet.has(l.toLowerCase())) || "";
  const num = (v) => { const n = parseInt(String(v || "").replace(/[^\d]/g, ""), 10); return Number.isFinite(n) ? n : 0; };
  const descr = htmlToText(body).replace(/\s+/g, " ").trim();
  return {
    src, type, title, alt, img, cat, meta,
    fileK: (/^[0-9a-f]{10}$/i.test(meta.file || "") ? meta.file : ((/\/v1\/f\/[^\/?#]*-([0-9a-f]{10})\.jar/i.exec(meta.jar || "") || [])[1] || "")).toLowerCase(),
    ver: meta.ver || meta.version || "", size: num(meta.size), price: num(meta.price),
    jar: directJar(meta.jar || ""), descr: meta.desc || descr,
    flags: (meta.net === "1" || /^(y|yes|true)$/i.test(meta.net || "") ? 4 : 0) | (meta.hot === "1" ? 8 : 0),
    published: Date.parse(e.published && e.published.$t) || 0,
    updated: Date.parse(e.updated && e.updated.$t) || 0,
  };
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
    if (p.cat) st.push(db.prepare("INSERT OR IGNORE INTO cats(name) VALUES(?)").bind(p.cat));
    st.push(db.prepare(
      `INSERT INTO items(src,type,title,norm,cat_n,ver,size_kb,price,jar,descr,url,flags,published,updated,sweep,file_h)
       VALUES(?,?,?,?,(SELECT n FROM cats WHERE name=?),?,COALESCE(NULLIF(?,0),(SELECT MAX(1,(size+512)/1024) FROM files WHERE k=?),0),?,?,?,?,?,?,?,?,(SELECT k FROM files WHERE k=?))
       ON CONFLICT(src) DO UPDATE SET type=excluded.type,title=excluded.title,norm=excluded.norm,cat_n=excluded.cat_n,ver=excluded.ver,
         size_kb=CASE WHEN excluded.size_kb>0 THEN excluded.size_kb ELSE items.size_kb END,
         price=excluded.price,descr=excluded.descr,url=excluded.url,flags=excluded.flags,published=excluded.published,updated=excluded.updated,sweep=excluded.sweep,
         file_h=excluded.file_h,
         jad_text=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.jad_text END,
         jar_size=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.jar_size END,
         ic32=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.ic32 END,
         ic64=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.ic64 END,
         icon_state=CASE WHEN items.jar IS NOT excluded.jar THEN NULL ELSE items.icon_state END,
         jar=excluded.jar`
    ).bind(p.src, p.type, p.title, norm(p.title), p.cat || null, p.ver, p.size, p.fileK || "", p.price, p.jar, clip(p.descr, 300), p.alt, p.flags, p.published, p.updated, sweep, p.fileK || ""));
  }
  const done = entries.length < FEED_PAGE;
  if (done) {
    if (!(cursor === 1 && entries.length === 0)) {
      st.push(db.prepare("DELETE FROM items WHERE sweep < ?").bind(sweep));
      st.push(db.prepare("DELETE FROM items WHERE src LIKE 'up-%' AND file_h IN (SELECT file_h FROM items WHERE src NOT LIKE 'up-%' AND file_h IS NOT NULL)"));
      st.push(db.prepare("DELETE FROM banners WHERE sweep < ?").bind(sweep));
      st.push(db.prepare("DELETE FROM cats WHERE n NOT IN (SELECT cat_n FROM items WHERE cat_n IS NOT NULL)"));
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
  const db = env.DB, now = Date.now();
  const total = (await db.prepare(`SELECT COUNT(*) c FROM items i WHERE i.type IN ('game','app') AND ${where}`).bind(...binds).first()).c;
  const rows = (await db.prepare(
    `SELECT i.n,i.flags,i.size_kb,i.price,i.ver,i.title,i.descr,i.published,i.updated,COALESCE(i.cat_n,0) cat,(i.dl >= 50) hot,
       (i.ic32 IS NOT NULL OR f.ic32 IS NOT NULL) hasic
     FROM items i LEFT JOIN files f ON f.k=i.file_h WHERE i.type IN ('game','app') AND ${where} ORDER BY ${order} LIMIT ${PAGE} OFFSET ${page * PAGE}`
  ).bind(...binds).all()).results;
  const o = new Out().i32(0x4d4b4c31).u8(1).u16(Math.min(total, 65535)).u16(page).u8(rows.length);
  for (const r of rows) {
    o.i32(r.n).u8(r.cat).u8(flagsOf(r, now)).u16(Math.min(r.size_kb || 0, 65535)).i32(r.price || 0)
      .utf(r.ver).utf(r.title).utf(clip(r.descr, 120)).utf(`/v1/j/${r.n}.jad`);
  }
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

async function download(env, ctx, req, n, ext) {
  const it = await env.DB.prepare("SELECT n,jar,jad_text,file_h FROM items WHERE n=?").bind(n).first();
  if (!it || (!it.jar && !it.file_h)) return text("not found", 404);
  const origin = `http://${env.API_HOST}`;
  if (ext === "jad") {
    let jad = it.file_h ? await fileJad(env, n, origin) : it.jad_text;
    if (!jad) {
      try { jad = await buildJad(env, { ...it, title: "" }, origin); } catch (e) { return text("jad error: " + e.message, 502); }
    }
    ctx.waitUntil(env.DB.prepare("UPDATE items SET dl=dl+1 WHERE n=?").bind(n).run());
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
      if ((m = /^\/v1\/l\/(new|hot|dl|app|game|c(\d+)|i(\d+))\/(\d+)\.bin$/.exec(p))) {
        const page = Math.min(parseInt(m[4], 10), 4000), k = m[1];
        if (m[3]) return await list(env, "i.n=?", [parseInt(m[3], 10)], 0, "i.n");
        if (k === "new") return await list(env, "1=1", [], page, "i.updated DESC");
        if (k === "hot") return await list(env, "1=1", [], page, "(i.flags & 8) DESC, i.dl DESC, i.updated DESC");
        if (k === "dl") return await list(env, "1=1", [], page, "i.dl DESC, i.updated DESC");
        if (k === "app" || k === "game") return await list(env, "i.type=?", [k], page, "i.updated DESC");
        return await list(env, "i.cat_n=?", [parseInt(m[2], 10)], page, "i.updated DESC");
      }
      if ((m = /^\/v1\/s\/([^/]*)\/(\d+)\.bin$/.exec(p))) {
        let key = "";
        try { key = norm(decodeURIComponent(m[1].replace(/\+/g, " "))).replace(/[%_\\]/g, "").trim().slice(0, 40); } catch (_) {}
        if (!key) return await list(env, "0=1", [], 0, "i.n");
        return await list(env, "i.norm LIKE ?", ["%" + key + "%"], Math.min(parseInt(m[2], 10), 4000), "i.updated DESC");
      }
      if ((m = /^\/v1\/b\/(\d+)-(\d+)\.png$/.exec(p))) return await banner(env, parseInt(m[1], 10), Math.min(Math.max(parseInt(m[2], 10), 60), 1000));
      if ((m = /^\/v1\/j\/(\d+)\.(jad|jar)$/.exec(p))) return await download(env, ctx, req, parseInt(m[1], 10), m[2]);
      if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization,content-type,x-token,x-filename", "access-control-allow-methods": "GET,POST,OPTIONS" } });
      if (p === "/up") return new Response(UP_PAGE, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
      if (p === "/v1/up" && req.method === "POST") return await handleUpload(env, ctx, req, u);
      if (p.startsWith("/v1/admin/")) return await admin(env, req, u);
      if ((m = /^\/v1\/f\/([^/]+)$/.exec(p))) return await fileDownload(env, req, decodeURIComponent(m[1]));
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
  },
};
