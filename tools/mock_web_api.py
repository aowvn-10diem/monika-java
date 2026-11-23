#!/usr/bin/env python3
"""Dung trang gia tu blogspot/monika-template.xml (mo phong b:if / b:loop / data: / expr:) de thu ma khong can Blogger.
Doc JSON {kind, posts, labels, extra} tu stdin, in HTML ra stdout. Duoc goi boi tools/mock_web_api.js (Playwright + API gia)."""
import json, re, sys, html
from pathlib import Path
from lxml import etree as E

ROOT = Path(__file__).resolve().parent.parent
TPL = ROOT / "blogspot" / "monika-template.xml"
NS_B = "http://www.google.com/2005/gml/b"
NS_D = "http://www.google.com/2005/gml/data"
NS_E = "http://www.google.com/2005/gml/expr"
VOID = {"meta", "input", "img", "br", "link", "hr"}


def lookup(ctx, name):
    cur = ctx
    for part in name.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def contains(a, b):
    if a is None:
        return False
    return b in a if isinstance(a, (str, list)) else False


def cond(expr, ctx):
    e = re.sub(r"data:([A-Za-z0-9_.]+)", lambda m: "L(%r)" % m.group(1), expr)
    e = re.sub(r"(L\('[^']*'\))\s+contains\s+(\"[^\"]*\")", r"C(\1,\2)", e)
    return bool(eval(e, {"L": lambda n: lookup(ctx, n), "C": contains}))


def esc(s):
    return html.escape("" if s is None else str(s), quote=False)


def qa(s):
    return html.escape("" if s is None else str(s), quote=True)


def attrs(node, ctx):
    out = []
    for k, v in node.attrib.items():
        q = E.QName(k)
        if q.namespace == NS_E:
            v2 = re.fullmatch(r"data:([A-Za-z0-9_.]+)", v)
            val = lookup(ctx, v2.group(1)) if v2 else ""
            out.append(' %s="%s"' % (q.localname, qa(val)))
        elif q.namespace is None:
            out.append(' %s="%s"' % (k, qa(v)))
        elif q.localname == "href":
            out.append(' href="%s"' % qa(v))
    return "".join(out)


def children(node, ctx):
    s = esc(node.text) if node.text and node.tag not in () else ""
    for c in node:
        s += render(c, ctx)
        if c.tail:
            s += esc(c.tail)
    return s


def render_if(node, ctx):
    segs = [(node.get("cond"), [])]
    pre = esc(node.text) if node.text else ""
    cur = segs[0][1]
    for c in node:
        if isinstance(c.tag, str) and E.QName(c).namespace == NS_B and E.QName(c).localname in ("else", "elseif"):
            segs.append((c.get("cond") if E.QName(c).localname == "elseif" else None, []))
            cur = segs[-1][1]
            if c.tail:
                cur.append(esc(c.tail))
            continue
        cur.append(render(c, ctx))
        if c.tail:
            cur.append(esc(c.tail))
    for cnd, parts in segs:
        if cnd is None or cond(cnd, ctx):
            return "".join(parts)
    return ""


def render(node, ctx):
    if not isinstance(node.tag, str):
        return ""
    q = E.QName(node)
    if q.namespace == NS_D:
        v = lookup(ctx, q.localname)
        if q.localname.endswith("body"):
            return v or ""
        return esc(v)
    if q.namespace == NS_B:
        n = q.localname
        if n == "if":
            return render_if(node, ctx)
        if n == "loop":
            vals = cond_val(node.get("values"), ctx) or []
            var = node.get("var")
            out = ""
            for v in vals:
                c2 = dict(ctx)
                c2[var] = v
                out += children(node, c2)
            return out
        if n == "section":
            out = ""
            for w in node:
                if isinstance(w.tag, str) and E.QName(w).localname == "widget":
                    out += render(w, ctx)
            return out
        if n == "widget":
            inc = [c for c in node if isinstance(c.tag, str) and E.QName(c).localname == "includable"]
            if inc:
                return children(inc[0], ctx)
            if node.get("type") == "Label":
                lis = "".join('<li><a href="/search/label/%s">%s</a><span>%d</span></li>' % (qa(n_), esc(n_), c) for n_, c in ctx["_labels"])
                return '<div class="widget"><h2>%s</h2><ul>%s</ul></div>' % (esc(node.get("title")), lis)
            return ""
        return ""
    tag = q.localname
    if tag in ("script", "style"):
        return "<%s%s>%s</%s>" % (tag, attrs(node, ctx), node.text or "", tag)
    if tag in VOID:
        return "<%s%s>" % (tag, attrs(node, ctx))
    return "<%s%s>%s</%s>" % (tag, attrs(node, ctx), children(node, ctx), tag)


def cond_val(expr, ctx):
    m = re.fullmatch(r"data:([A-Za-z0-9_.]+)", expr or "")
    return lookup(ctx, m.group(1)) if m else None


def build(kind, posts, labels, extra=None):
    root = E.parse(str(TPL)).getroot()
    head = root.find("{http://www.w3.org/1999/xhtml}head")
    body = root.find("{http://www.w3.org/1999/xhtml}body")
    skin = next(c for c in head if isinstance(c.tag, str) and E.QName(c).localname == "skin")
    css = skin.text
    ptype = {"home": "index", "label": "index", "item": "item", "static": "static_page", "error": "error_page"}[kind]
    ctx = {
        "blog": {"pageType": ptype, "pageTitle": "Monika", "homepageUrl": "/", "languageDirection": "ltr",
                 "searchLabel": "Game" if kind == "label" else None, "searchQuery": None},
        "posts": posts,
        "newerPageUrl": None,
        "olderPageUrl": "/?updated-max=x" if kind in ("home", "label") else None,
        "_labels": labels,
    }
    ctx.update(extra or {})
    out = "<!doctype html><html lang='vi' class='v2 nojs'><head><meta charset='utf-8'>" \
          "<meta name='viewport' content='width=device-width, initial-scale=1'><title>Monika</title><style>%s</style></head><body>%s</body></html>" % (css, children(body, ctx))
    return out


if __name__ == "__main__":
    spec = json.load(sys.stdin)
    sys.stdout.write(build(spec["kind"], spec["posts"], spec.get("labels", []), spec.get("extra")))
