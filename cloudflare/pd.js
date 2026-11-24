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
