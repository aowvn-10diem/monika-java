# Monika 0.2.0: app dùng chung giao diện và dữ liệu với web

Thay đổi so với 0.1.1 (xem `docs/first-build.md` cho phần nền):

| Hạng mục | Trạng thái |
| --- | --- |
| Dữ liệu từ API Cloudflare (`docs/api-contract.md`) thay cho danh mục mẫu trong JAR | Có. Danh mục mẫu chỉ còn dùng khi JAR không có máy chủ |
| Trang chủ hiện ngay, nạp dữ liệu nền, huy hiệu "Cập nhật" lấy từ `home.bin` | Có |
| Slot banner dưới header trang chủ, xoay 6 giây, bấm giữa hoặc phím 0 hoặc chạm để mở link banner | Có (ẩn khi màn cao dưới 240 px) |
| Danh sách vẽ theo giao diện web: tiêu đề tím, dòng đánh số 1-9 bấm nhanh, dòng chọn tô cam, huy hiệu MỚI / HOT / CẬP NHẬT, "Trang sau »" | Có |
| Màn Cập nhật có tab Mới / Hot / Tải nhiều (phím trái phải) | Có |
| Danh mục lấy từ `home.bin`, tìm kiếm qua API, yêu thích lưu kèm thông tin để mở lại không cần mạng | Có |
| Trang chi tiết: nút lớn "Tải về (JAR)" (phím 1), "Yêu thích" (phím 2), mô tả cuộn | Có |
| Cache RMS cho trang chủ và trang đầu mỗi danh sách, mất mạng vẫn mở được phần đã xem | Có |
| Ô "Máy chủ" trong Cài đặt để đổi API khi đổi tên miền | Có |
| Nhắc cập nhật khi `latestVersion` mới hơn (mục trong Tùy chọn) | Có |
| Banner trong trang danh sách và chi tiết | Không (giữ màn nhỏ gọn, chỉ trang chủ) |
| Màn Mua, QR, ký JAR, bản lite 64 KB | Chưa làm |

## Dựng bản phát hành

```sh
./tools/package.sh      # dist/Monika-0.2.0.zip chỉ chứa Monika.jar
MONIKA_BASE_URL=http://api.example ./tools/package.sh
```

`MONIKA_BASE_URL` được ghi vào MANIFEST của JAR (thuộc tính `Monika-Base-URL`), nên chỉ cần một file `.jar`, không cần `.jad`.
JAR kèm `MIDlet-Permissions: javax.microedition.io.Connector.http`.

## Đã kiểm tra thế nào

- Build sạch bằng ECJ + ProGuard, JAR 39 KB (ngân sách 150 KB).
- MicroEmulator 2.0.4 với `tools/mock_api.py`: trang chủ + banner, Ứng dụng, Trò chơi phân trang, Cập nhật 3 tab, Danh mục, Tìm kiếm, Yêu thích, trang chi tiết, link banner `l:` và `g:`, ở 240x320, 320x240, 176x208, 128x160, 360x640.
- Mất mạng: dùng cache cho trang đã xem, báo "Không vào được mạng (E12)" và thử lại cho trang chưa xem.
- API thật `http://api.justmonika.top` trả đúng định dạng (hiện chưa có bài nên danh sách trống).
- **Chưa thử trên máy thật.** Emulator không thử được `platformRequest` cài JAD, hộp thoại quyền mạng của JAR không ký, phím mềm E72/E52.
