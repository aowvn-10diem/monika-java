// Thử ảnh minh họa: chọn ảnh từ bài, resize Google, endpoint /v1/sc. Chạy: node cloudflare/test/shot.test.mjs
import assert from "node:assert/strict";
import w from "../worker.js";

const calls = [];
const stmt = (sql) => ({
  sql, args: [],
  bind(...a) { this.args = a; return this; },
  async first() { return sql.includes("SELECT img FROM items") ? { img: "https://blogger.googleusercontent.com/img/b/R29v/AVvX/s1600/shot.png" } : null; },
  async run() { calls.push(this); },
});
const batches = [];
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", SYNC_TOKEN: "t", DB: {
  prepare: (sql) => stmt(sql),
  batch: async (st) => { batches.push(...st); },
} };
const entry = (id, body) => ({ id: { $t: "tag:blogger.com,1999:blog-1.post-" + id }, title: { $t: "Game " + id }, published: { $t: "2026-11-20T00:00:00Z" }, updated: { $t: "2026-11-20T00:00:00Z" },
  category: [{ term: "Game" }], link: [{ rel: "alternate", href: "https://blog.test/p/" + id }], content: { $t: body } });
const feed = { feed: { entry: [
  entry(1, `<pre class="mk-meta">ver: 1</pre><img src="https://api.test/v1/fi/0123456789-64.png" width="64"/><img src="https://blogger.googleusercontent.com/img/b/A/s1600/a.png&amp;x=1"/>`),
  entry(2, `<img src="https://x/y/tiny.png" width="48"/><img src="https://x/y/big.jpg"/>`),
  entry(3, `<pre class="mk-meta">img: https://z/shot.jpg</pre><img src="https://x/other.jpg"/>`),
  entry(4, `<img src="https://api.test/v1/ic/9-32.png"/>`),
] } };
const fetches = [];
globalThis.fetch = async (u, o) => {
  u = String(u); fetches.push(u);
  if (u.includes("/feeds/posts/default")) return new Response(JSON.stringify(feed), { headers: { "content-type": "application/json" } });
  if (u.includes("-c-rj-l70")) return new Response(new Uint8Array([0xff, 0xd8, 1, 2]), { headers: { "content-type": "image/jpeg" } });
  return new Response("no", { status: 404 });
};

// 1) sync chọn đúng ảnh minh họa
await w.fetch(new Request("http://api.test/v1/sync?t=t"), env, { waitUntil() {} });
const ins = batches.filter((s) => s.sql.includes("INSERT INTO items"));
assert.equal(ins.length, 4);
const imgOf = (s) => s.args[16]; // vị trí cột img trong bind
assert.equal(imgOf(ins[0]), "https://blogger.googleusercontent.com/img/b/A/s1600/a.png&x=1", "bỏ icon /v1/fi/, giải mã &amp;");
assert.equal(imgOf(ins[1]), "https://x/y/big.jpg", "bỏ ảnh width<=80");
assert.equal(imgOf(ins[2]), "https://z/shot.jpg", "meta img ưu tiên");
assert.equal(imgOf(ins[3]), "", "chỉ có icon thì không có ảnh minh họa");

// 2) endpoint /v1/sc cắt đúng cỡ qua Google
const r = await w.fetch(new Request("http://api.test/v1/sc/7-150x113.jpg"), env, { waitUntil() {} });
assert.equal(r.status, 200);
assert.equal(r.headers.get("content-type"), "image/jpeg");
assert.ok(fetches.some((u) => u.endsWith("/w150-h113-c-rj-l70/shot.png")), fetches.join("\n"));
// 3) kẹp kích thước
await w.fetch(new Request("http://api.test/v1/sc/7-9999x1.jpg"), env, { waitUntil() {} });
assert.ok(fetches.some((u) => u.includes("/w320-h30-c-rj-l70/")));
console.log("shot.test ok");
