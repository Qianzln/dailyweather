Add-Type -AssemblyName System.Drawing
$dir = 'D:\DEV\dailyweather\app\src\main\res\drawable-nodpi'
$files = @(
    'weather_cloud_bank_v2.png',
    'weather_cloud_bank_day_v1.png',
    'weather_cloud_bank_day_v2.png',
    'weather_cloud_cumulus_v1.png',
    'weather_cloud_cumulus_day_v1.png',
    'weather_cloud_cumulus_day_v2.png',
    'weather_cloud_cumulus_day_v3.png',
    'weather_cloud_wisp_v1.png'
)
foreach ($f in $files) {
    $path = Join-Path $dir $f
    $img = [System.Drawing.Image]::FromFile($path)
    $w = $img.Width; $h = $img.Height
    $cx = [Math]::Floor($w/2); $cy = [Math]::Floor($h/2)
    $p1 = $img.GetPixel($cx, $cy)
    $p2 = $img.GetPixel(0, $cy)
    $p3 = $img.GetPixel($w-1, $cy)
    Write-Host "$f : ${w}x${h} mode=$($img.PixelFormat)"
    Write-Host "  center=($($p1.R),$($p1.G),$($p1.B)) A=$($p1.A)"
    Write-Host "  left=($($p2.R),$($p2.G),$($p2.B)) A=$($p2.A)"
    Write-Host "  right=($($p3.R),$($p3.G),$($p3.B)) A=$($p3.A)"
    $img.Dispose()
}
