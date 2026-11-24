// Thử nguồn Pixeldrain: đổi link, thêm khóa, /v1/p chỉ phát tệp waptai-<số>-*.jar. Chạy: node cloudflare/test/pd.test.mjs
import assert from "node:assert/strict";
import { pdDirect, upstream, pdServe, pdImage } from "../pd.js";

assert.equal(pdDirect("https://pixeldrain.com/u/AbC123xy"), "https://pixeldrain.com/api/file/AbC123xy");
assert.equal(pdDirect("https://pixeldrain.com/api/file/AbC123xy?download"), "https://pixeldrain.com/api/file/AbC123xy");
assert.equal(pdDirect("https://example.com/a.jar"), "https://example.com/a.jar");

const calls = [];
globalThis.fetch = async (u, i = {}) => {
  const h = new Headers(i.headers || {});
  calls.push({ u, auth: h.get("authorization"), range: h.get("range") });
  if (u.endsWith("/info")) return new Response(JSON.stringify({ name: u.includes("GOOD") ? "waptai-12-game.jar" : "Other.jar" }), { status: 200 });
  return new Response("JARDATA", { status: i.headers && h.get("range") ? 206 : 200, headers: { "content-length": "7" } });
};
const text = (t, s) => new Response(t, { status: s });
const req = (rg) => new Request("http://x/v1/p/GOOD1234/game.jar", { headers: rg ? { range: rg } : {} });

let r = await pdServe({ PD_KEY: "k" }, req(), "GOOD1234", "game.jar", text);
assert.equal(r.status, 200);
assert.equal(await r.text(), "JARDATA");
assert.equal(calls.at(-1).auth, "Basic " + btoa(":k"));
assert.match(r.headers.get("content-disposition"), /game\.jar/);

r = await pdServe({ PD_KEY: "k" }, req("bytes=0-3"), "GOOD1234", "game.jar", text);
assert.equal(r.status, 206);
assert.equal(calls.at(-1).range, "bytes=0-3");

r = await pdServe({ PD_KEY: "k" }, req(), "BAD12345", "x.jar", text);
assert.equal(r.status, 404); // tên không bắt đầu bằng waptai-<số>-: không phát

r = await pdServe({}, req(), "GOOD1234", "game.jar", text);
assert.equal(r.status, 503);

await upstream({}, "https://pixeldrain.com/api/file/X", {});
assert.equal(calls.at(-1).auth, null);

globalThis.fetch = async (u, i = {}) => {
  if (u.endsWith("/info")) return new Response(JSON.stringify({ name: u.includes("IMG1") ? "waptai-img-0123456789ab.jpg" : "other.jpg" }), { status: 200 });
  return new Response("IMGDATA", { status: 200, headers: { "content-length": "7" } });
};
const ireq = new Request("http://x/v1/pi/IMG12345/waptai-img-0123456789ab.jpg");
r = await pdImage({ PD_KEY: "k" }, ireq, "IMG12345", "waptai-img-0123456789ab.jpg", text);
assert.equal(r.status, 200);
assert.equal(r.headers.get("content-type"), "image/jpeg");
assert.match(r.headers.get("cache-control"), /immutable/);
r = await pdImage({ PD_KEY: "k" }, ireq, "OTHER123", "waptai-img-0123456789ab.jpg", text);
assert.equal(r.status, 404); // tên trên Pixeldrain không khớp
r = await pdImage({ PD_KEY: "k" }, ireq, "IMG12345", "evil.jar", text);
assert.equal(r.status, 404);
r = await pdImage({}, ireq, "IMG12345", "waptai-img-0123456789ab.jpg", text);
assert.equal(r.status, 503);
console.log("pd.test OK");

import { pdIds } from "../pd.js";
assert.deepEqual(pdIds(["240x320|407|JAR=http://api.justmonika.top/v1/p/s3DH8sJs/a_240x320.jar", "320x240|9|JAR=https://pixeldrain.com/u/VdThMV2b", "x|1|JAR=http://a.b/c.jar"]), ["s3DH8sJs", "VdThMV2b"]);
assert.deepEqual(pdIds([]), []);
console.log("pdIds OK");
