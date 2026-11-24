CREATE TABLE IF NOT EXISTS cats (n INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, kind INTEGER, slug TEXT, sort INTEGER); -- danh mục chuẩn: seed trong migrations/004-taxonomy.sql (sinh từ catalog/taxonomy.json)
CREATE TABLE IF NOT EXISTS items (
  n INTEGER PRIMARY KEY AUTOINCREMENT,
  src TEXT UNIQUE NOT NULL,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  norm TEXT NOT NULL DEFAULT '',
  cat_n INTEGER,
  ver TEXT DEFAULT '',
  size_kb INTEGER DEFAULT 0,
  price INTEGER DEFAULT 0,
  jar TEXT DEFAULT '',
  descr TEXT DEFAULT '',
  url TEXT DEFAULT '',
  flags INTEGER DEFAULT 0,
  published INTEGER DEFAULT 0,
  updated INTEGER DEFAULT 0,
  dl INTEGER DEFAULT 0,
  sweep INTEGER DEFAULT 0,
  jad_text TEXT,
  jar_size INTEGER,
  img TEXT,           -- ảnh minh họa (URL), app lấy qua /v1/sc/<id>-<w>x<h>.jpg
  file_h TEXT,        -- files.k nếu JAR nằm trong kho của Monika
  ic32 TEXT, ic64 TEXT, icon_state INTEGER, -- icon từ JAR ở link ngoài; icon_state: NULL chưa thử, 1 ok, 2 không có, 3 đang thử
  tags INTEGER DEFAULT 0, plat INTEGER DEFAULT 1, res TEXT DEFAULT '',  -- thẻ (bit), nền tảng (bit), cỡ màn hình "240x320,320x240"
  d1 INTEGER DEFAULT 0, d7 INTEGER DEFAULT 0, score INTEGER DEFAULT 0, hold INTEGER DEFAULT 0 -- lượt tải hôm nay/7 ngày (cron), điểm Hot, 1 = đang giữ chờ duyệt
);
CREATE INDEX IF NOT EXISTS items_updated ON items(updated DESC);
CREATE INDEX IF NOT EXISTS items_cat ON items(cat_n);
CREATE TABLE IF NOT EXISTS banners (
  n INTEGER PRIMARY KEY AUTOINCREMENT,
  src TEXT UNIQUE NOT NULL,
  slot TEXT DEFAULT 'top',
  img TEXT DEFAULT '',
  link TEXT DEFAULT '',
  alt TEXT DEFAULT '',
  updated INTEGER DEFAULT 0,
  sweep INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);

-- Luồng up file (R2/GitHub/KV): xem migrations/002-upload.sql để nâng cấp DB đã có.
CREATE TABLE IF NOT EXISTS files (
  k TEXT PRIMARY KEY,          -- 10 ký tự đầu SHA-256, nằm trong tên file chuẩn hóa
  h TEXT UNIQUE NOT NULL,      -- SHA-256 đầy đủ (chống trùng)
  fname TEXT UNIQUE NOT NULL,  -- ten-game-phienban-k.jar
  name TEXT, vendor TEXT, ver TEXT, descr TEXT,
  size INTEGER NOT NULL, store TEXT NOT NULL, skey TEXT NOT NULL, -- store: r2 | gh | kv
  mf TEXT,                     -- MANIFEST.MF gốc, dựng JAD khi cần
  ic32 TEXT, ic64 TEXT,        -- icon PNG base64, 32x32 (app) và 64x64 (web)
  has_icon INTEGER DEFAULT 0, orig TEXT, created INTEGER
);
CREATE INDEX IF NOT EXISTS files_name ON files(name);

-- Đếm lượt tải khử trùng (xem rank.js). dl_seen: khóa máy theo tuần; dl_cap: trần 3 lượt/IP/game/tuần; dl_day: lượt theo ngày (giờ VN).
CREATE TABLE IF NOT EXISTS dl_seen (k TEXT PRIMARY KEY, t INTEGER NOT NULL) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS dl_seen_t ON dl_seen(t);
CREATE TABLE IF NOT EXISTS dl_cap (k TEXT PRIMARY KEY, n INTEGER NOT NULL, t INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS dl_day (item INTEGER NOT NULL, day INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(item, day)) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS items_pub ON items(published DESC);
CREATE INDEX IF NOT EXISTS items_dl ON items(dl DESC);
CREATE INDEX IF NOT EXISTS items_score ON items(score) WHERE score > 0;
CREATE INDEX IF NOT EXISTS items_pin ON items(updated DESC) WHERE (flags & 8) <> 0;

-- Pixeldrain: ID tệp -> bài (migration 006), để /v1/p đếm lượt tải vào đúng mục
CREATE TABLE IF NOT EXISTS pd_links (pd TEXT PRIMARY KEY, src TEXT NOT NULL) WITHOUT ROWID;
