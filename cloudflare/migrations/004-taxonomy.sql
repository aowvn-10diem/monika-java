-- SINH TỰ ĐỘNG từ catalog/taxonomy.json bằng tools/gen-taxonomy.mjs. Chạy MỘT lần trên DB đã có (D1 không có ADD COLUMN IF NOT EXISTS).
-- Thứ tự deploy: chạy migration này trước, deploy Worker sau.
ALTER TABLE cats ADD COLUMN kind INTEGER;
ALTER TABLE cats ADD COLUMN slug TEXT;
ALTER TABLE cats ADD COLUMN sort INTEGER;
ALTER TABLE items ADD COLUMN tags INTEGER DEFAULT 0;
ALTER TABLE items ADD COLUMN plat INTEGER DEFAULT 1;
ALTER TABLE items ADD COLUMN res TEXT DEFAULT '';
ALTER TABLE items ADD COLUMN d1 INTEGER DEFAULT 0;
ALTER TABLE items ADD COLUMN d7 INTEGER DEFAULT 0;
ALTER TABLE items ADD COLUMN score INTEGER DEFAULT 0;
ALTER TABLE items ADD COLUMN hold INTEGER DEFAULT 0;

-- Danh mục chuẩn (id cố định). Cột name là UNIQUE nên hai danh mục 'Khác' cần tên khác nhau ở tầng DB.
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(10,'Hành động',1,'hanh-dong',0);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(11,'Phiêu lưu',1,'phieu-luu',1);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(12,'Nhập vai',1,'nhap-vai',2);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(13,'Chiến thuật',1,'chien-thuat',3);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(14,'Đua xe',1,'dua-xe',4);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(15,'Thể thao',1,'the-thao',5);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(16,'Bắn súng',1,'ban-sung',6);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(17,'Đối kháng',1,'doi-khang',7);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(18,'Giải đố & trí tuệ',1,'giai-do',8);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(19,'Arcade & chạy nhảy',1,'arcade',9);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(20,'Mô phỏng & quản lý',1,'mo-phong',10);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(21,'Cờ & bài',1,'co-bai',11);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(22,'Trẻ em & vui nhộn',1,'tre-em',12);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(23,'Khác (game)',1,'game-khac',13);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(30,'Mạng & trình duyệt',2,'mang',14);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(31,'Chat & mạng xã hội',2,'chat',15);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(32,'Đọc sách & truyện',2,'doc-sach',16);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(33,'Nhạc & video',2,'nhac-video',17);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(34,'Văn phòng & học tập',2,'hoc-tap',18);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(35,'Công cụ hệ thống',2,'cong-cu',19);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(36,'Bản đồ & GPS',2,'ban-do',20);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(37,'Đời sống',2,'doi-song',21);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(38,'Giao diện & hình nền',2,'giao-dien',22);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(39,'Khác (app)',2,'app-khac',23);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(50,'Thủ thuật',3,'thu-thuat',24);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(51,'Truyện & giải trí',3,'truyen',25);
INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(52,'Tin Monika',3,'tin-monika',26);

-- Danh mục demo cũ -> danh mục chuẩn, rồi xóa hàng cũ.
UPDATE items SET cat_n=35 WHERE cat_n=1;
UPDATE items SET cat_n=23 WHERE cat_n=2;
UPDATE items SET cat_n=30 WHERE cat_n=3;
UPDATE items SET cat_n=34 WHERE cat_n=4;
DELETE FROM cats WHERE kind IS NULL;
UPDATE items SET cat_n=NULL WHERE cat_n IS NOT NULL AND cat_n NOT IN (SELECT n FROM cats);

CREATE TABLE IF NOT EXISTS dl_seen (k TEXT PRIMARY KEY, t INTEGER NOT NULL) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS dl_seen_t ON dl_seen(t);
CREATE TABLE IF NOT EXISTS dl_cap (k TEXT PRIMARY KEY, n INTEGER NOT NULL, t INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS dl_day (item INTEGER NOT NULL, day INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(item, day)) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS items_pub ON items(published DESC);
CREATE INDEX IF NOT EXISTS items_dl ON items(dl DESC);
CREATE INDEX IF NOT EXISTS items_score ON items(score) WHERE score > 0;
CREATE INDEX IF NOT EXISTS items_pin ON items(updated DESC) WHERE (flags & 8) <> 0;
