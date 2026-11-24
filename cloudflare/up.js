// Luồng up file: nhận JAR -> trích tên/icon -> chống trùng -> lưu kho -> tạo mục cho app -> sinh HTML bài Blogspot.
import { analyzeJar, makeIcons, sha256hex, slugify, cleanVer, manifestToJad, b64, unb64 } from "./jar.js";
import { upstream } from "./pd.js";
import { putFile, serveFile, delFile, storeInfo } from "./store.js";
import { resolveCat, catName } from "./rank.js";

const MAX_UP = 24 * 1024 * 1024;
const FOREVER = 999999999; // sweep của mục up trực tiếp: không bị quét xóa theo feed Blogger
const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*", "cache-control": "no-store", ...extra } });
const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const norm = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase();
const clip = (s, n) => { s = String(s || ""); return s.length > n ? s.slice(0, n - 1) + "…" : s; };

export function authed(env, req, u) {
  const t = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "") || req.headers.get("x-token") || u.searchParams.get("t") || "";
  const k = env.UP_TOKEN || "";
  if (!k || t.length !== k.length) return false;
  let d = 0;
  for (let i = 0; i < k.length; i++) d |= k.charCodeAt(i) ^ t.charCodeAt(i);
  return d === 0;
}

export const hostOf = (env) => env.API_HOST || "api.justmonika.top";

// ---------- upload ----------
async function ensureItem(env, f, o) {
  const src = "up-" + f.k;
  const ex = await env.DB.prepare("SELECT n FROM items WHERE file_h=? ORDER BY (src LIKE 'up-%') ASC, n LIMIT 1").bind(f.k).first();
  if (ex) return ex.n;
  const now = Date.now(), st = [];
  const rc = resolveCat(o.cat, o.type);
  if (rc.unknown) st.push(env.DB.prepare("INSERT OR IGNORE INTO meta(k,v) VALUES(?,?)").bind("unk:" + rc.unknown.slice(0, 60), src));
  st.push(env.DB.prepare(
    `INSERT INTO items(src,type,title,norm,cat_n,ver,size_kb,price,jar,descr,url,flags,published,updated,sweep,file_h)
     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
     ON CONFLICT(src) DO NOTHING`
  ).bind(src, o.type, f.name, norm(f.name), rc.id || null, f.ver || "", Math.max(1, Math.round(f.size / 1024)), o.price, `https://${hostOf(env)}/v1/f/${f.fname}`, clip(o.desc || f.descr || "", 300), "", (o.net ? 4 : 0) | (o.hot ? 8 : 0), now, now, FOREVER, f.k));
  await env.DB.batch(st);
  return (await env.DB.prepare("SELECT n FROM items WHERE src=?").bind(src).first()).n;
}

function postPack(env, f, o, hasIcon) {
  const host = hostOf(env), kb = Math.max(1, Math.round(f.size / 1024)), desc = (o.desc || f.descr || "").trim();
  const html =
`<pre class="mk-meta">
file: ${f.k}
ver: ${esc(f.ver)}
size: ${kb}
cat: ${esc(catName(resolveCat(o.cat, o.type).id, o.cat))}
jar: https://${host}/v1/f/${f.fname}
net: ${o.net ? 1 : 0}
hot: ${o.hot ? 1 : 0}${desc ? "\ndesc: " + esc(clip(desc, 200)) : ""}
</pre>${hasIcon ? `\n<img src="https://${host}/v1/fi/${f.k}-64.png" width="64" height="64" alt="${esc(f.name)}" />` : ""}
<p>${esc(desc || f.name)}</p>`;
  return { title: f.name, labels: [o.type === "app" ? "App" : "Game", catName(resolveCat(o.cat, o.type).id, o.cat)].filter(Boolean), html };
}

export async function handleUpload(env, ctx, req, u) {
  if (!env.UP_TOKEN) return json({ ok: false, error: "UP_TOKEN chưa được cấu hình" }, 503);
  if (!authed(env, req, u)) return json({ ok: false, error: "Sai token" }, 401);
  const cl = parseInt(req.headers.get("content-length") || "0", 10);
  if (cl > MAX_UP) return json({ ok: false, error: "File quá lớn (tối đa 24MB)" }, 413);
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > MAX_UP) return json({ ok: false, error: "File quá lớn (tối đa 24MB)" }, 413);
  if (!bytes.length) return json({ ok: false, error: "File rỗng" }, 400);
  const q = u.searchParams;
  let orig = q.get("name") || req.headers.get("x-filename") || "";
  try { orig = decodeURIComponent(orig); } catch (_) {}
  const o = {
    type: (q.get("type") || "game").toLowerCase() === "app" ? "app" : "game",
    cat: (q.get("cat") || "").trim().slice(0, 40), desc: (q.get("desc") || "").trim().slice(0, 300),
    price: parseInt(q.get("price") || "0", 10) || 0, net: q.get("net") === "1", hot: q.get("hot") === "1",
    post: q.get("post") !== "0",
  };
  let an;
  try { an = await analyzeJar(bytes, orig); } catch (e) { return json({ ok: false, error: "JAR lỗi: " + e.message }, 400); }
  const h = await sha256hex(bytes), k = h.slice(0, 10);
  const host = hostOf(env);
  const view = (f, extra) => ({
    ok: true, k: f.k, fname: f.fname, name: f.name, vendor: f.vendor, ver: f.ver, sizeKb: Math.max(1, Math.round(f.size / 1024)), store: f.store,
    hasIcon: !!f.has_icon, urls: { jar: `https://${host}/v1/f/${f.fname}`, icon64: f.has_icon ? `https://${host}/v1/fi/${f.k}-64.png` : "", icon32: f.has_icon ? `https://${host}/v1/fi/${f.k}-32.png` : "" }, ...extra,
  });

  const dup = await env.DB.prepare("SELECT k,fname,name,vendor,ver,descr,size,store,has_icon FROM files WHERE h=?").bind(h).first();
  if (dup) {
    const item = o.post ? await ensureItem(env, dup, o) : null;
    return json(view(dup, { duplicate: true, item, post: postPack(env, dup, o, !!dup.has_icon) }));
  }

  let icons = null, iconErr = "";
  if (an.icon) { try { icons = await makeIcons(an.icon); } catch (e) { iconErr = e.message; } }
  const ver = an.ver || "";
  const fname = `${slugify(an.name) || "game"}-${cleanVer(ver)}-${k}.jar`;
  const similar = (await env.DB.prepare("SELECT k,fname,ver FROM files WHERE lower(name)=lower(?) AND IFNULL(vendor,'')=? AND IFNULL(ver,'')=? LIMIT 5").bind(an.name, an.vendor, ver).all()).results;

  let put;
  try { put = await putFile(env, fname, bytes); }
  catch (e) { return json({ ok: false, error: e.message, tried: e.tried || [] }, 507); }

  await env.DB.prepare(
    `INSERT INTO files(k,h,fname,name,vendor,ver,descr,size,store,skey,mf,ic32,ic64,has_icon,orig,created)
     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
  ).bind(k, h, fname, an.name, an.vendor, ver, an.desc, bytes.length, put.store, put.skey, an.manifest, icons ? b64(icons.ic32) : null, icons ? b64(icons.ic64) : null, icons ? 1 : 0, clip(orig, 120), Date.now()).run();
  const f = { k, fname, name: an.name, vendor: an.vendor, ver, descr: an.desc, size: bytes.length, store: put.store, has_icon: icons ? 1 : 0 };
  const item = o.post ? await ensureItem(env, f, o) : null;
  return json(view(f, { duplicate: false, item, similar, fallback: put.tried, iconPath: an.iconPath, iconError: iconErr || undefined, post: postPack(env, f, o, !!icons) }));
}

// ---------- phát file / icon ----------
const KEY_RE = /-([0-9a-f]{10})\.jar$/;
export async function fileDownload(env, req, fname) {
  const m = KEY_RE.exec(fname);
  if (!m) return new Response("not found", { status: 404 });
  const f = await env.DB.prepare("SELECT k,fname,store,skey FROM files WHERE k=?").bind(m[1]).first();
  if (!f) return new Response("not found", { status: 404 });
  return serveFile(env, req, f, f.fname);
}

export async function fileJad(env, n, origin) {
  const it = await env.DB.prepare("SELECT f.mf,f.size FROM items i JOIN files f ON f.k=i.file_h WHERE i.n=?").bind(n).first();
  return it ? manifestToJad(it.mf, `${origin}/v1/j/${n}.jar`, it.size) : null;
}
export async function itemFile(env, n) {
  return env.DB.prepare("SELECT f.k,f.fname,f.store,f.skey FROM items i JOIN files f ON f.k=i.file_h WHERE i.n=?").bind(n).first();
}

const png = (b64s) => new Response(unb64(b64s), { headers: { "content-type": "image/png", "cache-control": "public, max-age=86400", "access-control-allow-origin": "*" } });
export async function iconOfItem(env, n, size) {
  const col = size === 32 ? "ic32" : "ic64";
  const r = await env.DB.prepare(`SELECT COALESCE(f.${col},i.${col}) b FROM items i LEFT JOIN files f ON f.k=i.file_h WHERE i.n=?`).bind(n).first();
  return r && r.b ? png(r.b) : new Response("no icon", { status: 404, headers: { "cache-control": "public, max-age=300" } });
}
export async function iconOfFile(env, k, size) {
  const col = size === 32 ? "ic32" : "ic64";
  const r = await env.DB.prepare(`SELECT ${col} b FROM files WHERE k=?`).bind(k).first();
  return r && r.b ? png(r.b) : new Response("no icon", { status: 404, headers: { "cache-control": "public, max-age=300" } });
}
// Web dùng: ánh xạ đường dẫn bài Blogspot -> icon 64 (cho bài không có meta file:)
export async function iconsJson(env) {
  const rows = (await env.DB.prepare("SELECT i.n,i.url FROM items i LEFT JOIN files f ON f.k=i.file_h WHERE i.url<>'' AND (f.ic64 IS NOT NULL OR i.ic64 IS NOT NULL) LIMIT 500").all()).results;
  const o = {};
  for (const r of rows) { try { o[new URL(r.url).pathname] = `/v1/ic/${r.n}-64.png`; } catch (_) {} }
  return json(o, 200, { "cache-control": "public, max-age=120" });
}

// ---------- JAR ở link ngoài: trích icon + dựng JAD (dùng cho mục viết bài với jar: link) ----------
export async function ingestExternal(env, it, origin) {
  const r = await upstream(env, it.jar, { headers: { "user-agent": "MonikaSync/1" } });
  if (!r.ok) throw new Error("jar " + r.status);
  const cl = parseInt(r.headers.get("content-length") || "0", 10);
  if (cl > MAX_UP) throw new Error("jar quá lớn");
  const buf = new Uint8Array(await r.arrayBuffer());
  const an = await analyzeJar(buf, it.title);
  let icons = null;
  if (an.icon) { try { icons = await makeIcons(an.icon); } catch (_) {} }
  const jad = manifestToJad(an.manifest, `${origin}/v1/j/${it.n}.jar`, buf.length);
  const kb = Math.max(1, Math.round(buf.length / 1024));
  const i32 = icons ? b64(icons.ic32) : null, i64 = icons ? b64(icons.ic64) : null, st = icons ? 1 : 2;
  await env.DB.batch([
    env.DB.prepare("UPDATE items SET jad_text=?, jar_size=?, size_kb=?, ic32=?, ic64=?, icon_state=? WHERE n=?").bind(jad, buf.length, kb, i32, i64, st, it.n),
    // mục khác dùng cùng link JAR: dùng chung icon (JAD của từng mục vẫn dựng riêng khi được hỏi)
    env.DB.prepare("UPDATE items SET ic32=?, ic64=?, icon_state=? WHERE jar=? AND file_h IS NULL AND n<>? AND (icon_state IS NULL OR icon_state=3)").bind(i32, i64, st, it.jar, it.n),
  ]);
  return jad;
}

// Mỗi lần cron: 1 mục chưa có icon (tránh quá giới hạn CPU). Đánh dấu 3 trước, nếu bị cắt giữa chừng thì không thử lại mãi.
export async function backfillIcons(env) {
  const it = await env.DB.prepare("SELECT n,jar,title FROM items WHERE file_h IS NULL AND jar<>'' AND icon_state IS NULL AND type IN ('game','app') ORDER BY n LIMIT 1").first();
  if (!it) return { none: true };
  await env.DB.prepare("UPDATE items SET icon_state=3 WHERE n=?").bind(it.n).run();
  try { await ingestExternal(env, it, `http://${hostOf(env)}`); return { ok: it.n }; }
  catch (e) { await env.DB.prepare("UPDATE items SET icon_state=2 WHERE jar=? AND file_h IS NULL AND icon_state IN (3)").bind(it.jar).run(); return { fail: it.n, err: e.message }; }
}

// ---------- admin ----------
export async function admin(env, req, u) {
  if (!authed(env, req, u)) return json({ ok: false, error: "Sai token" }, 401);
  const p = u.pathname;
  if (p === "/v1/admin/files") {
    const q = norm(u.searchParams.get("q") || ""), lim = Math.min(parseInt(u.searchParams.get("limit") || "50", 10) || 0, 200);
    const rows = lim ? (await env.DB.prepare(
      "SELECT f.k,f.fname,f.name,f.vendor,f.ver,f.size,f.store,f.has_icon,f.created,(SELECT n FROM items WHERE file_h=f.k ORDER BY n LIMIT 1) item FROM files f WHERE ?1='' OR f.fname LIKE '%'||?1||'%' ORDER BY f.created DESC LIMIT ?2"
    ).bind(q.replace(/[%_]/g, ""), lim).all()).results : [];
    const by = (await env.DB.prepare("SELECT store, COUNT(*) c, COALESCE(SUM(size),0) b FROM files GROUP BY store").all()).results;
    const si = storeInfo(env);
    return json({ ok: true, stores: { r2: { bound: si.r2, limit: si.r2Limit }, gh: { configured: si.gh, limit: si.ghLimit }, kv: { bound: si.kv, limit: si.kvLimit } }, usage: by, files: rows });
  }
  if (p === "/v1/admin/del" && req.method === "POST") {
    const k = (u.searchParams.get("k") || "").toLowerCase();
    const f = await env.DB.prepare("SELECT k,store,skey FROM files WHERE k=?").bind(k).first();
    if (!f) return json({ ok: false, error: "không thấy" }, 404);
    const gone = await delFile(env, f);
    await env.DB.batch([env.DB.prepare("DELETE FROM items WHERE file_h=? AND src LIKE 'up-%'").bind(k), env.DB.prepare("DELETE FROM files WHERE k=?").bind(k)]);
    return json({ ok: true, storeDeleted: gone });
  }
  return json({ ok: false, error: "not found" }, 404);
}

// ---------- trang up ----------
export const UP_PAGE = `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Monika · Up game</title>
<style>:root{--bg:#1b0a38;--r1:#250e48;--ln:#3a1a66;--or:#ff7a1a;--mu:#cdbfe8;--pk:#dc1a70}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:#fff;font:15px/1.45 system-ui,sans-serif}
.w{max-width:860px;margin:0 auto;padding:14px}h1{margin:0 0 4px;font-size:22px}h1 b{color:var(--or)}.mu{color:var(--mu);font-size:13px}
.card{background:var(--r1);border:1px solid var(--ln);border-radius:8px;padding:12px;margin:12px 0}
label{display:block;font-size:12px;color:var(--mu);margin:8px 0 3px}input,select,textarea{width:100%;padding:8px;border:1px solid #4a2a7a;border-radius:4px;background:#1f0b3d;color:#fff;font:inherit}
.g{display:grid;grid-template-columns:1fr 1fr;gap:8px}@media(max-width:560px){.g{grid-template-columns:1fr}}
#dz{border:2px dashed #6a3ba0;border-radius:8px;padding:28px 12px;text-align:center;color:var(--mu);cursor:pointer}#dz.on{border-color:var(--or);color:#fff}
button{padding:7px 12px;border:0;border-radius:4px;background:linear-gradient(#cc580c,#b44606);color:#fff;font:inherit;font-weight:700;cursor:pointer}
.row{display:flex;gap:10px;align-items:center;padding:8px 0;border-top:1px solid var(--ln)}.row:first-child{border-top:0}
.ic{flex:none;width:48px;height:48px;border-radius:8px;background:#3b1a6b;image-rendering:pixelated;object-fit:cover}.rc{flex:1;min-width:0}.rc b{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bd{display:inline-block;font-size:11px;padding:0 6px;border-radius:8px;background:#3b1a6b;color:var(--mu);margin-right:4px}.bd.n{background:#1a7f4b;color:#fff}.bd.d{background:#8a6d00;color:#fff}.bd.e{background:var(--pk);color:#fff}
.a{display:flex;gap:6px;flex-wrap:wrap}.a a{color:var(--or);font-size:13px}
</style></head><body><div class="w"><h1><b>M</b>onika · Up game</h1><div class="mu">Thả file .jar vào đây: tự lấy tên + icon, chống trùng, lưu R2 (hết thì GitHub). Mỗi file ra sẵn HTML bài Blogspot.</div>
<div class="card"><label>Token up (lưu trong trình duyệt này)</label><input id="tk" type="password" autocomplete="off"><div class="mu" id="st" style="margin-top:6px"></div>
<div class="g"><div><label>Danh mục (tùy chọn)</label><input id="cat" placeholder="Hành động"></div><div><label>Loại</label><select id="type"><option value="game">Game</option><option value="app">App</option></select></div></div>
<label>Mô tả ngắn (tùy chọn, áp cho cả lô)</label><input id="desc"></div>
<div id="dz">Kéo thả file .jar vào đây hoặc bấm để chọn (nhiều file được)</div><input id="fi" type="file" accept=".jar,application/java-archive" multiple hidden>
<div class="card" id="out" style="display:none"></div></div>
<script>
var $=function(i){return document.getElementById(i)},ls={g:function(k){try{return localStorage.getItem(k)||''}catch(e){return''}},s:function(k,v){try{localStorage.setItem(k,v)}catch(e){}}};
$('tk').value=ls.g('mk_tk');$('cat').value=ls.g('mk_cat');
function stat(){var t=$('tk').value.trim();if(!t){$('st').textContent='';return}ls.s('mk_tk',t);
fetch('/v1/admin/files?limit=0',{headers:{Authorization:'Bearer '+t}}).then(function(r){return r.json()}).then(function(j){if(!j.ok){$('st').textContent='Token sai';return}
var u={};j.usage.forEach(function(x){u[x.store]=x});function m(s){return s?(s.b/1048576).toFixed(1)+' MB / '+s.c+' file':'0'}
$('st').textContent='R2: '+(j.stores.r2.bound?'bật ('+m(u.r2)+')':'chưa bật')+' · GitHub: '+(j.stores.gh.configured?'bật ('+m(u.gh)+')':'chưa cấu hình')+' · KV tạm: '+(j.stores.kv.bound?m(u.kv):'tắt')}).catch(function(){})}
$('tk').onchange=stat;stat();
var dz=$('dz'),fi=$('fi');dz.onclick=function(){fi.click()};fi.onchange=function(){go(fi.files);fi.value=''};
dz.ondragover=function(e){e.preventDefault();dz.className='on'};dz.ondragleave=function(){dz.className=''};dz.ondrop=function(e){e.preventDefault();dz.className='';go(e.dataTransfer.files)};
function sz(b){return b>=1048576?(b/1048576).toFixed(1)+' MB':Math.round(b/1024)+' KB'}
function row(name){var d=document.createElement('div');d.className='row';d.innerHTML='<img class="ic" alt=""><div class="rc"><b></b><div class="mu">Đang up…</div></div>';d.querySelector('b').textContent=name;$('out').style.display='block';$('out').insertBefore(d,$('out').firstChild);return d}
async function one(f){var d=row(f.name),m=d.querySelector('.mu');
try{var q='?name='+encodeURIComponent(f.name)+'&cat='+encodeURIComponent($('cat').value)+'&type='+$('type').value+'&desc='+encodeURIComponent($('desc').value);
var r=await fetch('/v1/up'+q,{method:'POST',headers:{Authorization:'Bearer '+$('tk').value.trim()},body:f}),j=await r.json();
if(!j.ok){m.innerHTML='<span class="bd e">Lỗi</span>';m.appendChild(document.createTextNode(j.error||r.status));return}
d.querySelector('b').textContent=j.name+(j.ver?' · v'+j.ver:'');
if(j.hasIcon)d.querySelector('.ic').src=j.urls.icon64;
m.innerHTML='';var b=function(t,c){var s=document.createElement('span');s.className='bd '+c;s.textContent=t;m.appendChild(s)};
b(j.duplicate?'Trùng, dùng lại':'Mới',j.duplicate?'d':'n');b(sz(f.size),'');b(j.store.toUpperCase(),'');if(!j.hasIcon)b('không có icon','e');
if(j.similar&&j.similar.length)b('trùng tên+phiên bản '+j.similar.length+' file','d');
var a=document.createElement('div');a.className='a';var cp=document.createElement('button');cp.textContent='Chép HTML bài';cp.onclick=function(){navigator.clipboard.writeText(j.post.html).then(function(){cp.textContent='Đã chép'})};a.appendChild(cp);
var l=document.createElement('a');l.href=j.urls.jar;l.textContent='Link tải';a.appendChild(l);
var t=document.createElement('span');t.className='mu';t.textContent='Tiêu đề: '+j.post.title+' · Nhãn: '+j.post.labels.join(', ');a.appendChild(t);m.parentNode.appendChild(a);
}catch(e){m.innerHTML='<span class="bd e">Lỗi</span>'+e.message}stat()}
async function go(fs){ls.s('mk_cat',$('cat').value);for(var i=0;i<fs.length;i++)await one(fs[i])}
</script></body></html>`;
