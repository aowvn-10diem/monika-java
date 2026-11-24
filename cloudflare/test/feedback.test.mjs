// Thử góp ý / báo lỗi: captcha, chống spam, trần theo máy/toàn site, gom email. SQLite thật (node:sqlite) làm D1 giả.
// Chạy: node cloudflare/test/feedback.test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import w from "../worker.js";
import { newChallenge, submit, flush, buildDigest, _resetReady } from "../feedback.js";

const here = dirname(fileURLToPath(import.meta.url));
function d1(db) {
  const stmt = (sql) => {
    const s = { sql, args: [], bind(...a) { s.args = a; return s; } };
    s.first = async () => db.prepare(sql).get(...s.args) || null;
    s.all = async () => ({ results: db.prepare(sql).all(...s.args) });
    s.run = async () => { const r = db.prepare(sql).run(...s.args); return { meta: { changes: Number(r.changes) } }; };
    return s;
  };
  return { prepare: stmt, batch: async (list) => { const out = []; for (const x of list) out.push(await x.run()); return out; } };
}
const fresh = (extra = {}) => {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(join(here, "../schema.sql"), "utf8"));
  _resetReady();
  return { db, env: { DB: d1(db), UP_TOKEN: "tok", API_HOST: "api.test", BLOG_URL: "http://m.test", FB_TO: "to@x.vn", FB_FROM: "gopy@x.vn", ...extra } };
};
const solve = (q) => { const m = /^(\d+) ([-+x]) (\d+) = \?$/.exec(q); const a = +m[1], b = +m[3]; return m[2] === "+" ? a + b : m[2] === "-" ? a - b : a * b; };
const NOW = Date.now() + 10000; // token vừa phát: 10 giây trước theo đồng hồ `now`
const body = (o) => new URLSearchParams(o).toString();
async function post(env, fields, { ip = "1.2.3.4", ua = "Monika/0.7.0 (NokiaE72; 320x240)", xff, now = NOW, solveIt = true, ans } = {}) {
  const c = await newChallenge(env);
  const f = { m: "Game này bị treo ở màn 3", k: "bug", g: "5", s: "app", o: "t", q: c.t, a: String(ans != null ? ans : solve(c.q)), ...fields };
  if (!solveIt) f.a = "999";
  const h = { "cf-connecting-ip": ip, "user-agent": ua };
  if (xff) h["x-forwarded-for"] = xff;
  const r = await submit(env, new Request("http://api.test/v1/fb", { method: "POST", body: body(f), headers: h }), now);
  return { status: r.status, text: await r.text(), token: c.t, q: c.q };
}
const count = (db, t = "feedback") => { try { return db.prepare(`SELECT COUNT(*) c FROM ${t}`).get().c; } catch (_) { return 0; } };

// 1. luồng đúng, lưu hàng, replay bị chặn
{
  const { db, env } = fresh();
  const r = await post(env, {});
  assert.equal(r.text, "ok"); assert.equal(count(db), 1);
  const row = db.prepare("SELECT * FROM feedback").get();
  assert.equal(row.kind, "bug"); assert.equal(row.item, 5); assert.equal(row.src, "app"); assert.match(row.ua, /NokiaE72/);
  assert.ok(!/1\.2\.3\.4/.test(JSON.stringify(row)), "không lưu IP thô");
  // dùng lại đúng token đã giải: nội dung khác để không dính chống trùng
  const again = await submit(env, new Request("http://api.test/v1/fb", { method: "POST", body: body({ m: "nội dung khác hẳn", o: "t", q: r.token, a: String(solve(r.q)) }), headers: { "cf-connecting-ip": "1.2.3.4" } }), NOW);
  assert.equal(await again.text(), "err:expired"); assert.equal(count(db), 1);
  console.log("ok luồng đúng + token dùng một lần");
}
// 2. captcha sai / hết hạn / quá nhanh: không ghi D1
{
  const { db, env } = fresh();
  assert.equal((await post(env, {}, { solveIt: false })).text, "err:bad_captcha");
  assert.equal((await post(env, {}, { now: Date.now() + 1000 })).text, "err:too_fast");
  assert.equal((await post(env, {}, { now: Date.now() + 31 * 60000 })).text, "err:expired");
  assert.equal((await post(env, { q: "abc" })).text, "err:bad_captcha");
  assert.equal((await post(env, { a: "" }, { ans: "" })).text, "err:bad_captcha");
  assert.equal(count(db), 0);
  assert.equal(count(db, "fb_rl"), 0, "lỗi captcha không ghi bộ đếm");
  console.log("ok captcha sai/hết hạn/quá nhanh không ghi D1");
}
// 3. độ dài, ô bẫy, nhiều liên kết, ký tự điều khiển
{
  const { db, env } = fresh();
  assert.equal((await post(env, { m: "ab" })).text, "err:short");
  assert.equal((await post(env, { m: "ổn rồi nhé", w: "http://spam" })).text, "ok");
  assert.equal((await post(env, { m: "xem http://a.com và http://b.com đi" })).text, "ok");
  assert.equal(count(db), 0, "honeypot và spam liên kết bị bỏ im lặng");
  assert.equal((await post(env, { m: "x".repeat(900) + "\u0000\u0007" })).text, "ok");
  assert.equal(db.prepare("SELECT length(msg) l FROM feedback").get().l, 400);
  assert.equal((await post(env, { m: "Một liên kết http://a.com thôi" })).text, "ok");
  assert.equal(count(db), 2);
  console.log("ok độ dài, honeypot, liên kết");
}
// 4. trần theo máy, chống trùng, Opera Mini tách theo X-Forwarded-For, trần toàn site
{
  const { db, env } = fresh();
  for (let i = 0; i < 8; i++) assert.equal((await post(env, { m: "góp ý số " + i })).text, "ok");
  assert.equal((await post(env, { m: "góp ý thứ chín" })).text, "err:slow");
  assert.equal(count(db), 8);
  assert.equal((await post(env, { m: "góp ý thứ chín" }, { ip: "9.9.9.9" })).text, "ok", "máy khác vẫn gửi được");
  const dup = await post(env, { m: "góp ý thứ chín" }, { ip: "9.9.9.9" });
  assert.equal(dup.text, "ok"); assert.equal(count(db), 9, "trùng nội dung trong 24h: nhận giả, không lưu");
  // Opera Mini: cùng IP máy chủ Opera, khác người
  const op = "Opera/9.80 (J2ME/MIDP; Opera Mini/7.1.32444/37.9; U; vi)";
  for (let i = 0; i < 8; i++) assert.equal((await post(env, { m: "opera A " + i }, { ip: "5.5.5.5", ua: op, xff: "10.0.0.1" })).text, "ok");
  assert.equal((await post(env, { m: "opera A x" }, { ip: "5.5.5.5", ua: op, xff: "10.0.0.1" })).text, "err:slow");
  assert.equal((await post(env, { m: "opera B 1" }, { ip: "5.5.5.5", ua: op, xff: "10.0.0.2" })).text, "ok", "người khác sau cùng IP Opera không bị chặn oan");
  // trần toàn site
  const d = fresh();
  const day = Math.floor((NOW + 7 * 3600000) / 86400000);
  d.db.exec("CREATE TABLE IF NOT EXISTS fb_rl (k TEXT PRIMARY KEY, n INTEGER, t INTEGER) WITHOUT ROWID");
  d.db.prepare("INSERT INTO fb_rl VALUES(?,?,?)").run("all:" + day, 300, NOW);
  assert.equal((await post(d.env, {})).text, "err:full");
  console.log("ok trần theo máy, chống trùng, Opera Mini, trần toàn site");
}
// 5. RL_FB binding chặn
{
  const { db, env } = fresh({ RL_FB: { limit: async () => ({ success: false }) } });
  assert.equal((await post(env, {})).text, "err:slow"); assert.equal(count(db), 0);
  console.log("ok RL_FB");
}
// 6. web: trang form + POST trả HTML
{
  const { db, env } = fresh();
  db.prepare("INSERT INTO items(src,type,title,published,updated) VALUES('123','game','Tên <Game>',1,1)").run();
  const r = await w.fetch(new Request("http://api.test/fb?g=1"), env, { waitUntil() {} });
  const h = await r.text();
  assert.equal(r.status, 200); assert.match(h, /Tên &lt;Game&gt;/); assert.match(h, /name="q" value="[0-9a-z]+\.[0-9a-f]{12}\.[0-9a-f]{20}"/); assert.match(h, /name="w"/);
  const rp = await (await w.fetch(new Request("http://api.test/fb?p=123"), env, { waitUntil() {} })).text();
  assert.match(rp, /Tên &lt;Game&gt;/); assert.match(rp, /name="g" value="1"/, "tìm theo mã bài Blogger");
  const tok = /name="q" value="([^"]+)"/.exec(h)[1], q = /<b>(\d+ [-+x] \d+ = \?)<\/b>/.exec(h)[1];
  const pr = await submit(env, new Request("http://api.test/v1/fb", { method: "POST", body: body({ m: "Từ web nhé", k: "idea", g: "1", s: "web", q: tok, a: String(solve(q)) }), headers: { "cf-connecting-ip": "2.2.2.2" } }), NOW);
  const ph = await pr.text();
  assert.equal(pr.status, 200); assert.match(ph, /Đã nhận/); assert.match(pr.headers.get("content-type"), /text\/html/);
  const bad = await submit(env, new Request("http://api.test/v1/fb", { method: "POST", body: body({ m: "Từ web nhé 2", g: "1", q: tok, a: "1" }), headers: { "cf-connecting-ip": "2.2.2.2" } }), NOW);
  assert.equal(bad.status, 400); assert.match(await bad.text(), /\/fb\?g=1/);
  assert.equal(count(db), 1);
  // c.bin: UTF câu hỏi + UTF token
  const cb = new Uint8Array(await (await w.fetch(new Request("http://api.test/v1/fb/c.bin"), env, { waitUntil() {} })).arrayBuffer());
  const l1 = (cb[0] << 8) | cb[1]; const q2 = new TextDecoder().decode(cb.slice(2, 2 + l1));
  const l2 = (cb[2 + l1] << 8) | cb[3 + l1]; assert.match(q2, /^\d+ [-+x] \d+ = \?$/); assert.equal(cb.length, 4 + l1 + l2);
  console.log("ok trang web + c.bin");
}
// 7. email tổng hợp: chờ, gửi, lỗi + lùi 15 phút, quản trị
{
  const sent = [];
  const mail = { send: async (m) => { sent.push(m); return { messageId: "x" }; } };
  const { db, env } = fresh({ EMAIL: mail });
  assert.equal(await flush(env, NOW), "none", "chưa có bảng");
  await post(env, { m: "lỗi một" }); await post(env, { m: "lỗi hai", c: "0900000000", k: "idea", g: "" });
  db.prepare("INSERT INTO items(n,src,type,title,url,published,updated) VALUES(5,'z','game','Rồng Thần','http://m.test/x.html',1,1)").run();
  assert.match(await flush(env, NOW), /^sent:cf/);
  assert.equal(sent.length, 1); assert.equal(sent[0].to, "to@x.vn"); assert.match(sent[0].subject, /2 góp ý/);
  assert.match(sent[0].text, /Rồng Thần/); assert.match(sent[0].text, /0900000000/); assert.match(sent[0].text, /lỗi hai/);
  assert.equal(db.prepare("SELECT COUNT(*) c FROM feedback WHERE sent=0").get().c, 0);
  assert.equal(await flush(env, NOW + 1), "empty");
  await post(env, { m: "lỗi ba" }, { ip: "3.3.3.3" });
  assert.equal(await flush(env, NOW + 60000), "wait", "chưa đủ 30 phút từ email trước");
  assert.match(await flush(env, NOW + 31 * 60000), /^sent/);
  // lỗi gửi: giữ tin, lùi 15 phút
  const bad = fresh({ EMAIL: { send: async () => { throw new Error("E_SENDER_DOMAIN_NOT_AVAILABLE"); } } });
  await post(bad.env, { m: "lỗi bốn" });
  assert.match(await flush(bad.env, NOW), /^fail:/);
  assert.equal(await flush(bad.env, NOW + 60000), "backoff");
  assert.equal(bad.db.prepare("SELECT COUNT(*) c FROM feedback WHERE sent=0").get().c, 1);
  // không có kênh gửi
  const none = fresh();
  await post(none.env, { m: "lỗi năm" });
  assert.match(await flush(none.env, NOW), /chưa có kênh gửi/);
  // quản trị
  const a1 = await w.fetch(new Request("http://api.test/v1/admin/feedback"), bad.env, { waitUntil() {} });
  assert.equal(a1.status, 401);
  const a2 = await (await w.fetch(new Request("http://api.test/v1/admin/feedback", { headers: { authorization: "Bearer tok" } }), bad.env, { waitUntil() {} })).json();
  assert.equal(a2.pending, 1); assert.match(a2.lastError, /E_SENDER/); assert.equal(a2.items.length, 1); assert.ok(!("ipk" in a2.items[0]));
  assert.match(buildDigest([{ id: 1, t: NOW, kind: "bug", item: 0, src: "web", msg: "m" }], "http://b"), /#1 · Báo lỗi game/);
  console.log("ok email tổng hợp, lùi khi lỗi, quản trị");
}
console.log("feedback.test: tất cả đạt");
