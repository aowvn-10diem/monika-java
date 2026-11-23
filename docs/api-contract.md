# API Monika (Cloudflare Worker) cho app J2ME

App và web (m.justmonika.top) dùng chung dữ liệu: Worker quét bài Blogspot rồi phát lại cho app.
Host API: `http://api.justmonika.top`. Mọi đường dẫn bắt đầu bằng `/v1`.

Quy tắc chung:

- **Chỉ HTTP cổng 80**, không redirect sang HTTPS (máy Java cũ không có TLS). Với Cloudflare: tắt *Always Use HTTPS* và *Automatic HTTPS Rewrites* cho host API.
- GET, nhị phân big-endian kiểu `DataOutputStream`. `UTF` = `ushort` độ dài + UTF-8. Nên có `Cache-Control: max-age=60`.
- Kích thước tối đa app chấp nhận: `home.bin` 16 KB, trang danh sách 64 KB, ảnh banner 48 KB.
- Tên và mô tả để UTF-8 có dấu, app tự bỏ dấu nếu máy không có font.

## Endpoint

| Endpoint | Nội dung |
| --- | --- |
| `GET /v1/home.bin` | Trang chủ: số mục mới, bản app mới nhất, danh mục, banner |
| `GET /v1/b/{id}-240.png`, `GET /v1/b/{id}-320.png` | Ảnh banner, cao khoảng 60 px, app chọn 320 nếu màn rộng từ 300 px, không scale |
| `GET /v1/l/{key}/{page}.bin` | Danh sách phân trang, 15 mục mỗi trang, `page` đếm từ 0 |
| `GET /v1/s/{từ khóa}/{page}.bin` | Tìm kiếm: từ khóa chữ thường, bỏ dấu, chỉ a-z 0-9, khoảng trắng thành `%20` |
| `GET /v1/sc/{id}-{w}x{h}.jpg` | Ảnh minh họa của mục, thu nhỏ vừa khung w x h (giữ tỉ lệ, không cắt, không méo; w 40-320, h 30-320), JPEG chất lượng 70. Chỉ gọi khi `flags` có bit 32. Nguồn Google/Blogger được Google thu nhỏ và nén, nguồn khác trả bản gốc (app giới hạn 48 KB) |
| `GET /v1/j/{id}.jad` | JAD có `MIDlet-Jar-URL` tuyệt đối dạng http, MIME `text/vnd.sun.j2me.app-descriptor` |
| `GET /v1/j/{id}.jar` | File JAR, MIME `application/java-archive`, `MIDlet-Jar-Size` trong JAD phải đúng |

`{key}`: `app` (mọi mục không phải game), `game`, `new` (mới hoặc vừa cập nhật), `hot`, `dl` (tải nhiều), `c<idDanhMục>`, `i<idApp>` (đúng một mục, dùng cho banner trỏ tới `g:<id>`).

## Định dạng

`home.bin`:

```
int   magic 0x4D4B4831 ("MKH1")
ubyte version = 1
ushort newCount            số mục mới (hiện trên huy hiệu "Cập nhật")
UTF   latestVersion        bản app mới nhất, rỗng = không nhắc
UTF   latestJarUrl         URL tuyệt đối của JAR mới
ubyte nCats                rồi mỗi danh mục: ubyte id, UTF name, ushort count
ubyte nBanners (tối đa 3 được dùng)
                           rồi mỗi banner: ubyte id, UTF alt, UTF link
```

Link banner: rỗng, `l:<key>` (mở danh sách) hoặc `g:<id>` (mở trang một app).

Trang danh sách (`/v1/l`, `/v1/s`):

```
int   magic 0x4D4B4C31 ("MKL1")
ubyte version = 1
ushort total               tổng số mục của danh sách
ushort page                trang này
ubyte count                số mục trong trang
count lần: int id, ubyte cat, ubyte flags, ushort sizeKb, int price (0 = miễn phí),
           UTF version, UTF name, UTF desc (tối đa 120 ký tự), UTF jadPath
```

`flags`: 1 mới, 2 có cập nhật, 4 cần mạng khi chạy, 8 hot, 16 có icon (tải `GET /v1/ic/<id>-32.png`, PNG 32x32 trích từ JAR), 32 có ảnh minh họa (tải `GET /v1/sc/<id>-<w>x<h>.jpg`, app 0.3 vẽ ô lưới 2 cột). Bản app cũ bỏ qua bit lạ vẫn chạy đúng. `jadPath` là đường dẫn tương đối (ví dụ `/v1/j/123.jad`) hoặc URL http tuyệt đối. Nút Tải về của app gọi `platformRequest(<host> + jadPath)`, trình duyệt máy tải JAD rồi JAR và cài.

Thử không cần Cloudflare: `python3 tools/mock_api.py 8081`, dựng app với `MONIKA_BASE_URL=http://127.0.0.1:8081 ./tools/build.sh`.

---

## v2: danh mục chuẩn, đếm lượt tải, xếp hạng (PR này)

Nguồn chuẩn danh mục: `catalog/taxonomy.json` (id thể loại: game 10–23, app 30–39, bài viết 50–52 chỉ dùng cho nhãn web; nền tảng và thẻ là bit). `/v1/l/...` và `/v1/home.bin` GIỮ NGUYÊN định dạng (app 0.3 vẫn chạy); chỉ có thứ tự xếp hạng mới (hot, dl, new theo logic mới). App 0.4 và web dùng các endpoint dưới đây.

### Key danh sách (cho `/v1/l/`, `/v1/l2/`, `/v1/list.json`)
`<base>(.<mod>)*` với:
- base: `new` (theo ngày đăng) · `upd` (mới cập nhật) · `hot` · `dl` (tải nhiều từ trước đến nay) · `top1` (hôm nay) · `top7` (tuần) · `app` · `game` · `c<catId>` · `i<itemId>`
- mod: `new|upd|hot|dl|top1|top7` (đổi cách xếp, dùng sau `app`/`game`/`c<id>`), `p<mask>` (lọc nền tảng, nhận mặt nạ nhiều bit: mục khớp nếu có BẤT KỲ bit nào, ví dụ `p30` = mọi S60/S^3; bit 1 j2me, 2 s60v2, 4 s60v3, 8 s60v5, 16 s3; mục chưa khai nền tảng coi là j2me), `r<W>x<H>` (lọc độ phân giải, mục chưa khai cỡ thì luôn khớp), `t<bit>` (phải có thẻ đó)
- Ví dụ: `c12.top7`, `game.hot.p4`, `new.r240x320`, `dl.t2` (Việt hóa tải nhiều).
- `hot`/`top1`/`top7` bỏ mục đang bị giữ (`hold`). Cache biên 60 giây theo đường dẫn.

### `GET /v1/home2.bin`
`int 0x4D4B4832, ubyte ver=2, ushort newCount, UTF latestVersion, UTF latestJarUrl, ubyte nCats { ubyte id, ubyte kind (1 game, 2 app), UTF name, ushort count }, ubyte nBanners { ubyte id, UTF alt, UTF link }` (banner như v1). Danh mục sắp theo kind rồi thứ tự trong taxonomy; chỉ liệt kê danh mục có mục.

### `GET /v1/l2/{key}/{page}.bin`
Header: `int 0x4D4B4C32, ubyte ver=2, ushort total, ushort page, ubyte count`. Mỗi mục: `int id, ubyte cat, ubyte flags, ushort sizeKb, int price, int dl, ushort tags, ubyte plat, UTF version, UTF name, UTF desc(≤120), UTF jadPath` (khác v1: thêm `dl` = tổng lượt tải đã khử trùng, `tags` = bit thẻ, `plat` = bit nền tảng, đặt giữa `price` và `version`). `flags` giữ như v1 (1 mới, 2 cập nhật, 4 cần mạng, 8 hot, 16 icon, 32 ảnh minh họa). Trang 15 mục.

### `GET /v1/list.json?key=<key>&page=0` (web)
`{"total":N,"page":0,"items":[{"id":12,"title":"…","url":"<permalink Blogger>","cat":12,"catName":"Hành động","ver":"1.0","sizeKb":120,"price":0,"dl":340,"d7":25,"tags":["vh","3d"],"plat":["j2me"],"flags":9,"icon":"/v1/ic/12-64.png","shot":"/v1/sc/12-240x320.jpg","pos":0}]}`. 15 mục mỗi trang, CORS *, cache 60 giây. `icon`/`shot` rỗng nếu không có. `pos` = thứ hạng trong trang kết quả (từ 0).

### `GET /v1/stats.json?ids=1,2,3` hoặc `?paths=/2026/11/a.html,...` (web, mỗi loại tối đa 50)
`{"1":{"id":1,"dl":340,"d1":3,"d7":25}}`; với `paths` khóa là pathname của bài. Cache 60 giây. Không có thì bỏ qua.

### `GET /v1/cats.json`
`[{"id":10,"kind":1,"slug":"hanh-dong","name":"Hành động","count":7}]` chỉ danh mục có mục; web dùng dựng menu. Cache 300 giây.

### meta bài (mk-meta) mới
`tags: vh, mod, 3d` (slug hoặc nhãn ngắn), `plat: j2me, s60v3`, `res: 240x320, 320x240`. `cat:` nhận tên chuẩn hoặc bí danh trong taxonomy; tên lạ rơi vào "Khác" (23 game, 39 app) và ghi `meta` khóa `unk:<tên>` để duyệt.

### Quy tắc đếm
1 lượt = byte đầu của file được phát (`/v1/j/{id}.jar`, `/v1/f/…jar`), không tính JAD. Mỗi (máy, game) tối đa 1 lượt mỗi tuần; trần 3 lượt mỗi (IP, game, tuần). HEAD, Range không bắt đầu từ 0, UA rỗng/bot không đếm (vẫn phục vụ). Không lưu IP.
