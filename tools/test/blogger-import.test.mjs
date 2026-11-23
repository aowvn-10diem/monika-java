import test from "node:test";
import assert from "node:assert/strict";
import { buildFeeds, slugify, postId, metaBlock } from "../blogger-import.mjs";

const now = new Date("2026-11-23T04:00:00Z");
const P = [
  { title: "[Test] Rắn săn mồi & <Đại chiến>", html: "<p>Xin chào “tiếng Việt”</p>", labels: ["Game", "Hành động"],
    meta: { type: "game", ver: "1.0", jar: "https://x.test/a.jar?x=1&y=2" }, published: "2026-11-23T03:00:00Z" },
  { title: "Tiện ích", html: "<p>b</p>", draft: false, published: "2026-11-22T03:00:00Z" },
];

test("slug bỏ dấu, tối đa 40 ký tự", () => {
  assert.equal(slugify("Rắn săn mồi Đại chiến"), "ran-san-moi-dai-chien");
  assert.ok(slugify("a".repeat(30) + " " + "b".repeat(30)).length <= 40);
  assert.equal(slugify("!!!"), "bai-viet");
});

test("id ổn định", () => {
  assert.equal(postId("a", "x"), postId("a", "x"));
  assert.notEqual(postId("a", "x"), postId("b", "x"));
  assert.match(postId("a", "x"), /^\d{17}$/);
});

test("meta thoát đúng", () => {
  const m = metaBlock({ jar: "https://x/a?b=1&c=2", empty: "" });
  assert.equal(m, '<pre class="mk-meta">\njar: https://x/a?b=1&amp;c=2\n</pre>\n');
});

test("mặc định NHÁP, draft:false thành LIVE, có nhãn và filename", () => {
  const [x] = buildFeeds(P, { now, blogId: "123" });
  assert.equal((x.match(/<blogger:status>DRAFT/g) || []).length, 1);
  assert.equal((x.match(/<blogger:status>LIVE/g) || []).length, 1);
  assert.match(x, /term='Hành động'/);
  assert.match(x, /<blogger:filename>\/2026\/11\/test-ran-san-moi-dai-chien\.html</);
  assert.match(x, /xmlns:blogger='http:\/\/schemas\.google\.com\/blogger\/2018'/);
});

test("cắt lô theo số bài và chặn trùng đường dẫn", () => {
  const many = Array.from({ length: 5 }, (_, i) => ({ title: "Bài " + i, published: "2026-11-23T03:00:00Z" }));
  assert.equal(buildFeeds(many, { now, maxPosts: 2 }).length, 3);
  assert.throws(() => buildFeeds([P[1], P[1]], { now }), /trùng/);
});
