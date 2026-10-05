const { createCanvas, loadImage } = require('canvas');
const fs = require('fs');
const path = require('path');

const SIZE = 256;
const CENTER = SIZE / 2;

// 配色方案
const COLORS = {
  sun: '#FFD93D',
  sunCore: '#FFC107',
  sunGlow: '#FFE94A',
  moon: '#E8E8F0',
  moonShadow: '#C8C8D8',
  cloudWhite: '#FFFFFF',
  cloudLight: '#F0F4F8',
  cloudGray: '#B8C4D0',
  cloudDark: '#7A8A9A',
  cloudDarker: '#5A6A7A',
  rainBlue: '#5BA3D9',
  rainLight: '#8BC4E8',
  rainDark: '#3D8BBF',
  snowWhite: '#FFFFFF',
  thunderYellow: '#FFD93D',
  fogGray: '#A8B4C0',
  fogLight: '#C8D4E0',
  windBlue: '#8BC4E8',
  hazeOrange: '#D4A860',
};

function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [
    parseInt(h.substr(0, 2), 16),
    parseInt(h.substr(2, 2), 16),
    parseInt(h.substr(4, 2), 16)
  ];
}

function drawCircle(ctx, x, y, r, fill) {
  if (fill) {
    ctx.fillStyle = fill;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawSun(ctx, cx, cy, r, glowAlpha) {
  // 光晕
  for (let i = 5; i > 0; i--) {
    const alpha = Math.floor(30 * (5 - i) / 5);
    const glowR = r + i * 8;
    ctx.fillStyle = COLORS.sunGlow;
    ctx.globalAlpha = alpha / 255;
    ctx.beginPath();
    ctx.arc(cx, cy, glowR, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalAlpha = 1;
  // 核心
  ctx.fillStyle = COLORS.sun;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fill();
  // 高光
  ctx.fillStyle = COLORS.sunCore;
  ctx.beginPath();
  ctx.arc(cx - r/3, cy - r/3, r/3, 0, Math.PI * 2);
  ctx.fill();
}

function drawMoon(ctx, cx, cy, r) {
  ctx.fillStyle = COLORS.moon;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fill();
  // 阴影缺口
  ctx.fillStyle = COLORS.moonShadow;
  ctx.beginPath();
  ctx.arc(cx + r/3, cy - r/4, r * 0.7, 0, Math.PI * 2);
  ctx.fill();
}

function drawCloud(ctx, cx, cy, r, color, alpha) {
  ctx.fillStyle = color || COLORS.cloudWhite;
  if (alpha !== undefined) ctx.globalAlpha = alpha;

  const offsets = [
    [-r * 0.5, r * 0.1],
    [r * 0.5, r * 0.1],
    [-r * 0.15, -r * 0.2],
    [r * 0.2, -r * 0.25],
    [0, -r * 0.1]
  ];

  offsets.forEach(([dx, dy]) => {
    const cr = r * (dy < 0 ? 0.55 : 0.45);
    ctx.beginPath();
    ctx.arc(cx + dx * 2, cy + dy * 2, cr, 0, Math.PI * 2);
    ctx.fill();
  });

  if (alpha !== undefined) ctx.globalAlpha = 1;
}

function drawRainDrop(ctx, x, y, r, color) {
  ctx.fillStyle = color || COLORS.rainBlue;
  ctx.beginPath();
  ctx.moveTo(x, y - r * 1.5);
  ctx.quadraticCurveTo(x + r * 0.8, y, x, y + r * 1.2);
  ctx.quadraticCurveTo(x - r * 0.8, y, x, y - r * 1.5);
  ctx.fill();
}

function drawSnowflake(ctx, x, y, r) {
  ctx.fillStyle = COLORS.snowWhite;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fill();
  // 十字分支
  ctx.strokeStyle = COLORS.snowWhite;
  ctx.lineWidth = 2;
  for (let i = 0; i < 4; i++) {
    const angle = (i * 90) * Math.PI / 180;
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x + r * Math.cos(angle), y + r * Math.sin(angle));
    ctx.stroke();
  }
}

function drawLightning(ctx, x1, y1, x2, y2) {
  ctx.fillStyle = COLORS.thunderYellow;
  const points = [
    [x1, y1],
    [x1 + 15, y1 + 40],
    [x1 - 10, y1 + 50],
    [x1 + 10, y1 + 90],
    [x2, y2]
  ];
  ctx.beginPath();
  ctx.moveTo(points[0][0], points[0][1]);
  for (let i = 1; i < points.length; i++) {
    ctx.lineTo(points[i][0], points[i][1]);
  }
  ctx.closePath();
  ctx.fill();
}

function drawWindLine(ctx, startX, startY, length, color) {
  ctx.strokeStyle = color || COLORS.windBlue;
  ctx.lineWidth = 3;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(startX, startY);
  ctx.lineTo(startX + length, startY);
  ctx.stroke();
  // 箭头
  const arrowSize = 6;
  ctx.fillStyle = color || COLORS.windBlue;
  ctx.beginPath();
  ctx.moveTo(startX + length, startY);
  ctx.lineTo(startX + length - arrowSize, startY - arrowSize/2);
  ctx.lineTo(startX + length - arrowSize, startY + arrowSize/2);
  ctx.closePath();
  ctx.fill();
}

// ========== 各天气图标 ==========

function iconClearDay(ctx) {
  drawSun(ctx, CENTER, CENTER - 10, 45);
  drawCloud(ctx, CENTER + 70, CENTER + 50, 25, COLORS.cloudLight, 0.7);
}

function iconClearNight(ctx) {
  drawMoon(ctx, CENTER, CENTER - 10, 40);
  // 星星
  for (let i = 0; i < 5; i++) {
    const sx = 30 + i * 45;
    const sy = 30 + (i % 3) * 30;
    ctx.fillStyle = '#FFFFFF';
    ctx.globalAlpha = 0.8;
    ctx.beginPath();
    ctx.arc(sx, sy, 2, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalAlpha = 1;
}

function iconPartlyCloudyDay(ctx) {
  drawSun(ctx, CENTER - 50, CENTER - 40, 38);
  drawCloud(ctx, CENTER + 20, CENTER, 50);
  drawCloud(ctx, CENTER + 60, CENTER + 30, 30, COLORS.cloudLight);
}

function iconPartlyCloudyNight(ctx) {
  drawMoon(ctx, CENTER - 40, CENTER - 30, 35);
  drawCloud(ctx, CENTER + 30, CENTER + 10, 45);
}

function iconOvercast(ctx) {
  drawCloud(ctx, CENTER, CENTER - 20, 55, COLORS.cloudDark);
  drawCloud(ctx, CENTER - 40, CENTER + 10, 40, COLORS.cloudDark);
  drawCloud(ctx, CENTER + 40, CENTER + 10, 40, COLORS.cloudDark);
}

function iconLightRain(ctx) {
  drawCloud(ctx, CENTER, CENTER - 30, 50, COLORS.cloudDark);
  for (let i = 0; i < 5; i++) {
    const x = CENTER - 50 + i * 25;
    const y = CENTER + 20 + (i % 2) * 20;
    drawRainDrop(ctx, x, y, 6, COLORS.rainLight);
  }
}

function iconModerateRain(ctx) {
  drawCloud(ctx, CENTER, CENTER - 30, 52, COLORS.cloudDarker);
  for (let i = 0; i < 8; i++) {
    const x = CENTER - 60 + i * 17;
    const y = CENTER + 15 + (i % 3) * 15;
    drawRainDrop(ctx, x, y, 7, COLORS.rainBlue);
  }
}

function iconHeavyRain(ctx) {
  drawCloud(ctx, CENTER, CENTER - 30, 55, COLORS.cloudDarker);
  for (let i = 0; i < 12; i++) {
    const x = CENTER - 70 + i * 13;
    const y = CENTER + 10 + (i % 4) * 12;
    drawRainDrop(ctx, x, y, 8, COLORS.rainDark);
  }
}

function iconThunder(ctx) {
  drawCloud(ctx, CENTER, CENTER - 35, 55, COLORS.cloudDarker);
  drawLightning(ctx, CENTER - 10, CENTER + 10, CENTER + 20, CENTER + 70);
  for (let i = 0; i < 6; i++) {
    const x = CENTER - 60 + i * 24;
    const y = CENTER + 50 + (i % 2) * 15;
    drawRainDrop(ctx, x, y, 7, COLORS.rainBlue);
  }
}

function iconSnow(ctx) {
  drawCloud(ctx, CENTER, CENTER - 25, 50, COLORS.cloudLight);
  for (let i = 0; i < 8; i++) {
    const angle = i * 45;
    const r = 50 + (i % 3) * 15;
    const x = CENTER + Math.floor(r * 0.7 * (i % 2 === 0 ? 1 : -1));
    const y = CENTER + 20 + Math.floor(r * 0.5 * ((i % 2 === 0) ? 1 : -1));
    drawSnowflake(ctx, x, y, 8);
  }
}

function iconFog(ctx) {
  for (let i = 0; i < 6; i++) {
    const y = CENTER - 40 + i * 18;
    const alpha = 0.4 + i * 0.08;
    ctx.strokeStyle = COLORS.fogGray;
    ctx.globalAlpha = alpha;
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.moveTo(30, y);
    ctx.lineTo(SIZE - 30, y);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
  drawCloud(ctx, CENTER, CENTER - 10, 40, COLORS.fogLight, 0.5);
}

function iconHaze(ctx) {
  for (let i = 0; i < 5; i++) {
    const y = CENTER - 30 + i * 15;
    const alpha = 0.3 + i * 0.06;
    ctx.strokeStyle = COLORS.hazeOrange;
    ctx.globalAlpha = alpha;
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(40, y);
    ctx.lineTo(SIZE - 40, y);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
  drawSun(ctx, CENTER, CENTER - 20, 30);
}

function iconWind(ctx) {
  for (let i = 0; i < 5; i++) {
    const y = CENTER - 50 + i * 25;
    const startX = 40 + (i % 2) * 30;
    const length = 80 + (i % 3) * 20;
    drawWindLine(ctx, startX, y, length, COLORS.windBlue);
  }
  drawCloud(ctx, CENTER + 60, CENTER - 20, 25, COLORS.cloudLight, 0.6);
}

function iconDrizzle(ctx) {
  drawCloud(ctx, CENTER, CENTER - 25, 45, COLORS.cloudLight);
  for (let i = 0; i < 4; i++) {
    const x = CENTER - 35 + i * 23;
    const y = CENTER + 25 + (i % 2) * 15;
    drawRainDrop(ctx, x, y, 5, COLORS.rainLight);
  }
}

function iconNightDrizzle(ctx) {
  drawMoon(ctx, CENTER - 50, CENTER - 40, 30);
  drawCloud(ctx, CENTER + 20, CENTER, 40, COLORS.cloudLight);
  for (let i = 0; i < 3; i++) {
    const x = CENTER - 10 + i * 20;
    const y = CENTER + 30;
    drawRainDrop(ctx, x, y, 4, COLORS.rainLight);
  }
}

function iconRainSnow(ctx) {
  drawCloud(ctx, CENTER, CENTER - 25, 48, COLORS.cloudDark);
  for (let i = 0; i < 4; i++) {
    const x = CENTER - 40 + i * 27;
    const y = CENTER + 20;
    if (i % 2 === 0) {
      drawRainDrop(ctx, x, y, 6, COLORS.rainBlue);
    } else {
      drawSnowflake(ctx, x, y, 7);
    }
  }
}

// ========== 导出 ==========
const outputDir = 'D:/DEV/dailyweather/app/src/main/assets/weather';
const blueOutputDir = 'D:/DEV/dailyweather/app/src/main/assets/weather_blue';

fs.mkdirSync(outputDir, { recursive: true });
fs.mkdirSync(blueOutputDir, { recursive: true });

const iconMap = {
  'clear-day': iconClearDay,
  'clear-night': iconClearNight,
  'partly-cloudy-day': iconPartlyCloudyDay,
  'partly-cloudy-night': iconPartlyCloudyNight,
  'overcast': iconOvercast,
  'drizzle': iconDrizzle,
  'rain': iconModerateRain,
  'extreme-rain': iconHeavyRain,
  'thunderstorms-rain': iconThunder,
  'snow': iconSnow,
  'rain-snow': iconRainSnow,
  'fog': iconFog,
  'haze': iconHaze,
  'wind': iconWind,
  'night-drizzle': iconNightDrizzle,
};

for (const [name, drawFunc] of Object.entries(iconMap)) {
  const canvas = createCanvas(SIZE, SIZE);
  const ctx = canvas.getContext('2d');

  // 透明背景
  ctx.clearRect(0, 0, SIZE, SIZE);

  drawFunc(ctx);

  const buffer = canvas.toBuffer('image/png');
  fs.writeFileSync(path.join(outputDir, `${name}.png`), buffer);

  // 蓝色主题版本（相同，可后续微调）
  const canvasBlue = createCanvas(SIZE, SIZE);
  const ctxBlue = canvasBlue.getContext('2d');
  ctxBlue.clearRect(0, 0, SIZE, SIZE);
  drawFunc(ctxBlue);
  const bufferBlue = canvasBlue.toBuffer('image/png');
  fs.writeFileSync(path.join(blueOutputDir, `${name}.png`), bufferBlue);
}

console.log(`已生成 ${Object.keys(iconMap).length} 个天气图标`);
console.log(`输出目录: ${outputDir}`);
console.log(`蓝色主题: ${blueOutputDir}`);
