// One-off downsampler for assets/brand/layer-*.png — the 1254x1254 sliced layers (2.7-3.2MB
// each) were failing to render on-device (broken-image placeholder), most likely too large to
// reliably transfer/decode over the Metro dev bridge. Displayed size on screen is ~350-420dp, so
// there is no reason to ship 1254px source images. Box-filter downsample to TARGET, preserving
// the alpha channel byte-for-byte in proportion (averaged, not thresholded).
//
// Usage: node tools/resize-layers.js

const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');

const DIR = path.join(__dirname, '..', 'assets', 'brand');
const TARGET = 640;
const FILES = ['layer-plate.png', 'layer-arcs.png', 'layer-hairlines.png'];

function resize(src, targetW, targetH) {
  const out = new PNG({ width: targetW, height: targetH });
  const sx = src.width / targetW;
  const sy = src.height / targetH;

  for (let ty = 0; ty < targetH; ty++) {
    for (let tx = 0; tx < targetW; tx++) {
      const x0 = Math.floor(tx * sx);
      const y0 = Math.floor(ty * sy);
      const x1 = Math.min(src.width, Math.ceil((tx + 1) * sx));
      const y1 = Math.min(src.height, Math.ceil((ty + 1) * sy));

      let r = 0, g = 0, b = 0, a = 0, count = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const idx = (src.width * y + x) << 2;
          r += src.data[idx];
          g += src.data[idx + 1];
          b += src.data[idx + 2];
          a += src.data[idx + 3];
          count++;
        }
      }
      const outIdx = (targetW * ty + tx) << 2;
      out.data[outIdx]     = Math.round(r / count);
      out.data[outIdx + 1] = Math.round(g / count);
      out.data[outIdx + 2] = Math.round(b / count);
      out.data[outIdx + 3] = Math.round(a / count);
    }
  }
  return out;
}

for (const file of FILES) {
  const p = path.join(DIR, file);
  const src = PNG.sync.read(fs.readFileSync(p));
  const targetH = Math.round(TARGET * (src.height / src.width));
  const out = resize(src, TARGET, targetH);
  fs.writeFileSync(p, PNG.sync.write(out));
  console.log(file, src.width, 'x', src.height, '->', TARGET, 'x', targetH, ',', fs.statSync(p).size, 'bytes');
}
