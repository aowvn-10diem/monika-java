// Góp ý / báo lỗi game: nhận từ app (POST /v1/fb, o=t) và từ web (trang /fb, form HTML thuần, không cần JavaScript),
// lưu D1, cron gom lại gửi email định kỳ cho chủ site. Chạy được trên máy cũ không HTTPS: không dùng Turnstile.
//
// Chống spam (rẻ trước, ghi D1 sau cùng):
//   1. Ô bẫy (honeypot) trên web, tin có từ 2 liên kết trở lên: giả vờ nhận nhưng bỏ.
//   2. Câu hỏi phép tính ngắn, token ký HMAC không cần lưu server (tuổi 3 giây đến 30 phút, dùng một lần nhờ UNIQUE nonce).
//   3. Binding RL_FB (3 lần/phút/IP) nếu có khai.
//   4. Độ dài: nội dung 5..400 ký tự, liên hệ <= 60.
//   5. Trần theo máy (IP băm có muối) 8 tin/ngày, trần toàn site 300 tin/ngày, chống trùng nội dung.
// Trả lời sai captcha hay vượt giới hạn KHÔNG ghi D1 (bảo vệ hạn mức 100.000 hàng ghi/ngày của gói Free).
import { authed } from "./up.js";

const MIN_AGE = 3000, MAX_AGE = 30 * 60000;
const DAY_CAP_IP = 8, DAY_CAP_ALL = 300;
const MSG_MIN = 5, MSG_MAX = 400, CONTACT_MAX = 60;
const MIN_GAP = 30 * 60000; // khoảng cách tối thiểu giữa hai email tổng hợp
const BATCH = 40;
const TZ = 7 * 3600000;
const KINDS = { bug: "Báo lỗi game", idea: "Góp ý", other: "Khác" };

const enc = new TextEncoder();
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
async function hmac(secret, s) {
  const k = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", k, enc.encode(s)));
}
const secretOf = (env) => env.FB_SECRET || env.UP_TOKEN || env.SYNC_TOKEN || "";
const rnd = (n) => crypto.getRandomValues(new Uint32Array(1))[0] % n;
const clip = (s, n) => String(s || "").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "").trim().slice(0, n);

// ---------- captcha phép tính ----------
export async function newChallenge(env, now = Date.now()) {
  let a = 2 + rnd(8), b = 2 + rnd(8), q, ans;
  const t = rnd(3);
  if (t === 0) { q = `${a} + ${b}`; ans = a + b; }
  else if (t === 1) { if (b > a) [a, b] = [b, a]; q = `${a} - ${b}`; ans = a - b; }
  else { q = `${a} x ${b}`; ans = a * b; }
  const nonce = hex(crypto.getRandomValues(new Uint8Array(6)));
  const iat = now.toString(36);
  const sig = (await hmac(secretOf(env), `${iat}.${nonce}.${ans}`)).slice(0, 20);
  return { q: q + " = ?", t: `${iat}.${nonce}.${sig}` };
}
// -> "" nếu đúng, ngược lại mã lỗi: bad_captcha | expired | too_fast
export async function checkChallenge(env, token, answer, now = Date.now()) {
  const m = /^([0-9a-z]{6,12})\.([0-9a-f]{12})\.([0-9a-f]{20})$/.exec(String(token || ""));
  const a = String(answer || "").trim();
  if (!m || !/^-?\d{1,3}$/.test(a)) return "bad_captcha";
  const age = now - parseInt(m[1], 36);
  if (!(age >= 0)) return "bad_captcha";
  const sig = (await hmac(secretOf(env), `${m[1]}.${m[2]}.${parseInt(a, 10)}`)).slice(0, 20);
  let d = 0;
  for (let i = 0; i < 20; i++) d |= sig.charCodeAt(i) ^ m[3].charCodeAt(i);
  if (d !== 0) return "bad_captcha";
  if (age > MAX_AGE) return "expired";
  if (age < MIN_AGE) return "too_fast";
  return "";
}

// ---------- D1 ----------
const SQL = [
  "CREATE TABLE IF NOT EXISTS feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, kind TEXT, item INTEGER, src TEXT, ver TEXT, ua TEXT, dev TEXT, msg TEXT NOT NULL, contact TEXT, ipk TEXT, hash TEXT, nonce TEXT UNIQUE, sent INTEGER DEFAULT 0)",
  "CREATE INDEX IF NOT EXISTS feedback_sent ON feedback(sent, id)",
  "CREATE INDEX IF NOT EXISTS feedback_hash ON feedback(hash)",
  "CREATE TABLE IF NOT EXISTS fb_rl (k TEXT PRIMARY KEY, n INTEGER, t INTEGER) WITHOUT ROWID",
];
let ready = false;
async function ensure(env) {
  if (ready) return;
  for (const s of SQL) await env.DB.prepare(s).run();
  ready = true;
}
export const _resetReady = () => { ready = false; };

async function h16(s) {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(s))).slice(0, 16);
}
// Opera Mini: mọi người dùng chung vài IP máy chủ Opera, IP thật nằm ở X-Forwarded-For. Chỉ dùng cho khóa giới hạn, không tin cho việc khác.
function clientKey(req) {
  const ip = req.headers.get("cf-connecting-ip") || "";
  const ua = req.headers.get("user-agent") || "";
  const xff = (req.headers.get("x-forwarded-for") || "").split(",")[0].trim();
  return /Opera Mini/i.test(ua) && /^[0-9a-f.:]{3,45}$/i.test(xff) ? xff : ip;
}

const resp = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } });
const plain = (s, status = 200) => new Response(s, { status, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } });

const MSG = {
  ok: "Đã nhận, cảm ơn bạn đã góp ý!",
  short: "Nội dung quá ngắn (tối thiểu 5 ký tự).",
  bad_captcha: "Đáp án phép tính chưa đúng, thử lại.",
  expired: "Câu hỏi đã hết hạn, hãy mở lại form.",
  too_fast: "Gửi nhanh quá, đợi vài giây rồi gửi lại.",
  slow: "Bạn gửi quá nhiều, thử lại sau.",
  full: "Hệ thống đang nhận quá nhiều góp ý hôm nay, thử lại ngày mai.",
  big: "Dữ liệu gửi lên quá lớn.",
};

// Xử lý POST. out: "t" (app, văn bản "ok" hoặc "err:<mã>") hoặc "h" (web, trang HTML).
export async function submit(env, req, now = Date.now()) {
  let f;
  try {
    const raw = await req.text();
    if (raw.length > 6000) return replyTo("t", 413, "big", "");
    f = new URLSearchParams(raw);
  } catch (_) { return replyTo("t", 400, "short", ""); }
  const out = f.get("o") === "t" ? "t" : "h";
  const g0 = /^\d{1,9}$/.test(f.get("g") || "") ? f.get("g") : "";
  const reply = (o, st, code) => replyTo(o, st, code, g0);
  const fake = () => reply(out, 200, "ok");
  if (f.get("w")) return fake(); // ô bẫy trên web: người thật không thấy nên không điền
  const msg = clip(f.get("m"), MSG_MAX), contact = clip(f.get("c"), CONTACT_MAX);
  if (msg.length < MSG_MIN) return reply(out, 400, "short");
  const rl = env.RL_FB;
  if (rl && typeof rl.limit === "function") {
    try { if (!(await rl.limit({ key: clientKey(req) || "?" })).success) return reply(out, 429, "slow"); } catch (_) {}
  }
  const bad = await checkChallenge(env, f.get("q"), f.get("a"), now);
  if (bad) return reply(out, 400, bad);
  if ((msg.match(/https?:\/\/|www\./gi) || []).length >= 2) return fake();
  const kind = KINDS[f.get("k")] ? f.get("k") : "other";
  const item = /^\d{1,9}$/.test(f.get("g") || "") ? parseInt(f.get("g"), 10) : 0;
  const src = f.get("s") === "app" ? "app" : "web";
  const ver = clip(f.get("v"), 20), dev = clip(f.get("d"), 24), ua = clip(req.headers.get("user-agent"), 120);
  const nonce = String(f.get("q")).split(".")[1];
  const salt = secretOf(env);
  const day = Math.floor((now + TZ) / 86400000);
  const ipk = await h16(`${salt}|fb|${clientKey(req)}`);
  const hash = await h16(`${salt}|m|${ipk}|${msg.toLowerCase().replace(/\s+/g, " ")}`);
  try {
    await ensure(env);
    const db = env.DB;
    if (await db.prepare("SELECT 1 x FROM feedback WHERE hash=? AND t>?").bind(hash, now - 86400000).first()) return fake(); // gửi trùng
    const mine = await db.prepare("SELECT n FROM fb_rl WHERE k=?").bind(`i:${ipk}:${day}`).first();
    if (mine && mine.n >= DAY_CAP_IP) return reply(out, 429, "slow");
    const all = await db.prepare("SELECT n FROM fb_rl WHERE k=?").bind(`all:${day}`).first();
    if (all && all.n >= DAY_CAP_ALL) return reply(out, 429, "full");
    await db.batch([
      db.prepare("INSERT INTO feedback(t,kind,item,src,ver,ua,dev,msg,contact,ipk,hash,nonce) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)").bind(now, kind, item, src, ver, ua, dev, msg, contact, ipk, hash, nonce),
      db.prepare("INSERT INTO fb_rl(k,n,t) VALUES(?,1,?) ON CONFLICT(k) DO UPDATE SET n=n+1, t=excluded.t").bind(`i:${ipk}:${day}`, now),
      db.prepare("INSERT INTO fb_rl(k,n,t) VALUES(?,1,?) ON CONFLICT(k) DO UPDATE SET n=n+1, t=excluded.t").bind(`all:${day}`, now),
      db.prepare("DELETE FROM fb_rl WHERE t<?").bind(now - 3 * 86400000),
    ]);
  } catch (e) {
    if (/UNIQUE/i.test(String(e && e.message))) return reply(out, 400, "expired"); // token đã dùng rồi
    return reply(out, 500, "slow");
  }
  return reply(out, 200, "ok");
}

function replyTo(out, status, code, g) {
  if (out === "t") return plain(code === "ok" ? "ok" : "err:" + code, status);
  const retry = code === "ok" ? "" : `<p><a href="/fb${g ? "?g=" + g : ""}">Thử lại</a></p>`;
  return new Response(page(MSG[code] || MSG.slow, code === "ok", retry), { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

// ---------- trang HTML cho web (và máy cũ): form thuần, bảng, không JavaScript ----------
const hesc = (t) => String(t == null ? "" : t).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const CSS = "body{background:#1b0a38;color:#fff;font:15px Arial,sans-serif;margin:0;padding:8px}h3{color:#ff7a1a;margin:4px 0}a{color:#ff9a4a}input,select,textarea{font:inherit;max-width:100%}.n{color:#cdbfe8;font-size:12px}.ok{background:#1a7f4b;padding:8px}.er{background:#8a2040;padding:8px}";
function page(note, ok, form) {
  return `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Góp ý / báo lỗi · Monika</title><style>${CSS}</style></head><body>` +
    `<h3>Góp ý / báo lỗi game</h3>${note ? `<p class="${ok ? "ok" : "er"}">${hesc(note)}</p>` : ""}${form || ""}<p><a href="http://m.justmonika.top/">← Về Monika</a></p></body></html>`;
}
// game = mục API (items.n), post = mã bài Blogger (data:post.id trong template, lưu ở items.src)
export async function formPage(env, game, post) {
  const c = await newChallenge(env);
  let it = null;
  const g = /^\d{1,9}$/.test(game || "") ? parseInt(game, 10) : 0;
  try {
    if (g) it = await env.DB.prepare("SELECT n,title FROM items WHERE n=?").bind(g).first();
    else if (/^\d{1,20}$/.test(post || "")) it = await env.DB.prepare("SELECT n,title FROM items WHERE src=?").bind(post).first();
  } catch (_) {}
  const form = `<form method="post" action="/v1/fb">` +
    (it ? `<p>Về game: <b>${hesc(it.title)}</b></p><input type="hidden" name="g" value="${it.n}">` : "") +
    `<input type="hidden" name="q" value="${hesc(c.t)}"><input type="hidden" name="s" value="web">` +
    `<table cellpadding="3" cellspacing="0" width="100%">` +
    `<tr><td>Loại</td><td><select name="k"><option value="bug">Báo lỗi game</option><option value="idea">Góp ý</option><option value="other">Khác</option></select></td></tr>` +
    `<tr><td colspan="2">Nội dung (tối đa ${MSG_MAX} ký tự)<br><textarea name="m" rows="6" cols="30" maxlength="${MSG_MAX}"></textarea></td></tr>` +
    `<tr><td colspan="2">Liên hệ, không bắt buộc (email hoặc số điện thoại)<br><input name="c" size="30" maxlength="${CONTACT_MAX}"></td></tr>` +
    `<tr><td colspan="2" style="display:none">Đừng điền ô này <input name="w" size="10" tabindex="-1" autocomplete="off"></td></tr>` +
    `<tr><td colspan="2">Chống spam: <b>${hesc(c.q)}</b> <input name="a" size="4" maxlength="3" inputmode="numeric"></td></tr>` +
    `<tr><td colspan="2"><input type="submit" value="Gửi góp ý"></td></tr></table></form>` +
    `<p class="n">Góp ý được gửi tới ban quản trị Monika. Tin nhắn không công khai.</p>`;
  return new Response(page("", false, form), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

// App: nhị phân DataOutputStream, UTF = ushort độ dài + modified UTF-8 (chỉ ASCII nên giống UTF-8 thường).
export async function challengeBin(env) {
  const c = await newChallenge(env);
  const b = (s) => { const x = enc.encode(s); return [x.length >> 8, x.length & 255, ...x]; };
  return new Response(new Uint8Array([...b(c.q), ...b(c.t)]), { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "access-control-allow-origin": "*" } });
}

// ---------- gửi email tổng hợp ----------
async function sendMail(env, subject, text) {
  const to = env.FB_TO, from = env.FB_FROM;
  if (!to || !from) throw new Error("thiếu FB_TO/FB_FROM");
  if (env.EMAIL && typeof env.EMAIL.send === "function") {
    await env.EMAIL.send({ to, from: { email: from, name: "Monika" }, subject, text });
    return "cf";
  }
  if (env.RESEND_KEY) {
    const r = await fetch("https://api.resend.com/emails", { method: "POST", headers: { authorization: "Bearer " + env.RESEND_KEY, "content-type": "application/json" }, body: JSON.stringify({ from: `Monika <${from}>`, to: [to], subject, text }) });
    if (!r.ok) throw new Error("resend " + r.status + " " + (await r.text()).slice(0, 120));
    return "resend";
  }
  throw new Error("chưa có kênh gửi email (binding EMAIL hoặc secret RESEND_KEY)");
}
const vn = (t) => { const d = new Date(t + TZ); const p = (n) => String(n).padStart(2, "0"); return `${p(d.getUTCDate())}/${p(d.getUTCMonth() + 1)} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`; };

export function buildDigest(rows, blog) {
  const lines = [];
  for (const r of rows) {
    lines.push(`#${r.id} · ${KINDS[r.kind] || "Khác"} · ${vn(r.t)} (giờ VN)`);
    if (r.item) lines.push(`Game: ${r.title || "(đã xóa)"} (mục ${r.item})${r.url ? " " + r.url : ""}`);
    lines.push("Từ: " + [r.src === "app" ? ("app " + (r.ver || "")).trim() : "web", r.ua, r.dev ? "máy " + r.dev : ""].filter(Boolean).join(" · "));
    if (r.contact) lines.push(`Liên hệ: ${r.contact}`);
    lines.push("", r.msg, "", "----------------------------------------");
  }
  return lines.join("\n") + `\nXem thêm: ${blog || ""}\n`;
}

// Cron gọi mỗi 5 phút. Chỉ gửi khi có tin chưa gửi và đã quá MIN_GAP kể từ email trước (hoặc >= 20 tin chờ). Lỗi: giữ nguyên, thử lại sau 15 phút.
export async function flush(env, now = Date.now()) {
  const db = env.DB;
  let c;
  try { c = await db.prepare("SELECT COUNT(*) c FROM feedback WHERE sent=0").first(); } catch (_) { return "none"; } // chưa có bảng = chưa có góp ý nào
  if (!c || !c.c) return "empty";
  const last = parseInt((await db.prepare("SELECT v FROM meta WHERE k='fb_last'").first() || {}).v || "0", 10);
  const fail = parseInt((await db.prepare("SELECT v FROM meta WHERE k='fb_fail'").first() || {}).v || "0", 10);
  if (now - fail < 15 * 60000) return "backoff";
  if (now - last < MIN_GAP && c.c < 20) return "wait";
  const rows = (await db.prepare("SELECT f.*, i.title title, i.url url FROM feedback f LEFT JOIN items i ON i.n=f.item WHERE f.sent=0 ORDER BY f.id LIMIT ?").bind(BATCH).all()).results;
  const more = c.c - rows.length;
  const subject = `[Monika] ${c.c} góp ý/báo lỗi mới`;
  try {
    const how = await sendMail(env, subject, buildDigest(rows, env.BLOG_URL) + (more > 0 ? `\nCòn ${more} tin nữa, sẽ gửi ở email sau.\n` : ""));
    await db.batch([
      db.prepare(`UPDATE feedback SET sent=1 WHERE id IN (${rows.map(() => "?").join(",")})`).bind(...rows.map((r) => r.id)),
      db.prepare("INSERT INTO meta(k,v) VALUES('fb_last',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(String(now)),
      db.prepare("DELETE FROM meta WHERE k IN ('fb_fail','fb_err')"),
    ]);
    return "sent:" + how;
  } catch (e) {
    await db.batch([
      db.prepare("INSERT INTO meta(k,v) VALUES('fb_fail',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(String(now)),
      db.prepare("INSERT INTO meta(k,v) VALUES('fb_err',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").bind(String((e && e.message) || e).slice(0, 200)),
    ]);
    return "fail:" + (e && e.message);
  }
}

// GET /v1/admin/feedback?limit=50 (Bearer UP_TOKEN): xem góp ý trong D1 kể cả khi email chưa gửi được.
export async function adminList(env, req, u) {
  if (!authed(env, req, u)) return resp({ ok: false, error: "Sai token" }, 401);
  await ensure(env);
  const lim = Math.min(Math.max(parseInt(u.searchParams.get("limit") || "50", 10) || 50, 1), 200);
  const rows = (await env.DB.prepare("SELECT id,t,kind,item,src,ver,ua,dev,msg,contact,sent FROM feedback ORDER BY id DESC LIMIT ?").bind(lim).all()).results;
  const pending = (await env.DB.prepare("SELECT COUNT(*) c FROM feedback WHERE sent=0").first()).c;
  const err = await env.DB.prepare("SELECT v FROM meta WHERE k='fb_err'").first();
  return resp({ ok: true, pending, lastError: err ? err.v : "", mailer: { binding: !!env.EMAIL, resend: !!env.RESEND_KEY, to: env.FB_TO || "", from: env.FB_FROM || "" }, items: rows });
}
