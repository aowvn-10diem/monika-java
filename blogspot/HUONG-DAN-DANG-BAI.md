# Hướng dẫn đăng bài Monika (Blogspot → web + app Java)

Mỗi bài Blogspot là một game/app. Web hiển thị trực tiếp, Worker `api.justmonika.top` quét blog mỗi 5 phút rồi đẩy sang app. Đăng bài ở chế độ **HTML** (không dùng Soạn thảo).

## 1. Bài game/app

Nhãn (Labels): `Game` hoặc `App`, thêm nhãn danh mục (ví dụ `Hành động`). Nội dung bài:

```html
<pre class="mk-meta">
ver: 1.2
size: 245
cat: Hành động
jar: https://link-tai-thang-file.jar
net: 0
hot: 0
desc: Mô tả ngắn hiện trong app (không bắt buộc)
</pre>
<img src="ảnh chụp màn hình hoặc icon" />
<p>Mô tả chi tiết cho web.</p>
```

| Dòng | Ý nghĩa |
|---|---|
| `ver` | phiên bản |
| `size` | dung lượng KB (bỏ trống thì app tự lấy từ file JAR) |
| `cat` | danh mục (bỏ trống thì lấy nhãn đầu tiên không phải Game/App) |
| `jar` | link tải thẳng file JAR (xem mục 3) |
| `net` | `1` nếu cần mạng |
| `hot` | `1` để ghim vào mục Hot |
| `price` | giá, bỏ trống là miễn phí |
| `desc` | mô tả ngắn cho app, bỏ trống thì cắt từ nội dung bài |

Ảnh đầu tiên trong bài là icon nhỏ ở danh sách web. App không tải ảnh game.

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

1. Theme → mũi tên cạnh Customize → Edit HTML → xóa hết, dán nội dung `monika-blogspot-template.xml` → Save. Nếu Blogspot báo lỗi, gửi nguyên văn thông báo để sửa.
2. Theme → bánh răng "Mobile" → chọn **No. Show desktop template on mobile devices** (template đã tự co giãn, không dùng bản mobile của Blogger).
3. Settings → Posts: số bài hiện trên trang chính = 15. Settings → Blog feeds (Site feed) = **Full**.
4. Settings → Custom domain: `m.justmonika.top`. DNS đã có bản ghi `m` → `ghs.google.com`. Blogspot sẽ đưa thêm một bản ghi CNAME xác minh riêng, gửi tôi hoặc tự thêm vào Cloudflare (để DNS only, đám mây xám).
5. Tạo hai Page nếu muốn (Giới thiệu, Liên hệ), template tự hiển thị trang tĩnh.
