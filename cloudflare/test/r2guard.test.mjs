// Thử chốt chặn hạn mức free R2 (r2guard.js): ngưỡng 80%/90%, khóa ghi/đọc, dự phòng, chế độ tiết kiệm D1, công tắc tay.
// Chạy: node cloudflare/test/r2guard.test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import w from "../worker.js";
import { r2Allow, r2Note, r2Flush, r2Status, r2Tick, r2Admin, r2Reset, r2SyncApi, r2Hit, meterDb, withGuard, tierEvery, ymOf, dayOf, ecoOn, PLACEHOLDER_JPG } from "../r2guard.js";
import { DATA, BASE } from "../pic-src.js";

const sq = new DatabaseSync(":memory:");
sq.exec("CREATE TABLE files(k TEXT, store TEXT, size INTEGER); CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)");
const DB = {
  prepare(sql) { const s = { args: [], bind(...a) { s.args = a; return s; },
    first: async () => sq.prepare(sql).get(...s.args) || null, all: async () => ({ results: sq.prepare(sql).all(...s.args) }),
    run: async () => { const r = sq.prepare(sql).run(...s.args); return { meta: { changes: Number(r.changes), rows_written: Number(r.changes) * 2 } }; } }; return s; },
  batch: async (l) => { const out = []; for (const x of l) out.push(await x.run()); return out; },
};
const ym = ymOf(), day = dayOf();
const setMeta = (k, v) => sq.prepare("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=?").run(k, String(v), String(v));
const reset = () => { sq.exec("DELETE FROM meta; DELETE FROM files"); r2Reset(); };
const base = { DB, API_HOST: "api.test" };

// 1) vắng vẻ: ok, mọi thứ cho qua
reset();
let s = await r2Status(base, true);
assert.equal(s.state, "ok"); assert.equal(s.writeLock, false); assert.equal(s.readLock, false); assert.equal(s.econ, false);
assert.equal((await r2Allow(base, "write", 1e6)).ok, true);

// 2) lưu trữ 80%: cảnh báo sớm (warn), chưa khóa; cron gửi webhook đúng một lần khi đổi trạng thái
reset(); setMeta("r2x:bytes", 8.2e9);
const hooks = [];
globalThis.fetch = async (u, init) => { hooks.push([String(u), init && init.body]); return new Response("{}"); };
const envA = { ...base, ALERT_URL: "https://hook.test/x" };
let t = await r2Tick(envA);
assert.equal(t.state, "warn"); assert.equal(hooks.length, 1); assert.match(hooks[0][1], /Cảnh báo sớm/);
assert.equal((await r2Allow(envA, "write", 1e6)).ok, true, "warn chưa khóa");
t = await r2Tick(envA); assert.equal(hooks.length, 1, "không gửi lại khi trạng thái không đổi");

// 3) lưu trữ 90%: khóa ghi (đọc vẫn mở); ghi file sắp vượt ngưỡng cũng bị chặn
setMeta("r2x:bytes", 9.1e9); r2Reset();
t = await r2Tick(envA);
assert.equal(t.state, "lock"); assert.match(hooks[1][1], /Chạm ngưỡng khóa/);
let g = await r2Allow(envA, "write", 10); assert.equal(g.ok, false); assert.match(g.reason, /khóa ghi/);
assert.equal((await r2Allow(envA, "read")).ok, true);
setMeta("r2x:bytes", 8.99e9); r2Reset();
assert.equal((await r2Allow(envA, "write", 0.05e9)).ok, false, "8,99GB + 50MB vượt ngưỡng 9GB");
assert.equal((await r2Allow(envA, "write", 1e6)).ok, true);

// 4) Class B: đếm gom, ghi D1 khi đủ 20; chạm 90% thì khóa đọc và /v1/pic chuyển sang dự phòng
reset();
for (let i = 0; i < 19; i++) await r2Note(base, "b");
assert.equal(sq.prepare("SELECT COUNT(*) c FROM meta WHERE k LIKE 'r2b:%'").get().c, 0, "chưa ghi D1 khi chưa đủ lô");
await r2Note(base, "b");
assert.equal(Number(sq.prepare("SELECT v FROM meta WHERE k=?").get("r2b:" + ym).v), 20);
await r2Note(base, "b", 3); await r2Flush(base);
assert.equal(Number(sq.prepare("SELECT v FROM meta WHERE k=?").get("r2b:" + ym).v), 23, "cộng dồn");
const picHits = [];
const R2 = { async get(k) { picHits.push(k); return k === "pic/1_1.jpg" ? { body: new Uint8Array([0xff, 0xd8, 1]) } : null; } };
const envB = { ...base, R2, BLOG_URL: "https://blog.test" };
const ctx = { waitUntil() {} };
let r = await w.fetch(new Request("http://api.test/v1/pic/1_1.jpg"), envB, ctx);
assert.equal(r.status, 200); assert.equal(picHits.length, 1);
setMeta("r2b:" + ym, 9.2e6); r2Reset();
r = await w.fetch(new Request("http://api.test/v1/pic/zz9_1.jpg"), envB, ctx);
assert.equal(picHits.length, 1, "khóa đọc: không đụng R2");
assert.equal(r.status, 200); assert.equal(r.headers.get("x-monika-fallback"), "r2-locked");
assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [...PLACEHOLDER_JPG]);
setMeta("pic:3_1.jpg", "http://dedomil.net/x/3.gif");
r = await w.fetch(new Request("http://api.test/v1/pic/3_1.jpg"), envB, ctx);
assert.equal(r.status, 302); assert.equal(r.headers.get("location"), "http://dedomil.net/x/3.gif");
// bảng ảnh gốc sinh sẵn (pic-src.js, 4.971 ảnh dedomil): khóa đọc thì web được chuyển hướng 302; app (/v1/sc) được Worker tải hộ ảnh gốc
const [n0, u0] = DATA.split("\n")[0].split("\t"); const origin0 = /^https?:/.test(u0) ? u0 : BASE + u0;
r = await w.fetch(new Request("http://api.test/v1/pic/" + n0), envB, ctx);
assert.equal(r.status, 302); assert.equal(r.headers.get("location"), origin0);
const fetched = [];
const keepFetch = globalThis.fetch;
globalThis.fetch = async (u) => { fetched.push(String(u)); return new Response(new Uint8Array([71, 73, 70]), { headers: { "content-type": "image/gif" } }); };
const row = DB.prepare("SELECT 1"); void row;
sq.exec("CREATE TABLE IF NOT EXISTS items(n INTEGER PRIMARY KEY, img TEXT)"); sq.prepare("INSERT OR REPLACE INTO items VALUES(77,?)").run("https://api.test/v1/pic/" + n0);
r = await w.fetch(new Request("http://api.test/v1/sc/77-120x160.jpg"), envB, ctx);
assert.equal(r.status, 200); assert.equal(r.headers.get("content-type"), "image/gif"); assert.deepEqual(fetched, [origin0], "app: tải hộ từ ảnh gốc");
globalThis.fetch = keepFetch;
// thiếu object trong R2 (chưa khóa) mà có ảnh gốc: cũng lấy ảnh gốc thay vì 404
setMeta("r2b:" + ym, 0); r2Reset();
r = await w.fetch(new Request("http://api.test/v1/pic/" + n0), envB, ctx);
assert.equal(r.status, 302); assert.equal(r.headers.get("x-monika-fallback"), "r2-missing");
// JAR trong R2 khi khóa đọc: 503 kèm Retry-After
setMeta("r2b:" + ym, 9.2e6); r2Reset();
const { serveFile } = await import("../store.js");
r = await serveFile(envB, new Request("http://x/"), { store: "r2", skey: "jar/a.jar", fname: "a.jar" });
assert.equal(r.status, 503); assert.equal(r.headers.get("retry-after"), "3600");

// 5) số thật từ Cloudflare: Class A/B tháng, D1 hàng ghi hôm nay, lượt gọi Worker; D1 >= 90% thì chế độ tiết kiệm (econ)
reset();
const api = { d1w: 95000, req: 4000, bytes: 66e6 };
globalThis.fetch = async (u, init) => {
  u = String(u);
  if (u.endsWith("/r2/metrics")) return new Response(JSON.stringify({ success: true, result: { standard: { published: { payloadSize: api.bytes, metadataSize: 144159 } }, infrequentAccess: { published: {} } } }));
  if (u.endsWith("/graphql")) return new Response(JSON.stringify({ data: { viewer: { accounts: [{
    ops: [{ dimensions: { actionType: "PutObject" }, sum: { requests: 5010 } }, { dimensions: { actionType: "GetObject" }, sum: { requests: 214 } }, { dimensions: { actionType: "DeleteObject" }, sum: { requests: 99 } }],
    d1: [{ sum: { rowsWritten: api.d1w } }], w: [{ sum: { requests: api.req } }] }] } } }));
  throw new Error("lạ: " + u);
};
const envC = { ...base, CF_API_TOKEN: "t", CF_ACCOUNT_ID: "acc" };
const sync = await r2SyncApi(envC);
assert.deepEqual([sync.a, sync.b, sync.d1w, sync.req], [5010, 214, 95000, 4000], "DeleteObject miễn phí không tính");
s = await r2Status(envC, true);
assert.equal(s.src, "cloudflare+worker"); assert.equal(s.econ, true); assert.equal(s.state, "lock"); assert.equal(await ecoOn(envC), true);
assert.equal(s.writeLock, false, "R2 chưa khóa vì D1");
t = await r2Tick(envC); assert.equal(t.econ, true);
// cron ở chế độ tiết kiệm: chỉ đồng bộ Blogger 1/4 số lượt, bỏ bù icon/Hot
api.d1w = 50000; sq.exec("DELETE FROM meta WHERE k='r2api:at'"); r2Reset();
s = await r2SyncApi(envC); s = await r2Status(envC, true);
assert.equal(s.econ, false); assert.equal(s.state, "ok");
api.d1w = 82000; await r2SyncApi(envC); s = await r2Status(envC, true);
assert.equal(s.state, "warn", "D1 82% = cảnh báo sớm"); assert.equal(s.econ, false);

// 5b) đo D1 + Worker ngay trong Worker (không cần token): bọc DB, cộng rows_written; bậc tiết kiệm 0-3
reset();
const mdb = meterDb(DB);
await mdb.prepare("INSERT INTO meta(k,v) VALUES('t1','1')").run();
await mdb.batch([mdb.prepare("INSERT INTO meta(k,v) VALUES('t2','1')"), mdb.prepare("INSERT INTO meta(k,v) VALUES('t3','1')")]);
await mdb.prepare("SELECT * FROM meta").all();
for (let i = 0; i < 4; i++) r2Hit({ DB: mdb }, null);
assert.equal(withGuard({ DB: mdb }).DB, mdb, "không bọc hai lần");
await r2Flush({ DB });
assert.equal(Number(sq.prepare("SELECT v FROM meta WHERE k=?").get("r2w:" + day).v), 6, "3 lệnh ghi x 2 hàng");
assert.equal(Number(sq.prepare("SELECT v FROM meta WHERE k=?").get("r2q:" + day).v), 4);
const tierOf = async (n) => { setMeta("r2w:" + day, n); r2Reset(); return (await r2Status(base, true)).tier; };
assert.deepEqual([await tierOf(10000), await tierOf(65000), await tierOf(85000), await tierOf(93000)], [0, 1, 2, 3]);
assert.deepEqual([0, 1, 2, 3].map(tierEvery), [1, 2, 4, 12]);
// đổi bậc thì ghi nhật ký, không cần báo ai (không có ALERT_URL cũng chạy)
setMeta("r2w:" + day, 93000); r2Reset();
t = await r2Tick(base);
assert.equal(t.tier, 3); assert.equal(t.every, 12);
const log = JSON.parse(sq.prepare("SELECT v FROM meta WHERE k='r2:log'").get().v);
assert.equal(log.at(-1).tier, 3); assert.match(log.at(-1).text, /tự đổi luồng/);
assert.equal(await ecoOn(base), true);
// cron theo bậc: chỉ đồng bộ Blogger khi slot chia hết cho bậc (mỗi 12 lượt ở bậc 3)
{
  const realNow = Date.now; let syncs = 0;
  const feed = async (u) => { if (String(u).includes("/feeds/posts/default")) { syncs++; return new Response(JSON.stringify({ feed: { entry: [] } })); } return new Response("{}"); };
  sq.exec("CREATE TABLE IF NOT EXISTS items(n INTEGER PRIMARY KEY, img TEXT)");
  const keep = globalThis.fetch; globalThis.fetch = feed;
  const cronEnv = { ...base, BLOG_URL: "https://blog.test" };
  const slot0 = Math.floor(realNow() / 3600000) * 12; // bội của 12 để đếm đúng 2 lần
  for (let slot = slot0; slot < slot0 + 24; slot++) {
    Date.now = () => slot * 300000 + 1000; r2Reset();
    setMeta("r2w:" + new Date(Date.now()).toISOString().slice(0, 10), 93000);
    await w.scheduled({}, cronEnv, { waitUntil: (p) => p });
  }
  Date.now = realNow; globalThis.fetch = keep;
  assert.equal(syncs, 2, "24 lượt cron ở bậc 3 chỉ đồng bộ 2 lần");
}
setMeta("r2w:" + day, 0); r2Reset();

// 6) công tắc tay
reset();
let a = await r2Admin(base, new URL("http://x/v1/admin/r2?lock=1"), true);
assert.equal(a.state, "lock"); assert.equal(a.writeLock, true); assert.equal(a.readLock, true);
assert.equal((await r2Allow(base, "read")).ok, false);
a = await r2Admin(base, new URL("http://x/v1/admin/r2?lock=0&ext_bytes=9500000000"), true);
assert.equal(a.writeLock, false, "mở tay thắng cả số liệu"); assert.equal(a.state, "warn", "vẫn báo warn vì 95%"); assert.equal((await r2Allow(base, "write", 1)).ok, true);
a = await r2Admin(base, new URL("http://x/v1/admin/r2?lock=auto"), true);
assert.equal(a.state, "lock", "auto: 9,5GB >= 90%");

// 7) D1 hỏng: đọc vẫn cho qua (không mất ảnh), ghi bị từ chối (file mới rơi sang GitHub/KV)
r2Reset();
const bad = { DB: { prepare() { throw new Error("d1 down"); } } };
assert.equal((await r2Allow(bad, "read")).ok, true);
assert.equal((await r2Allow(bad, "write", 1)).ok, false);

console.log("r2guard OK");
