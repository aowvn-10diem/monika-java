// Phân trang số (1 2 3 4 5 6 ... N) cho danh sách bài của Blogger.
// Blogger chỉ có liên kết "mới hơn / cũ hơn": trang p mở bằng ?updated-max=<ngày đăng của bài cuối trang p-1>&max-results=n&start=(p-1)*n&by-date=false
// (đã đối chiếu với liên kết "cũ hơn" Blogger tự sinh). Mốc đó phải tra từ feed, nên Worker tra giúp và trả JSON nhỏ thay cho 7 lần tải feed ~4 KB trên máy cũ.

// Các trang cần hiện: w trang quanh trang hiện tại (trang đầu thì 1..w), cộng trang 1 và trang cuối. Kết quả tăng dần, không trùng.
export function pageWindow(pages, cur, w) {
  const out = [];
  if (pages <= w + 2) { for (let p = 1; p <= pages; p++) out.push(p); return out; }
  const start = Math.min(Math.max(cur - Math.floor(w / 2), 1), pages - w + 1);
  const set = new Set([1, pages]);
  for (let p = start; p < start + w; p++) set.add(p);
  return [...set].sort((a, b) => a - b);
}

// phạm vi: "" (trang chủ), "label:<tên nhãn>", "q:<từ khóa tìm kiếm>"
export function feedPath(scope) {
  scope = String(scope || "").slice(0, 120);
  if (scope.startsWith("label:")) return { path: "/feeds/posts/summary/-/" + encodeURIComponent(scope.slice(6)), q: "" };
  if (scope.startsWith("q:")) return { path: "/feeds/posts/summary", q: "&q=" + encodeURIComponent(scope.slice(2)) };
  return { path: "/feeds/posts/summary", q: "" };
}

// "2026-11-23T00:52:00.000-08:00" -> "2026-11-23T00:52:00-08:00" (đúng dạng Blogger dùng trong updated-max)
export const markOf = (published) => String(published || "").replace(/\.\d+(?=Z|[+-]\d\d:\d\d$)/, "");

async function feedAt(base, fp, index, f) {
  const r = await f(`${base}${fp.path}?alt=json&start-index=${index}&max-results=1${fp.q}`, { headers: { "user-agent": "MonikaPager/1" } });
  if (!r.ok) throw new Error("feed " + r.status);
  const j = (await r.json()).feed || {};
  return { total: parseInt(j["openSearch$totalResults"] && j["openSearch$totalResults"].$t, 10) || 0, published: j.entry && j.entry[0] && j.entry[0].published && j.entry[0].published.$t };
}

// -> { total, n, pages, cur, items: [{ p }, { p, m }, ...] } (m = mốc updated-max của trang p, trang 1 không có)
export async function pagerData(env, params, f = fetch) {
  const base = String(env.BLOG_URL || "").replace(/\/+$/, "");
  const n = Math.min(Math.max(parseInt(params.n, 10) || 16, 4), 40);
  const w = Math.min(Math.max(parseInt(params.w, 10) || 6, 3), 8);
  const fp = feedPath(params.scope);
  const first = await feedAt(base, fp, 1, f);
  const pages = Math.max(1, Math.ceil(first.total / n));
  const cur = Math.min(Math.max(parseInt(params.cur, 10) || 1, 1), pages);
  if (pages < 2) return { total: first.total, n, pages: 1, cur: 1, items: [] };
  const want = pageWindow(pages, cur, w);
  const items = await Promise.all(want.map(async (p) => {
    if (p === 1) return { p };
    const r = await feedAt(base, fp, (p - 1) * n, f);
    return r.published ? { p, m: markOf(r.published) } : null;
  }));
  return { total: first.total, n, pages, cur, items: items.filter(Boolean) };
}

export async function pagerJson(env, u, ctx) {
  const params = { scope: u.searchParams.get("scope") || "", n: u.searchParams.get("n"), cur: u.searchParams.get("cur"), w: u.searchParams.get("w") };
  const hdr = { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=120", "access-control-allow-origin": "*" };
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const ck = new Request("https://pager.invalid/?" + new URLSearchParams(params).toString());
  if (cache) { const hit = await cache.match(ck); if (hit) return hit; }
  let data;
  try { data = await pagerData(env, params); } catch (e) { return new Response(JSON.stringify({ error: String(e && e.message) }), { status: 502, headers: { ...hdr, "cache-control": "no-store" } }); }
  const res = new Response(JSON.stringify(data), { headers: hdr });
  if (cache && ctx) ctx.waitUntil(cache.put(ck, res.clone()));
  return res;
}
