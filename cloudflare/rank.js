// Danh mục chuẩn, đếm lượt tải, xếp hạng, chống lạm dụng. Hợp đồng: docs/api-contract.md mục "v2".
// Giới hạn gói Workers Free: 100.000 request/ngày và 100.000 hàng ghi D1/ngày, nên mỗi lượt đếm chỉ tốn 4 hàng ghi và lọc rẻ trước.
import { TAXONOMY as T } from "./taxonomy.js";

export const DAY = 86400000;
const TZ = 7 * 3600000; // giờ Việt Nam
const PIN = 1000000000;
const MAX_PER_IP = 3; // lượt đếm tối đa mỗi (IP, game, tuần)
const HOT_MIN_D7 = 3; // dưới ngưỡng này không vào Hot
const HOT_SITE_MIN = 30; // cả site d7 dưới ngưỡng này thì Hot rơi về Mới
const NEW_BOOST_DAYS = 14;

export const normK = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

// ---------- taxonomy ----------
export const CATS = new Map(T.cats.map((c) => [c.id, c]));
const CAT_ALIAS = new Map();
for (const c of T.cats) {
  for (const a of [c.name, c.slug.replace(/-/g, " "), ...(c.alias || [])]) {
    const k = normK(a);
    if (k && !CAT_ALIAS.has(k)) CAT_ALIAS.set(k, c.id);
  }
}
const OTHERS = new Set([T.other.game, T.other.app]);

// -> { id, unknown }: id luôn là id chuẩn hoặc null (không khai cat). Tên lạ rơi vào "Khác" của loại, kèm unknown để ghi lại.
export function resolveCat(name, type) {
  const raw = String(name || "").trim();
  const fallback = T.other[type === "app" ? "app" : "game"];
  if (!raw) return { id: null };
  let id = CAT_ALIAS.get(normK(raw));
  if (id === undefined) return { id: fallback, unknown: raw };
  if (OTHERS.has(id)) id = fallback; // "Khác" khớp theo loại bài
  return { id };
}

const bitsOf = (list, s) => {
  let b = 0;
  for (const w of String(s || "").split(/[,;\s]+/)) {
    const k = normK(w);
    if (!k) continue;
    for (const x of list) if (k === normK(x.slug) || k === normK(x.short)) b |= x.bit;
  }
  return b;
};
export const parseTags = (s) => bitsOf(T.tags, s);
export const parsePlat = (s) => bitsOf(T.plats, s) || 1; // chưa khai = J2ME
export function guessTags(title) {
  let b = 0;
  const t = normK(title);
  if (/\bviet hoa\b|\bvh\b/.test(t)) b |= 2;
  if (/\b(mod|crack|hack)\b/.test(t)) b |= 4;
  return b;
}
// "240x320, 320x240" hoặc cỡ ở tên file/link -> "240x320,320x240" (sắp xếp, bỏ trùng, tối đa 6; chỉ nhận 64..800)
export function parseRes(...parts) {
  const set = new Set();
  const re = /(\d{2,3})\s*[x×]\s*(\d{2,3})/g;
  let m;
  const s = parts.join(" ");
  while ((m = re.exec(s)) && set.size < 6) {
    const w = +m[1], h = +m[2];
    if (w >= 64 && w <= 800 && h >= 64 && h <= 800) set.add(w + "x" + h);
  }
  return [...set].sort().join(",");
}
export const tagSlugs = (bits) => T.tags.filter((x) => bits & x.bit).map((x) => x.slug);
export const platSlugs = (bits) => T.plats.filter((x) => bits & x.bit).map((x) => x.slug);
export const catName = (id, fallback = "") => (CATS.get(id) || {}).name || fallback;

// ---------- đếm lượt tải ----------
const BOT_RE = /bot\b|spider|crawl|slurp|curl\/|wget|python|java\/\d|okhttp|httpclient|libwww|scrapy|headless|go-http|axios|node-fetch|monika(img|sync|dl)|facebookexternalhit|preview/i;

async function h8(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Trả chuỗi lý do (để test/gỡ lỗi): "counted" hoặc "skip:..."; không bao giờ ném lỗi ra ngoài.
export async function countDownload(env, req, n) {
  try {
    if (req.method !== "GET") return "skip:method";
    const ua = req.headers.get("user-agent") || "";
    if (!ua || BOT_RE.test(ua)) return "skip:bot";
    const rg = req.headers.get("range");
    if (rg && !/^bytes=0-/.test(rg)) return "skip:range";
    const ip = req.headers.get("cf-connecting-ip") || "";
    if (!ip) return "skip:noip";
    const now = Date.now();
    const week = Math.floor((now + TZ) / (7 * DAY));
    const salt = env.DL_SALT || env.UP_TOKEN || env.SYNC_TOKEN || "";
    const k1 = await h8(`${salt}|a|${ip}|${ua}|${n}|${week}`);
    let ck = null;
    try { if (typeof caches !== "undefined" && caches.default) ck = new Request(`https://dl.invalid/${k1}`); } catch (_) {}
    if (ck) { try { if (await caches.default.match(ck)) return "skip:cache"; } catch (_) {} }
    const ins = await env.DB.prepare("INSERT OR IGNORE INTO dl_seen(k,t) VALUES(?,?)").bind(k1, now).run();
    if (ck) { try { await caches.default.put(ck, new Response("1", { headers: { "cache-control": "public, max-age=86400" } })); } catch (_) {} }
    if (!ins.meta || !ins.meta.changes) return "skip:dup";
    const k2 = await h8(`${salt}|b|${ip}|${n}|${week}`);
    const cap = await env.DB.prepare("INSERT INTO dl_cap(k,n,t) VALUES(?,1,?) ON CONFLICT(k) DO UPDATE SET n=n+1, t=excluded.t RETURNING n").bind(k2, now).first();
    if (cap && cap.n > MAX_PER_IP) return "skip:cap";
    const day = Math.floor((now + TZ) / DAY);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO dl_day(item,day,n) VALUES(?,?,1) ON CONFLICT(item,day) DO UPDATE SET n=n+1").bind(n, day),
      env.DB.prepare("UPDATE items SET dl=dl+1 WHERE n=?").bind(n),
    ]);
    return "counted";
  } catch (e) {
    return "err:" + (e && e.message);
  }
}

// Chặn tải dồn dập: binding RL_DL (6 lần/phút/IP) nếu đã khai trong cấu hình Worker; chỉ áp cho lần bắt đầu tải, không áp cho Range tiếp theo.
export async function tooFast(env, req, binding) {
  const rl = env[binding];
  if (!rl || typeof rl.limit !== "function") return false;
  const rg = req.headers.get("range");
  if (rg && !/^bytes=0-/.test(rg)) return false;
  const ip = req.headers.get("cf-connecting-ip");
  if (!ip) return false;
  try { return !(await rl.limit({ key: ip })).success; } catch (_) { return false; }
}

// Chặn web khác nhúng link file của Monika: có Referer mà không thuộc host của mình thì chuyển về trang mục. Referer rỗng cho qua (điện thoại cũ không gửi).
export function hotlinked(env, req) {
  const ref = req.headers.get("referer");
  if (!ref) return false;
  let host;
  try { host = new URL(ref).hostname.toLowerCase(); } catch (_) { return false; }
  const ok = new Set([(env.API_HOST || "").split(":")[0].toLowerCase(), "localhost", "127.0.0.1"]);
  try { ok.add(new URL(env.BLOG_URL).hostname.toLowerCase()); } catch (_) {}
  for (const x of String(env.REF_OK || "").split(",")) if (x.trim()) ok.add(x.trim().toLowerCase());
  if (ok.has(host)) return false;
  for (const o of ok) if (o && o.includes(".") && host.endsWith("." + o)) return false;
  // cùng miền gốc (m.justmonika.top, api.justmonika.top, justmonika.top)
  const root = (h) => h.split(".").slice(-2).join(".");
  for (const o of ok) if (o && o.includes(".") && root(o) === root(host)) return false;
  return true;
}

// ---------- khóa danh sách ----------
const KEY_RE = /^(new|upd|hot|dl|top1|top7|app|game|c(\d+)|i(\d+))((?:\.(?:new|upd|hot|dl|top1|top7|p\d+|r\d+x\d+|t\d+))*)$/;
const SORTS = new Set(["new", "upd", "hot", "dl", "top1", "top7"]);

// -> { where, binds, sort } hoặc null nếu khóa sai. Dùng cho /v1/l, /v1/l2, /v1/list.json.
export function parseKey(key) {
  const m = KEY_RE.exec(String(key || "").toLowerCase());
  if (!m) return null;
  const base = m[1];
  const where = [], binds = [];
  let sort = SORTS.has(base) ? base : "new";
  if (m[3]) { where.push("i.n=?"); binds.push(parseInt(m[3], 10)); }
  else if (m[2]) { where.push("i.cat_n=?"); binds.push(parseInt(m[2], 10)); }
  else if (base === "app" || base === "game") { where.push("i.type=?"); binds.push(base); }
  for (const mod of (m[4] || "").split(".").filter(Boolean)) {
    if (SORTS.has(mod)) sort = mod;
    else if (mod[0] === "p") { where.push("(i.plat & ?) <> 0"); binds.push(Math.min(parseInt(mod.slice(1), 10), 255)); }
    else if (mod[0] === "t") { where.push("(i.tags & ?) <> 0"); binds.push(Math.min(parseInt(mod.slice(1), 10), 65535)); }
    else if (mod[0] === "r") { where.push("(i.res = '' OR (',' || i.res || ',') LIKE ?)"); binds.push("%," + mod.slice(1) + ",%"); }
  }
  if (sort === "hot" || sort === "top1" || sort === "top7") where.push("i.hold = 0");
  if (sort === "upd") where.push("i.updated - i.published > " + DAY);
  return { where: where.length ? where.join(" AND ") : "1=1", binds, sort };
}

export async function orderFor(env, sort) {
  switch (sort) {
    case "upd": return "i.updated DESC";
    case "dl": return "i.dl DESC, i.published DESC";
    case "top1": return "i.d1 DESC, i.d7 DESC, i.dl DESC, i.published DESC";
    case "top7": return "i.d7 DESC, i.d1 DESC, i.dl DESC, i.published DESC";
    case "hot": {
      const r = await env.DB.prepare("SELECT v FROM meta WHERE k='tot7'").first();
      const tot = r ? parseInt(r.v, 10) || 0 : 0;
      if (tot < HOT_SITE_MIN) return `(CASE WHEN i.score >= ${PIN} THEN 1 ELSE 0 END) DESC, i.published DESC`;
      return `(CASE WHEN i.score >= ${PIN} THEN 2 WHEN i.d7 >= ${HOT_MIN_D7} THEN 1 ELSE 0 END) DESC, i.score DESC, i.published DESC`;
    }
    default: return "i.published DESC, i.n DESC";
  }
}

export const LIST_COLS = `i.n,i.type,i.flags,i.size_kb,i.price,i.ver,i.title,i.descr,i.url,i.published,i.updated,COALESCE(i.cat_n,0) cat,
  i.dl,i.d1,i.d7,i.tags,i.plat,(i.d7 >= 10 AND i.hold = 0) hot,
  (i.ic32 IS NOT NULL OR f.ic32 IS NOT NULL) hasic, (i.img IS NOT NULL AND i.img <> '') hasimg`;

export async function fetchRows(env, where, binds, page, order, pageSize) {
  const db = env.DB;
  const total = (await db.prepare(`SELECT COUNT(*) c FROM items i WHERE i.type IN ('game','app') AND ${where}`).bind(...binds).first()).c;
  const rows = (await db.prepare(
    `SELECT ${LIST_COLS} FROM items i LEFT JOIN files f ON f.k=i.file_h WHERE i.type IN ('game','app') AND ${where} ORDER BY ${order} LIMIT ${pageSize} OFFSET ${page * pageSize}`
  ).bind(...binds).all()).results;
  return { total, rows };
}

export function jsonItem(r, pos, flags) {
  return {
    id: r.n, title: r.title, url: r.url || "", cat: r.cat, catName: catName(r.cat), ver: r.ver || "", sizeKb: r.size_kb || 0, price: r.price || 0,
    dl: r.dl || 0, d7: r.d7 || 0, tags: tagSlugs(r.tags || 0), plat: platSlugs(r.plat || 1), flags,
    icon: r.hasic ? `/v1/ic/${r.n}-64.png` : "", shot: r.hasimg ? `/v1/sc/${r.n}-240x320.jpg` : "", pos,
  };
}

// ---------- tính lại d1/d7/điểm (cron) ----------
export async function recompute(env, now = Date.now()) {
  const db = env.DB;
  const today = Math.floor((now + TZ) / DAY);
  const rows = (await db.prepare(
    "SELECT d.item n, SUM(d.n) d7, SUM(CASE WHEN d.day = ?1 THEN d.n ELSE 0 END) d1, i.published FROM dl_day d JOIN items i ON i.n = d.item WHERE d.day > ?2 GROUP BY d.item"
  ).bind(today, today - 7).all()).results;
  const cur = new Map((await db.prepare("SELECT n,d1,d7,score FROM items WHERE score > 0").all()).results.map((r) => [r.n, r]));
  const pins = (await db.prepare("SELECT n FROM items WHERE (flags & 8) <> 0 AND hold = 0 ORDER BY updated DESC LIMIT 3").all()).results.map((r) => r.n);
  const want = new Map();
  let tot7 = 0;
  for (const r of rows) {
    const raw = (3 * r.d1 + r.d7) * (now - r.published < NEW_BOOST_DAYS * DAY ? 3 : 2);
    want.set(r.n, { d1: r.d1, d7: r.d7, score: raw });
    tot7 += r.d7;
  }
  for (const n of pins) {
    const w = want.get(n) || { d1: 0, d7: 0, score: 0 };
    w.score += PIN;
    want.set(n, w);
  }
  const st = [];
  for (const [n, w] of want) {
    const c = cur.get(n);
    if (!c || c.d1 !== w.d1 || c.d7 !== w.d7 || c.score !== w.score) st.push(db.prepare("UPDATE items SET d1=?,d7=?,score=? WHERE n=?").bind(w.d1, w.d7, w.score, n));
  }
  for (const n of cur.keys()) if (!want.has(n)) st.push(db.prepare("UPDATE items SET d1=0,d7=0,score=0 WHERE n=?").bind(n));
  st.push(db.prepare("INSERT INTO meta(k,v) VALUES('tot7',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(String(tot7)));
  for (let i = 0; i < st.length; i += 50) await db.batch(st.slice(i, i + 50));
  // dọn dữ liệu cũ mỗi ngày một lần
  const last = parseInt(((await db.prepare("SELECT v FROM meta WHERE k='dl_clean'").first()) || {}).v || "0", 10);
  let cleaned = false;
  if (now - last > DAY) {
    await db.batch([
      db.prepare("DELETE FROM dl_day WHERE day <= ?").bind(today - 8),
      db.prepare("DELETE FROM dl_seen WHERE t < ?").bind(now - 9 * DAY),
      db.prepare("DELETE FROM dl_cap WHERE t < ?").bind(now - 9 * DAY),
      db.prepare("INSERT INTO meta(k,v) VALUES('dl_clean',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(String(now)),
    ]);
    cleaned = true;
  }
  return { active: rows.length, updates: st.length - 1, tot7, pins, cleaned };
}

// ---------- endpoint phụ ----------
export async function statsJson(env, ids, paths) {
  const out = {};
  const list = [...new Set(String(ids || "").split(",").map((x) => parseInt(x, 10)).filter((x) => x > 0))].slice(0, 50);
  if (list.length) {
    const rows = (await env.DB.prepare(`SELECT n,dl,d1,d7 FROM items WHERE n IN (${list.map(() => "?").join(",")})`).bind(...list).all()).results;
    for (const r of rows) out[r.n] = { id: r.n, dl: r.dl, d1: r.d1, d7: r.d7 };
  }
  // ?paths=/2026/11/a.html,... (pathname của bài Blogger) -> khóa là chính pathname đó
  const ps = [...new Set(String(paths || "").split(",").map((x) => x.trim()).filter((x) => /^\/[^\s?#]{1,200}$/.test(x)))].slice(0, 50);
  if (ps.length) {
    const base = String(env.BLOG_URL || "").replace(/\/+$/, "");
    const alt = base.replace(/^https?:/i, (m) => (m.toLowerCase() === "https:" ? "http:" : "https:"));
    const urls = [];
    for (const x of ps) { urls.push(base + x); if (alt !== base) urls.push(alt + x); }
    const rows = (await env.DB.prepare(`SELECT n,url,dl,d1,d7 FROM items WHERE url IN (${urls.map(() => "?").join(",")})`).bind(...urls).all()).results;
    for (const r of rows) {
      let pn = ""; try { pn = new URL(r.url).pathname; } catch (_) {}
      if (pn) out[pn] = { id: r.n, dl: r.dl, d1: r.d1, d7: r.d7 };
    }
  }
  return out;
}

export async function catsJson(env) {
  const rows = (await env.DB.prepare(
    "SELECT c.n,c.kind,c.slug,c.sort,COUNT(i.n) cnt FROM cats c JOIN items i ON i.cat_n=c.n AND i.type IN ('game','app') WHERE c.kind IS NOT NULL GROUP BY c.n ORDER BY c.kind,c.sort"
  ).all()).results;
  return rows.map((r) => ({ id: r.n, kind: r.kind, slug: r.slug, name: catName(r.n), count: r.cnt }));
}
