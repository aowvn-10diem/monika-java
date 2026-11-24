#!/usr/bin/env python3
"""Máy chủ giả lập API Monika (xem docs/api-contract.md) để thử app mà không cần Cloudflare.

Chạy: python3 tools/mock_api.py [cổng]   (mặc định 8081). Dữ liệu sinh ngẫu nhiên có dấu.
Có cả v1 (/v1/home.bin, /v1/l/) và v2 (/v1/home2.bin, /v1/l2/ với dl, tags, plat, danh mục chuẩn 10-39).
MOCK_V1_ONLY=1 python3 tools/mock_api.py: giả lập Worker cũ (endpoint v2 trả 404) để thử chế độ tương thích.
"""
import os
import struct
import sys
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

PAGE = 15
CATS = [(0, "Tiện ích"), (1, "Trò chơi"), (2, "Mạng & liên lạc"), (3, "Học tập & đọc")]
GAME_NAMES = ["Cờ caro", "Rắn săn mồi", "Xếp hình", "Cờ tướng", "Bắn gà", "Đua xe", "Nổ bóng", "Pikachu",
              "Bomberman", "Ma trận số", "Tìm đường", "Bắn cá", "Đào vàng", "Chặn bóng", "Nhảy dù",
              "Bóng đá", "Đại chiến tank", "Rubik"]
APP_NAMES = ["Lịch âm", "Từ điển Anh-Việt", "Thời tiết", "Đọc báo", "Máy tính", "Ghi chú", "Opera Mini",
              "Facebook", "Zalo", "Nghe nhạc", "Đọc truyện", "Xem phim", "Chat Yahoo"]


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


ITEMS = []
for i in range(36):
    name = GAME_NAMES[i % len(GAME_NAMES)] + (" %d" % (i // len(GAME_NAMES) + 1) if i >= len(GAME_NAMES) else "")
    ITEMS.append(dict(id=200 + i, cat=1, flags=(1 if i % 5 == 0 else 0) | (8 if i % 3 == 0 else 0), kb=40 + i * 3,
                      price=0, ver="1.%d" % (i % 4), name=name,
                      desc="Trò chơi %s cho Nokia S60, chơi offline, hỗ trợ màn hình 240x320." % name))
for i in range(len(APP_NAMES)):
    ITEMS.append(dict(id=100 + i, cat=[0, 3, 2][i % 3], flags=(4 if i % 3 == 2 else 0) | (2 if i % 4 == 0 else 0),
                      price=0 if i != 5 else 20000, ver="2.%d" % (i % 5), name=APP_NAMES[i], kb=20 + i * 7,
                      desc="Ứng dụng %s chạy trên Java S60." % APP_NAMES[i]))
EXTRA = [(300, 61, "Hình nền 240x320 số %d", 128), (310, 62, "Theme Nokia S60v3 số %d", 128),
         (320, 66, "Nhạc chuông MP3 số %d", 128), (330, 56, "Truyện kiếm hiệp chương %d", 64),
         (340, 54, "Mẹo cài game Java số %d", 64)]
for base, cat, nm, fl in EXTRA:
    for j in range(3):
        ITEMS.append(dict(id=base + j, cat=cat, cat2=cat, flags=fl | (1 if j == 0 else 0), kb=0 if fl == 64 else 90 + j,
                          price=0, ver="", name=nm % (j + 1), desc="Nội dung mẫu loại %d." % cat))
for it in ITEMS:
    it["path"] = "/v1/j/%d.jad" % it["id"]
    if it["id"] % 2 == 0:
        it["flags"] |= 16  # có icon: GET /v1/ic/<id>-32.png
    if it["id"] % 3 != 0:
        it["flags"] |= 32  # có ảnh minh họa: GET /v1/sc/<id>-<w>x<h>.jpg


# ---- v2: danh mục chuẩn (rút gọn từ catalog/taxonomy.json), dl, tags, plat, độ phân giải ----
CATS3 = [(60, 4, "Hình nền"), (61, 4, "Hình nền 240x320"), (62, 4, "Theme"), (66, 5, "Nhạc chuông"), (54, 3, "Thủ thuật & mẹo"), (56, 3, "Truyện kiếm hiệp")]
CATS2 = [(10, 1, "Hành động"), (11, 1, "Phiêu lưu"), (12, 1, "Nhập vai"), (14, 1, "Đua xe"), (15, 1, "Thể thao"),
         (16, 1, "Bắn súng"), (18, 1, "Giải đố & trí tuệ"), (19, 1, "Arcade & chạy nhảy"), (21, 1, "Cờ & bài"),
         (23, 1, "Khác"), (30, 2, "Mạng & trình duyệt"), (31, 2, "Chat & mạng xã hội"), (32, 2, "Đọc sách & truyện"),
         (33, 2, "Nhạc & video"), (34, 2, "Văn phòng & học tập"), (35, 2, "Công cụ hệ thống"), (39, 2, "Khác")]
GAME_CATS = [12, 12, 10, 14, 16, 18, 21, 12, 19, 15, 11, 23]
APP_CATS = [30, 31, 32, 33, 34, 35, 39]
DLS = [0, 3, 5, 7, 42, 120, 999, 1000, 1234, 5600, 12345, 987]
TAGS = [1, 2, 4, 2 | 8, 4 | 16, 1 | 128, 2 | 4 | 8, 32, 64 | 16, 8, 2 | 32 | 64, 4 | 128]
RES = [["240x320"], ["240x320", "320x240"], [], ["176x208", "128x160"]]
for n, it in enumerate(ITEMS):
    k = it["id"]
    if "cat2" not in it:
        it["cat2"] = GAME_CATS[n % len(GAME_CATS)] if it["cat"] == 1 else APP_CATS[n % len(APP_CATS)]
    it["dl"] = DLS[(k * 7) % len(DLS)] if not it["flags"] & 192 else 0
    if it["dl"] >= 5:
        it["dl"] += k % 7 * 37
    it["d1"] = (k * 37) % 23
    it["d7"] = (k * 53) % 91
    it["tags"] = TAGS[(k * 5) % len(TAGS)]
    it["plat"] = [1, 1 | 4, 4, 1 | 2 | 4, 8 | 16][k % 5]
    it["res"] = RES[k % 4]
    it["seq"] = n


def item_bytes2(it):
    return (struct.pack(">iBBHiiHB", it["id"], it["cat2"], it["flags"], it["kb"], it["price"], it["dl"],
                        it["tags"], it["plat"])
            + utf(it["ver"]) + utf(it["name"]) + utf(it["desc"]) + utf(it["path"]))


def select2(key):
    """Khóa v2: base + mod (.new .upd .hot .dl .top1 .top7 .p<bit> .r<WxH> .t<bit>), xem docs/api-contract.md."""
    parts = key.split(".")
    base, mods = parts[0], parts[1:]
    screen = None
    items = [i for i in ITEMS if not i["flags"] & 192]
    sort = "new"
    if base == "pick":
        items = [i for i in items if i["cat"] == 1]
        sort = "pick"
    elif base in ("new", "upd", "hot", "dl", "top1", "top7"):
        sort = base
    elif base == "app":
        items = [i for i in items if i["cat"] != 1]
    elif base == "game":
        items = [i for i in items if i["cat"] == 1]
    elif base[:1] == "c" and base[1:].isdigit():
        items = [i for i in ITEMS if i["cat2"] == int(base[1:])]
    elif base[:1] == "i" and base[1:].isdigit():
        return [i for i in ITEMS if i["id"] == int(base[1:])]
    else:
        return None
    for m in mods:
        if m in ("new", "upd", "hot", "dl", "top1", "top7"):
            sort = m
        elif m[:1] == "p" and m[1:].isdigit():
            bit = int(m[1:])
            items = [i for i in items if i["plat"] & bit]
        elif m[:1] == "t" and m[1:].isdigit():
            bit = int(m[1:])
            items = [i for i in items if i["tags"] & bit]
        elif m[:1] == "s" and "x" in m[1:]:
            screen = m[1:]
        elif m[:1] == "r" and "x" in m[1:]:
            items = [i for i in items if not i["res"] or m[1:] in i["res"]]
        else:
            return None
    if sort == "hot":
        items.sort(key=lambda i: (-(1 if i["flags"] & 8 else 0), -i["d7"]))
    elif sort == "top1":
        items.sort(key=lambda i: -i["d1"])
    elif sort == "top7":
        items.sort(key=lambda i: -i["d7"])
    elif sort == "dl":
        items.sort(key=lambda i: -i["dl"])
    elif sort == "pick":
        items.sort(key=lambda i: (i["id"] * 1103515245 + 7) % 2147483647)
    else:
        items.sort(key=lambda i: -i["seq"])
    if screen and sort not in ("hot", "top1", "top7", "dl"):
        items.sort(key=lambda i: 0 if screen in i["res"] else (1 if not i["res"] else 2))  # sort ổn định: giữ thứ tự cũ trong từng nhóm
    return items


def page_bytes2(items, page):
    sl = items[page * PAGE:(page + 1) * PAGE]
    return (struct.pack(">IBHHB", 0x4D4B4C32, 2, len(items), page, len(sl)) + b"".join(item_bytes2(i) for i in sl))


def home2_bytes(v3=False):
    new = len([i for i in ITEMS if i["flags"] & 3 and not i["flags"] & 192])
    cats = [(cid, kind, name, len([i for i in ITEMS if i["cat2"] == cid])) for cid, kind, name in CATS2 + (CATS3 if v3 else [])]
    cats = [c for c in cats if c[3] > 0]
    if v3:
        cats.sort(key=lambda c: [1, 2, 4, 5, 3].index(c[1]))
    out = struct.pack(">IBH", 0x4D4B4833 if v3 else 0x4D4B4832, 3 if v3 else 2, new) + utf("0.4.0") + utf("") + struct.pack(">B", len(cats))
    for cid, kind, name, cnt in cats:
        out += struct.pack(">BB", cid, kind) + utf(name) + struct.pack(">H", cnt)
    banners = [(1, "Monika - kho game Java", "l:hot"), (2, "Game mới mỗi ngày", "g:200")]
    out += struct.pack(">B", len(banners))
    for bid, alt, link in banners:
        out += struct.pack(">B", bid) + utf(alt) + utf(link)
    return out


def item_bytes(it):
    return (struct.pack(">iBBHi", it["id"], it["cat"], it["flags"], it["kb"], it["price"])
            + utf(it["ver"]) + utf(it["name"]) + utf(it["desc"]) + utf(it["path"]))


def select(key):
    if key == "app":
        return [i for i in ITEMS if i["cat"] != 1]
    if key == "game":
        return [i for i in ITEMS if i["cat"] == 1]
    if key == "new":
        return [i for i in ITEMS if i["flags"] & 3]
    if key == "hot":
        return [i for i in ITEMS if i["flags"] & 8]
    if key == "dl":
        return list(ITEMS)
    if key.startswith("c") and key[1:].isdigit():
        return [i for i in ITEMS if i["cat"] == int(key[1:])]
    if key.startswith("i") and key[1:].isdigit():
        return [i for i in ITEMS if i["id"] == int(key[1:])]
    return None


def strip(s):
    import unicodedata
    s = s.replace("đ", "d").replace("Đ", "D")
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()


def page_bytes(items, page):
    sl = items[page * PAGE:(page + 1) * PAGE]
    return (struct.pack(">IBHHB", 0x4D4B4C31, 1, len(items), page, len(sl)) + b"".join(item_bytes(i) for i in sl))


def home_bytes():
    new = len([i for i in ITEMS if i["flags"] & 3])
    out = struct.pack(">IBH", 0x4D4B4831, 1, new) + utf("0.2.0") + utf("") + struct.pack(">B", len(CATS))
    for cid, name in CATS:
        out += struct.pack(">B", cid) + utf(name) + struct.pack(">H", len([i for i in ITEMS if i["cat"] == cid]))
    banners = [(1, "Monika - kho game Java", "l:hot"), (2, "Game mới mỗi ngày", "g:200")]
    out += struct.pack(">B", len(banners))
    for bid, alt, link in banners:
        out += struct.pack(">B", bid) + utf(alt) + utf(link)
    return out


def png(w, h, c1, c2):
    rows = b""
    for y in range(h):
        row = b"\x00"
        for x in range(w):
            t = x / (w - 1)
            r = int(c1[0] * (1 - t) + c2[0] * t)
            g = int(c1[1] * (1 - t) + c2[1] * t)
            b = int(c1[2] * (1 - t) + c2[2] * t)
            # vài chấm trắng để thấy được banner thật
            if (x // 8 + y // 8) % 11 == 0:
                r, g, b = 255, 255, 255
            row += bytes((r, g, b))
        rows += row

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def shot(iid, w, h):
    """Ảnh minh họa JPEG w x h. Có Pillow thì vẽ hình + tên game, không thì trả PNG chuyển sắc (app nhận cả hai)."""
    try:
        import io
        import random
        from PIL import Image, ImageDraw
    except ImportError:
        return png(w, h, (30 + iid * 7 % 120, 40 + iid * 13 % 120, 80 + iid * 29 % 140), (255, 160, 60)), "image/png"
    rnd = random.Random(iid)
    # giống máy chủ thật: thu vừa khung w x h giữ tỉ lệ gốc (ngang 320x240, dọc 240x320, thỉnh thoảng 176x208)
    ow, oh = (176, 208) if iid % 5 == 1 else ((320, 240) if iid % 2 == 0 else (240, 320))
    k = min(w / ow, h / oh)
    w, h = max(1, int(ow * k)), max(1, int(oh * k))
    im = Image.new("RGB", (w, h), tuple(rnd.randint(20, 110) for _ in range(3)))
    d = ImageDraw.Draw(im)
    for _ in range(10):
        x, y, r = rnd.randint(0, w), rnd.randint(0, h), rnd.randint(w // 12, w // 3)
        d.ellipse([x - r, y - r, x + r, y + r], fill=tuple(rnd.randint(60, 255) for _ in range(3)))
    name = next((i["name"] for i in ITEMS if i["id"] == iid), "")
    d.text((4, 4), "".join(c for c in strip(name) if c.isascii()), fill=(255, 255, 255))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=70)
    return buf.getvalue(), "image/jpeg"


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        p = urllib.parse.urlparse(self.path).path
        body, ctype = None, "application/octet-stream"
        v2ok = not os.environ.get("MOCK_V1_ONLY")
        if p == "/v1/home3.bin" and v2ok and not os.environ.get("MOCK_V2_ONLY"):
            body = home2_bytes(True)
        elif p == "/v1/home2.bin" and v2ok:
            body = home2_bytes()
        elif p.startswith("/v1/l2/") and p.endswith(".bin") and v2ok:
            key, pg = p[7:-4].split("/")
            items = select2(key)
            if items is not None:
                body = page_bytes2(items, int(pg))
        elif p == "/v1/fb/c.bin":
            body = utf("7 + 5 = ?") + utf("tok.0123456789ab.sig")
        elif p == "/v1/home.bin":
            body = home_bytes()
        elif p.startswith("/v1/l/") and p.endswith(".bin"):
            key, pg = p[6:-4].split("/")
            items = select(key)
            if items is not None:
                body = page_bytes(items, int(pg))
        elif p.startswith("/v1/s/") and p.endswith(".bin"):
            key, pg = p[6:-4].split("/")
            q = strip(urllib.parse.unquote(key))
            body = page_bytes([i for i in ITEMS if q in strip(i["name"]) or q in strip(i["desc"])], int(pg))
        elif p.startswith("/v1/b/") and p.endswith(".png"):
            bid, size = p[6:-4].split("-")
            w = int(size)
            body = png(w, 60, (60, 20, 100) if bid == "1" else (200, 70, 30), (255, 130, 40))
            ctype = "image/png"
        elif p.startswith("/v1/ic/") and p.endswith("-32.png"):
            iid = int(p[7:-7])
            body = png(32, 32, (40 + iid * 7 % 180, 60 + iid * 13 % 160, 90 + iid * 29 % 140), (255, 200, 60))
            ctype = "image/png"
        elif p.startswith("/v1/sc/") and p.endswith(".jpg"):
            iid, size = p[7:-4].split("-")
            w, h = size.split("x")
            body, ctype = shot(int(iid), int(w), int(h))
        elif p.startswith("/v1/j/") and p.endswith(".jad"):
            iid = p[6:-4]
            host = self.headers.get("Host", "localhost")
            body = ("MIDlet-Name: Game %s\r\nMIDlet-Vendor: Mock\r\nMIDlet-Version: 1.0\r\n"
                    "MIDlet-1: Game, , Main\r\nMIDlet-Jar-URL: http://%s/v1/j/%s.jar\r\nMIDlet-Jar-Size: 10\r\n"
                    % (iid, host, iid)).encode()
            ctype = "text/vnd.sun.j2me.app-descriptor"
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        sys.stderr.write("200 %s %d\n" % (self.path, len(body)))

    def do_POST(self):
        # góp ý / báo lỗi: đáp án đúng là 12 và nội dung >= 5 ký tự (giống Worker, bản rút gọn)
        n = int(self.headers.get("Content-Length", "0"))
        f = urllib.parse.parse_qs(self.rfile.read(n).decode("utf-8"))
        ok = self.path == "/v1/fb" and f.get("a", [""])[0] == "12" and len(f.get("m", [""])[0]) >= 5
        sys.stderr.write("POST %s %s\n" % (self.path, {k: v[0] for k, v in f.items()}))
        body = (b"ok" if ok else b"err:bad_captcha")
        self.send_response(200 if ok else 400)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
    HTTPServer(("127.0.0.1", port), H).serve_forever()
