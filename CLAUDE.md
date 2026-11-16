# monika-java

Monika: cửa hàng ứng dụng Java (J2ME, MIDP 2.0 / CLDC 1.1) cho Nokia S60 của AOW.VN. Ngôn ngữ làm việc: tiếng Việt. Gọi người dùng là "sếp".

## Cấu trúc

- `client/`: nguồn MIDlet. Java kiểu 1.3, chỉ dùng API của CLDC 1.1 và MIDP 2.0. Không generics, không enum, không String.format, không collections của Java SE.
- `catalog/`: catalog mẫu, nguồn của `catalog_sample.bin`.
- `tools/`: `build.sh` (ECJ đích Java 1.3 + ProGuard preverify), `GenAssets.java`, `proguard.pro`, `libs.sha256`.
- `dist/`: `Monika.jar` và `Monika.jad` đã dựng. File nhị phân, không đọc, không sửa tay.
- `docs/`: tài liệu quyết định. Trạng thái công việc ghi ở đây hoặc ở issue, không ghi vào file này.
- `.github/`: workflow CI, mẫu PR, mẫu issue.

## Ràng buộc cứng

- JAR bản đầy đủ tối đa 150 KB (153600 byte). Mọi thay đổi `client/` phải giữ dưới ngân sách.
- Không thêm thư viện ngoài vào client. Thư viện dựng phải khớp `tools/libs.sha256`.
- Không dùng API ngoài CLDC/MIDP. Nghi ngờ thì hỏi sếp, không đoán.
- Không ký JAR, không đẩy lên máy chủ, không đụng miền `aowvn.us` khi chưa có lệnh của sếp.

## Quy trình

1. Việc nhiều file hoặc mơ hồ: viết kế hoạch ngắn, chờ sếp duyệt ("cứ làm" là bỏ bước này).
2. Sửa nhỏ, đúng chỗ. Gom các sửa cùng vùng vào một lượt.
3. Dựng và kiểm tra bằng CI, không dựng đi dựng lại trong phiên (xem mục Tiết kiệm token).
4. Xong đúng yêu cầu thì dừng, không thêm việc ngoài yêu cầu.
5. Nhánh làm việc do harness chỉ định. Không đẩy lên `main`. PR mở ở dạng nháp.

## Tiết kiệm token (quy tắc cụ thể)

- Không đọc nguyên file lớn hoặc nhị phân (`dist/*`, `*.bin`, `*.png`, `*.jar`). Cần thì `ls -l`, `wc -c`, hoặc đọc đúng đoạn bằng offset/limit.
- Tìm bằng Grep/Glob trước, rồi đọc đúng vùng. Không quét cả thư mục.
- Build/lint/test chạy ở GitHub Actions (`.github/workflows/`). Đẩy nhánh rồi đọc kết quả bằng công cụ GitHub (`actions_list`, `actions_get`, `get_job_logs`). Chỉ chạy `./tools/build.sh` trong phiên khi cần gỡ lỗi mà log CI không đủ.
- Lệnh dài: giới hạn đầu ra (`| tail -n 60`) và ghi ra file trong thư mục tạm thay vì in toàn bộ.
- Việc đọc nhiều, tìm kiếm rộng, quét file: giao subagent Haiku, nhận lại bản tóm tắt.
- Bế tắc sau 2 lần thử hoặc quyết định kiến trúc: hỏi subagent Opus làm cố vấn, Sonnet quyết định.
- Không dán lại nguyên log, diff lớn hay nội dung file vào câu trả lời. Trích đúng dòng cần.
- Sửa `CLAUDE.md` này chỉ qua PR, không sửa giữa phiên: mỗi lần đổi nội dung đầu vào, bộ nhớ đệm prompt của phiên sau phải ghi lại từ đầu.

## Quy ước

- Commit: một dòng tóm tắt, tiếng Việt hoặc tiếng Anh, thể mệnh lệnh. Phần đính kèm (đồng tác giả, liên kết phiên) do harness chỉ định, giữ nguyên.
- PR: dùng `.github/pull_request_template.md`. Phần "Việc cần sếp làm" là bảng markdown.
- Quyết định chốt gắn nhãn [A1], [A2]... và tham chiếu bằng nhãn.
- Không ghi bí mật, khóa, token vào repo.
