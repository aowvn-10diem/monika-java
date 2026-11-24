// /v1/sc cho ảnh nằm ở /v1/pi của chính API phải đọc thẳng Pixeldrain, không tự fetch tên miền API (Worker tự gọi mình sẽ treo rồi 502).
// Chạy: node cloudflare/test/shot-pi.test.mjs
import assert from "node:assert/strict";
import w from "../worker.js";

const stmt = (sql) => ({ sql, args: [], bind(...a) { this.args = a; return this; },
  async first() { return sql.includes("SELECT img FROM items") ? { img: "http://api.test/v1/pi/Z8ZQ1NbL/waptai-img-152881e2c45d.jpg" } : null; }, async run() {} });
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", PD_KEY: "k", DB: { prepare: (sql) => stmt(sql), batch: async () => {} } };
const urls = [];
globalThis.fetch = async (u) => {
  u = String(u); urls.push(u);
  if (u.includes("api.test")) throw new Error("không được tự fetch tên miền API: " + u);
  if (u.endsWith("/info")) return new Response(JSON.stringify({ name: "waptai-img-152881e2c45d.jpg" }));
  if (u.includes("pixeldrain.com/api/file/")) return new Response(new Uint8Array([0xff, 0xd8, 1, 2, 3]), { headers: { "content-type": "application/octet-stream" } });
  return new Response("no", { status: 404 });
};
const r = await w.fetch(new Request("http://api.test/v1/sc/5058-240x320.jpg"), env, { waitUntil() {} });
assert.equal(r.status, 200);
assert.equal(r.headers.get("content-type"), "image/jpeg");
assert.equal((await r.arrayBuffer()).byteLength, 5);
assert.ok(urls.every((u) => u.includes("pixeldrain.com")), urls.join(" "));
// host khác không được coi là /v1/pi của mình
console.log("shot-pi.test ok");
