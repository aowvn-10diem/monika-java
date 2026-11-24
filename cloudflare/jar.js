// Phân tích file JAR J2ME: zip, MANIFEST, icon PNG, băm SHA-256. Thuần JS, chạy được trong Worker và Node.

const td = new TextDecoder("utf-8");
const MAX_ENTRY = 4 * 1024 * 1024; // chặn zip bomb: chỉ giải nén MANIFEST/icon dưới 4MB

// ---------- zip ----------
async function inflate(data, fmt) {
  const s = new Blob([data]).stream().pipeThrough(new DecompressionStream(fmt));
  return new Uint8Array(await new Response(s).arrayBuffer());
}
async function deflate(data) {
  const s = new Blob([data]).stream().pipeThrough(new CompressionStream("deflate"));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

export function listZip(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let e = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65600); i--) if (dv.getUint32(i, true) === 0x06054b50) { e = i; break; }
  if (e < 0) throw new Error("Không phải file zip/JAR");
  const n = dv.getUint16(e + 10, true);
  let p = dv.getUint32(e + 16, true);
  const out = [];
  for (let k = 0; k < n; k++) {
    if (p + 46 > buf.length || dv.getUint32(p, true) !== 0x02014b50) break;
    const nl = dv.getUint16(p + 28, true), el = dv.getUint16(p + 30, true), cl = dv.getUint16(p + 32, true);
    out.push({
      name: td.decode(buf.subarray(p + 46, p + 46 + nl)),
      method: dv.getUint16(p + 10, true),
      csz: dv.getUint32(p + 20, true),
      usz: dv.getUint32(p + 24, true),
      lho: dv.getUint32(p + 42, true),
    });
    p += 46 + nl + el + cl;
  }
  if (!out.length) throw new Error("Zip rỗng");
  return out;
}

export async function readEntry(buf, e) {
  if (e.usz > MAX_ENTRY) throw new Error("Mục quá lớn: " + e.name);
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const lnl = dv.getUint16(e.lho + 26, true), lel = dv.getUint16(e.lho + 28, true);
  const start = e.lho + 30 + lnl + lel;
  const raw = buf.subarray(start, start + e.csz);
  if (e.method === 0) return raw;
  if (e.method === 8) return inflate(raw, "deflate-raw");
  throw new Error("Nén không hỗ trợ: " + e.method);
}

// ---------- manifest ----------
export function parseManifest(text) {
  const lines = [];
  for (const raw of text.replace(/^﻿/, "").replace(/\r\n?/g, "\n").split("\n")) {
    if (raw === "") { if (lines.length && lines.some((l) => l.includes(":"))) break; continue; } // chỉ lấy phần main (trước dòng trống đầu tiên)
    if (raw.startsWith(" ") && lines.length) lines[lines.length - 1] += raw.slice(1);
    else lines.push(raw);
  }
  const mf = {};
  for (const l of lines) {
    const i = l.indexOf(":");
    if (i > 0) mf[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim();
  }
  return mf;
}

export const DROP = /^(manifest-version|created-by|ant-version|main-class|class-path|sealed|name|build-jdk|built-by|midlet-jar-url|midlet-jar-size|midlet-install-notify|midlet-delete-confirm)$/i;
export function manifestToJad(mf, jarUrl, size) {
  const lines = [];
  for (const raw of mf.replace(/\r\n?/g, "\n").split("\n")) {
    if (raw === "" && lines.length) break;
    if (raw.startsWith(" ") && lines.length) lines[lines.length - 1] += raw.slice(1);
    else lines.push(raw);
  }
  const out = [];
  for (const l of lines) {
    const i = l.indexOf(":");
    if (i <= 0) continue;
    if (DROP.test(l.slice(0, i).trim())) continue;
    out.push(l.slice(0, i).trim() + ": " + l.slice(i + 1).trim());
  }
  out.push("MIDlet-Jar-URL: " + jarUrl, "MIDlet-Jar-Size: " + size);
  return out.join("\r\n") + "\r\n";
}

// ---------- tên chuẩn hóa ----------
export const slugify = (s, max = 40) =>
  String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase()
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, max).replace(/-+$/, "");
export const cleanVer = (v) => String(v || "").trim().replace(/[^0-9A-Za-z._]+/g, "").slice(0, 16) || "0";

export async function sha256hex(buf) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", buf));
  let s = "";
  for (const b of d) s += b.toString(16).padStart(2, "0");
  return s;
}

const isPng = (u) => u && u.length > 8 && u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47;

// ---------- phân tích JAR ----------
export async function analyzeJar(buf, fallbackName = "") {
  const entries = listZip(buf);
  const byName = new Map(entries.map((e) => [e.name.replace(/^\/+/, "").toLowerCase(), e]));
  const mfE = byName.get("meta-inf/manifest.mf");
  if (!mfE) throw new Error("Không có META-INF/MANIFEST.MF, không phải JAR J2ME");
  const mfText = td.decode(await readEntry(buf, mfE));
  const mf = parseManifest(mfText);
  const midlets = [];
  for (let i = 1; i <= 30; i++) {
    const v = mf["midlet-" + i];
    if (!v) break;
    const p = v.split(",");
    midlets.push({ name: (p[0] || "").trim(), icon: (p[1] || "").trim(), cls: (p[2] || "").trim() });
  }
  if (!midlets.length && !mf["midlet-name"]) throw new Error("MANIFEST không có MIDlet-1: không phải JAR J2ME");
  const base = String(fallbackName || "").replace(/\.(jar|zip)$/i, "").replace(/[_]+/g, " ").trim();
  const name = (mf["midlet-name"] || (midlets[0] && midlets[0].name) || base || "Game").trim();
  const cands = [mf["midlet-icon"], ...midlets.map((m) => m.icon), "icon.png", "Icon.png", "i.png", "logo.png", "/icon.png"].filter(Boolean);
  let icon = null, iconPath = "";
  for (const c of cands) {
    const e = byName.get(c.replace(/^\/+/, "").toLowerCase());
    if (!e) continue;
    try {
      const b = await readEntry(buf, e);
      if (isPng(b)) { icon = b; iconPath = c; break; }
    } catch (_) {}
  }
  return {
    name,
    vendor: (mf["midlet-vendor"] || "").trim(),
    ver: (mf["midlet-version"] || "").trim(),
    desc: (mf["midlet-description"] || "").trim(),
    profile: mf["microedition-profile"] || "",
    config: mf["microedition-configuration"] || "",
    midlets, manifest: mfText, icon, iconPath,
    files: entries.length,
  };
}

// ---------- PNG: giải mã, thu phóng, mã hóa ----------
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const MAX_DIM = 256;

export async function decodePng(u8) {
  for (let i = 0; i < 8; i++) if (u8[i] !== PNG_SIG[i]) throw new Error("png: sai chữ ký");
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  let p = 8, w = 0, h = 0, bd = 0, ct = 0, il = 0, plte = null, trns = null;
  const idat = [];
  let idatLen = 0;
  while (p + 8 <= u8.length) {
    const len = dv.getUint32(p), type = String.fromCharCode(u8[p + 4], u8[p + 5], u8[p + 6], u8[p + 7]);
    const d = u8.subarray(p + 8, p + 8 + len);
    if (type === "IHDR") { w = dv.getUint32(p + 8); h = dv.getUint32(p + 12); bd = u8[p + 16]; ct = u8[p + 17]; il = u8[p + 20]; }
    else if (type === "PLTE") plte = d;
    else if (type === "tRNS") trns = d;
    else if (type === "IDAT") { idat.push(d); idatLen += d.length; }
    else if (type === "IEND") break;
    p += 12 + len;
  }
  if (!w || !h) throw new Error("png: thiếu IHDR");
  if (w > MAX_DIM || h > MAX_DIM) throw new Error(`png: ảnh quá lớn ${w}x${h}`);
  if (il) throw new Error("png: interlaced không hỗ trợ");
  const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct];
  if (!ch || ![1, 2, 4, 8, 16].includes(bd)) throw new Error("png: định dạng lạ");
  const all = new Uint8Array(idatLen);
  let o = 0;
  for (const d of idat) { all.set(d, o); o += d.length; }
  const raw = await inflate(all, "deflate");
  const bpp = Math.max(1, (ch * bd) >> 3), stride = Math.ceil((w * ch * bd) / 8);
  if (raw.length < (stride + 1) * h) throw new Error("png: thiếu dữ liệu");
  // bỏ filter tại chỗ
  const px = new Uint8Array(stride * h);
  for (let y = 0; y < h; y++) {
    const ft = raw[y * (stride + 1)], src = y * (stride + 1) + 1, dst = y * stride, up = dst - stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[dst + x - bpp] : 0, b = y ? px[up + x] : 0, c = x >= bpp && y ? px[up + x - bpp] : 0;
      let v = raw[src + x];
      if (ft === 1) v += a;
      else if (ft === 2) v += b;
      else if (ft === 3) v += (a + b) >> 1;
      else if (ft === 4) { const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      px[dst + x] = v;
    }
  }
  // sang RGBA 8 bit
  const out = new Uint8Array(w * h * 4);
  const smp = (rs, i) => {
    if (bd === 8) return px[rs + i];
    if (bd === 16) return (px[rs + 2 * i] << 8) | px[rs + 2 * i + 1];
    const per = 8 / bd;
    return (px[rs + ((i / per) | 0)] >> (8 - bd * ((i % per) + 1))) & ((1 << bd) - 1);
  };
  const to8 = (v) => (bd === 16 ? v >> 8 : bd === 8 ? v : Math.round((v * 255) / ((1 << bd) - 1)));
  let key = null;
  if (trns && ct === 0 && trns.length >= 2) key = [(trns[0] << 8) | trns[1]];
  if (trns && ct === 2 && trns.length >= 6) key = [(trns[0] << 8) | trns[1], (trns[2] << 8) | trns[3], (trns[4] << 8) | trns[5]];
  for (let y = 0; y < h; y++) {
    const rs = y * stride;
    for (let x = 0; x < w; x++) {
      const q = (y * w + x) * 4;
      if (ct === 6) { for (let k = 0; k < 4; k++) out[q + k] = to8(smp(rs, x * 4 + k)); }
      else if (ct === 2) {
        const r = smp(rs, x * 3), g = smp(rs, x * 3 + 1), b = smp(rs, x * 3 + 2);
        out[q] = to8(r); out[q + 1] = to8(g); out[q + 2] = to8(b);
        out[q + 3] = key && key[0] === r && key[1] === g && key[2] === b ? 0 : 255;
      } else if (ct === 0) {
        const g = smp(rs, x);
        out[q] = out[q + 1] = out[q + 2] = to8(g);
        out[q + 3] = key && key[0] === g ? 0 : 255;
      } else if (ct === 4) {
        const g = to8(smp(rs, x * 2));
        out[q] = out[q + 1] = out[q + 2] = g; out[q + 3] = to8(smp(rs, x * 2 + 1));
      } else { // 3: palette
        const i = smp(rs, x);
        if (!plte || i * 3 + 2 >= plte.length) throw new Error("png: palette hỏng");
        out[q] = plte[i * 3]; out[q + 1] = plte[i * 3 + 1]; out[q + 2] = plte[i * 3 + 2];
        out[q + 3] = trns && i < trns.length ? trns[i] : 255;
      }
    }
  }
  return { w, h, data: out };
}

// Đưa ảnh vào khung T x T, giữ tỉ lệ, canh giữa. Phóng to: lấy mẫu gần nhất (giữ nét pixel). Thu nhỏ: trung bình diện tích (alpha nhân trước).
export function fitSquare(img, T) {
  const { w, h, data } = img;
  const s = Math.min(T / w, T / h);
  const nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
  const ox = (T - nw) >> 1, oy = (T - nh) >> 1;
  const out = new Uint8Array(T * T * 4);
  for (let y = 0; y < nh; y++) {
    for (let x = 0; x < nw; x++) {
      const q = ((oy + y) * T + ox + x) * 4;
      if (s >= 1) {
        const sx = Math.min(w - 1, Math.floor(x / s)), sy = Math.min(h - 1, Math.floor(y / s)), t = (sy * w + sx) * 4;
        out[q] = data[t]; out[q + 1] = data[t + 1]; out[q + 2] = data[t + 2]; out[q + 3] = data[t + 3];
      } else {
        const x0 = Math.floor(x / s), x1 = Math.max(x0 + 1, Math.min(w, Math.ceil((x + 1) / s)));
        const y0 = Math.floor(y / s), y1 = Math.max(y0 + 1, Math.min(h, Math.ceil((y + 1) / s)));
        let r = 0, g = 0, b = 0, a = 0, n = 0;
        for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
          const t = (yy * w + xx) * 4, al = data[t + 3];
          r += data[t] * al; g += data[t + 1] * al; b += data[t + 2] * al; a += al; n++;
        }
        if (a > 0) { out[q] = Math.round(r / a); out[q + 1] = Math.round(g / a); out[q + 2] = Math.round(b / a); }
        out[q + 3] = Math.round(a / n);
      }
    }
  }
  return { w: T, h: T, data: out };
}

let crcT = null;
function crc32(u8) {
  if (!crcT) { crcT = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[n] = c >>> 0; } }
  let c = 0xffffffff;
  for (let i = 0; i < u8.length; i++) c = crcT[(c ^ u8[i]) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const o = new Uint8Array(12 + data.length), dv = new DataView(o.buffer);
  dv.setUint32(0, data.length);
  for (let i = 0; i < 4; i++) o[4 + i] = type.charCodeAt(i);
  o.set(data, 8);
  dv.setUint32(8 + data.length, crc32(o.subarray(4, 8 + data.length)));
  return o;
}

// PNG 8 bit: dùng palette (kèm tRNS) nếu ≤ 256 màu, nếu không thì RGBA.
export async function encodePng(img) {
  const { w, h, data } = img;
  const pal = new Map();
  const list = [];
  let indexed = true;
  for (let i = 0; i < w * h && indexed; i++) {
    const k = ((data[i * 4] << 24) | (data[i * 4 + 1] << 16) | (data[i * 4 + 2] << 8) | data[i * 4 + 3]) >>> 0;
    if (!pal.has(k)) { if (list.length >= 256) { indexed = false; break; } pal.set(k, list.length); list.push(k); }
  }
  const parts = [new Uint8Array(PNG_SIG)];
  const ihdr = new Uint8Array(13), dv = new DataView(ihdr.buffer);
  dv.setUint32(0, w); dv.setUint32(4, h); ihdr[8] = 8; ihdr[9] = indexed ? 3 : 6;
  parts.push(chunk("IHDR", ihdr));
  let rows;
  if (indexed) {
    const plte = new Uint8Array(list.length * 3), trns = new Uint8Array(list.length);
    let hasA = false;
    list.forEach((k, i) => { plte[i * 3] = k >>> 24; plte[i * 3 + 1] = (k >>> 16) & 255; plte[i * 3 + 2] = (k >>> 8) & 255; trns[i] = k & 255; if ((k & 255) !== 255) hasA = true; });
    parts.push(chunk("PLTE", plte));
    if (hasA) parts.push(chunk("tRNS", trns));
    rows = new Uint8Array((w + 1) * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      rows[y * (w + 1) + 1 + x] = pal.get(((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]) >>> 0);
    }
  } else {
    rows = new Uint8Array((w * 4 + 1) * h);
    for (let y = 0; y < h; y++) rows.set(data.subarray(y * w * 4, (y + 1) * w * 4), y * (w * 4 + 1) + 1);
  }
  parts.push(chunk("IDAT", await deflate(rows)));
  parts.push(chunk("IEND", new Uint8Array(0)));
  const out = new Uint8Array(parts.reduce((n, a) => n + a.length, 0));
  let o = 0;
  for (const a of parts) { out.set(a, o); o += a.length; }
  return out;
}

// Từ PNG gốc của JAR ra hai cỡ: 32 (app) và 64 (web).
export async function makeIcons(png) {
  const img = await decodePng(png);
  return { ic32: await encodePng(fitSquare(img, 32)), ic64: await encodePng(fitSquare(img, 64)), srcW: img.w, srcH: img.h };
}

export function b64(u8) {
  let s = "";
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}
export function unb64(s) {
  const bin = atob(s), u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}
