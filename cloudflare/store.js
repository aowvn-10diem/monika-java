// Kho chứa file JAR: R2 (chính) -> GitHub (dự phòng khi R2 đầy/lỗi) -> KV (tạm, khi chưa bật R2/GitHub).
// Cột files.store lưu nơi đã chứa ("r2" | "gh" | "kv"), files.skey là khóa/tên file trong kho đó.
import { b64 } from "./jar.js";
import { r2Limits, r2Allow, r2Note } from "./r2guard.js";

const GB = 1e9;
const lim = (v, d) => { const n = Number(v); return Number.isFinite(n) && n > 0 ? n : d; };

export function storeInfo(env) {
  return {
    r2: !!env.R2, gh: !!(env.GH_TOKEN && env.GH_REPO), kv: !!env.FILES,
    r2Limit: r2Limits(env).bytes * r2Limits(env).lock, // ngưỡng khóa ghi R2 (mặc định 90% của 10GB free), xem r2guard.js
    ghLimit: lim(env.GH_LIMIT, 0.9 * GB), ghMaxFile: lim(env.GH_MAX_FILE, 20e6),
    kvLimit: lim(env.KV_LIMIT, 0.9 * GB),
  };
}

async function used(db, store) {
  return (await db.prepare("SELECT COALESCE(SUM(size),0) s FROM files WHERE store=?").bind(store).first()).s;
}

const ghHead = (env) => ({ authorization: "Bearer " + env.GH_TOKEN, "user-agent": "MonikaUp/1", accept: "application/vnd.github+json" });
const ghPath = (env, fname) => `${(env.GH_DIR || "jars").replace(/^\/+|\/+$/g, "")}/${fname}`;
const ghBranch = (env) => env.GH_BRANCH || "files";

async function ghEnsureBranch(env) {
  const h = ghHead(env), api = `https://api.github.com/repos/${env.GH_REPO}`;
  const r = await fetch(`${api}/git/ref/heads/${ghBranch(env)}`, { headers: h });
  if (r.ok) return;
  const repo = await (await fetch(api, { headers: h })).json();
  const base = await (await fetch(`${api}/git/ref/heads/${repo.default_branch}`, { headers: h })).json();
  const c = await fetch(`${api}/git/refs`, { method: "POST", headers: h, body: JSON.stringify({ ref: "refs/heads/" + ghBranch(env), sha: base.object.sha }) });
  if (!c.ok && c.status !== 422) throw new Error("gh branch " + c.status);
}
async function ghPut(env, fname, bytes) {
  const url = `https://api.github.com/repos/${env.GH_REPO}/contents/${ghPath(env, fname)}`;
  const body = JSON.stringify({ message: "up " + fname, content: b64(bytes), branch: ghBranch(env) });
  let r = await fetch(url, { method: "PUT", headers: ghHead(env), body });
  if (r.status === 404 || r.status === 422) {
    const t = await r.text();
    if (/branch|ref/i.test(t) && !/sha/i.test(t)) { await ghEnsureBranch(env); r = await fetch(url, { method: "PUT", headers: ghHead(env), body }); }
    else if (/sha/i.test(t)) return; // file đã có (cùng tên chuẩn hóa = cùng hash) -> coi như xong
    else throw new Error("gh " + r.status + " " + t.slice(0, 120));
  }
  if (!r.ok) throw new Error("gh " + r.status + " " + (await r.text()).slice(0, 120));
}

// Trả {store, skey, tried:[...lý do bỏ qua]} hoặc ném lỗi nếu mọi kho đều không nhận.
export async function putFile(env, fname, bytes) {
  const si = storeInfo(env), tried = [];
  const size = bytes.length;
  if (si.r2) {
    const g = await r2Allow(env, "write", size); // chốt chặn hạn mức free: bị khóa thì rơi xuống GitHub -> KV
    if (!g.ok) tried.push("r2: " + g.reason);
    else try { await env.R2.put("jar/" + fname, bytes, { httpMetadata: { contentType: "application/java-archive" } }); await r2Note(env, "a"); return { store: "r2", skey: "jar/" + fname, tried }; }
    catch (e) { tried.push("r2: " + e.message); }
  } else tried.push("r2: chưa bật");
  if (si.gh) {
    if (size > si.ghMaxFile) tried.push("gh: file quá lớn");
    else if ((await used(env.DB, "gh")) + size > si.ghLimit) tried.push("gh: đã đầy");
    else try { await ghPut(env, fname, bytes); return { store: "gh", skey: fname, tried }; } catch (e) { tried.push(e.message); }
  } else tried.push("gh: chưa cấu hình GH_TOKEN");
  if (si.kv) {
    if (size > 24e6) tried.push("kv: file quá lớn");
    else if ((await used(env.DB, "kv")) + size > si.kvLimit) tried.push("kv: đã đầy");
    else try { await env.FILES.put("jar:" + fname, bytes); return { store: "kv", skey: "jar:" + fname, tried }; } catch (e) { tried.push("kv: " + e.message); }
  }
  const err = new Error("Không kho nào nhận file: " + tried.join("; "));
  err.tried = tried;
  throw err;
}

export async function delFile(env, f) {
  try {
    if (f.store === "r2" && env.R2) await env.R2.delete(f.skey);
    else if (f.store === "kv" && env.FILES) await env.FILES.delete(f.skey);
    else if (f.store === "gh" && env.GH_TOKEN) {
      const url = `https://api.github.com/repos/${env.GH_REPO}/contents/${ghPath(env, f.skey)}`;
      const g = await fetch(url + "?ref=" + ghBranch(env), { headers: ghHead(env) });
      if (g.ok) {
        const sha = (await g.json()).sha;
        await fetch(url, { method: "DELETE", headers: ghHead(env), body: JSON.stringify({ message: "del " + f.skey, sha, branch: ghBranch(env) }) });
      }
    }
    return true;
  } catch (_) { return false; }
}

const jarHeaders = (f, name) => ({
  "content-type": "application/java-archive", "accept-ranges": "bytes", "cache-control": "public, max-age=3600",
  "content-disposition": `attachment; filename="${name || f.fname}"`, "access-control-allow-origin": "*",
});

// Phát file ra, hỗ trợ Range (máy Java cũ hay tải ngắt quãng).
export async function serveFile(env, req, f, name) {
  const rg = req.headers.get("range");
  if (f.store === "r2" && env.R2) {
    const g = await r2Allow(env, "read");
    if (!g.ok) return new Response("R2 tam nghi de khong vuot han muc mien phi, thu lai sau.", { status: 503, headers: { "retry-after": "3600", "content-type": "text/plain; charset=utf-8", "access-control-allow-origin": "*" } });
    await r2Note(env, "b");
    const o = await env.R2.get(f.skey, rg ? { range: req.headers } : undefined);
    if (!o) return new Response("not found", { status: 404 });
    const h = new Headers(jarHeaders(f, name));
    h.set("content-length", String(o.range ? (o.range.length != null ? o.range.length : o.size - (o.range.offset || 0)) : o.size));
    let status = 200;
    if (rg && o.range) {
      const off = o.range.offset != null ? o.range.offset : o.size - o.range.suffix, len = o.range.length != null ? o.range.length : o.size - off;
      status = 206; h.set("content-range", `bytes ${off}-${off + len - 1}/${o.size}`); h.set("content-length", String(len));
    }
    return new Response(o.body, { status, headers: h });
  }
  if (f.store === "gh") {
    const url = `https://raw.githubusercontent.com/${env.GH_REPO}/${ghBranch(env)}/${ghPath(env, f.skey)}`;
    const hd = { "user-agent": "MonikaDl/1" };
    if (env.GH_TOKEN) hd.authorization = "Bearer " + env.GH_TOKEN;
    if (rg) hd.range = rg;
    const r = await fetch(url, { headers: hd });
    if (!r.ok && r.status !== 206) return new Response("upstream " + r.status, { status: 502 });
    const h = new Headers(jarHeaders(f, name));
    for (const k of ["content-length", "content-range"]) if (r.headers.get(k)) h.set(k, r.headers.get(k));
    return new Response(r.body, { status: r.status, headers: h });
  }
  if (f.store === "kv" && env.FILES) {
    const ab = await env.FILES.get(f.skey, "arrayBuffer");
    if (!ab) return new Response("not found", { status: 404 });
    const total = ab.byteLength, h = new Headers(jarHeaders(f, name));
    const m = rg && /^bytes=(\d*)-(\d*)$/.exec(rg.trim());
    if (m && (m[1] || m[2])) {
      let a = m[1] ? parseInt(m[1], 10) : Math.max(0, total - parseInt(m[2], 10)), b = m[1] && m[2] ? parseInt(m[2], 10) : total - 1;
      if (a >= total) return new Response("range", { status: 416, headers: { "content-range": "bytes */" + total } });
      b = Math.min(b, total - 1);
      h.set("content-range", `bytes ${a}-${b}/${total}`); h.set("content-length", String(b - a + 1));
      return new Response(ab.slice(a, b + 1), { status: 206, headers: h });
    }
    h.set("content-length", String(total));
    return new Response(ab, { headers: h });
  }
  return new Response("store unavailable: " + f.store, { status: 503 });
}
