// Sinh cloudflare/taxonomy.js và cloudflare/migrations/005-taxonomy-v3.sql từ catalog/taxonomy.json.
// Chạy: node tools/gen-taxonomy.mjs   (thêm --check để chỉ kiểm tra file sinh ra còn khớp nguồn)
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const t = JSON.parse(readFileSync(join(root, "catalog/taxonomy.json"), "utf8"));
const q = (s) => "'" + String(s).replace(/'/g, "''") + "'";

const js = `// SINH TỰ ĐỘNG từ catalog/taxonomy.json bằng tools/gen-taxonomy.mjs. Không sửa tay.
export const TAXONOMY = ${JSON.stringify({ kinds: t.kinds, cats: t.cats, legacy: t.legacy, other: t.other, plats: t.plats, tags: t.tags })};
`;

// 004-taxonomy.sql đã chạy trên production, đóng băng (không sinh lại). 005 nạp danh mục v3: chạy lại nhiều lần vẫn an toàn.
const kindName = Object.fromEntries(t.kinds.map((k) => [k.kind, k.name]));
const sql = [
  "-- SINH TỰ ĐỘNG từ catalog/taxonomy.json bằng tools/gen-taxonomy.mjs. Danh mục chuẩn v3 (thêm nhóm giao diện, âm thanh, bài viết; thể loại game mới).",
  "-- Chạy được nhiều lần (INSERT OR REPLACE theo id). Cần migration 004 đã chạy. Thứ tự deploy: chạy migration này trước, deploy Worker sau.",
  "-- Cột name là UNIQUE nên các danh mục 'Khác' cần tên khác nhau ở tầng DB.",
  ...t.cats.map((c, i) => {
    const dbName = c.name === "Khác" ? `Khác (${kindName[c.kind]})` : c.name;
    return `INSERT OR REPLACE INTO cats(n,name,kind,slug,sort) VALUES(${c.id},${q(dbName)},${c.kind},${q(c.slug)},${i});`;
  }),
  "",
].join("\n");

const out = { "cloudflare/taxonomy.js": js, "cloudflare/migrations/005-taxonomy-v3.sql": sql };
if (process.argv.includes("--check")) {
  let bad = 0;
  for (const [f, c] of Object.entries(out)) {
    let cur = ""; try { cur = readFileSync(join(root, f), "utf8"); } catch (_) {}
    if (cur !== c) { console.error("LỆCH:", f, "(chạy node tools/gen-taxonomy.mjs)"); bad++; }
  }
  process.exit(bad ? 1 : 0);
}
for (const [f, c] of Object.entries(out)) { writeFileSync(join(root, f), c); console.log("ghi", f); }
