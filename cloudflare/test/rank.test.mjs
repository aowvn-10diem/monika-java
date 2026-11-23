// Thử danh mục chuẩn, đếm lượt tải, xếp hạng bằng SQLite thật (node:sqlite làm D1 giả).
// Chạy: node cloudflare/test/rank.test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import w from "../worker.js";
import { resolveCat, parseTags, parsePlat, parseRes, parseKey, countDownload, recompute, hotlinked, DAY } from "../rank.js";

const here = dirname(fileURLToPath(import.meta.url));
const rd = (f) => readFileSync(join(here, f), "utf8");

// ---------- D1 giả ----------
function d1(db) {
  const stmt = (sql) => {
    const s = { sql, args: [], bind(...a) { s.args = a; return s; } };
    s.first = async () => db.prepare(sql).get(...s.args) || null;
    s.all = async () => ({ results: db.prepare(sql).all(...s.args) });
    s.run = async () => { const r = db.prepare(sql).run(...s.args); return { meta: { changes: Number(r.changes) } }; };
    return s;
  };
  return { prepare: stmt, batch: async (list) => { const out = []; for (const x of list) out.push(await x.run()); return out; } };
}
const mk = (schema) => { const db = new DatabaseSync(":memory:"); db.exec(schema); return db; };

// ---------- 1. migration 004 chạy được trên schema cũ (trước 004) và cho cùng kết quả như schema mới ----------
{
  const old = mk(rd("schema-pre004.sql"));
  old.exec("INSERT INTO cats(name) VALUES('Tiện ích'),('Trò chơi'),('Mạng'),('Học tập'),('Lạ')");
  old.exec("INSERT INTO items(src,type,title,cat_n,published,updated) VALUES('a','game','A',2,1,1),('b','app','B',1,1,1),('c','app','C',5,1,1)");
  old.exec(rd("../migrations/004-taxonomy.sql"));
  const cat = (s) => old.prepare("SELECT cat_n c FROM items WHERE src=?").get(s).c;
  assert.equal(cat("a"), 23, "Trò chơi cũ -> Khác (game)");
  assert.equal(cat("b"), 35, "Tiện ích cũ -> Công cụ hệ thống");
  assert.equal(cat("c"), null, "danh mục lạ không có ánh xạ -> rỗng");
  assert.equal(old.prepare("SELECT COUNT(*) c FROM cats").get().c, 27);
  assert.equal(old.prepare("SELECT plat p FROM items WHERE src='a'").get().p, 1, "mục cũ coi là J2ME");
  console.log("ok migration 004 trên DB cũ");
}

// ---------- 2. phân tích meta ----------
{
  assert.deepEqual(resolveCat("Hành động", "game"), { id: 10 });
  assert.deepEqual(resolveCat("hanh dong", "game"), { id: 10 });
  assert.deepEqual(resolveCat("Action", "game"), { id: 10 });
  assert.deepEqual(resolveCat("Khác", "app"), { id: 39 });
  assert.deepEqual(resolveCat("Khác", "game"), { id: 23 });
  assert.deepEqual(resolveCat("Quân sự lạ", "game"), { id: 23, unknown: "Quân sự lạ" });
  assert.deepEqual(resolveCat("", "game"), { id: null });
  assert.equal(parseTags("vh, MOD, 3D"), 2 | 4 | 8);
  assert.equal(parseTags("Việt hóa"), 0, "chỉ nhận slug hoặc nhãn ngắn");
  assert.equal(parsePlat(""), 1);
  assert.equal(parsePlat("s60v3, j2me"), 5);
  assert.equal(parseRes("240x320, 320x240", "", ""), "240x320,320x240");
  assert.equal(parseRes("", "http://x/Tam-quoc_240x320.jar", "Game"), "240x320");
  assert.equal(parseRes("", "", "Game 2x2"), "");
  assert.equal(parseKey("zzz"), null);
  const q = parseKey("c12.top7.p4.r240x320.t2");
  assert.equal(q.sort, "top7");
  assert.deepEqual(q.binds, [12, 4, "%,240x320,%", 2]);
  assert.ok(parseKey("game.hot").where.includes("i.hold = 0"));
  console.log("ok phân tích meta và khóa danh sách");
}

// ---------- 3. đồng bộ feed -> cột mới ----------
const schema = rd("../schema.sql") + rd("../migrations/004-taxonomy.sql").split("\n").filter((l) => l.startsWith("INSERT OR REPLACE INTO cats")).join("\n");
const entry = (id, title, body, labels = ["Game"], pub = "2026-11-20T00:00:00Z") => ({
  id: { $t: "tag:blogger.com,1999:blog-1.post-" + id }, title: { $t: title }, published: { $t: pub }, updated: { $t: pub },
  category: labels.map((term) => ({ term })), link: [{ rel: "alternate", href: "https://blog.test/p/" + id }], content: { $t: body },
});
const FEED = [
  entry(1, "Tam quốc đại chiến", `<pre class="mk-meta">cat: Chiến thuật\ntags: vh, 3d\nplat: j2me, s60v3\nres: 240x320\njar: https://x.test/a.jar\nnet: 1</pre>`),
  entry(2, "Cá lớn nuốt cá bé [Việt hóa]", `<pre class="mk-meta">cat: Arcade\njar: https://x.test/Ca-lon_320x240.jar</pre>`),
  entry(3, "Opera Mini", `<pre class="mk-meta">type: app\ncat: Mạng\njar: https://x.test/o.jar</pre>`, ["App"]),
  entry(4, "Game lạ", `<pre class="mk-meta">cat: Thể loại chưa có\njar: https://x.test/l.jar</pre>`),
];
const db = mk(schema);
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", SYNC_TOKEN: "st", UP_TOKEN: "ut", DB: d1(db) };
const realFetch = globalThis.fetch;
globalThis.fetch = async (u) => (String(u).startsWith("https://blog.test/feeds") ? new Response(JSON.stringify({ feed: { entry: FEED } })) : new Response("x", { status: 404 }));
const ctx = { waitUntil: (p) => { ctx.p.push(p); }, p: [] };
const flush = async () => { await Promise.all(ctx.p.splice(0)); };
const call = async (path, headers = {}) => { const r = await w.fetch(new Request("http://api.test" + path, { headers }), env, ctx); await flush(); return r; };

{
  const r = await call("/v1/sync?t=st");
  assert.equal(r.status, 200);
  const row = (src) => db.prepare("SELECT * FROM items WHERE src=?").get(src);
  assert.equal(row("1").cat_n, 13);
  assert.equal(row("1").tags, 2 | 8 | 128, "VH + 3D + cần mạng");
  assert.equal(row("1").plat, 5);
  assert.equal(row("1").res, "240x320");
  assert.equal(row("2").cat_n, 19);
  assert.equal(row("2").tags & 2, 2, "đoán VH từ tiêu đề");
  assert.equal(row("2").res, "320x240", "cỡ lấy từ tên file");
  assert.equal(row("3").cat_n, 30);
  assert.equal(row("4").cat_n, 23, "thể loại lạ rơi vào Khác");
  assert.ok(db.prepare("SELECT k FROM meta WHERE k LIKE 'unk:%'").get(), "ghi lại thể loại lạ");
  console.log("ok đồng bộ feed -> cat/tags/plat/res");
}

// ---------- 4. đếm lượt tải ----------
const UA = "NokiaE72/1.0 Series60/3.1 Profile/MIDP-2.0";
const dl = (id, ip, ua = UA, extra = {}) => call(`/v1/j/${id}.jar`, { "user-agent": ua, "cf-connecting-ip": ip, ...extra });
const n1 = db.prepare("SELECT n FROM items WHERE src='1'").get().n;
const n2 = db.prepare("SELECT n FROM items WHERE src='2'").get().n;
const n3 = db.prepare("SELECT n FROM items WHERE src='3'").get().n;
const count = (n) => db.prepare("SELECT dl FROM items WHERE n=?").get(n).dl;
{
  globalThis.fetch = async (u, o) => (String(u).startsWith("https://x.test") ? new Response("JARDATA", { headers: { "content-length": "7" } }) : new Response("x", { status: 404 }));
  const r = await dl(n1, "1.1.1.1");
  assert.equal(r.status, 200);
  assert.equal(count(n1), 1, "tải đầu tiên được đếm");
  await dl(n1, "1.1.1.1");
  assert.equal(count(n1), 1, "tải lại cùng máy trong tuần không đếm");
  await dl(n1, "1.1.1.1", UA + " b");
  assert.equal(count(n1), 2, "UA khác (máy khác cùng IP nhà mạng) vẫn đếm");
  await dl(n1, "1.1.1.1", UA + " c");
  assert.equal(count(n1), 3);
  await dl(n1, "1.1.1.1", UA + " d");
  assert.equal(count(n1), 3, "trần 3 lượt/IP/game/tuần chặn xoay UA");
  await dl(n1, "2.2.2.2", "curl/8.0");
  await dl(n1, "2.2.2.3", "Googlebot/2.1");
  await dl(n1, "2.2.2.4", "");
  assert.equal(count(n1), 3, "bot, curl, UA rỗng không đếm");
  await dl(n1, "2.2.2.5", UA, { range: "bytes=100-" });
  assert.equal(count(n1), 3, "Range không bắt đầu từ 0 không đếm");
  await dl(n1, "2.2.2.6", UA, { range: "bytes=0-" });
  assert.equal(count(n1), 4, "Range từ 0 được đếm");
  const jad = await call(`/v1/j/${n1}.jad`, { "user-agent": UA, "cf-connecting-ip": "3.3.3.3" }).catch(() => null);
  assert.equal(count(n1), 4, "JAD không đếm");
  const hl = await dl(n1, "4.4.4.4", UA, { referer: "http://spam.example/page" });
  assert.equal(hl.status, 302, "hotlink bị chuyển hướng");
  assert.equal(count(n1), 4);
  const ok = await dl(n1, "4.4.4.5", UA, { referer: "http://blog.test/p/x" });
  assert.equal(ok.status, 200, "Referer từ blog cho qua");
  assert.equal(count(n1), 5);
  assert.equal(await countDownload(env, new Request("http://api.test/x", { method: "HEAD", headers: { "user-agent": UA, "cf-connecting-ip": "5.5.5.5" } }), n1), "skip:method");
  assert.equal(hotlinked(env, new Request("http://x", { headers: { referer: "https://api.test/up" } })), false);
  console.log("ok đếm, khử trùng, trần IP, bot, Range, JAD, hotlink");
}

// ---------- 5. xếp hạng ----------
{
  // mục 2: 2 lượt, mục 3: 1 lượt
  await dl(n2, "6.6.6.1"); await dl(n2, "6.6.6.2"); await dl(n3, "6.6.6.1");
  const rc = await recompute(env, Date.now());
  assert.ok(rc.active >= 3);
  const g = (n) => db.prepare("SELECT d1,d7,score,dl FROM items WHERE n=?").get(n);
  assert.equal(g(n1).d7, 5);
  assert.equal(g(n2).d1, 2);
  const keys = async (key) => (await (await call("/v1/list.json?key=" + key)).json()).items.map((x) => x.id);
  assert.deepEqual((await keys("top7")).slice(0, 3), [n1, n2, n3], "top7 theo lượt 7 ngày");
  assert.deepEqual((await keys("dl")).slice(0, 2), [n1, n2]);
  const hot = await keys("hot");
  assert.equal(hot.length, 4, "hot trả đủ mục (site ít lượt thì rơi về mới)");
  const gameOnly = await keys("game.top7");
  assert.ok(!gameOnly.includes(n3), "game.* không có app");
  const cat13 = await keys("c13");
  assert.deepEqual(cat13, [n1]);
  db.prepare("UPDATE items SET hold=1 WHERE n=?").run(n1);
  assert.ok(!(await keys("top7")).includes(n1), "mục bị giữ không vào top7");
  assert.ok((await keys("dl")).includes(n1), "nhưng vẫn trong Tải nhiều/danh sách thường");
  db.prepare("UPDATE items SET hold=0 WHERE n=?").run(n1);
  // ngưỡng Hot: site tổng d7 >= 30 thì mới xếp theo điểm
  db.prepare("INSERT INTO meta(k,v) VALUES('tot7','100') ON CONFLICT(k) DO UPDATE SET v=excluded.v").run();
  const hot2 = await keys("hot");
  assert.equal(hot2[0], n1, "đủ lượt: điểm cao nhất lên đầu");
  assert.ok(hot2.indexOf(n3) > hot2.indexOf(n2), "d7<3 không vào nhóm Hot");
  // ghim tay bằng cờ 8
  db.prepare("UPDATE items SET flags=flags|8 WHERE n=?").run(n3);
  await recompute(env, Date.now());
  assert.equal((await keys("hot"))[0], n3, "mục ghim (cờ 8) lên đầu Hot");
  // lọc nền tảng, cỡ màn hình, thẻ
  assert.deepEqual(await keys("game.p4"), [n1], "S60v3 chỉ có mục 1");
  assert.ok((await keys("game.r320x240")).includes(n2));
  assert.ok(!(await keys("game.r320x240")).includes(n1), "mục 1 chỉ có cỡ 240x320");
  assert.equal((await keys("new.t2")).length, 2);
  console.log("ok xếp hạng top7/dl/hot/ghim/hold/lọc");
}

// ---------- 6. định dạng nhị phân và endpoint phụ ----------
{
  const u8 = async (p) => new Uint8Array(await (await call(p)).arrayBuffer());
  const l2 = await u8("/v1/l2/top7/0.bin");
  const dv = new DataView(l2.buffer);
  assert.equal(dv.getInt32(0), 0x4d4b4c32);
  assert.equal(l2[4], 2);
  const cnt = l2[9];
  assert.equal(cnt, 4);
  assert.equal(dv.getInt32(10), n1, "bản ghi đầu là mục 1");
  assert.equal(dv.getInt32(10 + 4 + 1 + 1 + 2 + 4), 5, "trường dl ngay sau price");
  const l1 = await u8("/v1/l/top7/0.bin");
  assert.equal(new DataView(l1.buffer).getInt32(0), 0x4d4b4c31, "v1 giữ nguyên tiêu đề");
  const h2 = await u8("/v1/home2.bin");
  assert.equal(new DataView(h2.buffer).getInt32(0), 0x4d4b4832);
  const h1 = await u8("/v1/home.bin");
  assert.equal(new DataView(h1.buffer).getInt32(0), 0x4d4b4831);
  const st = await (await call(`/v1/stats.json?ids=${n1},${n2},99999`)).json();
  assert.equal(st[n1].dl, 5);
  assert.ok(!st[99999]);
  const cj = await (await call("/v1/cats.json")).json();
  assert.ok(cj.find((c) => c.id === 13 && c.name === "Chiến thuật" && c.count === 1));
  assert.equal((await call("/v1/l2/khong-hop-le/0.bin")).status, 404);
  console.log("ok l2, home2, stats, cats");
}

globalThis.fetch = realFetch;
console.log("TẤT CẢ QUA");
