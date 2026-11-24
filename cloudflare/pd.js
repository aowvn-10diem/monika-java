// Pixeldrain: tài khoản của sếp chặn hotlink (hotlinking_enabled=false), nên Worker gọi API kèm khóa PD_KEY (secret) ở phía máy chủ.
// Link `pixeldrain.com/u/ID` hoặc `pixeldrain.com/api/file/ID` trong khối mk-meta (`jar:`) được đổi sang API; khóa không bao giờ ra ngoài Worker.
export const PD_RE = /^https?:\/\/(?:www\.)?pixeldrain\.(?:com|dev)\/(?:u|api\/file)\/([A-Za-z0-9]+)/i;

export function pdDirect(u) {
  const m = PD_RE.exec(u || "");
  return m ? `https://pixeldrain.com/api/file/${m[1]}` : u;
}

// fetch tới host nguồn: thêm Basic auth khi là Pixeldrain và Worker có PD_KEY.
export function upstream(env, url, init = {}) {
  if (PD_RE.test(url) && env.PD_KEY) {
    const h = new Headers(init.headers || {});
    h.set("authorization", "Basic " + btoa(":" + env.PD_KEY));
    return fetch(url, { ...init, headers: h });
  }
  return fetch(url, init);
}

// GET /v1/p/<ID>/<tên>.jar: phát lại file Pixeldrain (JAR đã lọc waptai-*) cho máy cũ qua HTTP; hỗ trợ Range. Tên file chỉ để hiển thị khi tải.
export async function pdServe(env, req, id, name, text) {
  if (!env.PD_KEY) return text("pixeldrain chua cau hinh", 503);
  const info = await upstream(env, `https://pixeldrain.com/api/file/${id}/info`, { cf: { cacheTtl: 3600, cacheEverything: true } });
  if (!info.ok) return text("not found", 404);
  const meta = await info.json();
  // chỉ phát JAR đã làm sạch của dự án, không biến Worker thành proxy cho cả thư viện Pixeldrain
  if (!/^waptai-\d+-.+\.jar$/i.test(meta.name || "")) return text("not found", 404);
  const h = {};
  const rg = req.headers.get("range"); if (rg) h.range = rg;
  const r = await upstream(env, `https://pixeldrain.com/api/file/${id}`, { headers: { ...h, "user-agent": "MonikaDl/1" } });
  if (!r.ok && r.status !== 206) return text("upstream " + r.status, 502);
  const hd = new Headers({ "content-type": "application/java-archive", "cache-control": "public, max-age=300", "accept-ranges": "bytes",
    "content-disposition": `attachment; filename="${String(name).replace(/[^\w.-]/g, "_")}"` });
  for (const k of ["content-length", "content-range"]) if (r.headers.get(k)) hd.set(k, r.headers.get(k));
  return new Response(r.body, { status: r.status, headers: hd });
}
