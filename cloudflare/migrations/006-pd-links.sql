-- Ánh xạ ID tệp Pixeldrain (trong dòng v1..vN của bài link-only) -> bài (src), để route /v1/p đếm lượt tải vào đúng mục.
CREATE TABLE IF NOT EXISTS pd_links (pd TEXT PRIMARY KEY, src TEXT NOT NULL) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS pd_links_src ON pd_links(src);
