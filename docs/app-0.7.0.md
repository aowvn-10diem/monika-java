# Monika 0.7.0: góp ý / báo lỗi game

Thay đổi so với 0.6.1 (xem `docs/app-0.6.1.md`). Giao thức và chống spam: `docs/api-contract.md` mục v4.

| Hạng mục | Trạng thái |
| --- | --- |
| Trang chi tiết game có nút nhỏ "Báo lỗi / góp ý game này" (phím 9, chạm, hoặc lệnh trong menu khi không toàn màn hình). Tùy chọn > "Góp ý / báo lỗi" là góp ý chung | Có |
| Form góp ý (`Panel.FORM`): Loại (Báo lỗi game / Góp ý / Khác), Nội dung (tối đa 400 ký tự, gõ nhiều dòng, hiện 6 dòng cuối và bộ đếm), Liên hệ không bắt buộc (60 ký tự), Phép tính chống spam | Có, thử trên MicroEmulator 2.0.4 ở 240x320 và 320x240 |
| Phép tính: tải `GET /v1/fb/c.bin` lúc mở form, gõ đáp án (ô số gõ trực tiếp, không cần bấm nhiều lần như nhắn tin). Sai hoặc hết hạn thì tự lấy câu mới | Có |
| Gửi bằng `POST /v1/fb` (form-urlencoded UTF-8, `Net.post`); gửi kèm phiên bản app, mã thiết bị, User-Agent (có tên máy, cỡ màn hình) để dễ tái hiện lỗi | Có |
| Ô nhập (`Panel.INPUT`) có giới hạn ký tự theo từng ô (mặc định vẫn 60), ô nhập gốc cho máy cảm ứng dùng đúng giới hạn đó và bàn phím số cho phép tính | Có |
| JAR 73.996 byte (giới hạn 153.600) | Có |

**Chưa thử máy thật** (E72/E52): chỉ thử trên emulator với `tools/mock_api.py` (đã thêm `/v1/fb/c.bin` và `POST /v1/fb`). Chưa thử qua Worker thật từ app vì chưa deploy khi viết tài liệu này.

Web: `blogspot/monika-template.xml` thêm link "Báo lỗi / góp ý về bài này" cuối bài (`/fb?p=<mã bài>`) và link "Góp ý / báo lỗi" ở chân trang. Cần dán lại template vào Blogspot.
