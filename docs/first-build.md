# Monika client: bản dựng đầu tiên (v0.1.0)

Bản này là khung chạy được của app chợ trên máy Java S60, dựng theo mục 4 và 6 của kế hoạch Monika
và theo mockup (nền tím đậm, banner Monika, menu 5 mục, phím mềm Tùy chọn / Tìm ứng dụng / Thoát).
Nó dùng **danh mục mẫu** đóng gói sẵn trong JAR, chưa có máy chủ thật.

## Có gì trong bản này

| Hạng mục | Trạng thái |
| --- | --- |
| Màn hình chính vẽ bằng Canvas theo mockup, co giãn từ 128x128 đến 360x640, huy hiệu cập nhật | Có |
| Danh sách Ứng dụng, Trò chơi, Danh mục, Cập nhật, Yêu thích | Có (dữ liệu mẫu) |
| Chi tiết app, nút Cài đặt (`platformRequest` tới JAD), Yêu thích lưu RMS | Có (nút Cài chỉ hiện khi mục có đường dẫn JAD) |
| Tìm kiếm bỏ dấu (gõ "lich" ra "Lịch âm") | Có |
| Lần chạy đầu hỏi "Bạn đọc được dòng này không?", chọn có dấu hoặc không dấu | Có |
| Cài đặt, Thông tin máy (nền tảng, heap, màn hình, id thiết bị ngẫu nhiên), Giới thiệu | Có |
| Lớp mạng `Net`: luồng riêng, thử lại 2 lần, mã lỗi E12/E13/E17, hậu tố BlackBerry | Có, chưa thử với máy chủ thật |
| Định dạng catalog nhị phân MKA1, cache RMS, rơi về danh mục mẫu khi mất mạng | Có |
| Tự cập nhật `/v1/ver`, màn Mua và QR, trang WAP, proxy trình duyệt, sprite icon, thống kê máy | **Chưa làm** |
| Bản lite 64 KB cho S40 đời thấp | **Chưa làm** |
| Ký JAR | **Chưa làm** (JAR không ký, máy sẽ hỏi quyền mạng) |

## Cách dựng

```sh
./tools/build.sh                     # ra dist/Monika.jar và dist/Monika.jad
MIDLET_JAR_URL=http://<tên miền>/Monika.jar \
MONIKA_BASE_URL=http://<tên miền> \
MONIKA_FULLSCREEN=0 ./tools/build.sh # bản trỏ tới máy chủ thật
```

Cần JDK 8 trở lên, `curl`, `unzip`. Thư viện build tải từ Maven Central, kiểm SHA-256 theo `tools/libs.sha256`.

Chuỗi build: ECJ biên dịch nguồn và đích Java 1.3 với bootclasspath chỉ gồm CLDC 1.1 và MIDP 2.0 (dùng nhầm API
Java hiện đại là lỗi biên dịch), rồi ProGuard thu nhỏ, làm rối và preverify (`-microedition`).
Kết quả: class file version 47, có thuộc tính StackMap, JAR khoảng 21 KB (ngân sách 150 KB).

Vì sao ECJ chứ không phải javac: javac mới luôn sinh `StringBuilder` cho phép nối chuỗi (CLDC chỉ có `StringBuffer`)
và không còn đích dưới Java 8.

## Thuộc tính JAD tùy chọn

| Thuộc tính | Ý nghĩa |
| --- | --- |
| `Monika-Base-URL` | Gốc máy chủ catalog. Rỗng = chỉ dùng danh mục mẫu. Client gọi `<gốc>/v1/c/vi/all/0.bin` |
| `Monika-Net-Suffix` | Hậu tố kết nối, ví dụ `;deviceside=true` cho BlackBerry |
| `Monika-Fullscreen` | `1` = toàn màn hình, tự vẽ ba nhãn phím mềm. Mặc định `0` |

Chế độ thường (mặc định) giữ thanh trạng thái của máy: Tùy chọn và Thoát là Command gốc của máy,
nút "Tìm ứng dụng" vẽ ở đáy và bấm bằng phím `*`, phím `S` hoặc chạm.
Phím số `1` đến `5` mở thẳng từng mục menu.

## Định dạng catalog MKA1

Nguồn là `catalog/sample.tsv`, công cụ `tools/GenAssets.java` đóng thành `catalog_sample.bin`:
`int magic 0x4D4B4131, byte phiên bản 1, ushort số mục`, mỗi mục
`int id, ubyte cat, ubyte cờ, ushort dung lượng KB, int giá, UTF phiên bản, UTF tên, UTF mô tả, UTF đường dẫn JAD`.
Cờ: 1 mới, 2 có cập nhật, 4 cần mạng.

## Đã kiểm tra thế nào

- Build sạch, JAR có StackMap, MIDlet-Jar-Size khớp file thật.
- Chạy bản JAR đã làm rối + preverify trong MicroEmulator 2.0.4: font test, màn chính, danh sách, chi tiết,
  yêu thích, tìm kiếm không dấu, danh mục, cài đặt, thông tin máy, giới thiệu.
- Chụp màn chính ở 320x240, 240x320, 360x640, 176x208, 128x160, 320x200, cả chế độ thường và toàn màn hình.
- **Chưa thử trên máy thật nào.** Emulator không kiểm được preverify kiểu máy thật, quyền Java, phím mềm của E72/E52.

## Quyết định ghi lại

- [A1] Màn chính dùng Canvas vẽ tay để khớp mockup (huy hiệu, icon, banner gradient). Kế hoạch mục 4 chỉ cho Canvas ở QR và trình duyệt,
  nên đây là một ngoại lệ có chủ đích. Mọi màn còn lại vẫn là LCDUI cấp cao (List, Form, TextBox).
- [A2] Biên dịch bằng ECJ đích Java 1.3 (xem trên).
- [A3] Danh mục mẫu toàn bộ ghi "(mẫu)", không có file cài đặt thật.
- [A4] Mới có một trang catalog `all/0.bin` thay cho phân trang theo chuyên mục của kế hoạch.
- [A5] Logo Monika vẽ bằng chữ (M cam, "onika" trắng), chưa dùng ảnh logo gradient trong mockup vì chưa có file gốc.
