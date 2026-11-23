# Monika 0.3.0: lưới ảnh game

Thay đổi so với 0.2.0 (xem `docs/app-0.2.md`):

| Hạng mục | Trạng thái |
| --- | --- |
| Danh sách app (Ứng dụng, Trò chơi, Cập nhật, tìm kiếm, kết quả danh mục, Yêu thích) thành lưới ô trên màn rộng từ 170 px: 2 cột (3 cột từ 420 px), mỗi ô có ảnh minh họa 4:3, icon góc dưới, huy hiệu HOT / MỚI / CẬP NHẬT, tên 1-2 dòng, dung lượng và phiên bản | Có |
| Chiều cao ô chia để mỗi màn hình hiện đúng số hàng nguyên: E72 320x240 = 1 hàng = **2 game mỗi lần cuộn**, 240x320 = 2 hàng = 4 game, 360x640 = 3 hàng | Có |
| Ảnh minh họa `/v1/sc/<id>-<w>x<h>.jpg` (cờ 32): tải nền, ô đang hiện đứng đầu hàng đợi, giữ ô đang hiện và 1 hàng dự phòng, ô ra xa thì giải phóng RAM; chưa có hoặc lỗi thì nền gradient + icon lớn (hoặc chữ cái đầu) | Có |
| Điều hướng: lên/xuống nhảy một hàng, trái/phải đổi ô, ở mép thì trái/phải đổi tab (Cập nhật) hoặc trang; phím 1-9 vẫn mở nhanh; kéo ngón tay để cuộn, chạm nhả ra mới mở (màn cảm ứng) | Có |
| Màn hẹp dưới 170 px (128x160...) và danh mục: giữ danh sách dòng như 0.2 | Có |
| Dòng phụ bỏ chữ "Miễn phí" (miễn phí là mặc định, chỉ hiện giá khi có phí) | Có |

JAR 44 KB (ngân sách 150 KB). Cần Worker mới (cột `img`, `/v1/sc`) để có ảnh; chưa deploy thì app vẫn chạy, ô hiện icon/chữ cái.

## Đã kiểm tra thế nào

- MicroEmulator 2.0.4 với `tools/mock_api.py` (đã thêm `/v1/sc/`): 320x240, 240x320, 176x208, 128x160 (dòng), 360x640; phím lên/xuống/phải, đổi tab, kéo cuộn, chạm mở chi tiết.
- **Chưa thử trên máy thật** (E72, E52): giải mã JPEG, tốc độ tải ảnh qua GPRS/3G, bộ nhớ.
