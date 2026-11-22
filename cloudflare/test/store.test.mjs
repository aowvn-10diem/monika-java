// Kiểm tra chuỗi dự phòng R2 -> GitHub -> KV bằng kho giả (không cần mạng).
import assert from "node:assert/strict";
import { putFile, serveFile } from "../store.js";

const used = { r2: 0, gh: 0, kv: 0 };
const db = { prepare: () => ({ bind: (s) => ({ first: async () => ({ s: used[s] }) }) }) };
const calls = [];
globalThis.fetch = async (url, init = {}) => {
  calls.push((init.method || "GET") + " " + url);
  const u = String(url);
  if (init.method === "PUT") { // contents API: lần đầu 404 vì chưa có nhánh, sau khi tạo nhánh thì 201
    if (!calls.some((c) => c.startsWith("POST") )) return new Response(JSON.stringify({ message: "Branch files not found" }), { status: 404 });
    return new Response("{}", { status: 201 });
  }
  if (u.endsWith("/git/ref/heads/files")) return new Response("{}", { status: 404 });
  if (u.endsWith("/git/ref/heads/main")) return new Response(JSON.stringify({ object: { sha: "abc" } }));
  if (u.endsWith("/git/refs")) return new Response("{}", { status: 201 });
  if (u.endsWith("/repos/o/r")) return new Response(JSON.stringify({ default_branch: "main" }));
  if (u.startsWith("https://raw.githubusercontent.com/")) return new Response("RAWBYTES", { status: init.headers.range ? 206 : 200, headers: { "content-length": "8" } });
  throw new Error("lạ: " + u);
};
const bytes = new Uint8Array(1000).fill(7);
const env = { DB: db, GH_TOKEN: "t", GH_REPO: "o/r" };

// 1) không R2, có GH -> GH, tự tạo nhánh files
let r = await putFile(env, "a-1-0123456789.jar", bytes);
assert.equal(r.store, "gh"); assert.equal(r.skey, "a-1-0123456789.jar");
assert.ok(calls.some((c) => c === "POST https://api.github.com/repos/o/r/git/refs"));
assert.ok(r.tried[0].startsWith("r2: chưa bật"));

// 2) R2 đầy -> GH
const r2 = { put: async () => { throw new Error("x"); }, delete: async () => {} };
used.r2 = 9.6e9;
r = await putFile({ ...env, R2: r2 }, "a-1-0123456789.jar", bytes);
assert.equal(r.store, "gh"); assert.ok(r.tried[0].includes("đã đầy"));

// 3) R2 lỗi khi ghi -> GH ; R2 ổn -> R2
used.r2 = 0;
r = await putFile({ ...env, R2: r2 }, "a-1-0123456789.jar", bytes);
assert.equal(r.store, "gh"); assert.ok(r.tried[0].startsWith("r2: x"));
const ok = { put: async () => {}, delete: async () => {} };
assert.equal((await putFile({ ...env, R2: ok }, "a.jar", bytes)).store, "r2");

// 4) không R2, không GH, có KV -> KV ; không có gì -> ném lỗi rõ ràng
const kvPut = [];
r = await putFile({ DB: db, FILES: { put: async (k) => kvPut.push(k) } }, "a.jar", bytes);
assert.equal(r.store, "kv");
await assert.rejects(putFile({ DB: db }, "a.jar", bytes), /Không kho nào nhận file/);

// 5) phát file từ GH, có Range
const resp = await serveFile(env, new Request("http://x/", { headers: { range: "bytes=0-7" } }), { store: "gh", skey: "a-1-0123456789.jar", fname: "a-1-0123456789.jar" });
assert.equal(resp.status, 206); assert.equal(await resp.text(), "RAWBYTES");
console.log("store OK");
