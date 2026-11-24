// Thử danh mục v3 (nhóm giao diện, âm thanh, bài viết, thể loại game mới), Đề xuất hôm nay, phân trang số, menu.json, home3.bin.
// Chạy: node cloudflare/test/v3.test.mjs
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import w, { chooserHtml } from "../worker.js";
import { resolveCat, typeFromLabels, parseKey, pickOrder, orderFor, screenOrder, isSmart, CATS, DAY } from "../rank.js";
import { pageWindow, markOf, pagerData } from "../pager.js";

const here = dirname(fileURLToPath(import.meta.url));
const rd = (f) => readFileSync(join(here, f), "utf8");
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

// ---------- 1. migration 005 chạy sau 004, chạy lại vẫn an toàn ----------
{
  const db = new DatabaseSync(":memory:");
  db.exec(rd("../schema.sql"));
  db.exec(rd("../migrations/004-taxonomy.sql").split("\n").filter((l) => l.startsWith("INSERT OR REPLACE INTO cats")).join("\n"));
  db.exec("INSERT INTO items(src,type,title,cat_n,published,updated) VALUES('a','game','A',12,1,1),('b','app','B',33,1,1)");
  db.exec(rd("../migrations/005-taxonomy-v3.sql"));
  db.exec(rd("../migrations/005-taxonomy-v3.sql"));
  assert.equal(db.prepare("SELECT COUNT(*) c FROM cats").get().c, CATS.size, "27 danh mục cũ cộng mới, không nhân đôi khi chạy lại");
  assert.equal(db.prepare("SELECT kind k FROM cats WHERE n=66").get().k, 5);
  assert.equal(db.prepare("SELECT name FROM cats WHERE n=65").get().name, "Khác (Hình nền & Theme)");
  assert.equal(db.prepare("SELECT cat_n c FROM items WHERE src='a'").get().c, 12, "mục cũ giữ nguyên id danh mục");
  assert.equal(db.prepare("SELECT name FROM cats WHERE n=33").get().name, "Trình phát nhạc & video");
  console.log("ok migration 005");
}

// ---------- 2. phân giải danh mục theo loại bài, suy loại bài từ nhãn ----------
{
  assert.deepEqual(resolveCat("Kiếm hiệp", "game"), { id: 24 });
  assert.deepEqual(resolveCat("Kiếm hiệp", "post"), { id: 54 });
  assert.deepEqual(resolveCat("Thủ thành", "game"), { id: 26 });
  assert.deepEqual(resolveCat("Nhạc chuông", "media"), { id: 66 });
  assert.deepEqual(resolveCat("Wallpaper", "media"), { id: 60 });
  assert.deepEqual(resolveCat("Truyện cười", "post"), { id: 51 });
  assert.deepEqual(resolveCat("Khác", "media"), { id: 65 });
  assert.deepEqual(resolveCat("Khác", "post"), { id: 56 });
  assert.deepEqual(resolveCat("Hành động", "media"), { id: 65, unknown: "Hành động" }, "tên game gắn cho giao diện thì rơi vào Khác");
  assert.deepEqual(resolveCat("Mạng", "game"), { id: 30 }, "game gắn tên thể loại app vẫn nhận như cũ");
  assert.equal(typeFromLabels(["Truyện cười"]), "post");
  assert.equal(typeFromLabels(["Hình nền anime & artist"]), "media");
  assert.equal(typeFromLabels(["Giao diện"]), "media");
  assert.equal(typeFromLabels(["Bài viết", "Thủ thuật & mẹo"]), "post");
  assert.equal(typeFromLabels(["Hành động"]), "", "nhãn game/app không tự suy ra loại");
  assert.equal(typeFromLabels(["Khác"]), "", "nhãn mơ hồ không suy ra loại");
  // không có alias nào trùng giữa hai danh mục cùng nhóm (trừ "Khác")
  const seen = new Map();
  for (const c of CATS.values()) for (const a of [c.name, ...c.alias]) {
    const k = a.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/gi, "d").toLowerCase();
    const o = seen.get(k + "|" + c.kind);
    assert.ok(!o || o === c.id || c.name === "Khác", `alias trùng trong cùng nhóm: ${a} (${o}, ${c.id})`);
    seen.set(k + "|" + c.kind, c.id);
  }
  console.log("ok danh mục v3");
}

// ---------- 3. khóa danh sách, đồng bộ bài viết/giao diện, menu.json, home3 ----------
const schema = rd("../schema.sql") + rd("../migrations/005-taxonomy-v3.sql").split("\n").filter((l) => l.startsWith("INSERT")).join("\n");
const entry = (id, title, body, labels, pub) => ({
  id: { $t: "tag:blogger.com,1999:blog-1.post-" + id }, title: { $t: title }, published: { $t: pub }, updated: { $t: pub },
  category: labels.map((term) => ({ term })), link: [{ rel: "alternate", href: "https://blog.test/p/" + id }], content: { $t: body },
});
const T0 = Date.UTC(2026, 10, 20);
const FEED = [];
for (let i = 1; i <= 12; i++) FEED.push(entry(i, "Game " + i, `<pre class="mk-meta">cat: ${i % 2 ? "Hành động" : "Kiếm hiệp"}\njar: https://x.test/${i}.jar${i <= 8 ? "\nimg: https://x.test/s" + i + ".jpg" : ""}</pre>`, ["Game"], new Date(T0 + i * 60000).toISOString()));
FEED.push(entry(21, "Hình nền anime A", `<p>x</p><pre class="mk-meta">cat: Hình nền anime\nv1: 240x320|90|JPG=https://x.test/a.jpg</pre>`, ["Giao diện", "Hình nền anime & artist"], new Date(T0 + 100000).toISOString()));
FEED.push(entry(22, "Nhạc chuông hay", `<pre class="mk-meta">type: media\ncat: Nhạc chuông\nv1: MP3 128k|300|MP3=https://x.test/n.mp3</pre>`, ["Nhạc chuông"], new Date(T0 + 110000).toISOString()));
FEED.push(entry(23, "Truyện cười số 1", `<p>Chuyện vui</p>`, ["Truyện cười"], new Date(T0 + 120000).toISOString()));
FEED.push(entry(24, "Bài không nhãn", `<p>x</p>`, ["Linh tinh"], new Date(T0 + 130000).toISOString()));
const db = new DatabaseSync(":memory:");
db.exec(schema);
const env = { BLOG_URL: "https://blog.test", API_HOST: "api.test", SYNC_TOKEN: "st", UP_TOKEN: "ut", DB: d1(db) };
globalThis.fetch = async (u) => (String(u).startsWith("https://blog.test/feeds/posts/default") ? new Response(JSON.stringify({ feed: { entry: FEED } })) : new Response("x", { status: 404 }));
const ctx = { waitUntil: (p) => { ctx.p.push(p); }, p: [] };
const call = async (path, headers = {}) => { const r = await w.fetch(new Request("http://api.test" + path, { headers }), env, ctx); await Promise.all(ctx.p.splice(0)); return r; };
{
  assert.equal((await call("/v1/sync?t=st")).status, 200);
  const row = (src) => db.prepare("SELECT * FROM items WHERE src=?").get(src);
  assert.equal(row("21").type, "media"); assert.equal(row("21").cat_n, 61);
  assert.equal(row("22").type, "media"); assert.equal(row("22").cat_n, 66);
  assert.equal(row("23").type, "post"); assert.equal(row("23").cat_n, 51);
  assert.equal(row("24"), undefined, "bài không có type và nhãn lạ vẫn bị bỏ qua");
  assert.equal(row("1").cat_n, 10); assert.equal(row("2").cat_n, 24);

  const js = async (path) => (await call(path)).json();
  assert.equal((await js("/v1/list.json?key=new")).total, 12, "Mới chỉ gồm game và app");
  assert.equal((await js("/v1/list.json?key=k4")).total, 1, "nhóm giao diện");
  assert.equal((await js("/v1/list.json?key=k5")).total, 1, "nhóm âm thanh và video");
  assert.equal((await js("/v1/list.json?key=k3")).total, 1);
  assert.equal((await js("/v1/list.json?key=c66")).items[0].title, "Nhạc chuông hay");
  assert.equal((await js("/v1/list.json?key=c51")).items[0].title, "Truyện cười số 1");
  assert.equal((await js("/v1/list.json?key=c24")).total, 6);
  assert.equal(parseKey("k6"), null);

  // tìm kiếm thấy cả media và bài viết
  const sb = new Uint8Array(await (await call("/v1/s/truyen/0.bin")).arrayBuffer());
  assert.equal((sb[5] << 8) | sb[6], 1, "tìm 'truyen' thấy 1 bài");

  const mj = await js("/v1/menu.json");
  assert.deepEqual(mj.kinds.map((k) => k.kind), [1, 2, 4, 5, 3]);
  assert.equal(mj.kinds[0].label, "Game"); assert.equal(mj.kinds[0].count, 12);
  assert.deepEqual(mj.kinds[2].cats.map((c) => c.id), [61], "chỉ liệt kê danh mục có mục");
  assert.equal(mj.kinds[4].cats[0].name, "Truyện cười & tiếu lâm");
  const cj = await js("/v1/cats.json"); assert.ok(cj.some((c) => c.kind === 3 && c.id === 51));

  const h2 = new Uint8Array(await (await call("/v1/home2.bin")).arrayBuffer());
  const h3 = new Uint8Array(await (await call("/v1/home3.bin")).arrayBuffer());
  assert.equal(h2[3], 0x32); assert.equal(h2[4], 2); assert.equal(h3[3], 0x33); assert.equal(h3[4], 3);
  assert.ok(h3.length > h2.length, "home3 có thêm nhóm 3, 4, 5; home2 vẫn chỉ game/app cho app cũ");
  console.log("ok đồng bộ và danh sách theo nhóm");
}

// ---------- 4. Đề xuất hôm nay ----------
{
  const ids = (j) => j.items.map((x) => x.id).join(",");
  const a = await (await call("/v1/list.json?key=pick")).json();
  assert.equal(a.total, 12);
  assert.ok(a.items.slice(0, 8).every((x) => x.shot), "mục có ảnh minh họa lên trước");
  assert.equal(ids(a), ids(await (await call("/v1/list.json?key=pick")).json()), "cùng ngày thì cùng thứ tự");
  assert.ok(parseKey("pick").where.includes("i.hold = 0"));
  const d0 = Date.UTC(2026, 10, 24, 5);
  const order = (now) => db.prepare(`SELECT n FROM items i WHERE i.type='game' ORDER BY ${pickOrder(now)}`).all().map((r) => r.n).join(",");
  assert.equal(order(d0), order(d0 + 3600e3), "trong ngày không đổi");
  assert.notEqual(order(d0), order(d0 + DAY), "qua ngày mới đổi thứ tự");
  const base = Date.UTC(2026, 10, 24, 16, 59); // 23:59 giờ Việt Nam
  assert.notEqual(order(base), order(base + 2 * 60000), "đổi ngày lúc nửa đêm giờ Việt Nam");
  db.exec("UPDATE items SET hold=1 WHERE src='1'");
  const held = await (await call("/v1/list.json?key=pick")).json();
  assert.ok(!held.items.some((x) => x.title === "Game 1"), "mục đang giữ không được đề xuất");
  console.log("ok đề xuất hôm nay");
}

// ---------- 5. phân trang số ----------
{
  assert.deepEqual(pageWindow(1, 1, 6), [1]);
  assert.deepEqual(pageWindow(7, 3, 6), [1, 2, 3, 4, 5, 6, 7], "ít trang thì hiện hết");
  assert.deepEqual(pageWindow(20, 1, 6), [1, 2, 3, 4, 5, 6, 20], "trang đầu: 1 2 3 4 5 6 … cuối");
  assert.deepEqual(pageWindow(20, 10, 6), [1, 7, 8, 9, 10, 11, 12, 20]);
  assert.deepEqual(pageWindow(20, 20, 6), [1, 15, 16, 17, 18, 19, 20]);
  assert.deepEqual(pageWindow(20, 3, 4), [1, 2, 3, 4, 20]);
  assert.equal(markOf("2026-11-23T00:52:00.000-08:00"), "2026-11-23T00:52:00-08:00");
  assert.equal(markOf("2026-11-23T00:52:00Z"), "2026-11-23T00:52:00Z");

  // feed giả: 50 bài, bài thứ i đăng lúc 12:00 - i phút (mới nhất trước)
  const urls = [];
  const pubOf = (i) => new Date(Date.UTC(2026, 10, 23, 12, 0) - i * 60000).toISOString().replace("Z", "+00:00");
  const feed = async (u) => {
    u = String(u); urls.push(u);
    const i = +/start-index=(\d+)&max-results=1/.exec(u)[1];
    return new Response(JSON.stringify({ feed: { "openSearch$totalResults": { $t: "50" }, entry: i <= 50 ? [{ published: { $t: pubOf(i) } }] : [] } }));
  };
  const e = { BLOG_URL: "https://blog.test/" };
  let r = await pagerData(e, { scope: "", n: "16", cur: "1", w: "6" }, feed);
  assert.equal(r.pages, 4); assert.equal(r.total, 50);
  assert.deepEqual(r.items.map((x) => x.p), [1, 2, 3, 4]);
  assert.equal(r.items[0].m, undefined);
  assert.equal(r.items[1].m, markOf(pubOf(16)), "trang 2 mở sau bài thứ 16");
  assert.ok(urls.some((u) => u.includes("start-index=48&")), "trang 4 tra bài thứ 48");
  urls.length = 0;
  r = await pagerData(e, { scope: "label:Hình nền & Theme", n: "4", cur: "7", w: "4" }, feed);
  assert.equal(r.pages, 13); assert.equal(r.cur, 7);
  assert.deepEqual(r.items.map((x) => x.p), [1, 5, 6, 7, 8, 13]);
  assert.ok(urls[0].startsWith("https://blog.test/feeds/posts/summary/-/H%C3%ACnh%20n%E1%BB%81n%20%26%20Theme?alt=json"), urls[0]);
  urls.length = 0;
  await pagerData(e, { scope: "q:kiếm hiệp", n: "16" }, feed);
  assert.ok(urls[0].includes("&q=ki%E1%BA%BFm%20hi%E1%BB%87p"));
  const one = await pagerData(e, { scope: "", n: "100" }, async () => new Response(JSON.stringify({ feed: { "openSearch$totalResults": { $t: "30" }, entry: [] } })));
  assert.equal(one.pages, 1); assert.deepEqual(one.items, [], "một trang thì không cần số trang");
  // qua endpoint
  globalThis.fetch = async (u) => feed(u);
  const rr = await call("/v1/pager.json?scope=&n=16&cur=2&w=6");
  assert.equal(rr.status, 200); assert.equal(rr.headers.get("access-control-allow-origin"), "*");
  const jj = await rr.json(); assert.equal(jj.cur, 2); assert.equal(jj.pages, 4);
  globalThis.fetch = async () => new Response("no", { status: 500 });
  assert.equal((await call("/v1/pager.json?scope=zz&n=16&cur=1")).status, 502);
  console.log("ok phân trang số");
}
// ---------- 7. ưu tiên mềm theo màn hình (.s) + cờ bài viết / giao diện ----------
{
  const q = parseKey("pick.s240x320"); assert.deepEqual(q.screen, { w: 240, h: 320 });
  assert.equal(parseKey("new").screen, null);
  assert.equal(parseKey("pick.sabc"), null, "khóa sai bị từ chối");
  assert.ok(isSmart(360, 800) && isSmart(480, 800) && isSmart(800, 360));
  assert.ok(!isSmart(240, 320) && !isSmart(320, 240) && !isSmart(360, 640) && !isSmart(176, 220));
  assert.equal(screenOrder(null), ""); assert.equal(screenOrder({ w: 0, h: 5 }), "");
  const db2 = new DatabaseSync(":memory:"); db2.exec(rd("../schema.sql"));
  const ins = db2.prepare("INSERT INTO items(src,type,title,published,updated,res,plat,hold) VALUES(?,?,?,?,?,?,?,0)");
  ins.run("a", "game", "A-176", 5, 5, "176x220", 1); ins.run("b", "game", "B-240", 4, 4, "240x320", 1);
  ins.run("c", "game", "C-any", 3, 3, "", 1); ins.run("d", "game", "D-and", 2, 2, "480x800", 32); ins.run("e", "game", "E-240and", 1, 1, "240x400", 1);
  const ord = (key) => { const k = parseKey(key); const o = k.sort === "pick" ? pickOrder(1) : "i.published DESC, i.n DESC"; return db2.prepare(`SELECT title FROM items i WHERE ${k.where} ORDER BY ${screenOrder(k.screen)}${o}`).all().map((r) => r.title).join(","); };
  assert.equal(ord("new.s240x320"), "B-240,C-any,E-240and,A-176,D-and", "cỡ khớp đầu, không khai cỡ, cùng bề rộng, còn lại; không loại mục nào");
  assert.equal(ord("new.s360x800"), "D-and,C-any,A-176,B-240,E-240and", "smartphone: bản Android trước, rồi mục không khai cỡ");
  assert.equal(ord("new"), "A-176,B-240,C-any,D-and,E-240and", "không .s thì giữ thứ tự cũ");
  // bảng xếp hạng không bị đổi thứ tự bởi .s
  assert.equal(await orderFor({}, "dl", { w: 240, h: 320 }), "i.dl DESC, i.published DESC");
  assert.ok((await orderFor({}, "new", { w: 240, h: 320 })).startsWith("(CASE WHEN"));
  // chooser: bảng thật có cột
  const h = chooserHtml("T", ["240x320|90|JAD=http://a/x.jad|JAR=http://a/x.jar", "176x220|50|JAR=http://a/y.jar"], "");
  assert.ok(h.includes("<table") && h.includes("<th") && h.includes("Dung lượng") && h.includes("90 KB"));
  const rowsH = h.split("<tr>").slice(2); assert.equal(rowsH.length, 2);
  assert.equal((rowsH[1].match(/<td>/g) || []).length, (rowsH[0].match(/<td>/g) || []).length, "mọi dòng cùng số cột");
  console.log("ok ưu tiên màn hình");
}
// ---------- 8. tag màn hình tiêu biểu 240x320 / 320x240 ----------
{
  const R = await import("../rank.js");
  assert.equal(R.resBits("240x320"), 64); assert.equal(R.resBits("176x220,320x240"), 128);
  assert.equal(R.resBits("240x320,320x240,176x220"), 192); assert.equal(R.resBits(""), 0); assert.equal(R.resBits(null), 0);
  assert.equal(R.resBits("1240x3200"), 0, "không khớp nhầm đuôi/đầu số");
  assert.deepEqual(R.SCREEN_MAIN, ["240x320", "320x240"]);
  assert.deepEqual(R.jsonItem({ n: 1, title: "A", res: "240x320,320x240" }, 0, "http://x").res, ["240x320", "320x240"]);
  assert.deepEqual(R.jsonItem({ n: 1, title: "A", res: "" }, 0, "http://x").res, []);
  const k = parseKey("game.x240x320.dl"); assert.ok(k && k.sort === "dl");
  assert.equal(parseKey("game.xabc"), null);
  const db3 = new DatabaseSync(":memory:"); db3.exec(rd("../schema.sql"));
  const ins3 = db3.prepare("INSERT INTO items(src,type,title,published,updated,res,plat,hold) VALUES(?,?,?,?,?,?,?,0)");
  ins3.run("a", "game", "Both", 5, 5, "240x320,320x240", 1); ins3.run("b", "game", "Only240", 4, 4, "240x320", 1);
  ins3.run("c", "game", "Only320", 3, 3, "320x240,128x160", 1); ins3.run("d", "game", "Unknown", 2, 2, "", 1); ins3.run("e", "game", "Other", 1, 1, "176x220", 1);
  const sel = (key) => db3.prepare(`SELECT title FROM items i WHERE ${parseKey(key).where}`.replace("?", "?")).all(...parseKey(key).binds).map((r) => r.title).sort().join(",");
  assert.equal(sel("game.x240x320"), "Both,Only240", ".x chỉ lấy game có đúng cỡ, bỏ bài chưa rõ cỡ");
  assert.equal(sel("game.x320x240"), "Both,Only320");
  assert.equal(sel("game.r240x320"), "Both,Only240,Unknown", ".r cũ vẫn lấy bài chưa rõ cỡ");
  // menu.json: số game theo cỡ màn hình
  const env = { DB: { prepare: (sql) => { const st = db3.prepare(sql); return { first: async () => st.get(), all: async () => ({ results: st.all() }), bind: (...b) => ({ first: async () => st.get(...b), all: async () => ({ results: st.all(...b) }) }) }; } } };
  const mj = await R.menuJson(env);
  const sc = Object.fromEntries(mj.screens.map((x) => [x.res, x.count]));
  assert.equal(sc["240x320"], 2); assert.equal(sc["320x240"], 2); assert.equal(sc["176x220"], 1);
  assert.deepEqual(mj.screens.slice(0, 2).map((x) => x.res), ["240x320", "320x240"], "hai cỡ tiêu biểu luôn đứng đầu");
  // parseRes: tối đa 12 cỡ, luôn giữ hai cỡ tiêu biểu dù bài có 17 cỡ
  const many = ["128x128","128x160","132x176","176x208","176x220","208x208","240x240","240x320","240x400","240x432","320x240","320x320","320x480","352x416","360x640","480x800","480x854"].join(", ");
  const pr = R.parseRes(many).split(",");
  assert.equal(pr.length, 12); assert.ok(pr.includes("240x320") && pr.includes("320x240"));
  assert.equal(R.parseRes("240x320, 320x240").split(",").length, 2);
  assert.equal(R.parseRes("00x320, 240x320"), "240x320", "cỡ hỏng 00x320 bị bỏ");
  assert.equal(R.parseRes("Phần 2 240x320 VH, Phần 3 320x240"), "240x320,320x240", "nhãn nhiều phần vẫn lấy được cỡ");
  console.log("ok tag màn hình");
}
console.log("v3.test ok");
