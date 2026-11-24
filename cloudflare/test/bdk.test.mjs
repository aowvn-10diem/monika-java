// Thử nguồn bisduckopath: cờ Port (ẩn khỏi app, hiện trên web) và khóa tải ổn định /v1/p/b<hex>/... trỏ tới tệp hiện hành.
// Chạy: node cloudflare/test/bdk.test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import w from "../worker.js";
import { _resetAliasCache } from "../pd.js";

const here = dirname(fileURLToPath(import.meta.url));
const rd = (f) => readFileSync(join(here, f), "utf8");
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
const schema = rd("../schema.sql") + rd("../migrations/004-taxonomy.sql").split("\n").filter((l) => l.startsWith("INSERT OR REPLACE INTO cats")).join("\n");
const db = new DatabaseSync(":memory:"); db.exec(schema);
const now = new Date().toISOString();
const entry = (id, title, body, labels = ["Game"]) => ({
  id: { $t: "tag:blogger.com,1999:blog-1.post-" + id }, title: { $t: title }, published: { $t: now }, updated: { $t: now },
  category: labels.map((term) => ({ term })), link: [{ rel: "alternate", href: "https://blog.test/p/" + id }], content: { $t: body },
});
const FEED = [
  entry(1, "Game thường", `<pre class="mk-meta">cat: Hành động\nv1: 240x320|400|JAR=http://api.test/v1/p/babcdef0123/g_240x320.jar</pre>`),
  entry(2, "[Port] Game port", `<pre class="mk-meta">cat: Hành động\nport: 1\nv1: 240x320|400|JAR=http://api.test/v1/p/b0123456789/p_240x320.jar</pre>`),
];
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", SYNC_TOKEN: "st", UP_TOKEN: "ut", PD_KEY: "k", DB: d1(db) };
const calls = [];
globalThis.fetch = async (u, i = {}) => {
  u = String(u); calls.push(u);
  if (u.startsWith("https://blog.test/feeds")) return new Response(JSON.stringify({ feed: { entry: FEED } }));
  if (u.includes("bdk-aliases.json")) return new Response(JSON.stringify({ babcdef0123: { pd: "PDnew001", name: "bdk-5-aaaaaa-g.jar" } }));
  if (u.endsWith("/info")) return new Response(JSON.stringify({ name: u.includes("PDnew001") ? "bdk-5-aaaaaa-g.jar" : "Other.jar" }));
  if (u.startsWith("https://pixeldrain.com/api/file/")) return new Response("JARDATA", { headers: { "content-length": "7" } });
  return new Response("x", { status: 404 });
};
const ctx = { waitUntil: (p) => { ctx.p.push(p); }, p: [] };
const call = async (path, headers = {}) => { const r = await w.fetch(new Request("http://api.test" + path, { headers }), env, ctx); await Promise.all(ctx.p.splice(0)); return r; };

assert.equal((await call("/v1/sync?t=st")).status, 200);
const row = (s) => db.prepare("SELECT n,flags FROM items WHERE src=?").get(s);
assert.equal(row("1").flags & 256, 0);
assert.equal(row("2").flags & 256, 256, "meta port: 1 -> cờ 256");

// web (JSON) thấy cả hai, có trường port; app (bin) chỉ thấy game thường
const web = (await (await call("/v1/list.json?key=new")).json()).items;
assert.equal(web.length, 2);
assert.equal(web.find((x) => x.id === row("2").n).port, 1);
assert.equal(web.find((x) => x.id === row("1").n).port, 0);
const total = async (path) => new DataView(await (await call(path)).arrayBuffer()).getUint16(5);
assert.equal(await total("/v1/l/new/0.bin"), 1, "app v1 không có Port");
assert.equal(await total("/v1/l2/new/0.bin"), 1, "app v2 không có Port");
assert.equal(await total("/v1/s/game/0.bin"), 1, "tìm kiếm trong app không có Port");
const newCount = async (p) => new DataView(await (await call(p)).arrayBuffer()).getUint16(5);
assert.equal(await newCount("/v1/home3.bin"), 1, "số mục mới trên app không tính Port");
assert.equal(await newCount("/v1/home.bin"), 1);
const cats = (await (await call("/v1/cats.json")).json()).find((c) => c.id === 10);
assert.equal(cats.count, 2, "danh mục trên web đếm cả Port");

// khóa tải ổn định
_resetAliasCache();
let r = await call("/v1/p/babcdef0123/Game_240x320.jar");
assert.equal(r.status, 200);
assert.equal(await r.text(), "JARDATA");
assert.ok(calls.some((u) => u.endsWith("/api/file/PDnew001")), "phát tệp hiện hành của khóa");
r = await call("/v1/p/b9999999999/x.jar");
assert.equal(r.status, 404, "khóa chưa có trong bản đồ");
r = await call("/v1/p/AbCd1234/x.jar");
assert.equal(r.status, 404, "ID thường vẫn đi đường cũ (tên tệp không hợp lệ -> 404)");
console.log("ok Port ẩn khỏi app, hiện trên web; khóa tải ổn định");
