// 生成补充云精灵 PNG（透明底，风格对齐南风 weather_cloud_*）
// 输出到 app/src/main/assets/cloudsprites/
const { createCanvas } = require('canvas');
const fs = require('fs');
const path = require('path');

const OUT = path.resolve('app/src/main/assets/cloudsprites');

function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.substr(0, 2), 16), parseInt(h.substr(2, 2), 16), parseInt(h.substr(4, 2), 16)];
}
function rgbToCss(r, g, b, a) { return `rgba(${r|0},${g|0},${b|0},${a})`; }

// 径向渐变圆：中心亮、边缘略灰，模拟云团体积光
function puff(ctx, x, y, r, top, bottom, alpha) {
  const [tr, tg, tb] = hexToRgb(top);
  const [br, bg, bb] = hexToRgb(bottom);
  const g = ctx.createRadialGradient(x, y - r * 0.25, r * 0.1, x, y, r);
  g.addColorStop(0, rgbToCss(tr, tg, tb, alpha));
  g.addColorStop(0.7, rgbToCss((tr + br) / 2, (tg + bg) / 2, (tb + bb) / 2, alpha * 0.95));
  g.addColorStop(1, rgbToCss(br, bg, bb, alpha * 0.75));
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fill();
}

// 底部阴影：云团下缘的灰蓝暗部
function shade(ctx, x, y, r, color, alpha) {
  const [cr, cg, cb] = hexToRgb(color);
  ctx.fillStyle = rgbToCss(cr, cg, cb, alpha);
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fill();
}

function save(cv, name) {
  // 根目录边界校验：resolve 后必须仍在 OUT 内，且只接受纯文件名。
  if (!/^[A-Za-z0-9_.-]+$/.test(name) || name.startsWith('.')) {
    throw new Error('illegal asset name: ' + name);
  }
  const p = path.resolve(OUT, name);
  if (!p.startsWith(OUT + path.sep)) {
    throw new Error('asset path escapes output dir: ' + name);
  }
  fs.writeFileSync(p, cv.toBuffer('image/png'));
  console.log('written', name, cv.width + 'x' + cv.height);
}

// ---- 大积云 v4：饱满大团，亮顶暗底，底部收平 ----
function cumulusV4() {
  const W = 768, H = 512;
  const cv = createCanvas(W, H);
  const ctx = cv.getContext('2d');
  const cx = W / 2, cy = H * 0.42;
  // 底部阴影团（先画，形成底暗）
  shade(ctx, cx - 120, cy + 95, 95, '#7A8A9A', 0.30);
  shade(ctx, cx + 40, cy + 110, 120, '#7A8A9A', 0.35);
  shade(ctx, cx + 170, cy + 80, 80, '#7A8A9A', 0.25);
  // 主体圆团：左中右三峰 + 顶部主峰
  puff(ctx, cx - 170, cy + 20, 105, '#FFFFFF', '#C6D0DC', 0.95);
  puff(ctx, cx - 60, cy - 40, 130, '#FFFFFF', '#C6D0DC', 1.0);
  puff(ctx, cx + 80, cy - 10, 150, '#FFFFFF', '#C6D0DC', 1.0);
  puff(ctx, cx + 200, cy + 35, 95, '#FFFFFF', '#BCC8D6', 0.9);
  puff(ctx, cx - 10, cy - 95, 90, '#FFFFFF', '#DCE4EC', 1.0);
  // 顶部高光
  puff(ctx, cx - 60, cy - 70, 55, '#FFFFFF', '#F2F5F8', 1.0);
  puff(ctx, cx + 80, cy - 60, 65, '#FFFFFF', '#F2F5F8', 1.0);
  save(cv, 'weather_cloud_cumulus_day_v4.png');
}

// ---- 大积云 v5：更宽更扁的成团云，如屏中部"云堤" ----
function cumulusV5() {
  const W = 768, H = 512;
  const cv = createCanvas(W, H);
  const ctx = cv.getContext('2d');
  const cx = W / 2, cy = H * 0.5;
  shade(ctx, cx - 150, cy + 95, 110, '#7A8A9A', 0.32);
  shade(ctx, cx + 120, cy + 105, 140, '#7A8A9A', 0.34);
  puff(ctx, cx - 220, cy + 25, 110, '#FCFDFE', '#C2CCD8', 0.95);
  puff(ctx, cx - 90, cy - 15, 120, '#FFFFFF', '#C6D0DC', 1.0);
  puff(ctx, cx + 40, cy - 5, 135, '#FFFFFF', '#C6D0DC', 1.0);
  puff(ctx, cx + 170, cy + 30, 115, '#FBFCFF', '#BCC8D6', 0.95);
  puff(ctx, cx + 280, cy + 20, 85, '#F8FAFD', '#C6D0DC', 0.85);
  puff(ctx, cx - 20, cy - 80, 80, '#FFFFFF', '#E6EBF2', 1.0);
  save(cv, 'weather_cloud_cumulus_day_v5.png');
}

// ---- 高积云群：成片的小碎团（鱼鳞状），半透明 ----
function altocumulusV1() {
  const W = 768, H = 512;
  const cv = createCanvas(W, H);
  const ctx = cv.getContext('2d');
  const cx = W / 2, cy = H * 0.36;
  const cols = 7, rows = 3;
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const x = 90 + c * 105 + (r % 2) * 52;
      const y = cy - 55 + r * 85 + (c % 3) * 8;
      const rad = 38 + ((c * 7 + r * 13) % 20);
      const alpha = 0.55 + ((c * 3 + r * 5) % 10) * 0.03;
      puff(ctx, x, y, rad, '#FFFFFF', '#C6D0DC', alpha);
    }
  }
  // 群底一条淡影，保证"成片"感
  shade(ctx, cx - 40, cy + 105, 130, '#9AA6B4', 0.16);
  save(cv, 'weather_cloud_altocumulus_v1.png');
}

// ---- 层云带：低平长条，阴天/雨天的厚云基座 ----
function stratusV1() {
  const W = 768, H = 512;
  const cv = createCanvas(W, H);
  const ctx = cv.getContext('2d');
  const cx = W / 2, cy = H * 0.5;
  shade(ctx, cx, cy + 60, 300, '#7A8A9A', 0.28);
  // 多层扁椭圆压叠成带状
  for (let i = 0; i < 9; i++) {
    const x = 80 + i * 82;
    const y = cy - 30 + ((i * 37) % 46);
    const rx = 95, ry = 42 + (i % 3) * 10;
    const [tr, tg, tb] = hexToRgb('#E8EDF3');
    const [br, bg, bb] = hexToRgb('#9AA6B4');
    const g = ctx.createRadialGradient(x, y - 10, 8, x, y, rx);
    g.addColorStop(0, rgbToCss(tr, tg, tb, 0.9));
    g.addColorStop(0.75, rgbToCss((tr + br) / 2, (tg + bg) / 2, (tb + bb) / 2, 0.8));
    g.addColorStop(1, rgbToCss(br, bg, bb, 0.5));
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(x, y, rx, ry, 0, 0, Math.PI * 2);
    ctx.fill();
  }
  save(cv, 'weather_cloud_stratus_v1.png');
}

cumulusV4();
cumulusV5();
altocumulusV1();
stratusV1();
