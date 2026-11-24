// Thử bài link-only (dòng v1..vN trong mk-meta) và ảnh R2 /v1/pic. Chạy: node cloudflare/test/ext.test.mjs
import assert from "node:assert/strict";
import w, { chooserHtml } from "../worker.js";

const ITEM = { n: 7, src: "555", title: "Asphalt 6 <Adrenaline>", jar: "", jad_text: null, file_h: null, url: "https://blog.test/p/555", img: "https://api.test/v1/pic/7_1.jpg" };
const stmt = (sql) => ({
  sql, args: [],
  bind(...a) { this.args = a; return this; },
  async first() { return sql.includes("FROM items WHERE n=?") ? ITEM : null; },
  async run() {},
});
const store = { "pic/7_1.jpg": new Uint8Array([0xff, 0xd8, 9, 9]) };
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", DB: { prepare: stmt, batch: async () => {} },
  R2: { async get(k) { return store[k] ? { body: store[k] } : null; } } };

let entryHtml = `<pre class="mk-meta">
type: game
v2: 320x240|580|JAD=http://dedomil.net/games/263/download/4042|JAR=http://dedomil.net/games/263/download-jar/4042
v1: 240x320|365|JAD=http://dedomil.net/games/263/download/4037|JAR=javascript:alert(1)
v3: 176x220|305|JAR=ftp://x/y.jar
v: 128x128|126|JAR=http://dedomil.net/games/263/download-jar/4036
v4: kiểu sai|1|JAR=http://x/z.jar
</pre><p>noi dung</p>`;
const fetches = [];
globalThis.fetch = async (u) => {
  u = String(u); fetches.push(u);
  if (u === "https://blog.test/feeds/posts/default/555?alt=json") {
    return new Response(JSON.stringify({ entry: { id: { $t: "tag:blogger.com,1999:blog-1.post-555" }, title: { $t: "x" }, category: [{ term: "Game" }], content: { $t: entryHtml } } }), { headers: { "content-type": "application/json" } });
  }
  return new Response("no", { status: 404 });
};
const ctx = { waitUntil() {} };

// 1) trang chọn bản: đúng thứ tự (v, v1, v2), chỉ link http/https, bỏ dòng sai
let r = await w.fetch(new Request("http://api.test/v1/j/7.jad"), env, ctx);
assert.equal(r.status, 200);
assert.match(r.headers.get("content-type"), /^text\/html/);
const t = await r.text();
assert.ok(t.indexOf("128x128") < t.indexOf("240x320") && t.indexOf("240x320") < t.indexOf("320x240"), t);
assert.ok(t.includes('href="http://dedomil.net/games/263/download/4037">JAD'));
assert.ok(!t.includes("javascript:") && !t.includes("ftp://") && !t.includes("kiểu sai"), "chặn scheme lạ và dòng sai cỡ");
assert.ok(!t.includes("176x220"), "dòng chỉ có link ftp bị bỏ");
assert.ok(t.includes("Asphalt 6 &lt;Adrenaline&gt;"), "thoát HTML tiêu đề");

// 2) không có dòng v nào thì 404; .jar của bài link-only luôn 404
entryHtml = `<pre class="mk-meta">type: game</pre>`;
r = await w.fetch(new Request("http://api.test/v1/j/7.jad"), env, ctx);
assert.equal(r.status, 404);
r = await w.fetch(new Request("http://api.test/v1/j/7.jar"), env, ctx);
assert.equal(r.status, 404);
assert.equal(chooserHtml("T", ["abc"], ""), "");

// 3) /v1/pic đọc R2; /v1/sc đọc thẳng R2, không fetch ra ngoài
r = await w.fetch(new Request("http://api.test/v1/pic/7_1.jpg"), env, ctx);
assert.equal(r.status, 200); assert.equal(r.headers.get("content-type"), "image/jpeg");
assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [0xff, 0xd8, 9, 9]);
r = await w.fetch(new Request("http://api.test/v1/pic/nope.jpg"), env, ctx);
assert.equal(r.status, 404);
const before = fetches.length;
r = await w.fetch(new Request("http://api.test/v1/sc/7-150x113.jpg"), env, ctx);
assert.equal(r.status, 200);
assert.equal(fetches.length, before, "ảnh R2 không đi qua fetch ngoài");
r = await w.fetch(new Request("http://api.test/v1/pic/7_1.jpg"), { ...env, R2: undefined }, ctx);
assert.equal(r.status, 404, "chưa bật R2 thì 404");
console.log("ext.test ok");
