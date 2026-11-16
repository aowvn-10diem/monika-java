# Tiết kiệm token và cache cho Monika

## Kết luận

- **[A1] Cache 99% cho cả dự án: không thực tế.** Trong một phiên chạy liên tục thì phần lớn input được đọc từ cache, nhưng 99% trên toàn dự án thì không có nguồn nào bảo đảm, và cơ chế (bên dưới) khiến nó khó đạt: mỗi phiên mới bắt đầu nguội, cache hết hạn khi nghỉ, đổi cấu hình giữa phiên làm mất cache. Tôi không đưa con số hứa hẹn nào vì không có số đo của dự án này.
- **[A2] Mục tiêu đúng là giảm tiền và token, không phải đuổi tỉ lệ cache.** Giá đọc cache của Sonnet 5.5 là 0,10 USD/MTok so với 2 USD/MTok input thường (rẻ hơn 20 lần), nhưng ghi cache tốn 1,25 lần (TTL 5 phút) hoặc 2 lần (TTL 1 giờ). Cache tốt thì rẻ, nhưng đưa ít token vào ngữ cảnh ngay từ đầu còn rẻ hơn.
- **[A3] Làm việc với điều phối ở ngoài hay chat thẳng với phiên: đều được.** Gợi ý: việc mới thì giao qua điều phối; tiếp tục đúng việc đang làm thì chat thẳng trong thread đó khi nó còn ấm. Đây là suy luận từ cơ chế cache theo prefix, chưa đo trên dự án này.
- **[A4] GitHub đã gánh được build.** PR #1 đã có workflow `build-client` (dựng JAR/JAD, báo kích thước). Việc còn thiếu là quy tắc để agent đọc kết quả CI thay vì tự chạy build. Phần này nằm trong `CLAUDE.md` của PR kèm theo.

## Cơ chế cache (từ tài liệu chính thức)

| Điều tài liệu nói | Hệ quả cho dự án |
| --- | --- |
| Cache theo prefix, thứ tự `tools` → `system` → `messages`; "Cache hits require 100 percent identical prompt segments". Đổi ở tầng nào thì tầng đó và các tầng sau mất cache | Prefix phải ổn định: không đổi plugin, connector, effort, model giữa phiên |
| Đổi định nghĩa tool thì mất toàn bộ cache | Bật/tắt MCP hoặc plugin giữa phiên là đắt. Làm giữa các phiên |
| TTL mặc định 5 phút; tùy chọn 1 giờ (ghi 2x). Thời gian tính từ đầu request | Nghỉ lâu hơn TTL thì lần nhắn sau là miss toàn bộ |
| Claude Code: gói thuê bao, hội thoại chính TTL 1 giờ; API key hoặc credit: 5 phút. Subagent và việc nền mặc định 5 phút | Sếp nhắn rải rác theo giờ thì hay miss nếu dùng API key |
| Cache tách theo workspace trong org, không chia sẻ giữa org | Không "mượn" cache của người khác |
| Cache chỉ có sẵn sau khi request đầu bắt đầu trả lời | Chạy song song nhiều request cùng prefix không hưởng cache lẫn nhau |
| Subagent không-fork có cache riêng, không tính vào `/usage` "Prompt cache (main)" | Tỉ lệ cache của luồng chính không phản ánh subagent |
| Nén ngữ cảnh (compact) là một request lớn | Tránh để ngữ cảnh phình rồi mới nén. Dùng `/clear` giữa các việc |
| Mức tối thiểu để cache: 512 token cho Sonnet 5.5, Opus 5.5, Haiku 5.5 | Prefix dự án vượt xa mức này, không phải lo |

## Giá (USD / MTok, đầu vào / ghi 5 phút / ghi 1 giờ / đọc cache / đầu ra)

| Model | Đầu vào | Ghi 5p | Ghi 1h | Đọc cache | Đầu ra |
| --- | --- | --- | --- | --- | --- |
| Sonnet 5.5 | 2 | 2,50 | 4 | 0,10 | 10 |
| Opus 5.5 | 4 | 5 | 8 | 0,20 | 20 |
| Haiku 5.5 (prompt tới 100k token) | 0,10 | 0,125 | 0,20 | 0,01 | 0,50 |

Haiku 5.5 rẻ hơn Sonnet 5.5 khoảng 20 lần ở đầu vào, nên đẩy việc đọc/quét/sinh boilerplate sang Haiku là đòn bẩy lớn nhất về tiền.

## Bảng hành động

| # | Việc | Ai làm | Ghi chú |
| --- | --- | --- | --- |
| 1 | Merge PR kèm theo (CLAUDE.md, mẫu PR, mẫu issue). Merge PR #1 trước vì `CLAUDE.md` nhắc `client/`, `tools/`, `dist/` | Sếp | `CLAUDE.md` ngắn, ổn định, không có ngày giờ hay trạng thái |
| 2 | Giữ nguyên `CLAUDE.md` giữa phiên; sửa chỉ qua PR | Claude | Mỗi lần đổi nội dung đầu vào là ghi cache lại từ đầu |
| 3 | Build/lint/test chạy ở GitHub Actions; agent đọc kết quả bằng `actions_list`, `actions_get`, `get_job_logs` | Claude | Đã có `build-client` trong PR #1 |
| 4 | Thêm bước kiểm tra ngân sách JAR trong CI (fail nếu quá 153600 byte) | Claude | Làm khi PR #1 vào `main`, để khỏi xung đột. Hiện workflow mới chỉ báo cáo kích thước |
| 5 | Giao việc đọc nhiều, quét file, sinh hàng loạt cho subagent Haiku; Opus chỉ làm cố vấn khi bế tắc | Claude | Đúng quy tắc dự án. Subagent có cache riêng |
| 6 | Cài context-mode đầu mỗi thread | Claude | Cài ở user scope trong container tạm nên mất khi phiên kết thúc; tài liệu nói plugin khai báo trong repo không tự nạp ở phiên cloud. Giữ bước cài ở hướng dẫn dự án. Cần khởi động lại phiên mới có hook |
| 7 | Tắt connector và plugin không dùng cho dự án Monika (Notion, Drive, ElevenLabs, các plugin thiết kế/dữ liệu/quản lý sản phẩm...) | Sếp | Danh sách skill và hướng dẫn MCP nằm trong prefix của mọi request. Chỉ đổi giữa các phiên |
| 8 | ~~Cho biết gói thuê bao hay API key~~ Sếp dùng gói Claude Pro (thuê bao) nên hội thoại chính đã có TTL 1 giờ, không cần `ENABLE_PROMPT_CACHING_1H` | Xong | Subagent và việc nền vẫn mặc định 5 phút. Vượt hạn mức gói và dùng credit thì hội thoại chính rơi về 5 phút |
| 9 | Dùng `/usage` xem dòng "Prompt cache (main)" trước và sau khi áp dụng | Sếp hoặc Claude | Cách duy nhất để có số đo thật của dự án (cần Claude Code v2.1.251 trở lên) |
| 10 | Tắt việc nền tốn token nếu không cần: `idleCompaction`, `promptSuggestionEnabled`, `awaySummaryEnabled` | Sếp | Chỉ áp dụng cho giao diện tương tác; chưa chắc ảnh hưởng thread chạy từ ứng dụng. Chưa đưa vào repo vì chưa chắc có lợi |
| 11 | Dùng `/clear` giữa các việc không liên quan; thread nào xong thì giải quyết, việc mới mở thread mới | Sếp, Claude | Ngữ cảnh dài bị tính cả lần đọc lại mỗi lượt |

## Context-mode

- Plugin cục bộ, giấy phép Elastic License 2.0 (nguồn mở có điều kiện), cần Node 24.15 trở lên. Môi trường có Node 24.22.3; đã cài phiên bản 2.2.0 trong thread này.
- Cơ chế: chạy script trong tiến trình con, lưu toàn bộ đầu ra vào SQLite cục bộ, chỉ trả phần cần; hook PreToolUse chuyển hướng các lần đọc file lớn, WebFetch, curl sang lệnh `ctx_*`.
- Con số "giảm 99%" là **tự đo của dự án**, tính theo byte vào ngữ cảnh, không phải token hóa đơn hay chi phí cả phiên. Phần tìm kiếm chỉ tiết kiệm khoảng 77%. README không bàn về prompt cache.
- Lưu ý an toàn: `ctx_execute` chạy mã với quyền của người dùng trên máy chủ chạy phiên; README khuyên giữ hỏi quyền.
- Kết luận: đáng dùng cho thread có nhiều đọc/quét; muốn biết lợi thật thì đối chiếu `/usage` trước và sau.

## Thực tế và không thực tế

| Thực tế | Không thực tế |
| --- | --- |
| Prefix ổn định, giữ phiên liên tục, tỉ lệ đọc cache cao trong phiên dài | 99% trên toàn dự án (phiên mới, nghỉ quá TTL, subagent cache riêng, nén ngữ cảnh) |
| Đưa build/lint/test sang GitHub Actions | CI thay hoàn toàn việc chạy cục bộ khi gỡ lỗi |
| Haiku làm việc đọc/quét/boilerplate | Haiku quyết kiến trúc hoặc sửa lõi |
| Ghi "đừng đọc file lớn, giới hạn đầu ra" bằng quy tắc cụ thể | Ghi chung chung "hãy tiết kiệm token" (một bài viết cảnh báo nó khiến agent phân tích nông và ngại dùng subagent; nguồn bên thứ ba) |

## Câu hỏi còn mở cho sếp

Đã trả lời: sếp dùng gói Claude Pro (việc 8 xong) và đồng ý thêm bước kiểm tra ngân sách JAR vào CI sau khi PR #1 merge (việc 4, chờ PR #1).

## Nguồn

- [Prompt caching (Anthropic)](https://platform.claude.com/docs/en/build-with-claude/prompt-caching): bảng giá, TTL, mức tối thiểu, vô hiệu hóa cache. Phần giữa trang do công cụ đọc tóm tắt, các mục nêu ở trên về bảng vô hiệu hóa chi tiết chưa được đọc nguyên văn.
- [Claude Code: quản lý chi phí](https://code.claude.com/docs/en/costs): CLAUDE.md dưới 200 dòng, skill nạp theo yêu cầu, hook lọc đầu ra, subagent, `/usage`.
- [Claude Code: memory](https://code.claude.com/docs/en/memory), [GitHub Actions](https://code.claude.com/docs/en/github-actions), [Subagents](https://code.claude.com/docs/en/sub-agents), [Settings](https://code.claude.com/docs/en/settings): TTL theo gói, biến môi trường cache, `max-turns`, cache subagent.
- [context-mode](https://github.com/mksglu/context-mode): README, giấy phép, số đo tự báo cáo.
