# Monika Java 0.6.0

JAR 59 778 byte (ngân sách 153 600).

## Thay đổi
- Danh mục theo nhóm mới: Trò chơi, Ứng dụng, Hình nền & Theme, Nhạc & Video, Truyện & Mẹo (dữ liệu `home3.bin`; máy chủ cũ không có thì app rơi về `home2`, rồi `home`).
- Mục **Đề xuất hôm nay** đầu menu Danh mục (khóa `pick`, Worker chọn cho cả ngày).
- Bài viết (cờ 64): nút "Mở bài", không hiện dung lượng, phiên bản, lượt tải. Giao diện/âm thanh (cờ 128): nút "Mở trang tải".
- Lượt tải luôn hiện (kể cả 0) ở ô lưới và trang chi tiết.
- Mọi danh sách gửi thêm `.s<W>x<H>` (ưu tiên mềm game đúng cỡ màn hình; màn lớn thì ưu tiên bản smartphone). Bật "Lọc màn hình" trong Cài đặt vẫn lọc hẳn bằng `.r`.

## Đã kiểm tra
- Dựng JAR bằng `tools/build.sh`, kích thước đạt.
- Phân tích `home3.bin`, `l2` (pick.s240x320, c61) từ mock bằng đoạn Java chạy trực tiếp trên lớp của app: nhóm, thứ tự 1,2,4,5,3, cờ 64/128, `dlText`.
- Chưa thử: emulator (chưa tải được MicroEmulator trong phiên này), máy thật, Worker thật.

## Cách thử
`python3 tools/mock_api.py 8081` (có home3, bài viết/giao diện/âm thanh mẫu, `.s`), rồi `MONIKA_BASE_URL=http://127.0.0.1:8081 OUT=.build/test tools/build.sh`.
