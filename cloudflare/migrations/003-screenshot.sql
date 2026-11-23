-- Chạy một lần trên DB đã có: ảnh minh họa của mục (cron 5 phút tự điền lại từ bài Blogspot).
ALTER TABLE items ADD COLUMN img TEXT;
