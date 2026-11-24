# Monika 0.7.1: tag màn hình 240x320 / 320x240

Thay đổi so với 0.7.0 (`docs/app-0.7.0.md`). Giao thức: `docs/api-contract.md` mục v5.

- Ô game trong lưới: thêm hàng tag màn hình (xanh ngọc) dưới huy hiệu thẻ; game có cả hai cỡ hiện cả hai tag, chỉ có một cỡ thì hiện một tag.
- Trang chi tiết: dòng "Màn hình: 240x320, 320x240".
- Danh mục > Game: hai mục đầu "Game 240x320" và "Game 320x240" (lọc chặt `.x`, có đủ tab Mới / Hot / Top ngày / Top tuần / Tải nhiều).
- Không đổi định dạng dữ liệu: màn hình nằm trong bit 64/128 của byte `plat` (MKL2).
