# Monika 0.6.1: thông báo ủng hộ, thông báo lần đầu, phím QWERTY và T9

Thay đổi so với 0.6.0 (xem `docs/app-0.6.md`). Web và app dùng chung nội dung thông báo ủng hộ.

| Hạng mục | Trạng thái |
| --- | --- |
| Thông báo ủng hộ (`DonateCanvas`): tiêu đề, hai đoạn lời nhắn đúng chữ sếp viết, mã QR VietQR, số tài khoản `9021870093495` (Timo), nút Sao chép, nút Đóng thông báo ở cuối trang. Nội dung dài hơn màn hình nên kéo xuống mới thấy nút Đóng; Đóng chỉ bấm được khi đã kéo xuống cuối (đang chưa tới cuối thì OK/phím mềm phải chỉ cuộn xuống) | Có |
| QR vẽ bằng ô vuông từ ma trận 37x37 nhúng trong code (`DonateCanvas.ROWS`), không thêm ảnh vào JAR (JAR tăng ~8 KB). Đã giải mã lại ảnh chụp màn hình emulator, ra đúng nội dung VietQR gốc | Có |
| Sao chép: máy J2ME không có clipboard, nên nút Sao chép mở ô chữ chứa số tài khoản để người dùng tự chọn và chép (cách chép trong ô chữ phụ thuộc máy, **chưa thử máy thật**) | Có |
| Thời điểm hiện: bấm Tải về ở game thứ 2 (bấm lại đúng game vừa tải không tính), rồi cứ 5 game một lần (lần 2, 7, 12...). Hiện khi app quay lại sau trình tải, hoặc ngay lần mở app kế tiếp nếu máy buộc thoát app khi cài. App J2ME không biết lúc nào tải xong thật, nên "đã bấm tải" là mốc đếm. **Chưa thử máy thật** (emulator không có trình tải) | Có |
| Thông báo hướng dẫn lần đầu (và lần đầu sau khi nâng từ 0.5.2): khung nhỏ trên trang chủ "Nhấn * để tìm kiếm", 2 4 6 8 / 5, gợi ý phím QWERTY; phím hoặc chạm bất kỳ để tắt | Có |
| Sửa lỗi E72 nhấn A nhảy vào tìm kiếm: phím chữ không còn là phím tìm kiếm (trước đây `S` là tìm kiếm), và chữ cái không bao giờ bị hiểu là OK / phím mềm / hướng qua `getGameAction` hay `getKeyName` của máy | Có, **chưa chắc nguyên nhân**, cần thử máy thật + màn Kiểm tra phím |
| Phím QWERTY (E72): R T Y = 1 2 3, F G H = 4 5 6, V B N = 7 8 9, M = 0, U = `*`, J = `#` (theo số in trên phím). Nếu máy gửi sẵn mã số thì vẫn chạy như cũ | Có |
| Phím số di chuyển (mặc định bật): 2 lên, 4 trái, 6 phải, 8 xuống, 5 OK trên mọi màn (trang chủ, danh sách, chi tiết, menu). Với QWERTY là T F H B G. Máy T9 có cần điều hướng vẫn dùng cần như cũ | Có |
| Đổi theo: khi bật phím số di chuyển thì bỏ số bấm nhanh 1..9 (ô số góc trái trên ô lưới, số đầu dòng menu); Yêu thích ở trang chi tiết từ phím 2 sang phím 3 (1 vẫn là Tải về). Cài đặt > "Phím số di chuyển" tắt đi là về kiểu cũ (bấm 1..9 mở nhanh mục thứ 1..9). Bảng xếp hạng vẫn hiện hạng 1-9 | Có |
| Màn Kiểm tra phím (Tùy chọn > Kiểm tra phím): mỗi phím bấm hiện mã, tên máy báo, `ga` (game action) và cách Monika hiểu. Thoát bằng phím mềm phải hoặc phím C | Có |
| Bản ghi cài đặt lên version 4: thêm `numNav`, `tipDone`, `dlCount`, `donateAt`, `lastDl`; đọc được bản 1 đến 3 | Có |

## Nếu E72 vẫn nhảy vào tìm kiếm khi nhấn A

Mở Tùy chọn > Kiểm tra phím, nhấn A, chụp màn hình gửi lại: dòng hiện mã phím, tên và `ga` cho biết máy gửi mã gì cho phím A.

## Web

`blogspot/monika-template.xml`: khối thông báo ủng hộ chèn vào đầu trang (PC: lớp phủ giữa màn hình). Đếm lượt bấm nút tải bằng cookie `mkd` (`<số lượt>.<lượt đã nhắc>.<đang chờ hiện>`), nhắc ở lượt 2 rồi cứ 5 lượt; hiện sau 2,5 giây hoặc khi quay lại trang. Sao chép dùng `navigator.clipboard`, rồi `execCommand('copy')`, cuối cùng bôi đen số để người dùng tự chép. Ảnh QR (270x312, 2,4 KB, QR dựng lại sạch từ nội dung VietQR kèm logo VietQR / napas 247) nhúng dạng `data:` nên không cần Worker. Trên máy cũ không hiện được `data:` thì ảnh bị ẩn, vẫn còn số tài khoản và nút sao chép.

Nội dung QR (VietQR tĩnh, Timo, không kèm số tiền): `00020101021138570010A00000072701270006963388011390218700934950208QRIBFTTA53037045802VN63040607`. Đổi tài khoản thì dựng lại ma trận QR (mức sửa lỗi M) và cập nhật cả `DonateCanvas.ROWS` lẫn ảnh trong template.

## Cách thử

```
node tools/test_donate_web.js          # Playwright: lượt 1 không hiện, lượt 2 hiện, nút Đóng, nút chép, cookie, 3 cỡ màn hình
tools/package.sh                       # dist/Monika-0.6.1.zip
```

App: chạy `dist/Monika.jar` trong MicroEmulator 2.0.4 (`--resizableDevice W H`, `--rms memory` để thấy thông báo lần đầu mỗi lần chạy).
