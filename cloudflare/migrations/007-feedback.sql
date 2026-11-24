-- Góp ý / báo lỗi game. Không bắt buộc chạy: Worker tự tạo bảng khi nhận góp ý đầu tiên (CREATE TABLE IF NOT EXISTS, feedback.js). Giữ file để tham chiếu lược đồ.
CREATE TABLE IF NOT EXISTS feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, kind TEXT, item INTEGER, src TEXT, ver TEXT, ua TEXT, dev TEXT, msg TEXT NOT NULL, contact TEXT, ipk TEXT, hash TEXT, nonce TEXT UNIQUE, sent INTEGER DEFAULT 0);
CREATE INDEX IF NOT EXISTS feedback_sent ON feedback(sent, id);
CREATE INDEX IF NOT EXISTS feedback_hash ON feedback(hash);
CREATE TABLE IF NOT EXISTS fb_rl (k TEXT PRIMARY KEY, n INTEGER, t INTEGER) WITHOUT ROWID;
