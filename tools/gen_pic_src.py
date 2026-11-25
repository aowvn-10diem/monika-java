#!/usr/bin/env python3
"""Sinh cloudflare/pic-src.js: bảng tên ảnh R2 (pic/<tên>.jpg) -> URL ảnh gốc, để Worker chuyển hướng khi R2 khóa đọc hoặc thiếu object.
Dùng: python3 tools/gen_pic_src.py <thư mục data/dedomil/games của monika-sideproject> [danh sách tên trong R2, mỗi dòng một tên] > cloudflare/pic-src.js
Không đưa danh sách tên thì lấy mọi ảnh thu nhỏ có trong dữ liệu."""
import glob, json, os, sys

BASE = "http://dedomil.net/hry_scr/"
games = sys.argv[1]
keep = set(open(sys.argv[2], encoding="utf-8").read().split()) if len(sys.argv) > 2 else None
m = {}
for p in sorted(glob.glob(os.path.join(games, "*.json"))):
    try:
        g = json.load(open(p, encoding="utf-8"))
    except Exception:
        continue
    for t in g.get("thumbs") or []:
        n, u = os.path.basename(t.get("file", "")), t.get("source_url", "")
        if n.endswith(".jpg") and u.startswith("http") and (keep is None or n in keep):
            m[n] = u
lines = [n + "\t" + (u[len(BASE):] if u.startswith(BASE) else u) for n, u in sorted(m.items())]
sys.stdout.write("// Sinh bởi tools/gen_pic_src.py, đừng sửa tay. Tên ảnh trong R2 -> URL ảnh gốc (nguồn dedomil.net), dùng khi R2 khóa đọc hoặc thiếu object.\n")
sys.stdout.write("export const BASE = %s;\nexport const DATA = %s;\n" % (json.dumps(BASE), json.dumps("\n".join(lines))))
sys.stderr.write("%d ảnh\n" % len(m))
