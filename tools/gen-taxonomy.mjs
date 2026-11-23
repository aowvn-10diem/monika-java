// Sinh cloudflare/taxonomy.js và cloudflare/migrations/004-taxonomy.sql từ catalog/taxonomy.json.
// Chạy: node tools/gen-taxonomy.mjs   (thêm --check để chỉ kiểm tra file sinh ra còn khớp nguồn)
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const t = JSON.parse(readFileSync(join(root, "catalog/taxonomy.json"), "utf8"));
const q = (s) => "'" + String(s).replace(/'/g, "''") + "'";

const js = `// SINH TỰ ĐỘNG từ catalog/taxonomy.json bằng tools/gen-taxonomy.mjs. Không sửa tay.
export const TAXONOMY = ${JSON.stringify({ cats: t.cats, legacy: t.legacy, other: t.other, plats: t.plats, tags: t.tags })};
`;

const sql = [
  "-- SINH TỰ ĐỘNG từ catalog/taxonomy.json bằng tools/gen-taxonomy.mjs. Chạy MỘT lần trên DB đã có (D1 không có ADD COLUMN IF NOT EXISTS).",
  "-- Thứ tự deploy: chạy migration này trước, deploy Worker sau.",
  "ALTER TABLE cats ADD COLUMN kind INTEGER;",
  "ALTER TABLE cats ADD COLUMN slug TEXT;",
  "ALTER TABLE cats ADD COLUMN sort INTEGER;",
  "ALTER TABLE items ADD COLUMN tags INTEGER DEFAULT 0;",
  "ALTER TABLE items ADD COLUMN plat INTEGER DEFAULT 1;",
  "ALTER TABLE items ADD COLUMN res TEXT DEFAULT '';",
  "ALTER TABLE items ADD COLUMN d1 INTEGER DEFAULT 0;",
  "ALTER TABLE items ADD COLUMN d7 INTEGER DEFAULT 0;",
  "ALTER TABLE items ADD COLUMN score INTEGER DEFAULT 0;",
  "ALTER TABLE items ADD COLUMN hold INTEGER DEFAULT 0;",
  "",
  "-- Danh mục chuẩn (id cố định). Cột name là UNIQUE nên hai danh mục 'Khác' cần tên khác nhau ở tầng DB.",
  ...t.cats.map((c, i) => {
    const dbName = c.name === "Khác" ? (c.kind === 1 ? "Khác (game)" : "Khác (app)") : c.name;
    return `INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(${c.id},${q(dbName)},${c.kind},${q(c.slug)},${i});`;
  }),
  "",
  "-- Danh mục demo cũ -> danh mục chuẩn, rồi xóa hàng cũ.",
  ...Object.entries(t.legacy).map(([from, to]) => `UPDATE items SET cat_n=${to} WHERE cat_n=${from};`),
  "DELETE FROM cats WHERE kind IS NULL;",
  "UPDATE items SET cat_n=NULL WHERE cat_n IS NOT NULL AND cat_n NOT IN (SELECT n FROM cats);",
  "",
  "CREATE TABLE IF NOT EXISTS dl_seen (k TEXT PRIMARY KEY, t INTEGER NOT NULL) WITHOUT ROWID;",
  "CREATE INDEX IF NOT EXISTS dl_seen_t ON dl_seen(t);",
  "CREATE TABLE IF NOT EXISTS dl_cap (k TEXT PRIMARY KEY, n INTEGER NOT NULL, t INTEGER NOT NULL) WITHOUT ROWID;",
  "CREATE TABLE IF NOT EXISTS dl_day (item INTEGER NOT NULL, day INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(item, day)) WITHOUT ROWID;",
  "CREATE INDEX IF NOT EXISTS items_pub ON items(published DESC);",
  "CREATE INDEX IF NOT EXISTS items_dl ON items(dl DESC);",
  "CREATE INDEX IF NOT EXISTS items_score ON items(score) WHERE score > 0;",
  "CREATE INDEX IF NOT EXISTS items_pin ON items(updated DESC) WHERE (flags & 8) <> 0;",
  "",
].join("\n");

const out = { "cloudflare/taxonomy.js": js, "cloudflare/migrations/004-taxonomy.sql": sql };
if (process.argv.includes("--check")) {
  let bad = 0;
  for (const [f, c] of Object.entries(out)) {
    let cur = ""; try { cur = readFileSync(join(root, f), "utf8"); } catch (_) {}
    if (cur !== c) { console.error("LỆCH:", f, "(chạy node tools/gen-taxonomy.mjs)"); bad++; }
  }
  process.exit(bad ? 1 : 0);
}
for (const [f, c] of Object.entries(out)) { writeFileSync(join(root, f), c); console.log("ghi", f); }
