# Monika 0.4.0: danh mục 2 cấp, xếp hạng, lượt tải

Thay đổi so với 0.3.0 (xem `docs/app-0.3.md`). Dữ liệu theo phần "v2" của `docs/api-contract.md`.

| Hạng mục | Trạng thái |
| --- | --- |
| Endpoint v2: `home2.bin` (danh mục có `kind` 1 game / 2 app) và `l2/{key}/{page}.bin` (mỗi mục thêm `dl`, `tags`, `plat`). Máy chủ trả 404 / sai magic / sai version thì rơi về v1 (`home.bin`, `l/`, bỏ mod, `top1`/`top7` thành `hot`, `dl` = 0); 404 không thử lại 3 lần nữa | Có |
| Danh mục 2 cấp: Danh mục > Trò chơi / Ứng dụng (theo `kind`, đếm từ home2) > thể loại > danh sách. Không viết cứng danh mục | Có |
| Tab xếp hạng **Mới / Hot / Hôm nay (top1) / Tuần (top7) / Tải nhiều (dl)** ở mục Cập nhật và trong thể loại (khóa `c<id>.<sort>`, ví dụ `c12.top7`). Màn hẹp: nhãn ngắn (Ngày, Tuần, Tải), máy rất hẹp (128 px) chỉ hiện tab đang chọn giữa hai mũi tên (trái/phải hoặc chạm hai nửa để đổi) | Có |
| Số lượt tải ở ô lưới (viên thuốc có mũi tên xuống, góc dưới phải ảnh) và trang chi tiết: dưới 5 ẩn, dưới 1000 số thật, từ 1000 dạng `1,2k` | Có |
| Huy hiệu thẻ theo bit `tags`: tối đa 2 ô lưới (VH, MOD trước, rồi 3D, TOUCH, VN, MP); bỏ GỐC và NET (đã có cờ riêng). Trang chi tiết liệt kê đủ ở dòng "Thẻ" | Có |
| Hạng 1-2-3 (vòng tròn vàng/bạc/đồng vẽ bằng Canvas) cho trang 0 của bảng xếp hạng `top1`, `top7`, `dl`, `hot` (cả ô lưới và danh sách dòng màn hẹp) | Có |
| Lọc theo máy: gọi danh sách kèm `.r<W>x<H>` (cỡ canvas hiện tại, xoay ngang thì dùng cỡ đang xoay). Cài đặt có "Chỉ hiện game hợp màn hình", mặc định BẬT. Kết quả rỗng ở trang 0 thì tự tải lại không lọc (các trang sau cũng không lọc). Không áp dụng cho tìm kiếm và mục đơn `i<id>` | Có |
| Cache RMS: khóa mới `home2`, `l2:<khóa>`; cache cũ (`home`, `l:...`) giữ nguyên, không đọc nhầm. Bản ghi cài đặt lên version 3 (thêm 1 byte lọc màn hình), đọc được bản 1 và 2 | Có |

JAR 50 100 byte (ngân sách 153 600). Yêu thích vẫn lưu định dạng v1, nên không hiện lượt tải/thẻ cho đến khi mở lại từ danh sách.

## Cách thử

```
python3 tools/mock_api.py 8081          # v1 + v2 có dl, tags, plat, res đa dạng
MOCK_V1_ONLY=1 python3 tools/mock_api.py 8081   # giả lập Worker cũ: v2 trả 404, app phải rơi về v1
MONIKA_BASE_URL=http://127.0.0.1:8081 OUT=.build/test tools/build.sh
```

Rồi chạy `.build/test/Monika.jad` trong MicroEmulator 2.0.4 (`--resizableDevice W H`). Đóng gói: `tools/package.sh` ra `dist/Monika-0.4.0.zip`.
Thử nhanh bộ lọc rỗng: ở 240x320 vào Trò chơi > Đua xe (mock chỉ khai 176x208 và 128x160), app phải tự tải lại không lọc.

## Đã kiểm tra thế nào

- MicroEmulator 2.0.4 với mock v2: 240x320, 320x240, 176x208, 128x160. Trang chủ, danh mục 2 cấp, danh sách có huy hiệu, lượt tải và hạng 1-2-3 ở các tab, trang chi tiết (240x320), màn Cài đặt.
- Chế độ v1 (`MOCK_V1_ONLY=1`) và bộ lọc rỗng tự tải lại: đã thử.
- Chưa thử: Worker thật (chưa có v2 triển khai), máy thật (E72, E52, 5800), chế độ không toàn màn hình (cỡ canvas nhỏ hơn màn hình được quy về cỡ máy theo bảng cỡ phổ biến, chưa đối chiếu máy thật), mất mạng với cache v2.
