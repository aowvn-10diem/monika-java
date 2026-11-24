# Hướng dẫn đăng bài Monika (Blogspot → web + app Java)

Mỗi bài Blogspot là một game/app. Web hiển thị trực tiếp, Worker `api.justmonika.top` quét blog mỗi 5 phút rồi đẩy sang app. Đăng bài ở chế độ **HTML** (không dùng Soạn thảo).

## 1. Bài game/app

Nhãn (Labels), gắn đủ ba nhóm (xem mục 1b): loại `Game` hoặc `App` + thể loại chuẩn (ví dụ `Hành động`) + thẻ nếu có (`VH`, `MOD`, `3D`, `VN`). Nội dung bài:

```html
<pre class="mk-meta">
ver: 1.2
size: 245
cat: Hành động
jar: https://link-tai-thang-file.jar
net: 0
hot: 0
tags: vh, 3d
plat: j2me, s60v3
res: 240x320, 320x240
desc: Mô tả ngắn hiện trong app (không bắt buộc)
</pre>
<img src="ảnh chụp màn hình hoặc icon" />
<p>Mô tả chi tiết cho web.</p>
```

| Dòng | Ý nghĩa |
|---|---|
| `ver` | phiên bản |
| `size` | dung lượng KB (bỏ trống thì app tự lấy từ file JAR) |
| `cat` | thể loại: tên chuẩn hoặc bí danh trong `catalog/taxonomy.json` (bỏ trống thì lấy nhãn đầu tiên không phải Game/App). Tên lạ rơi vào "Khác" |
| `tags` | thẻ, cách nhau dấu phẩy: `goc`, `vh`, `mod`, `3d`, `cam-ung`, `game-viet`, `nhieu-nguoi`, `can-mang` (hoặc viết tắt `VH`, `MOD`, `3D`, `TOUCH`, `VN`, `MP`, `NET`). Hiện thành huy hiệu vàng ở ô lưới và trang bài |
| `plat` | nền tảng: `j2me`, `s60v2`, `s60v3`, `s60v5`, `s3` (bỏ trống coi là `j2me`). Dùng cho bộ lọc Java / S60 ở trang Xếp hạng |
| `res` | độ phân giải màn hình hỗ trợ, ví dụ `240x320, 320x240` (bỏ trống nghĩa là chạy mọi cỡ) |
| `jar` | link tải thẳng file JAR (xem mục 3) |
| `net` | `1` nếu cần mạng |
| `hot` | `1` để ghim vào mục Hot |
| `price` | giá, bỏ trống là miễn phí |
| `desc` | mô tả ngắn cho app, bỏ trống thì cắt từ nội dung bài |

**Ảnh minh họa** (hiện to ở ô lưới trang chủ, tìm kiếm, chuyên mục của web và app): ảnh đầu tiên trong bài **không phải icon Monika** (ảnh `/v1/fi/...` do trang up file tự thêm bị bỏ qua, ảnh khai width ≤ 80 cũng bỏ qua). Muốn chọn ảnh khác thì thêm dòng `img: https://...` vào khối `mk-meta`. Nên dùng ảnh chụp màn hình tỉ lệ **240×320 (dọc) hoặc 320×240 (ngang)**, tải thẳng lên Blogspot (Google tự thu nhỏ vừa ô cho web và app, giữ tỉ lệ, không cắt, JPEG vài KB). Kích thước khác vẫn được: ảnh tự co vừa ô. Ảnh host ngoài Google vẫn hiện trên web, còn app nhận bản gốc nên cần dưới 48KB, quá thì app dùng icon thay.

Icon nhỏ gắn vào góc ô là icon trích từ JAR (bài đăng bằng trang up file thì có sẵn, xem mục 6). Bài chưa có ảnh minh họa thì ô hiện nền gradient + icon lớn (hoặc chữ cái đầu).

## 1b. Nhóm, loại bài, nhãn và danh mục chuẩn (v3)

Mỗi bài thuộc một **nhóm** (menu trên web và trong app). Meta `type:` và nhãn nhóm quyết định bài vào đâu:

| Nhóm (menu) | `type:` | Nhãn nhóm | Dùng cho |
| --- | --- | --- | --- |
| Game | `game` | `Game` | game Java/Symbian (không nhận game online, mod/hack game online) |
| Phần mềm | `app` | `App` | ứng dụng, tiện ích, giả lập |
| Hình nền & Theme | `media` | `Giao diện` | hình nền, theme, ảnh, icon |
| Nhạc & Video | `media` | `Âm thanh` | nhạc chuông, nhạc, video, flash |
| Truyện & Mẹo | `post` | `Bài viết` | truyện, thủ thuật, tin nhắn hay, tin Monika |

Mỗi bài có: (1) nhãn nhóm, (2) **một danh mục chuẩn** ghi trong `cat:` và làm nhãn Blogger (đúng chính tả, tên không dấu cũng nhận), (3) thẻ `VH` (Việt hóa), `MOD`, `3D`, `VN` (game Việt) nếu đúng, ghi `tags: vh, 3d`; nhãn trùng tên thẻ hiện huy hiệu vàng cả khi tắt JavaScript. Bài không khai `type` mà có nhãn nhóm (Giao diện, Âm thanh, Bài viết) hoặc nhãn là tên danh mục của nhóm 3, 4, 5 vẫn được nhận; bài game/app cần `type:` hoặc nhãn `Game`/`App`.

Danh mục chuẩn:

Game: `Hành động`, `Phiêu lưu`, `Nhập vai`, `Kiếm hiệp & võ hiệp`, `Chiến thuật`, `Thủ thành`, `Đua xe`, `Thể thao`, `Bắn súng`, `Đối kháng`, `Giải đố & trí tuệ`, `Arcade & chạy nhảy`, `Mô phỏng & quản lý`, `Cờ & bài`, `Sinh tồn & kinh dị`, `Trẻ em & vui nhộn`, `Khác`

App: `Mạng & trình duyệt`, `Chat & mạng xã hội`, `Đọc sách & truyện`, `Trình phát nhạc & video`, `Văn phòng & học tập`, `Công cụ hệ thống`, `Bản đồ & GPS`, `Đời sống`, `Tùy biến máy`, `Ảnh & camera`, `Giả lập & chạy game`, `Khác`

Giao diện: `Hình nền điện thoại`, `Hình nền anime & artist`, `Theme Symbian & Nokia`, `Ảnh đẹp & ảnh vui`, `Icon & biểu tượng`, `Khác`

Âm thanh: `Nhạc chuông`, `Nhạc & album`, `Video & Flash`, `Khác`

Bài viết: `Thủ thuật & mẹo`, `Truyện cười & tiếu lâm`, `Truyện tình yêu & ngôn tình`, `Truyện kiếm hiệp & tiên hiệp`, `Truyện tranh & manga`, `Truyện ngắn & khác`, `Tin nhắn & câu nói hay`, `Tử vi & đời sống`, `Tin Monika`

Bài `media` ở nguồn ngoài (không có file trong kho) khai link bằng dòng `v1`, `v2`...: `cỡ-hoặc-nhãn|KB|NHÃN=URL`, ví dụ `v1: 240x320|90|JPG=http://...` hoặc `v1: Nokia S60v3|300|Theme=http://...`. Nút Tải mở trang liệt kê các bản. Bài `post` (truyện, thủ thuật) không có nút tải: nút Mở trong app chuyển tới trang bài.

Lưu ý: `Khác` có ở mỗi nhóm; nhãn `Khác` đơn lẻ không phân biệt được nhóm, nên luôn đặt kèm nhãn nhóm. Tên danh mục lạ rơi vào `Khác` của nhóm và được ghi lại để duyệt.

## 1c. Trang chủ web

Trang chủ có sẵn: **Đề xuất hôm nay** (Worker chọn game, đổi mỗi ngày giờ Việt Nam; 2 ô trên điện thoại, 4 ô trên máy rộng), **Top tải nhiều** (tab Hôm nay, Tuần, Mọi lúc; 10 hạng ở cột bên trên PC, 5 hạng dưới Đề xuất trên điện thoại) và **phân trang số** `1 2 3 4 5 6 … cuối` dưới danh sách (trang chủ, nhãn, tìm kiếm). Không cần cài gì thêm ngoài dán template mới. Muốn loại một game khỏi Đề xuất và Top: `UPDATE items SET hold=1 WHERE n=?`. Nhóm chưa có bài nào tự ẩn khỏi menu.

## 2. Bài banner

Nhãn: `Banner`. Ảnh đầu bài là banner, nên làm tỉ lệ **4:1** (ví dụ 640×160, dưới 20KB cho app). Nội dung:

```html
<pre class="mk-meta">
slot: top
link: hot
alt: Tên banner
</pre>
<img src="ảnh banner" />
```

- `slot`: `top` (đầu trang web + màn hình chính app), `list` (cột phải web, nên làm 300×250), `item` (cuối bài), `foot` (chân trang).
- `link`: `hot`, `new`, `app`, `game`, `cat:Hành động`, link một bài trên blog, hoặc link web bất kỳ. Bỏ trống thì banner không bấm được.
- App chỉ hiện banner `top`. Web hiện cả bốn vị trí, mỗi vị trí chọn ngẫu nhiên một banner.

## 3. Nơi để file JAR

Worker tải file JAR từ link rồi phát lại qua HTTP cho máy Java cũ, nên link phải cho phép máy chủ tải.

- Dùng được: GitHub (link `blob` hoặc `raw`, Releases), Google Drive (link chia sẻ "bất kỳ ai có liên kết", dạng `drive.google.com/file/d/ID/view` tự đổi sang link tải), host riêng.
- Không dùng được: Pixeldrain (chặn hotlink, trả 403), Maven Central.
- Gợi ý lâu dài: bật Cloudflare R2 (miễn phí 10GB) rồi nhờ tôi nối vào Worker.

## 4. Bài của chính app Monika (tự cập nhật)

Đăng một bài với `type: self`, `ver:` là phiên bản mới nhất, `jar:` là link JAR. App sẽ thấy phiên bản mới ở màn hình chính.

```html
<pre class="mk-meta">
type: self
ver: 0.2.0
jar: https://link-jar-monika
</pre>
```

## 5. Cài đặt Blogspot một lần

1. Theme → mũi tên cạnh Customize → Edit HTML → xóa hết, dán nội dung `blogspot/monika-template.xml` → Save. Nếu Blogspot báo lỗi, gửi nguyên văn thông báo để sửa.
2. Theme → bánh răng "Mobile" → chọn **No. Show desktop template on mobile devices** (template đã tự co giãn, không dùng bản mobile của Blogger).
3. Settings → Posts: số bài hiện trên trang chính = **16** (lưới 4 cột trên PC, 2 cột trên điện thoại/Nokia đều chia hết). Settings → Blog feeds (Site feed) = **Full**.
4. Settings → Custom domain: `m.justmonika.top`. DNS đã có bản ghi `m` → `ghs.google.com`. Blogspot sẽ đưa thêm một bản ghi CNAME xác minh riêng, gửi tôi hoặc tự thêm vào Cloudflare (để DNS only, đám mây xám).
5. Tạo hai Page nếu muốn (Giới thiệu, Liên hệ), template tự hiển thị trang tĩnh.
6. **Trang Xếp hạng** (làm một lần): Pages → New page → tiêu đề `Xếp hạng`, chế độ **HTML**, nội dung để trống → ô Permalink chọn *Custom permalink* và gõ `xep-hang` để địa chỉ thành `/p/xep-hang.html` (đúng chuẩn này, menu và template nhận diện theo đường dẫn) → Publish. Template tự dựng các tab Hôm nay / Tuần / Tải nhiều / Hot / Mới, bộ lọc Game|App|Tất cả, thể loại và nền tảng (Java / S60) từ API. Tắt JavaScript thì chỉ hiện thông báo kèm link nhãn.

Lượt tải hiện ở góc ô lưới và trang bài (dưới 5 ẩn, dưới 1000 hiện số thật, từ 1000 dạng `1,2k`), lấy từ API. Chỉ hiện khi Worker biết mục đó (xem mục "Việc sếp cần làm").

### Việc sếp cần làm

| Việc | Khi nào | Ghi chú |
|---|---|---|
| Dán lại `monika-template.xml` vào Theme → Edit HTML | Mỗi lần template đổi | Nếu Blogspot báo lỗi, gửi nguyên văn thông báo |
| Tạo Page `Xếp hạng`, nội dung trống, permalink `xep-hang` | Một lần | Bước 6 ở trên; thiếu trang này thì menu Xếp hạng báo "Không tìm thấy trang" |
| Gắn nhãn `Game`/`App` + thể loại chuẩn + `VH`/`MOD`/`3D`/`VN` cho bài mới và bài cũ | Mỗi bài | Mục 1b; sửa nhãn bài cũ để menu thể loại có bài |
| Thêm dòng `tags:`, `plat:`, `res:` vào `mk-meta` | Bài mới (bài cũ thêm dần) | Chưa có thì mặc định `j2me`, không thẻ, mọi cỡ màn hình |
| Worker thêm `GET /v1/stats.json?paths=...` (trả `id` + `dl`) | Một lần, bên Worker | Để mọi bài hiện lượt tải; tạm thời web chỉ hiện cho bài có icon trong `/v1/icons.json` |

## 6. Đăng bằng trang up file (khuyên dùng)

Mở `https://api.justmonika.top/up`, nhập token một lần (trình duyệt nhớ), kéo thả một hoặc nhiều file `.jar`. Máy chủ tự làm:

1. Đọc `META-INF/MANIFEST.MF` lấy tên game, hãng, phiên bản; trích icon trong JAR (khai báo `MIDlet-Icon` hoặc trường icon của `MIDlet-1`), thu về 32px và 64px.
2. Tính SHA-256 để **chống trùng**: file đã có thì không lưu lại, trả về bản cũ.
3. Lưu file theo thứ tự **R2 (chính, giới hạn mềm 9,5GB) → GitHub (dự phòng, tự đổi tên `ten-game-phienban-<hash10>.jar`) → KV (tạm)**. Link tải luôn là `https://api.justmonika.top/v1/f/<tên>.jar`, đổi nơi lưu không đổi link.
4. Tạo sẵn mục trong app (hiện ngay, có icon) và trả về khối HTML + nhãn để dán vào bài Blogspot. Khi bài Blogspot được đăng, mục tạm tự biến mất, bài thay thế.

Dòng `file: <hash10>` trong `mk-meta` là khóa nối bài với file và icon. Web và app lấy icon qua `/v1/fi/<hash10>-64.png`, `/v1/ic/<id>-32.png`; bài không có `file:` vẫn được thử khớp icon theo link `jar`.

Biến môi trường Worker liên quan: `UP_TOKEN` (bắt buộc), `R2` (binding), `GH_TOKEN` + `GH_REPO` (GitHub dự phòng; `GH_BRANCH` mặc định `files`, `GH_DIR` mặc định `jars`), `R2_LIMIT`, `GH_LIMIT`, `KV_LIMIT`.

## 1d. Bảng link tải, lượt tải, ưu tiên màn hình (0.6.0)
- Bài có các dòng `v1..vN` trong `mk-meta` hiện **bảng 3 cột** (Máy / màn hình, Dung lượng, Tải về), dùng bảng HTML thường nên máy cũ vẫn thẳng cột. Dòng khớp màn hình máy đang xem được xếp đầu và gắn "Hợp máy bạn".
- Lượt tải luôn hiện kể cả 0. Bài viết (truyện, thủ thuật) không có lượt tải.
- "Đề xuất hôm nay" tự ưu tiên game đúng cỡ màn hình (240x320 thì game 240x320; máy lớn, Android, iPhone thì bản smartphone). Danh sách bài do Blogger dựng sẵn không đổi thứ tự được.
