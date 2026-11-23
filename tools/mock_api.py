#!/usr/bin/env python3
"""Máy chủ giả lập API Monika (xem docs/api-contract.md) để thử app mà không cần Cloudflare.

Chạy: python3 tools/mock_api.py [cổng]   (mặc định 8081). Dữ liệu sinh ngẫu nhiên có dấu.
"""
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
for it in ITEMS:
    it["path"] = "/v1/j/%d.jad" % it["id"]
    if it["id"] % 2 == 0:
        it["flags"] |= 16  # có icon: GET /v1/ic/<id>-32.png
    if it["id"] % 3 != 0:
        it["flags"] |= 32  # có ảnh minh họa: GET /v1/sc/<id>-<w>x<h>.jpg


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
        if p == "/v1/home.bin":
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

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
    HTTPServer(("127.0.0.1", port), H).serve_forever()
