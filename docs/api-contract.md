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
| `GET /v1/sc/{id}-{w}x{h}.jpg` | Ảnh minh họa của mục, cắt giữa đúng w x h (w 40-320, h 30-320), JPEG chất lượng 70. Chỉ gọi khi `flags` có bit 32. Nguồn Google/Blogger được Google cắt và nén, nguồn khác trả bản gốc (app giới hạn 48 KB) |
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
