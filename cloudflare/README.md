# Monika API (Cloudflare Worker + D1)

Host: `http://api.justmonika.top` (HTTP cổng 80 cho máy Java cũ). Worker `monika-api`, D1 `monika`, cron mỗi 5 phút quét feed Blogger `https://m.justmonika.top/feeds/posts/default`.

- `worker.js`: mã nguồn Worker. Bản đang chạy là bản rút gọn của file này (esbuild --minify).
- `schema.sql`: lược đồ D1.
- Contract nhị phân giữa Worker, web và app Java: xem project memory `monika-api-contract`.
- Cách đăng bài: `../blogspot/HUONG-DAN-DANG-BAI.md`.

Biến cấu hình: `API_HOST`, `BLOG_URL` (plain text), `SYNC_TOKEN` (secret, dùng cho `/v1/sync?t=` chạy tay một lượt quét).
Link JAR: GitHub, Google Drive, host riêng. Pixeldrain chặn hotlink nên không dùng được.
