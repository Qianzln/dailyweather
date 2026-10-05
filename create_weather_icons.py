"""
生成现代风格天气图标，256x256 PNG，扁平化设计，适配深色背景。
"""
import os
from PIL import Image, ImageDraw
import math

SIZE = 256
CENTER = SIZE // 2

# 配色方案
COLORS = {
    'sun': '#FFD93D',
    'sun_core': '#FFC107',
    'sun_glow': '#FFE94A',
    'moon': '#E8E8F0',
    'moon_shadow': '#C8C8D8',
    'cloud_white': '#FFFFFF',
    'cloud_light': '#F0F4F8',
    'cloud_gray': '#B8C4D0',
    'cloud_dark': '#7A8A9A',
    'cloud_darker': '#5A6A7A',
    'rain_blue': '#5BA3D9',
    'rain_light': '#8BC4E8',
    'rain_dark': '#3D8BBF',
    'snow_white': '#FFFFFF',
    'thunder_yellow': '#FFD93D',
    'fog_gray': '#A8B4C0',
    'fog_light': '#C8D4E0',
    'wind_blue': '#8BC4E8',
    'haze_orange': '#D4A860',
}

def hex_to_rgb(hex_color):
    h = hex_color.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def draw_sun(draw, cx, cy, r, glow_alpha=1.0):
    """绘制太阳"""
    # 光晕
    for i in range(5, 0, -1):
        alpha = int(30 * (5-i) / 5)
        glow_r = r + i * 8
        draw.ellipse([cx-glow_r, cy-glow_r, cx+glow_r, cy+glow_r],
                     fill=hex_to_rgb(COLORS['sun_glow']) + (alpha,))
    # 核心
    draw.ellipse([cx-r, cy-r, cx+r, cy+r], fill=hex_to_rgb(COLORS['sun']))
    # 高光
    draw.ellipse([cx-r//3, cy-r//3, cx+r//3, cy+r//3],
                 fill=hex_to_rgb(COLORS['sun_core']))

def draw_moon(draw, cx, cy, r):
    """绘制月亮"""
    draw.ellipse([cx-r, cy-r, cx+r, cy+r], fill=hex_to_rgb(COLORS['moon']))
    # 阴影缺口
    draw.ellipse([cx+r//3, cy-r//2, cx+r*2//3, cy+r//2],
                 fill=hex_to_rgb(COLORS['moon_shadow']))

def draw_cloud(draw, cx, cy, r, color=None, alpha=255):
    """绘制云"""
    if color is None:
        color = hex_to_rgb(COLORS['cloud_white'])
    elif isinstance(color, str):
        color = hex_to_rgb(color)

    offsets = [
        (-r*0.5, r*0.1),
        (r*0.5, r*0.1),
        (-r*0.15, -r*0.2),
        (r*0.2, -r*0.25),
        (r*0.0, -r*0.1),
    ]
    for dx, dy in offsets:
        cr = r * (0.55 if dy < 0 else 0.45)
        draw.ellipse([cx+dx*2-cr, cy+dy*2-cr, cx+dx*2+cr, cy+dy*2+cr], fill=color)

def draw_cloud_dark(draw, cx, cy, r):
    draw_cloud(draw, cx, cy, r, COLORS['cloud_dark'])

def draw_cloud_darker(draw, cx, cy, r):
    draw_cloud(draw, cx, cy, r, COLORS['cloud_darker'])

def draw_rain_drop(draw, x, y, r, color=None):
    if color is None:
        color = hex_to_rgb(COLORS['rain_blue'])
    elif isinstance(color, str):
        color = hex_to_rgb(color)

    points = [
        (x, y - r*1.5),
        (x + r*0.8, y),
        (x, y + r*1.2),
        (x - r*0.8, y),
    ]
    draw.polygon(points, fill=color)

def draw_snow_flake(draw, x, y, r):
    color = hex_to_rgb(COLORS['snow_white'])
    draw.ellipse([x-r, y-r, x+r, y+r], fill=color)
    for dx, dy in [(0, -1), (0, 1), (-1, 0), (1, 0)]:
        draw.line([x, y, x + dx*r, y + dy*r], fill=color, width=2)

def draw_lightning(draw, x1, y1, x2, y2, color=None):
    if color is None:
        color = hex_to_rgb(COLORS['thunder_yellow'])
    elif isinstance(color, str):
        color = hex_to_rgb(color)

    points = [
        (x1, y1),
        (x1 + 15, y1 + 40),
        (x1 - 10, y1 + 50),
        (x1 + 10, y1 + 90),
        (x2, y2),
    ]
    draw.polygon(points, fill=color)

def draw_wind_line(draw, start_x, start_y, length, angle_deg, color=None):
    if color is None:
        color = hex_to_rgb(COLORS['wind_blue'])
    elif isinstance(color, str):
        color = hex_to_rgb(color)

    rad = math.radians(angle_deg)
    end_x = start_x + length * math.cos(rad)
    end_y = start_y + length * math.sin(rad)

    draw.line([start_x, start_y, end_x, end_y], fill=color, width=3)
    arrow_size = 6
    draw.polygon([
        (end_x, end_y),
        (end_x - arrow_size * math.cos(rad - 0.3), end_y - arrow_size * math.sin(rad - 0.3)),
        (end_x - arrow_size * math.cos(rad + 0.3), end_y - arrow_size * math.sin(rad + 0.3)),
    ], fill=color)

def create_icon(name, draw_func):
    img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_func(draw)
    return img

# ========== 各天气图标 ==========
def icon_clear_day(draw):
    draw_sun(draw, CENTER, CENTER - 10, 45)
    draw_cloud(draw, CENTER + 70, CENTER + 50, 25, COLORS['cloud_light'], 180)

def icon_clear_night(draw):
    draw_moon(draw, CENTER, CENTER - 10, 40)
    for i in range(5):
        sx = 30 + i * 45
        sy = 30 + (i % 3) * 30
        draw.ellipse([sx-2, sy-2, sx+2, sy+2], fill=(255, 255, 200, 200))

def icon_partly_cloudy_day(draw):
    draw_sun(draw, CENTER - 50, CENTER - 40, 38)
    draw_cloud(draw, CENTER + 20, CENTER, 50)
    draw_cloud(draw, CENTER + 60, CENTER + 30, 30, COLORS['cloud_light'])

def icon_partly_cloudy_night(draw):
    draw_moon(draw, CENTER - 40, CENTER - 30, 35)
    draw_cloud(draw, CENTER + 30, CENTER + 10, 45)

def icon_overcast(draw):
    draw_cloud_dark(draw, CENTER, CENTER - 20, 55)
    draw_cloud_dark(draw, CENTER - 40, CENTER + 10, 40)
    draw_cloud_dark(draw, CENTER + 40, CENTER + 10, 40)

def icon_light_rain(draw):
    draw_cloud_dark(draw, CENTER, CENTER - 30, 50)
    for i in range(5):
        x = CENTER - 50 + i * 25
        y = CENTER + 20 + (i % 2) * 20
        draw_rain_drop(draw, x, y, 6, COLORS['rain_light'])

def icon_moderate_rain(draw):
    draw_cloud_darker(draw, CENTER, CENTER - 30, 52)
    for i in range(8):
        x = CENTER - 60 + i * 17
        y = CENTER + 15 + (i % 3) * 15
        draw_rain_drop(draw, x, y, 7, COLORS['rain_blue'])

def icon_heavy_rain(draw):
    draw_cloud_darker(draw, CENTER, CENTER - 30, 55)
    for i in range(12):
        x = CENTER - 70 + i * 13
        y = CENTER + 10 + (i % 4) * 12
        draw_rain_drop(draw, x, y, 8, COLORS['rain_dark'])

def icon_thunder(draw):
    draw_cloud_darker(draw, CENTER, CENTER - 35, 55)
    draw_lightning(draw, CENTER - 10, CENTER + 10, CENTER + 20, CENTER + 70, COLORS['thunder_yellow'])
    for i in range(6):
        x = CENTER - 60 + i * 24
        y = CENTER + 50 + (i % 2) * 15
        draw_rain_drop(draw, x, y, 7, COLORS['rain_blue'])

def icon_snow(draw):
    draw_cloud(draw, CENTER, CENTER - 25, 50, COLORS['cloud_light'])
    for i in range(8):
        angle = i * 45
        r = 50 + (i % 3) * 15
        x = CENTER + int(r * 0.7 * (1 if i % 2 == 0 else -1))
        y = CENTER + 20 + int(r * 0.5 * ((-1)**i))
        draw_snow_flake(draw, x, y, 8)

def icon_sleet(draw):
    draw_cloud_dark(draw, CENTER, CENTER - 25, 48)
    for i in range(5):
        x = CENTER - 40 + i * 20
        y = CENTER + 30
        draw_rain_drop(draw, x, y, 5, COLORS['rain_light'])
    for i in range(3):
        x = CENTER - 30 + i * 30
        y = CENTER + 50
        draw_snow_flake(draw, x, y, 6)

def icon_fog(draw):
    for i in range(6):
        y = CENTER - 40 + i * 18
        alpha = 100 + i * 20
        draw.line([30, y, SIZE - 30, y], fill=hex_to_rgb(COLORS['fog_gray']) + (alpha,), width=4)
    draw_cloud(draw, CENTER, CENTER - 10, 40, COLORS['fog_light'], 120)

def icon_haze(draw):
    for i in range(5):
        y = CENTER - 30 + i * 15
        alpha = 80 + i * 15
        draw.line([40, y, SIZE - 40, y], fill=hex_to_rgb(COLORS['haze_orange']) + (alpha,), width=3)
    draw_sun(draw, CENTER, CENTER - 20, 30)

def icon_wind(draw):
    for i in range(5):
        y = CENTER - 50 + i * 25
        start_x = 40 + (i % 2) * 30
        length = 80 + (i % 3) * 20
        draw_wind_line(draw, start_x, y, length, 0, COLORS['wind_blue'])
    draw_cloud(draw, CENTER + 60, CENTER - 20, 25, COLORS['cloud_light'], 150)

def icon_drizzle(draw):
    draw_cloud(draw, CENTER, CENTER - 25, 45, COLORS['cloud_light'])
    for i in range(4):
        x = CENTER - 35 + i * 23
        y = CENTER + 25 + (i % 2) * 15
        draw_rain_drop(draw, x, y, 5, COLORS['rain_light'])

def icon_night_drizzle(draw):
    draw_moon(draw, CENTER - 50, CENTER - 40, 30)
    draw_cloud(draw, CENTER + 20, CENTER, 40, COLORS['cloud_light'])
    for i in range(3):
        x = CENTER - 10 + i * 20
        y = CENTER + 30
        draw_rain_drop(draw, x, y, 4, COLORS['rain_light'])

def icon_rain_snow(draw):
    draw_cloud_dark(draw, CENTER, CENTER - 25, 48)
    for i in range(4):
        x = CENTER - 40 + i * 27
        y = CENTER + 20
        if i % 2 == 0:
            draw_rain_drop(draw, x, y, 6, COLORS['rain_blue'])
        else:
            draw_snow_flake(draw, x, y, 7)

# 导出所有图标
output_dir = "D:/DEV/dailyweather/app/src/main/assets/weather"
blue_output_dir = "D:/DEV/dailyweather/app/src/main/assets/weather_blue"

os.makedirs(output_dir, exist_ok=True)
os.makedirs(blue_output_dir, exist_ok=True)

icon_map = {
    'clear-day': icon_clear_day,
    'clear-night': icon_clear_night,
    'partly-cloudy-day': icon_partly_cloudy_day,
    'partly-cloudy-night': icon_partly_cloudy_night,
    'overcast': icon_overcast,
    'drizzle': icon_drizzle,
    'rain': icon_moderate_rain,
    'extreme-rain': icon_heavy_rain,
    'thunderstorms-rain': icon_thunder,
    'snow': icon_snow,
    'rain-snow': icon_rain_snow,
    'fog': icon_fog,
    'haze': icon_haze,
    'wind': icon_wind,
    'night-drizzle': icon_night_drizzle,
}

for name, func in icon_map.items():
    img = create_icon(name, func)
    img.save(os.path.join(output_dir, f'{name}.png'), 'PNG')

    # 蓝色主题版本
    img_blue = create_icon(name, func)
    img_blue.save(os.path.join(blue_output_dir, f'{name}.png'), 'PNG')

print(f"已生成 {len(icon_map)} 个天气图标")
print(f"输出目录: {output_dir}")
