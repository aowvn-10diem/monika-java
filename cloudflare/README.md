# Monika API (Cloudflare Worker + D1)

Host: `http://api.justmonika.top` (HTTP cổng 80 cho máy Java cũ). Worker `monika-api`, D1 `monika`, cron mỗi 5 phút quét feed Blogger `https://m.justmonika.top/feeds/posts/default`.

- `worker.js`: mã nguồn Worker. Bản đang chạy là bản rút gọn của file này (esbuild --minify).
- `schema.sql`: lược đồ D1.
- Contract nhị phân giữa Worker, web và app Java: xem project memory `monika-api-contract`.
- Cách đăng bài: `../blogspot/HUONG-DAN-DANG-BAI.md`.

Biến cấu hình: `API_HOST`, `BLOG_URL` (plain text), `SYNC_TOKEN` (secret, dùng cho `/v1/sync?t=` chạy tay một lượt quét).
Link JAR: GitHub, Google Drive, host riêng. Pixeldrain chặn hotlink nên Worker gọi API kèm secret `PD_KEY` (`cloudflare/pd.js`): `jar: https://pixeldrain.com/u/<ID>` dùng được.

## Up file, icon, kho lưu trữ

- `POST /v1/up?name=<file>&type=game|app&cat=&desc=&net=&hot=&post=0|1` (header `Authorization: Bearer <UP_TOKEN>` hoặc `?token=`) nhận JAR thô, trả JSON gồm link, icon, khối HTML bài đăng. Trang giao diện: `/up`.
- `GET /v1/f/<tên>-<hash10>.jar` tải JAR (có Range), `/v1/fi/<hash10>-32|64.png` icon theo file, `/v1/ic/<id>-32|64.png` icon theo mục, `/v1/icons.json` bảng đường dẫn bài → icon cho web.
- `/v1/admin/files`, `/v1/admin/del` (cần token) xem thống kê và xóa file.
- Kho lưu: R2 (binding `R2`, tạo bucket `monika-files`) → GitHub (`GH_TOKEN`, repo `GH_REPO`, tối đa 20MB mỗi file, giới hạn mềm 0,9GB) → KV `FILES` (tối đa 25MB mỗi file, giới hạn mềm 0,9GB). Chưa bật R2 thì tự dùng KV.
- Cron mỗi 5 phút đồng bộ blog và bù icon cho file cũ (`backfillIcons`, có cơ chế chặn file lỗi lặp lại).
- Ảnh minh họa cho lưới trong app: cột `items.img` (migration `migrations/003-screenshot.sql`, **chạy trước khi deploy Worker mới**), cờ 32 trong `flags`, `GET /v1/sc/<id>-<w>x<h>.jpg` (Google thu nhỏ vừa khung, giữ tỉ lệ, JPEG q70, Cloudflare cache 1 ngày). Cron 5 phút tự điền `img` từ bài Blogspot. Thử: `node cloudflare/test/shot.test.mjs`.
- Migration: `migrations/002-upload.sql` (cột `file_h`, `jad_text`, `jar_size`, `ic32`, `ic64`, `icon_state`, bảng `files`).
- Thử cục bộ: `node cloudflare/test/jar.test.mjs`, `store.test.mjs`, `test/integ.sh` (cần wrangler).

## Danh mục chuẩn, đếm lượt tải, xếp hạng (v2)

- Nguồn danh mục: `catalog/taxonomy.json`. Sửa xong chạy `node tools/gen-taxonomy.mjs` (sinh `taxonomy.js` và `migrations/004-taxonomy.sql`); `--check` để kiểm tra file sinh ra còn khớp.
- Migration 004 (thêm cột `tags`, `plat`, `res`, `d1`, `d7`, `score`, `hold`, bảng `dl_seen`, `dl_cap`, `dl_day`, seed 27 danh mục, ánh xạ 4 danh mục demo cũ): **chạy trước, deploy Worker sau**. Chỉ chạy một lần.
- Đếm lượt tải (`rank.js`): 1 lượt = byte đầu file được phát, 1 lượt/máy/game/tuần, trần 3 lượt/IP/game/tuần, bot/HEAD/Range giữa chừng/UA rỗng không đếm, JAD không đếm. Không lưu IP (chỉ băm có muối). Mỗi lượt đếm tốn 4 hàng ghi D1.
- Cron 5 phút: cứ 15 phút tính lại `d1`/`d7`/điểm Hot (chỉ ghi mục thay đổi), mỗi ngày dọn dữ liệu quá 8 ngày. Hot: ghim tay (cờ `hot: 1` trong meta, tối đa 3 mục) rồi điểm `(3·d1 + d7) × (đăng dưới 14 ngày ? 1,5 : 1)`, cần `d7 ≥ 3`; cả site `d7 < 30` thì Hot = Mới.
- `hold = 1` (đặt tay trong D1: `UPDATE items SET hold=1 WHERE n=?`) loại mục khỏi Hot, Hôm nay, Tuần.
- Chặn lạm dụng: binding `RL_DL` (6 lần bắt đầu tải/phút/IP) và `RL_SEARCH` (60 lần/phút/IP) khai trong `wrangler.jsonc`; hotlink theo Referer (Referer rỗng cho qua); `REF_OK` thêm host được phép. Không bật Bot Fight Mode / JS challenge cho host API (máy J2ME không giải được).
- Endpoint mới: `/v1/home2.bin`, `/v1/l2/{key}/{page}.bin`, `/v1/list.json`, `/v1/stats.json`, `/v1/cats.json` (xem `docs/api-contract.md`, mục v2). `/v1/l/...` và `/v1/home.bin` giữ nguyên định dạng.
- Test: `node cloudflare/test/rank.test.mjs` (dùng `node:sqlite`, Node 24 trở lên).

## Deploy bằng GitHub Actions

Workflow `.github/workflows/deploy-worker.yml` chạy test rồi `wrangler deploy`, sau đó gọi thử 5 endpoint. Cần secret repo `CLOUDFLARE_API_TOKEN` và `CLOUDFLARE_ACCOUNT_ID` (tài khoản Cloudflare riêng của Monika Java). Secret của Worker giữ nguyên. Tự chạy khi push có sửa `cloudflare/` lên `main`.

## Danh mục v3, đề xuất hôm nay, phân trang số

- `node tools/gen-taxonomy.mjs` giờ sinh `taxonomy.js` và `migrations/005-taxonomy-v3.sql` (004 đã chạy, đóng băng). Migration 005 chạy được nhiều lần: nạp 48 danh mục theo nhóm; **chạy trước, deploy Worker sau**; mục đã có giữ nguyên id danh mục.
- Loại bài mới `media` (giao diện, âm thanh) và `post` (truyện, thủ thuật) cùng bảng `items`; Mới/Hot/Tải nhiều/Đề xuất và `home.bin`/`home2.bin` chỉ xét game và app.
- Endpoint mới: `/v1/menu.json`, `/v1/home3.bin`, `/v1/pager.json`, khóa `pick`/`k3`/`k4`/`k5` (xem `docs/api-contract.md`, mục v3).
- Test mới: `node cloudflare/test/v3.test.mjs` (migration 005, phân giải danh mục, đồng bộ bài media/post, đề xuất đổi theo ngày giờ Việt Nam, phân trang số).

- **Pixeldrain (migration 006, `pd_links`)**: dòng `v1..vN` chứa `…/v1/p/<ID>/…` được đồng bộ vào bảng `pd_links(pd, src)`; route `/v1/p/<ID>/<tên>.jar` tra ra mục của bài rồi `countDownload` (mọi độ phân giải cộng vào cùng một mục). Chưa chạy migration thì route vẫn phát file, chỉ không đếm. Thử: `node cloudflare/test/rank.test.mjs`, `node cloudflare/test/pd.test.mjs`.
