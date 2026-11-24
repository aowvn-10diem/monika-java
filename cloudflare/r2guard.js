// Chốt chặn hạn mức miễn phí của R2 (10 GB-tháng lưu trữ, 1 triệu thao tác Class A, 10 triệu Class B mỗi tháng; nguồn: developers.cloudflare.com/r2/pricing).
// Cloudflare KHÔNG tự khóa khi vượt, chỉ tính tiền. Nên mọi thao tác R2 của Worker đi qua đây:
//  - đếm Class A (ghi, list) và Class B (đọc) theo tháng UTC, lưu trong bảng meta của D1 (không cần migration);
//  - >= WARN_PCT (80%) thì trạng thái "warn" + gửi cảnh báo; >= LOCK_PCT (90%) thì "lock":
//    khóa GHI R2 (file mới tự rơi sang GitHub -> KV) khi lưu trữ hoặc Class A chạm ngưỡng,
//    khóa ĐỌC R2 khi Class B chạm ngưỡng (ảnh chuyển sang nguồn gốc/ảnh giữ chỗ, JAR trả 503);
//  - có CF_API_TOKEN + CF_ACCOUNT_ID thì cron mỗi giờ lấy số thật từ Cloudflare (gồm cả thao tác ngoài Worker như wrangler r2 object put).
// Cùng bộ đếm canh thêm hai hạn mức NGÀY (reset 00:00 UTC) dễ chạm trần nhất: D1 100k hàng ghi/ngày và Worker 100k lượt gọi/ngày (số thật từ Cloudflare, cần CF_API_TOKEN).
// D1 >= LOCK_PCT thì vào "chế độ tiết kiệm D1": cron đồng bộ Blogger thưa đi (20 phút/lần), tạm dừng tính Hot, bù icon và đếm lượt tải. Lượt gọi Worker chỉ cảnh báo (vượt thì Cloudflare tự từ chối, Worker không cứu được).
// Công tắc tay: POST /v1/admin/r2?lock=1|0|auto.
const GB = 1e9;
const lim = (v, d) => { const n = Number(v); return Number.isFinite(n) && n > 0 ? n : d; };

export function r2Limits(env) {
  return {
    bytes: lim(env.R2_STORAGE_LIMIT, 10 * GB), a: lim(env.R2_A_LIMIT, 1e6), b: lim(env.R2_B_LIMIT, 1e7),
    d1w: lim(env.D1_WRITE_LIMIT, 1e5), req: lim(env.REQ_LIMIT, 1e5),
    warn: lim(env.R2_WARN_PCT, 80) / 100, lock: lim(env.R2_LOCK_PCT, 90) / 100,
  };
}
export const ymOf = (t = Date.now()) => new Date(t).toISOString().slice(0, 7);
export const dayOf = (t = Date.now()) => new Date(t).toISOString().slice(0, 10);

const CLASS_A = new Set(["ListBuckets", "PutBucket", "ListObjects", "PutObject", "CopyObject", "CompleteMultipartUpload", "CreateMultipartUpload", "LifecycleStorageTierTransition", "ListMultipartUploads", "UploadPart", "UploadPartCopy", "ListParts", "PutBucketEncryption", "PutBucketCors", "PutBucketLifecycleConfiguration"]);
const CLASS_B = new Set(["HeadBucket", "HeadObject", "GetObject", "UsageSummary", "GetBucketEncryption", "GetBucketLocation", "GetBucketCors", "GetBucketLifecycleConfiguration"]);

// Bộ đếm chờ ghi (mỗi isolate một bộ). Gom lại để không tốn một hàng ghi D1 cho mỗi lượt đọc (D1 free: 100k hàng ghi/ngày).
let pend = { a: 0, b: 0 }, lastFlush = 0, snap = null, snapAt = 0;
const FLUSH_N = 20, FLUSH_MS = 20000, SNAP_MS = 30000;

async function metaGet(env, keys) {
  const q = keys.map(() => "?").join(",");
  const rows = (await env.DB.prepare(`SELECT k,v FROM meta WHERE k IN (${q})`).bind(...keys).all()).results || [];
  const o = {}; for (const r of rows) o[r.k] = r.v;
  return o;
}
const metaSet = (env, k, v) => env.DB.prepare("INSERT INTO meta(k,v) VALUES(?1,?2) ON CONFLICT(k) DO UPDATE SET v=?2").bind(k, String(v)).run();

export async function r2Flush(env) {
  const a = pend.a, b = pend.b;
  if (!a && !b) return;
  pend = { a: 0, b: 0 }; lastFlush = Date.now();
  const ym = ymOf();
  try {
    const st = (k, n) => n ? env.DB.prepare("INSERT INTO meta(k,v) VALUES(?1,?2) ON CONFLICT(k) DO UPDATE SET v=CAST(v AS INTEGER)+?2").bind(k + ym, n) : null;
    await env.DB.batch([st("r2a:", a), st("r2b:", b)].filter(Boolean));
    snap = null;
  } catch (_) { pend.a += a; pend.b += b; } // lỗi D1: giữ lại đếm tiếp lần sau
}

// Ghi nhận n thao tác lớp cls ("a" | "b"). Chỉ chạm D1 khi gom đủ.
export async function r2Note(env, cls, n = 1) {
  pend[cls] += n;
  if (pend.a + pend.b >= FLUSH_N || Date.now() - lastFlush > FLUSH_MS) await r2Flush(env);
}

// Tình trạng hiện tại. Lỗi D1 thì trả null (nơi gọi tự quyết định mở hay đóng).
export async function r2Status(env, fresh = false) {
  if (!fresh && snap && Date.now() - snapAt < SNAP_MS) return withPend(snap);
  try {
    const ym = ymOf(), L = r2Limits(env);
    const day = dayOf();
    const m = await metaGet(env, ["r2a:" + ym, "r2b:" + ym, "r2x:bytes", "r2x:a:" + ym, "r2api:bytes", "r2api:at", "r2api:a:" + ym, "r2api:b:" + ym, "r2api:d1w:" + day, "r2api:req:" + day, "r2:force", "r2:state", "r2:note"]);
    const fs = (await env.DB.prepare("SELECT COALESCE(SUM(size),0) s FROM files WHERE store='r2'").first());
    const n = (k) => Number(m[k]) || 0;
    const apiFresh = n("r2api:at") && Date.now() - n("r2api:at") < 6 * 3600e3;
    snap = {
      ym, L, force: m["r2:force"] || "auto", state: m["r2:state"] || "ok", at: Date.now(), apiAt: n("r2api:at") || 0,
      bytes: apiFresh ? n("r2api:bytes") : (fs ? Number(fs.s) || 0 : 0) + n("r2x:bytes"),
      a: Math.max(n("r2a:" + ym) + n("r2x:a:" + ym), apiFresh ? n("r2api:a:" + ym) : 0),
      b: Math.max(n("r2b:" + ym), apiFresh ? n("r2api:b:" + ym) : 0),
      src: apiFresh ? "cloudflare" : "worker",
      d1w: apiFresh ? n("r2api:d1w:" + day) : 0, req: apiFresh ? n("r2api:req:" + day) : 0, // chỉ có khi đồng bộ được số thật
    };
    snapAt = Date.now();
    return withPend(snap);
  } catch (_) { return null; }
}

function withPend(s) {
  const o = { ...s, a: s.a + pend.a, b: s.b + pend.b };
  const L = s.L;
  const pct = { storage: o.bytes / L.bytes, a: o.a / L.a, b: o.b / L.b, d1w: o.d1w / L.d1w, req: o.req / L.req };
  const top = Math.max(pct.storage, pct.a, pct.b, pct.d1w, pct.req);
  let writeLock = pct.storage >= L.lock || pct.a >= L.lock, readLock = pct.b >= L.lock;
  const reasons = [];
  if (pct.storage >= L.lock) reasons.push("lưu trữ " + Math.round(pct.storage * 100) + "%");
  if (pct.a >= L.lock) reasons.push("Class A " + Math.round(pct.a * 100) + "%");
  if (pct.b >= L.lock) reasons.push("Class B " + Math.round(pct.b * 100) + "%");
  const econ = pct.d1w >= L.lock; // chế độ tiết kiệm D1
  if (econ) reasons.push("D1 ghi " + Math.round(pct.d1w * 100) + "% hạn mức ngày");
  if (pct.req >= L.lock) reasons.push("Worker " + Math.round(pct.req * 100) + "% lượt gọi ngày (chỉ cảnh báo)");
  if (o.force === "1") { writeLock = readLock = true; reasons.push("khóa tay"); }
  else if (o.force === "0") { writeLock = readLock = false; }
  const state = writeLock || readLock || econ || pct.req >= L.lock ? "lock" : top >= L.warn ? "warn" : "ok";
  return { ...o, pct, writeLock, readLock, econ, state, reasons };
}

// Được phép dùng R2 không? kind: "write" (kèm size byte sắp ghi) | "read".
// Không đọc được tình trạng: ĐỌC cho qua (không để lỗi D1 làm mất ảnh), GHI từ chối (file mới tự rơi sang GitHub/KV).
export async function r2Allow(env, kind, size = 0) {
  const s = await r2Status(env);
  if (!s) return kind === "read" ? { ok: true } : { ok: false, reason: "không đọc được bộ đếm hạn mức R2" };
  if (kind === "read") return s.readLock ? { ok: false, reason: "R2 khóa đọc (" + s.reasons.join(", ") + ")", s } : { ok: true, s };
  if (s.writeLock) return { ok: false, reason: "R2 khóa ghi (" + s.reasons.join(", ") + ")", s };
  if (size && s.force !== "0" && s.bytes + size > s.L.bytes * s.L.lock) return { ok: false, reason: "R2 sắp chạm ngưỡng lưu trữ " + Math.round(s.L.lock * 100) + "%", s };
  return { ok: true, s };
}

// Lấy số thật từ Cloudflare (cần secret CF_API_TOKEN quyền đọc R2 + Account Analytics, và CF_ACCOUNT_ID). Không có thì bỏ qua.
export async function r2SyncApi(env) {
  if (!env.CF_API_TOKEN || !env.CF_ACCOUNT_ID) return { skipped: "chưa có CF_API_TOKEN/CF_ACCOUNT_ID" };
  const H = { authorization: "Bearer " + env.CF_API_TOKEN, "content-type": "application/json" };
  const base = "https://api.cloudflare.com/client/v4";
  const ym = ymOf(), since = ym + "-01T00:00:00Z";
  const mj = await (await fetch(`${base}/accounts/${env.CF_ACCOUNT_ID}/r2/metrics`, { headers: H })).json();
  if (!mj.success) return { error: "metrics: " + JSON.stringify(mj.errors || []).slice(0, 160) };
  const sd = mj.result && mj.result.standard && mj.result.standard.published || {};
  const ia = mj.result && mj.result.infrequentAccess && mj.result.infrequentAccess.published || {};
  const bytes = (sd.payloadSize || 0) + (sd.metadataSize || 0) + (ia.payloadSize || 0) + (ia.metadataSize || 0);
  const day = dayOf(), dayStart = day + "T00:00:00Z";
  const q = `query{viewer{accounts(filter:{accountTag:"${env.CF_ACCOUNT_ID}"}){ops:r2OperationsAdaptiveGroups(limit:200,filter:{datetime_geq:"${since}"}){dimensions{actionType} sum{requests}}
    d1:d1AnalyticsAdaptiveGroups(limit:50,filter:{date_geq:"${day}"}){sum{rowsWritten}}
    w:workersInvocationsAdaptive(limit:100,filter:{datetime_geq:"${dayStart}"}){sum{requests}}}}}`;
  const gj = await (await fetch(`${base}/graphql`, { method: "POST", headers: H, body: JSON.stringify({ query: q }) })).json();
  const acc = gj && gj.data && gj.data.viewer && gj.data.viewer.accounts && gj.data.viewer.accounts[0];
  const ops = acc && acc.ops;
  if (!ops) return { error: "graphql: " + JSON.stringify(gj && gj.errors || []).slice(0, 160) };
  const sumOf = (rows, f) => (rows || []).reduce((t, r) => t + ((r.sum && r.sum[f]) || 0), 0);
  const d1w = sumOf(acc.d1, "rowsWritten"), req = sumOf(acc.w, "requests");
  let a = 0, b = 0;
  for (const o of ops) { const t = o.dimensions.actionType, n = o.sum.requests || 0; if (CLASS_A.has(t)) a += n; else if (CLASS_B.has(t)) b += n; }
  await env.DB.batch([["r2api:bytes", bytes], ["r2api:a:" + ym, a], ["r2api:b:" + ym, b], ["r2api:d1w:" + day, d1w], ["r2api:req:" + day, req], ["r2api:at", Date.now()]]
    .map(([k, v]) => env.DB.prepare("INSERT INTO meta(k,v) VALUES(?1,?2) ON CONFLICT(k) DO UPDATE SET v=?2").bind(k, String(v))));
  snap = null;
  return { ok: true, bytes, a, b, d1w, req };
}

const pctTxt = (x) => Math.round(x * 100) + "%";
export function r2Summary(s) {
  const mb = (n) => (n / 1e6).toFixed(1) + " MB";
  return `R2 ${s.state.toUpperCase()}: lưu trữ ${mb(s.bytes)}/${(s.L.bytes / 1e9).toFixed(0)} GB (${pctTxt(s.pct.storage)}), Class A ${s.a}/${s.L.a} (${pctTxt(s.pct.a)}), Class B ${s.b}/${s.L.b} (${pctTxt(s.pct.b)}) tháng ${s.ym}` +
    (s.src === "cloudflare" ? `; hôm nay D1 ghi ${s.d1w}/${s.L.d1w} (${pctTxt(s.pct.d1w)}), Worker ${s.req}/${s.L.req} (${pctTxt(s.pct.req)})` : "; D1/Worker chưa có số (cần CF_API_TOKEN)") +
    (s.reasons.length ? ` — ${s.reasons.join(", ")}` : "") + (s.writeLock || s.readLock || s.econ ? ` [khóa: ${[s.writeLock && "R2 ghi", s.readLock && "R2 đọc", s.econ && "tiết kiệm D1"].filter(Boolean).join("+")}]` : "");
}

// Gọi từ cron (5 phút một lần): ghi đếm còn chờ, đồng bộ số thật mỗi giờ, đổi trạng thái thì cảnh báo (ALERT_URL = webhook nhận JSON {text, content}).
export async function r2Tick(env) {
  const out = {};
  try {
    await r2Flush(env);
    const old = await metaGet(env, ["r2api:at"]);
    if (env.CF_API_TOKEN && Date.now() - (Number(old["r2api:at"]) || 0) > 25 * 60e3) out.sync = await r2SyncApi(env).catch((e) => ({ error: String(e.message).slice(0, 120) }));
    const s = await r2Status(env, true);
    if (!s) return { error: "không đọc được trạng thái" };
    const prev = (await metaGet(env, ["r2:state"]))["r2:state"] || "ok";
    if (prev !== s.state) {
      await metaSet(env, "r2:state", s.state);
      await metaSet(env, "r2:note", r2Summary(s));
      snap = null;
      const text = (s.state === "ok" ? "R2 đã về mức an toàn. " : s.state === "warn" ? "Cảnh báo sớm (>=80% hạn mức free). " : "CHẠM NGƯỠNG KHÓA (>=90% hạn mức free), đã bật chặn/dự phòng. ") + r2Summary(s);
      console.warn(text);
      if (env.ALERT_URL) { try { await fetch(env.ALERT_URL, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ text, content: text }) }); out.alert = "sent"; } catch (e) { out.alert = "fail"; } }
    }
    out.state = s.state; out.econ = s.econ;
  } catch (e) { out.error = String(e && e.message).slice(0, 160); }
  return out;
}

// POST /v1/admin/r2?lock=1|0|auto  hoặc ?ext_bytes=N (cộng dữ liệu nằm ngoài bảng files, ví dụ ảnh seed bằng wrangler) &ext_a=N (cộng Class A ngoài Worker)
export async function r2Admin(env, u, post) {
  if (post) {
    const lock = u.searchParams.get("lock");
    if (lock === "1" || lock === "0") await metaSet(env, "r2:force", lock);
    else if (lock === "auto") await env.DB.prepare("DELETE FROM meta WHERE k='r2:force'").run();
    const xb = u.searchParams.get("ext_bytes");
    if (xb != null && /^\d+$/.test(xb)) await metaSet(env, "r2x:bytes", xb);
    const xa = u.searchParams.get("ext_a");
    if (xa != null && /^\d+$/.test(xa)) await metaSet(env, "r2x:a:" + ymOf(), xa);
    if (u.searchParams.get("sync") === "1") await r2SyncApi(env).catch(() => {});
    snap = null;
  }
  await r2Flush(env);
  const s = await r2Status(env, true);
  return s ? { ok: true, summary: r2Summary(s), state: s.state, writeLock: s.writeLock, readLock: s.readLock, econ: s.econ, force: s.force, month: s.ym, source: s.src, apiAt: s.apiAt,
    storage: { bytes: s.bytes, limit: s.L.bytes, pct: s.pct.storage }, classA: { n: s.a, limit: s.L.a, pct: s.pct.a }, classB: { n: s.b, limit: s.L.b, pct: s.pct.b },
    d1WritesToday: { n: s.d1w, limit: s.L.d1w, pct: s.pct.d1w }, workerRequestsToday: { n: s.req, limit: s.L.req, pct: s.pct.req },
    warnPct: s.L.warn, lockPct: s.L.lock } : { ok: false, error: "không đọc được D1" };
}

// Ảnh giữ chỗ 1x1 (JPEG xám) khi R2 khóa đọc mà không có nguồn gốc để chuyển hướng.
export const PLACEHOLDER_JPG = Uint8Array.from(atob("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/yQALCAABAAEBAREA/8wABgAQEP/aAAgBAQAAPwD0/9k="), (c) => c.charCodeAt(0));

// Đang ở chế độ tiết kiệm D1? (dùng để bỏ qua việc ghi D1 không thiết yếu). Lỗi/thiếu số thì coi là không.
export async function ecoOn(env) { const s = await r2Status(env); return !!(s && s.econ); }

// Chỉ cho test: xóa bộ nhớ đệm trong isolate.
export function r2Reset() { snap = null; pend = { a: 0, b: 0 }; lastFlush = Date.now(); }
