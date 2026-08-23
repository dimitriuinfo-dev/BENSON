// BENSON logo layer slicer — Item 4 "exact asset integration".
//
// Decomposes assets/benson-logo.png (the approved master medallion — square, brushed-metal
// B·E·N·S·O·N wordmark, broken gold/green arc ring, gold/green hairline circles, tick marks at
// 12/6 o'clock) into three layers for independent-rotation compositing in the app:
//   - layer-plate.png      — static base: background + wordmark + "AI BUTLER" + tick marks +
//                             the hairline-ring pixels that cross the wordmark/tick-mark zones
//                             (those never rotate, so they stay baked into the plate).
//   - layer-arcs.png       — ONLY the broken gold/green arc ring annulus (background inside that
//                             annulus is kept, per spec, since it's uniform enough to rotate
//                             over itself seamlessly).
//   - layer-hairlines.png  — ONLY the thin hairline-circle annulus, with the wordmark band and
//                             the 12/6 o'clock tick-mark wedges cut out (kept in plate instead)
//                             so no seam ever crosses a letter or a tick mark.
//
// No redrawing/tracing/vectorizing/approximating happens anywhere here — every output pixel is
// copied byte-for-byte from the master PNG; only the alpha channel is modified to build masks.
//
// Uses pngjs (already present transitively via expo-notifications -> @expo/image-utils ->
// parse-png) rather than adding a new `sharp` dependency for a one-off script.
//
// Radii are MEASURED from the actual pixels at runtime (radial color-deviation scans from the
// image center), never hardcoded guesses — see measureGeometry() below. Re-run this script any
// time assets/benson-logo.png changes; it re-derives geometry from whatever image is there.
//
// Usage: node tools/slice-logo.js

const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');

const SRC = process.argv[2] || path.join(__dirname, '..', 'assets', 'benson-logo.png');
const OUT_DIR = path.join(__dirname, '..', 'assets', 'brand');

function loadPng(p) {
  return PNG.sync.read(fs.readFileSync(p));
}

function writePng(png, p) {
  fs.writeFileSync(p, PNG.sync.write(png));
}

function clonePng(src) {
  const out = new PNG({ width: src.width, height: src.height });
  src.data.copy(out.data);
  return out;
}

function getPixel(png, x, y) {
  const idx = (png.width * y + x) << 2;
  return [png.data[idx], png.data[idx + 1], png.data[idx + 2], png.data[idx + 3]];
}

function setAlpha(png, x, y, a) {
  const idx = (png.width * y + x) << 2;
  png.data[idx + 3] = a;
}

function isGoldish(p) {
  // Bronze/gold letter/arc/hairline color vs. the neutral slate background. The brushed-metal
  // arcs carry a bright, near-white highlight streak (e.g. 249,236,175) that a strict R-G
  // threshold misses — loosened here (R-G>6, was >15) to still catch that highlight without
  // starting to match the neutral gray background (which never has R-G beyond ~3).
  return p[0] > 140 && p[0] - p[2] > 35 && p[0] - p[1] > 6;
}

function isGreenish(p) {
  // Brushed-jade arc/hairline color vs. the neutral slate background.
  return p[1] > 35 && p[1] - p[0] > 8 && p[1] - p[2] > 5;
}

// ---- Step 1: measure real radii from the pixels — never hardcoded guesses ----
//
// Detection is hue-based (gold vs. green vs. neither), NOT generic "distance from a single
// corner-sampled background color": the master image's background has a natural vignette
// (subtle darkening away from center), so a plain color-distance threshold picks up that
// vignette as if it were ring content and produces a radius range wide enough to accidentally
// sweep in the wordmark's own letters. Hue detection is immune to that, since the vignette never
// turns the neutral gray background into a saturated gold or green.
function measureGeometry(png) {
  const { width, height } = png;
  const cx = width / 2;
  const cy = height / 2;
  const maxR = Math.floor(Math.min(width, height) / 2) - 2;

  function scanAngle(angleDeg, rMax) {
    const rad = (angleDeg * Math.PI) / 180;
    const dx = Math.sin(rad);
    const dy = -Math.cos(rad); // 0deg = straight up (12 o'clock), clockwise positive
    const bands = [];
    let inBand = false;
    let start = 0;
    for (let r = 0; r <= rMax; r++) {
      const x = Math.round(cx + dx * r);
      const y = Math.round(cy + dy * r);
      if (x < 0 || y < 0 || x >= width || y >= height) break;
      const p = getPixel(png, x, y);
      const isBand = isGoldish(p) || isGreenish(p);
      if (isBand && !inBand) {
        inBand = true;
        start = r;
      }
      if (!isBand && inBand) {
        inBand = false;
        bands.push([start, r - 1]);
      }
    }
    if (inBand) bands.push([start, maxR]);
    return bands;
  }

  // Arc ring: scan diagonal angles that pass through the middle of each of the four arc
  // segments (avoiding the 12/3/6/9 o'clock gaps and the horizontal wordmark row). A "real" ring
  // band is wide (>20px) and starts well outside the text/hairline zone (>350px, empirically the
  // wordmark+hairlines never extend past ~340px on this image).
  let arcInner = Infinity;
  let arcOuter = 0;
  for (const deg of [35, 45, 55, 125, 135, 145, 215, 225, 235, 305, 315, 325]) {
    for (const [a, b] of scanAngle(deg, maxR)) {
      if (a > 350 && b - a > 20) {
        arcInner = Math.min(arcInner, a);
        arcOuter = Math.max(arcOuter, b);
      }
    }
  }
  if (!isFinite(arcInner)) throw new Error('Could not detect the arc ring — check the source image.');

  // Hairline circles: scan near-vertical angles just off the 12-o'clock tick mark (which would
  // otherwise dominate a pure 0deg scan), looking for thin (<=15px) bands between the text zone
  // and the arc's inner radius.
  const hairlineBands = [];
  for (const deg of [10, 12, 15, 18, 20, -10, -12, -15, -18, -20]) {
    for (const [a, b] of scanAngle(deg, arcInner - 5)) {
      if (a > 200 && b - a <= 15) hairlineBands.push([a, b]);
    }
  }
  hairlineBands.sort((x, y) => x[0] - y[0]);
  const hairlineMin = hairlineBands.length ? hairlineBands[0][0] - 6 : Math.round(arcInner * 0.75);
  const hairlineMax = hairlineBands.length ? hairlineBands[hairlineBands.length - 1][1] + 6 : arcInner - 20;

  // Wordmark vertical extent: gold-colored pixels within a radius comfortably inside the
  // hairline ring (so arc/hairline pixels are never mistaken for letters).
  const textSearchR = hairlineMin - 10;
  let textMinY = Infinity;
  let textMaxY = -Infinity;
  for (let xOff = -Math.floor(width * 0.3); xOff <= Math.floor(width * 0.3); xOff += 3) {
    for (let yOff = -200; yOff <= 220; yOff += 1) {
      if (Math.hypot(xOff, yOff) > textSearchR) continue;
      const x = Math.round(cx + xOff);
      const y = Math.round(cy + yOff);
      if (x < 0 || y < 0 || x >= width || y >= height) continue;
      if (isGoldish(getPixel(png, x, y))) {
        if (yOff < textMinY) textMinY = yOff;
        if (yOff > textMaxY) textMaxY = yOff;
      }
    }
  }
  if (!isFinite(textMinY)) throw new Error('Could not detect the wordmark — check the source image.');

  return {
    cx,
    cy,
    arcInner,
    arcOuter,
    hairlineMin,
    hairlineMax,
    textBand: { minY: textMinY - 20, maxY: textMaxY + 20 },
    plateRadius: arcOuter + 8, // "just inside the outer edge of the arcs' shadow falloff"
  };
}

// True if (x,y) sits within `halfWidthDeg` of the 12-o'clock or 6-o'clock axis — protects the
// tick marks (drawn only at those two positions) from being cut into a rotating layer, the same
// way the text band protects the wordmark.
function inTickWedge(x, y, cx, cy, halfWidthDeg) {
  const dx = x - cx;
  const dy = y - cy;
  let angle = (Math.atan2(dx, -dy) * 180) / Math.PI;
  angle = ((angle % 360) + 360) % 360;
  const distTo = (target) => Math.min(Math.abs(angle - target), 360 - Math.abs(angle - target));
  return distTo(0) <= halfWidthDeg || distTo(180) <= halfWidthDeg;
}

// ---- Step 2: build the three layers by masking alpha only — every RGB pixel is untouched ----
function buildLayers(png, geo) {
  const { width, height } = png;
  const plate = clonePng(png);
  const arcs = clonePng(png);
  const hairlines = clonePng(png);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const r = Math.hypot(x - geo.cx, y - geo.cy);
      const yOffFromCenter = y - geo.cy;
      const inTextBand = yOffFromCenter >= geo.textBand.minY && yOffFromCenter <= geo.textBand.maxY;
      const inWedge = inTickWedge(x, y, geo.cx, geo.cy, 6);

      // The wordmark's outermost letters ("B", "N") sit near-horizontal and can reach radii
      // that overlap the arc annulus — but they're always within the wordmark's own y-band
      // (that's what makes them part of the same text row), so excluding that y-band from the
      // rotating arc ring — the same guard already needed for the hairline ring — keeps them
      // out regardless of the exact measured radius. The real arc segments live at much larger
      // |y-offset| than the wordmark row (they start ~30° off vertical), so this never clips them.
      const inArcRing = r >= geo.arcInner && r <= geo.arcOuter && !inTextBand;
      const inHairlineRing = r >= geo.hairlineMin && r <= geo.hairlineMax;

      // Plate: everything, minus the arc ring and the hairline ring — except the hairline
      // pixels that cross the wordmark band or a tick-mark wedge, which stay here (static)
      // rather than in the rotating hairline layer.
      if (inArcRing) setAlpha(plate, x, y, 0);
      if (inHairlineRing && !inTextBand && !inWedge) setAlpha(plate, x, y, 0);
      if (r > geo.plateRadius) setAlpha(plate, x, y, 0);

      // Arcs layer: only the arc ring annulus (wordmark band already excluded above).
      if (!inArcRing) setAlpha(arcs, x, y, 0);

      // Hairlines layer: only the hairline annulus, minus the wordmark/tick-mark protected zones
      // (never duplicated between plate and this layer).
      if (!inHairlineRing || inTextBand || inWedge) setAlpha(hairlines, x, y, 0);
    }
  }

  return { plate, arcs, hairlines };
}

function main() {
  const png = loadPng(SRC);
  const geo = measureGeometry(png);
  console.log('Measured geometry from', SRC, ':', geo);

  const { plate, arcs, hairlines } = buildLayers(png, geo);

  fs.mkdirSync(OUT_DIR, { recursive: true });
  writePng(plate, path.join(OUT_DIR, 'layer-plate.png'));
  writePng(arcs, path.join(OUT_DIR, 'layer-arcs.png'));
  writePng(hairlines, path.join(OUT_DIR, 'layer-hairlines.png'));
  console.log('Wrote layer-plate.png, layer-arcs.png, layer-hairlines.png to', OUT_DIR);
}

main();
