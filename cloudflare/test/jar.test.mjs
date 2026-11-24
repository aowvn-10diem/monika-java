import fs from "node:fs";
import assert from "node:assert/strict";
import { analyzeJar, makeIcons, decodePng, slugify, sha256hex, manifestToJad, parseManifest } from "../jar.js";
const buf = new Uint8Array(fs.readFileSync(new URL("../../demo/Monika.jar", import.meta.url)));
const a = await analyzeJar(buf);
assert.equal(a.name, "Monika"); assert.equal(a.vendor, "AowVN"); assert.equal(a.ver, "0.1.1");
assert.ok(a.icon && a.iconPath === "/icon.png");
const ic = await makeIcons(a.icon);
console.log("icon src", ic.srcW, ic.srcH, "ic32", ic.ic32.length, "ic64", ic.ic64.length);
const d = await decodePng(ic.ic32); assert.equal(d.w, 32);
console.log(slugify("Bounce Tales: Đại chiến!"), (await sha256hex(buf)).slice(0,10));
console.log(manifestToJad(a.manifest, "http://x/y.jar", buf.length));
const pm = parseManifest("A: 1\nB: lon\n g\n\nName: x\nC: 2"); assert.equal(pm.b, "long"); assert.equal(pm.c, undefined);
fs.writeFileSync("/tmp/ic32.png", ic.ic32); fs.writeFileSync("/tmp/ic64.png", ic.ic64);
// PNG đủ loại từ PIL
import { execFileSync } from "node:child_process";
execFileSync("python3", ["-c", `
from PIL import Image
import io, struct
im = Image.new('RGBA',(40,24)); 
for x in range(40):
  for y in range(24): im.putpixel((x,y),(x*6,y*10,128,255 if (x+y)%7 else 0))
im.save('/tmp/t_rgba.png')
im.convert('RGB').save('/tmp/t_rgb.png')
im.convert('L').save('/tmp/t_gray.png')
im.convert('P', palette=Image.ADAPTIVE, colors=16).save('/tmp/t_p16.png', bits=4)
im.convert('LA').save('/tmp/t_la.png')
Image.new('RGBA',(128,128),(255,0,0,255)).save('/tmp/t_big.png')
Image.new('RGB',(16,16),(0,255,0)).save('/tmp/t_16.png', interlace=False)
`]);
for (const f of ["rgba","rgb","gray","p16","la","big","16"]) {
  const p = new Uint8Array(fs.readFileSync(`/tmp/t_${f}.png`));
  const i = await makeIcons(p);
  const back = await decodePng(i.ic32);
  console.log(f, i.srcW+"x"+i.srcH, "->", back.w, i.ic32.length, i.ic64.length);
  fs.writeFileSync(`/tmp/o_${f}.png`, i.ic32);
}
console.log("OK");
